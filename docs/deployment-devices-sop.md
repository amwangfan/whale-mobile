# 设备面生产部署 SOP（whale-core 侧）

> 面向 whale-core 维护会话。设备面代码已在 dev 仓 main（6d2004c 起），随 SOP A 同步进生产即生效。本文档是上线检查单。

## 1. 同步前检查

- [ ] dev 仓 `pytest tests/ -q` 全绿（当前 1451）
- [ ] 生产 `/opt/whale/whale-core` 走 SOP A：`git fetch origin && git reset --hard origin/main`，门禁测试全绿后再重启
- [ ] 可选依赖：设备 WSS 网关需 `websockets` + `cryptography`（`pip install -e '.[devices]'` 或对照 pyproject extras 安装到生产 venv/系统）

## 2. 端口与暴露

- 管理 REST（配对审批等）挂在既有 API 端口（生产 8180，回环 + tailscale serve 已有）
- 设备 WSS 在 **API 端口 +2**（生产即 8182），默认随 API 绑 127.0.0.1 → 手机经 tailnet 访问需加一条 serve：
  `tailscale serve --bg --https 8444 http://127.0.0.1:8182`
  手机端网关地址：`wss://office-n100.tail69bfea.ts.net:8444/api/v1/devices/ws`
- **不要**把 8182 直接绑 0.0.0.0 暴露公网。

## 3. 数据与隔离

- 设备注册/配对/回执存生产 MemoryStore kv（与记忆同库不同键域 `device:*`）；审计走 `audit:studio:*` 约定
- 设备认证 ≠ 主人认证；screen.* scope 由 resolver 矩阵裁决（群聊硬拒）
- 生产启用前先在 dev 实例（whale-core-dev8190.service，:8190/:8192）跑通一轮真机（已完成：leaf5 全链）

## 4. 回滚

- 设备面无侵入既有路径：回退 = 切回旧代码 + 重启；设备 kv 键残留无害
- tailscale serve 8444 用 `tailscale serve --https=8444 off` 撤

## 5. 上线后验收（手机端已在场时）

- 配对→在线→query（screen.history.search）→action（phone.notification.post）→ revoke 踢线，五项各一次
