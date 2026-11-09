package com.wangfanlab.whale.transport.v2

/** Owned by the transport executor; delayed callbacks may act only on their own connection. */
class ConnectionGeneration {
    class Token internal constructor()
    private var current: Token? = null

    fun begin(): Token = Token().also { current = it }
    fun invalidate() { current = null }
    fun runIfCurrent(token: Token, block: () -> Unit) {
        if (current === token) block()
    }
}
