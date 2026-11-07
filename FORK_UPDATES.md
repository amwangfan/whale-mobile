# Dreaminutes Fork 更新说明

## 第 08 批：Core 超时对账修复（本次停止）

配套 Core 修复超时推定后迟到回执不能更新结果，以及清理任务与回执写入竞争的问题；Mobile 不需要新增业务实现。最终联合检查 Core 200 项、Mobile 25 项通过。按本次要求，第07、08批完成后停止。

[第08批说明](https://github.com/dreaminutes/whale-core/blob/refactor/simplify-integration/docs/updates/08-late-receipt-reconciliation.md)

## 第 07 批：回包类型匹配

等待项增加预期回包类型，与请求 ID、设备一起核对。错误类型不会消耗原等待项，后续正确回包仍可完成请求。联合检查 Core 186 项、Mobile 25 项通过。

[第07批说明](https://github.com/dreaminutes/whale-core/blob/refactor/simplify-integration/docs/updates/07-response-type-matching.md)

## 第 06 批：最后一轮验证工具收尾

Schema 校验工具检查元数据、必需文件和示例覆盖；生成器检查 hello/hello_ack 能力枚举一致性。配套 Core 检查入口增加阶段超时、独立运行目录和 summary.json。

最终联合检查：Core 185 项、Mobile 25 项通过，协议 20/20、两仓 36 文件一致。实现提交：Mobile 2dd31ac、Core f176ab4。按用户要求，完成本批后停止，不继续新一轮。

[第06批完整说明](https://github.com/dreaminutes/whale-core/blob/refactor/simplify-integration/docs/updates/06-validation-tooling.md)

## 第 05 批：请求发送可靠性

参考网关新增发送前协议预检，非法动作不会消耗幂等键；每条连接序号与发送串行化；拒绝非法超时和显式幂等键类型。既有能力与默认授权保持不变。

参考网关及工具联合回归 22 项通过。详见 [Core 第05批](https://github.com/dreaminutes/whale-core/blob/refactor/simplify-integration/docs/updates/05-dispatch-reliability.md)。

## 第 03 批：设备互联整理

状态：实现和本机联合验证完成，已推送。日期：2026-11-07。

对应提交：[Mobile 637f3e6](https://github.com/dreaminutes/whale-mobile/commit/637f3e6)、[Core 9afddd4](https://github.com/dreaminutes/whale-core/commit/9afddd4)。

- 用协议生成能力表，统一 Python 参考网关、Kotlin、Dart 与配套 Core 的词表。
- 参考网关认识眼镜与自主体 scope；保留旧默认配对的 9 个 scope，额外能力仍需显式授权。
- 查询/动作回包绑定目标设备，不再仅按请求 ID 完成等待。
- Windows 优先使用虚拟环境依赖，修复被 Linux vendored 原生库遮蔽的问题。
- 增加生成器测试，并将能力检查接入手动 CI。
- 修复两仓都缺同类协议文件时，漂移检查误报成功的问题。

验证：Mobile 工具与参考网关 20 项、Core 设备 125 项通过；协议 20/20、跨仓 36 文件一致，五阶段联合检查通过。Core Windows 全量测试在现有 Linux-only 测试收集处中断；未执行 APK、Flutter/Kotlin 或真机联调。

详见 [互联开发说明](docs/device-interop-development.md)、[本仓代码差异](https://github.com/dreaminutes/whale-mobile/compare/main...refactor/simplify-integration) 和 [Core 第 03 批更新页](https://github.com/dreaminutes/whale-core/blob/refactor/simplify-integration/docs/updates/03-device-interoperability.md)。
