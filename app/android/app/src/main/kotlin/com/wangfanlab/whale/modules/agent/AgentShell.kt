package com.wangfanlab.whale.modules.agent

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Root Shell 执行器（core#58）。
 *
 * - 每条命令经 `su 0 sh -c`（需设备 root + Magisk 授权本 App）；
 *   输出落 filesDir/agent/out/，exit code 以行内标记捕获；
 * - 持续会话：session 名 → 同一 su root sh 进程复用（保 cwd/环境变量）；
 * - 资源限制：默认超时 30s（上限 300s）、输出尾部截 64KB、并发会话 ≤3；
 *   会话日志超 [SESSION_LOG_CAP] 截断保尾（长期运行不胀盘）；
 * - 超时真实生效：先 waitFor(timeout) 再读输出（不许先阻塞读）；
 * - 紧急停止：销毁持续会话 + 强杀在途一次性命令。
 *
 * 不做命令白名单（issue 明确）；失控防护靠超时/并发/回收/紧急停止。
 */
class AgentShell(
    private val baseDir: File,
    private val suCommand: List<String> = listOf("su", "0", "sh", "-c"),
) {

    data class Result(
        val exitCode: Int?,
        val outputTail: String,
        val outputFile: String,
        val timedOut: Boolean,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 在途一次性命令进程（estop 强杀范围）。 */
    private val runningOnce = ConcurrentHashMap.newKeySet<Process>()

    private inner class Session(val name: String) {
        var process: Process? = null
        var stdin: java.io.OutputStream? = null
        var outputFile: File = File(baseDir, "session-$name.log")

        @Synchronized
        fun ensureAlive(): Boolean {
            val p = process
            if (p != null && p.isAlive) return true
            outputFile.delete()
            return try {
                val pb = ProcessBuilder("su", "0", "sh")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile))
                val proc = pb.start()
                process = proc
                stdin = proc.outputStream
                true
            } catch (t: Throwable) {
                Log.w(TAG, "会话 $name 启动失败: ${t.message}")
                false
            }
        }

        /** 日志有界回收：超上限保留尾部（含半个 UTF-8 字符的边界容忍）。 */
        @Synchronized
        fun rotateIfNeeded() {
            val f = outputFile
            if (!f.exists() || f.length() <= SESSION_LOG_CAP) return
            try {
                val tail = f.readText().takeLast(SESSION_LOG_CAP / 2)
                f.writeText(tail)
            } catch (t: Throwable) {
                Log.w(TAG, "会话日志回收失败: ${t.message}")
            }
        }

        @Synchronized
        fun destroy() {
            try { stdin?.close() } catch (_: Throwable) {}
            try { process?.destroy() } catch (_: Throwable) {}
            process = null
            stdin = null
        }
    }

    /**
     * 执行命令。[sessionId] 非空时走持续会话（同 session 保环境）；
     * 否则一次性 `su 0 sh -c`。
     */
    fun execute(
        command: String,
        timeoutS: Int = AgentLogic.DEFAULT_TIMEOUT_S,
        sessionId: String? = null,
        estop: () -> Boolean = { false },
    ): Result {
        if (estop()) {
            destroyAll()
            return Result(null, "紧急停止生效，命令未执行", "", timedOut = false)
        }
        return if (sessionId.isNullOrBlank()) {
            executeOnce(command, timeoutS, estop)
        } else {
            executeInSession(command, timeoutS, sessionId, estop)
        }
    }

    private fun executeOnce(command: String, timeoutS: Int, estop: () -> Boolean): Result {
        val marker = "__EXIT_${System.nanoTime()}__"
        val outFile = File(baseDir, "once-${System.nanoTime()}.log")
        val wrapped = "{\n$command\n} > ${quote(outFile.absolutePath)} 2>&1; echo \"$marker\$?\""
        return try {
            val pb = ProcessBuilder(*suCommand.toTypedArray(), wrapped)
            val proc = pb.start()
            runningOnce.add(proc)
            // 正确顺序：先限时等待进程退出，再读输出（修复先 readText 卡死
            // 导致超时永不生效的缺陷）。
            val completed = proc.waitFor(timeoutS.toLong(), TimeUnit.SECONDS)
            if (!completed) {
                proc.destroyForcibly()
                return Result(null, tail(outFile), outFile.absolutePath, timedOut = true)
            }
            val stdout = proc.inputStream.bufferedReader().readText()
            val exit = stdout.lineSequence()
                .lastOrNull { it.startsWith(marker) }
                ?.removePrefix(marker)?.trim()?.toIntOrNull()
            Result(exit, tail(outFile), outFile.absolutePath, timedOut = false)
        } catch (t: Throwable) {
            Log.w(TAG, "executeOnce 失败: ${t.message}")
            Result(null, "shell 启动失败: ${t.message}", outFile.absolutePath, timedOut = false)
        } finally {
            runningOnce.removeIf { !it.isAlive }
        }
    }

    private fun executeInSession(
        command: String, timeoutS: Int, sessionId: String, estop: () -> Boolean,
    ): Result {
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(sessionId)) {
            return Result(null, "会话数超限（$MAX_SESSIONS）", "", timedOut = false)
        }
        val session = sessions.getOrPut(sessionId) { Session(sessionId) }
        if (!session.ensureAlive()) {
            sessions.remove(sessionId)
            return Result(null, "会话启动失败（无 root?）", "", timedOut = false)
        }
        session.rotateIfNeeded()
        val marker = "__EXIT_${System.nanoTime()}__"
        val logFile = session.outputFile
        val sizeBefore = if (logFile.exists()) logFile.length() else 0L
        return try {
            val stdin = session.stdin ?: return Result(null, "会话 stdin 不可用", "", false)
            stdin.write("{\n$command\n}; echo \"$marker\$?\"\n".toByteArray())
            stdin.flush()
            val deadline = System.currentTimeMillis() + timeoutS * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (estop()) {
                    session.destroy()
                    sessions.remove(sessionId)
                    return Result(null, "紧急停止生效", logFile.absolutePath, timedOut = false)
                }
                val text = if (logFile.exists()) logFile.readText() else ""
                val idx = text.indexOf(marker)
                if (idx >= 0) {
                    val exitStr = text.substring(idx + marker.length).lineSequence()
                        .firstOrNull()?.trim()
                    val exit = exitStr?.toIntOrNull()
                    val out = text.substring(0, idx)
                        .drop(sizeBefore.toInt().coerceAtMost(text.length))
                    return Result(exit, out.takeLast(MAX_TAIL_CHARS), logFile.absolutePath, false)
                }
                Thread.sleep(120)
            }
            // 超时：持续会话的进程继续跑（会话语义），但标记 timedOut 让调用方知道
            Result(null, tail(logFile), logFile.absolutePath, timedOut = true)
        } catch (t: Throwable) {
            Log.w(TAG, "executeInSession 失败: ${t.message}")
            Result(null, "会话执行失败: ${t.message}", logFile.absolutePath, false)
        }
    }

    fun destroySession(sessionId: String) {
        sessions.remove(sessionId)?.destroy()
    }

    /** 紧急停止：销毁持续会话 + 强杀在途一次性命令。 */
    fun destroyAll() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        for (p in runningOnce) {
            try { p.destroyForcibly() } catch (_: Throwable) {}
        }
        runningOnce.clear()
    }

    val activeSessions: Set<String> get() = sessions.keys.toSet()

    private fun tail(f: File): String =
        if (f.exists()) f.readText().takeLast(MAX_TAIL_CHARS) else ""

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "WhaleAgentShell"
        const val MAX_SESSIONS = 3
        const val MAX_TAIL_CHARS = 64 * 1024

        /** 会话日志回收阈值（512KB 保尾一半）。 */
        const val SESSION_LOG_CAP = 512 * 1024
    }
}
