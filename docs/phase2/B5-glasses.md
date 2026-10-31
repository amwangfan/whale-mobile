# B5 雷鸟 iO / HUD 输出 — 设计（阶段二预研）

> 约束：不刷眼镜固件；复用既有 Turbo-IO/手机眼镜桥方案。

## 1. 呈现契约（whale-mobile ⇄ 眼镜桥）

| 消息 | 语义 |
| --- | --- |
| show_card | {card_id, title, body, priority, ttl, actions[]} |
| update_card | {card_id, 变更字段} |
| dismiss_card | {card_id} |
| feedback | {card_id, action_id?, 眼镜侧用户操作} |

回执分级：云端发出 → 手机接收 → 眼镜桥转发 → 眼镜实际显示，四级状态分开，不伪报成功。

## 2. 内容规则

- 只上镜：低频重要提醒、课程提示、来电状态、实时对话状态
- 不上镜：完整 OCR、通知正文原文（owner_sensitive 默认不上镜，除非逐笔授权）
- 未连接眼镜：降级为手机通知 + 回执注明 degraded；蓝牙不可达时探活报 unavailable

## 3. 技术落点（待实物确认）

- 模块 modules/glasses/：桥接既有手机端眼镜通道（蓝牙/厂商 App 转发）
- action.request capability: `glasses.card.show`（合约 V1.1 候选）
- 回执 provider_ref 携带桥侧句柄

## 4. 待确认

- 雷鸟 iO 的可用桥接通道（Turbo-IO App？直连蓝牙 GATT？）——需用户确认现有方案细节
- 眼镜显示能力上限（字符数/刷新率/卡片模板）
