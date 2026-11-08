# V2 互联整理：逐轮更新

日期：2026-11-08。分支：`refactor/v2-integration`；上游基线：`646c4ec`。

[查看第 01–10 轮更新说明](https://github.com/dreaminutes/whale-core/blob/refactor/v2-integration/docs/v2-updates/README.md)

第 01–04 轮修复位于 Core：统一协议路径、严格加载 schema、补齐发布包中的协议、修复动作和查询预检。Mobile 的 V2 协议和 Android 实现保持现状，不重复搬入 Core 逻辑。

第 05 轮 Core 同步脚本补齐本仓已有的对象示例范围；两仓检查都阻止相同但不完整的契约目录误报通过，统一路径分隔符，保留各自函数返回格式。本仓增加独立工具回归测试。

第 06–10 轮修复位于 Core：支持 Agent2 图片输出 data_base64 并保留截断标记、如实返回在途动作、统一观察/执行授权、保护确认等待项、修复连接替换时的清理。本仓 Android 生产代码与协议未改。

最新联合验证：68 个契约文件两侧一致；本仓 `tests/contract_validate.py` 为 72/72，工具测试 4/4；Core 相关回归 165/165。没有 Android 真机或 APK 构建验收结果。

端侧输出读取 offset/max_bytes 分页尚待后续实现与 Android 验证，本轮没有将其标为完成。

保留旧工作分支；本轮未修改 snow 的主分支，也未重开旧 PR #48。
