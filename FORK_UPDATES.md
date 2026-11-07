# Dreaminutes Fork 更新说明

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
