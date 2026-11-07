package com.wangfanlab.whale.transport.v2

/**
 * v1 模块执行器契约（自 v1 `WssClient.ActionExecutor` 抽出；v1 栈下线后保留给
 * 既有模块执行器实现：`phone.notification.post`/`phone.call.ring`/
 * `phone.device.safe_action`/`glasses.card.*` 等）。
 *
 * 接线：[com.wangfanlab.whale.runtime.v2.NodeRuntimeV2] 经
 * `adaptV1Executor` 把本接口适配为 [ReceiptDispatcherV2.ActionHandler]，
 * v1 终态状态词（executed/failed）在适配层映射为 v2 五终态。
 *
 * 实现必须恰好回一次终态（reply("executed", null) 或 reply("failed", errorCode)）；
 * 调用线程为 WssClientV2 executor 线程，实现如需切线程自行安排。
 */
interface LegacyActionExecutor {
    fun execute(
        payload: Map<String, Any?>,
        reply: (state: String, errorCode: String?, providerRef: String?, result: Map<String, Any?>?) -> Unit,
    )
}
