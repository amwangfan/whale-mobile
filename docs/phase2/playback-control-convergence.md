# 播放控制收敛：#96 根因定案与方案（Agent2 / GUI 自主任务）

> 来源：Core #58 验收遗留 known-gap（Core issue #96）。
> 场景：高负载 GSI 测试机（Android 9，常态高负载）上 Bilibili 播放控制类
> 阶段（playpause / seek / switch）两轮不收敛（task-timeout /
> max-consecutive-failures）；P0/P1(launch/search/feed/exit)/P2 全过。
> 本文证据全部来自验收 trace（run_01M7W5P9* 等 133 份）与两仓源码，
> 区分「真 bug」与「环境假象」。

## 一、证据：三种失败模式

每次逻辑观察恒 ~30s（WSS 往返 2–3 跳 ×10–15s；端侧无障碍树采集实测 <1s，
**观察开销在传输往返，不在端侧 dump**）。一步 = 决策 ~3–5s + 动作 + 观察，
预算 600–1200s 下总步数被卡在 ~12–24。

### 模式 A：瞬态控件 vs 慢观察（playpause，task-timeout）

run_01M7W5P9（17 步，610s 超时）时间线：

- 观察拿到 66 元素（推荐流）→ 点第一视频 element:29 → **stale_observation**
  （5.3s 间 feed 自动播放预览/时间戳使布局漂移，§8 稳定键已失配）；
- 改坐标点 (540,394) 唤出控制条 → 观察撕裂捕获到暂停钮（78 元素）→
  点暂停钮 element:5 → **stale_observation**（控制条 3–5s 自隐，tap 前的
  §8 fresh 重校验（实测 0.4–0.8s）时按钮已消失）；
- 该循环重复 5 轮（5× stale），其间 1 次 `device.observe failed/unavailable`
  （WSS 抖动），终至 task-timeout。

**要点：§8 fresh 重校验（反呆设计）在瞬态控件上 100% 拒绝执行——观察越慢，
控件越短命，越点不中。这不是模型策略问题，是控制环路的物理不自洽。**

### 模式 B：播放状态不可判定（playpause 二轮 / seek）

run_01M7WA57（19:20，26 步未收敛）：

- 点开视频成功；唤出控制条成功（观察元素 40↔37 振荡 = 控制条显隐翻转）；
- 进入 19 元素极简树后暂停钮 element:6 稳定可点，**连点 13 次**——
  每次 toggle 播放↔暂停互相抵消，而验证信号在 `界面已变化/界面无可见变化`
  间抛硬币（树里的时间戳/动画节点噪声翻转 ui_signature）；
- **「已暂停」对 UI 树不可见**（控制条隐藏后树里无播放状态字段），
  完成判据不可判定 → 永不收敛。

run_01M7WB0Q（seek，25 步未收敛）：控制条隐藏时进度条不在树里，模型
盲猜 y=1988 连拖 20 次全「界面无可见变化」（即便拖中，进度文本随控制条
隐藏，树签名不变 → 成功也会被验证误判 failed）。**seek 在树观察下既不可
执行也不可验证。**

### 模式 C：传输退化连败（max-consecutive-failures）

run_01M7W8N519 / run_01M7WBW7：observe 动作超时（60s 上限被打满）或
WSS 掉线 → 网关按选路序退到 adb → 设备 adbd 已死（connect 10s 超时 ×4）→
连败熔断。**真实但次级**：A/B 模式在链路完美时同样不收敛。

### 附带真 bug（本 PR 修掉）

1. **媒体键契约不对称**：Core 决策白名单早已含 `media_play_pause`
   （decision.py），本端 `Agent2Logic.ALLOWED_KEYS` 未放行 → 即便模型想出
   媒体键也被本端拒绝；且规则文本从未教过模型有这条路。
2. **swipe 参数先验拒止**：模型对 swipe 有强 `x1/y1` 先验，本端校验只收
   `x/y` → run_01M7W6909Q 单阶段 9 连「缺少/非法坐标: x」。Core adb 通道
   已做别名兼容（a7e6803），embedded 主路（本端 Agent2Logic）残留不对称。

## 二、根因定案（一句话）

**播放控制三阶段撞的是「瞬态控件寿命（3–5s）≪ 观察-动作环延迟（~35s/步）」
与「播放状态对 UI 树不可见」的结构性双缺口；传输抖动是放大器而非主因。**
非仿真假象，非模型能力问题，非单纯设备不稳定。

## 三、收敛方案（本仓已落地，契约内零协议变更）

1. **`device.key` 媒体键放行**（`MEDIA_PLAY_PAUSE / MEDIA_NEXT /
   MEDIA_PREVIOUS` 进 `ALLOWED_KEYS`）：root 版走 `input keyevent`（行为零
   变化）；无 root 版（daily / GSI 无 su）新增 **MediaSession 直控后端**
   （`MediaSessionControl` / `AndroidMediaSessionControl`）——经既有通知
   监听授权（`WhaleNotificationListenerService` 的 ComponentName）读控活跃
   媒体会话，绕开瞬态控件整条死路。
   - 目标选择保守：前台包+播放中 → 前台包 → 唯一播放中 → 唯一会话；
     歧义/无会话/未授权一律诚实 failed/unavailable，绝不盲控。
   - `play_pause` 带**确定性验证**（≤1.5s 有界轮询会话状态翻转，
     passed/failed/skipped 如实）——补上模式 B 缺的「已暂停」判据。
   - next/previous 语义由应用自定，验证诚实 skipped。
2. **观察负载加 `media` 段**（`buildObservationJson` 附加字段：
   package/state/playing/position_ms/duration_ms/title≤40 字）+ 观察回执
   摘要挂一行媒体状态。播放状态首次成为可观察事实；字段为附加键，
   Core 现有解析宽容未知键，不炸老链路。
3. **`device.swipe` 收 `x1/y1` 别名**（`Agent2Logic.normalizeAliases`，
   规范键优先，入参不改写），消灭参数先验连败，与 adb 通道对齐。

### 授权与隐私边界

- 复用通知监听授权，不新增权限申请；未授权 fail-closed（unavailable）。
- 媒体段含前台媒体标题（≤40 字）——与观察元素 text 同类屏幕内容，
  走既有 device.observe 通道，不扩大数据面。

## 四、Core 侧配套跟进（不在本仓；建议落 Core issue #96 评论/后续 PR）

1. 决策规则文本教媒体键：`device.key {"key":"media_play_pause"}` 用于
   播放/暂停（一步直达，免唤控制条）；
2. `observation_codec` 透传 `media` 段 + 决策渲染进观察文本
   （switch 阶段「确认正在播放」= media.playing==true + 标题变化）；
3. `verify_action` 对 device.key 媒体键用 media 段做确定性验证；
4. **seek 需要契约增量**：`device.media.seek_to {position_ms|ratio}`（或
   复用 device.key 形态不了——seek 无键码）——经 `transportControls.seekTo`，
   需 contracts/v2 §5.1 增项 + scope 表 + 两仓 CapabilitiesV2 同步；
   草稿形态见本 PR 讨论区。
5. 播放器页观察节奏：观察前若以媒体段确认播放中，可省一次控制条唤出
   往返（~30s/次），高负载机收益直接。

## 五、真机验证窗检查单（另约窗，掉线即停）

前置：测试机确认 Whale 通知使用权已授予且监听已绑定
（`WhaleNotificationListenerService.bindingStatus` permission+bound）。
1. playpause：`device.key media_play_pause` 一步暂停，验证 passed
   （播放中→已暂停），对照旧路径 5×stale；
2. seek：（待 Core 契约增量后）`seek_to` 直达一半，media.position_ms 验证；
3. switch：back → 点开另一视频 → 观察 media 段 playing+标题变化即完成；
4. 回归：launch/search/feed/exit 不受影响（本 PR 不动既有路径语义）。

## 六、离线自证（本 PR）

- 新增单测：`Agent2LogicTest` 媒体键白名单/swipe 别名归一；
  `MediaSessionControlTest` 目标选择矩阵（含歧义拒绝）/状态名/摘要；
  `Agent2ModuleTest` 媒体键无 root 走 MediaSession（翻转验证 passed）、
  无会话/未授权/歧义三态 fail-closed、root 版仍走 keyevent 不碰
  MediaSession、观察媒体段有/无/读取失败三态、swipe 别名端到端落点。
- 全量 daily 单测回归绿（执行时计数见 PR 描述）。
