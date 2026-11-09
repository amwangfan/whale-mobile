# V2 互联整理：逐轮更新

最近更新：2026-11-09。分支：`refactor/v2-integration`；上游基线：`646c4ec`。

[查看第 01–17 轮更新说明](https://github.com/dreaminutes/whale-core/blob/refactor/v2-integration/docs/v2-updates/README.md)

第 01–04 轮修复位于 Core：统一协议路径、严格加载 schema、补齐发布包中的协议、修复动作和查询预检。Mobile 的 V2 协议和 Android 实现保持现状，不重复搬入 Core 逻辑。

第 05 轮 Core 同步脚本补齐本仓已有的对象示例范围；两仓检查都阻止相同但不完整的契约目录误报通过，统一路径分隔符，保留各自函数返回格式。本仓增加独立工具回归测试。

第 06–10 轮修复位于 Core：支持 Agent2 图片输出 data_base64 并保留截断标记、如实返回在途动作、统一观察/执行授权、保护确认等待项、修复连接替换时的清理。本仓 Android 生产代码与协议未改。

第 06–10 轮联合验证：68 个契约文件两侧一致；本仓 `tests/contract_validate.py` 为 72/72，工具测试 4/4；Core 相关回归 165/165。没有 Android 真机或 APK 构建验收结果。

第 11–15 轮扩大为完整链路改动：

- 11：新增随机文件字节分页与旧输出适配，canonical PROTOCOL §7.4 同步 Core。
- 12：观察采集真正遵守截图/UI 树开关、元素数量上限；Core 接通完整观察解析。
- 13–14：Core 持久只读对账、按会话收束查询/动作/确认，Mobile 不重复实现这些职责。
- 15：补齐 Agent2Runtime 分页门面与客户端重建后的 dispatcher 重绑定；page_format=bytes.v1 显式启用分页，保留旧调用格式。

第 11–15 轮验证：Core **249/249**；Mobile 隔离纯 JVM **18/18**、协议 **72/72**、工具 **4/4**；**68 个契约文件一致**。Kotlin 实际生成的 203 页、202048 字节经过 Core 校验并完整重组，字节一致。

第 16–17 轮全局迭代：Core 修复跨平台运行锁、原子动作记录与保留清理、内存事务回滚、备份句柄和记忆时钟；本仓录制模块按 dispatcher 重绑定，复用原录制控制器保持正在录制/暂停状态。

最新验证：完整 Core Python **2627 passed / 18 skipped**；网页 **96/96**；Mobile 扩展纯 JVM **108/108**、协议 **72/72**、Python工具 **4 passed / 1 skipped**；**68 个契约文件一致**，新 wheel 源码外安装通过。Python媒体宿主测试因缺javac跳过，未算作通过。

新增[纯 JVM 验证入口与范围](../TESTING-PURE-JVM.md)，可在显式提供本地依赖后复现协议、恢复、通知、通话、眼镜及新功能的测试，不会下载工具或构建APK。

端侧分页逻辑已实现并验证，仍缺 Flutter/Android SDK/JDK17 环境下的完整 APK 编译与真机验收；JVM 测试不代表 Android 组件已构建。原有单参数 readOutput 与未协商分页的输出形状保留。

保留旧工作分支；本轮未修改 snow 的主分支，也未重开旧 PR #48。
