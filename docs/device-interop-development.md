# 设备互联开发与联合检查

本页对应 Dreaminutes Fork 第 03 批，配套 Core 分支为 `refactor/simplify-integration`。

## 能力定义只从协议生成

事实源仍是 `contracts/v1/messages/`，不新增第二套协议。生成器读取：

- `device.hello` 的声明能力全集。
- `action.request` 的动作与授权范围枚举。
- `sensor.query` 的查询操作枚举。

```sh
python scripts/generate_capabilities.py --core ../whale-core
python scripts/generate_capabilities.py --core ../whale-core --check
```

第一条更新四个生成文件（Mobile Python、Kotlin、Dart，以及 Core Python）；第二条只校验，不写文件。单仓开发可省略 `--core`，联合交付必须带上 Core 路径。

运行时代码继续保持原公开入口：`WssClient.V1_CAPABILITIES`、`TransportClient.v1Capabilities`、Core `registry.DEVICE_CAPABILITIES`。生成文件随源码发布，运行时不需要调用生成器或跨仓读取文件。

## 支持能力与默认授权分开

- 目前支持 16 个声明能力、15 个动作能力、13 个查询操作，集合并不相同。
- Dart 默认声明仍为原来的 15 项，`device.agent.output.read` 仍需明确声明与配对授权。
- 参考网关默认配对仍为原来的 9 个基础 screen/phone scope；眼镜和自主体能力可显式指定，不随能力全集扩展而自动授予。
- 已配对设备的 scopes 不会因重新生成代码而改变。

## 平台依赖

```sh
python -m pip install -r server/requirements.txt pytest
python tests/contract_validate.py
python -m pytest tests/test_protocol_tooling.py server/test_dev_gateway.py -q
```

优先使用当前虚拟环境的 jsonschema；只有未安装时才使用 `tests/_vendor` 离线副本。副本含平台相关原生库，不能在 Windows/macOS 上优先覆盖已安装的兼容依赖。

## 两仓联合入口

在 Core checkout 中：

```sh
python -m pip install -e '.[dev,devices]' -r ../whale-mobile/server/requirements.txt
python scripts/check_device_interop.py --mobile ../whale-mobile --log-dir ../logs/04-device-interop
```

五个阶段按顺序执行：镜像协议、生成能力表、黄金示例与负例、Core 设备测试、Mobile 参考网关与生成器测试。失败即停止；使用 `--contracts-only` 可只运行前三项。日志写入指定目录，命令不需要真实模型 Key，也不操作真实手机。

Core 的手动 `contract-sync` workflow 可选择 Mobile 仓库、配套 ref，以及 contracts/full 检查集。验收本 Fork 时，Mobile 仓库选 `dreaminutes/whale-mobile`，两边 ref 都选 `refactor/simplify-integration`。工作流配置已提供，是否实际执行须看 Actions 记录。

## 请求归属

Core 的感知查询、设备确认及动作等待均绑定目标设备；参考网关的查询与动作同样绑定设备。其他设备的回包不会消费原等待项，正常设备仍可继续返回结果。未知动作回执不写入参考网关数据库。

## 验收范围

Python 协议和回环网关测试不等于 APK 编译、Flutter 测试或真机验收。Android/Flutter 代码本批仅把原列表改为生成表的别名；完整 APK、Root、通知、语音和眼镜仍需相应环境单独测试。
