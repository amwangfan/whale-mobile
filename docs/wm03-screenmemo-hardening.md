# WM-03 ScreenMemo MCP 服务安全加固

- 仓库：`/opt/whale/ScreenMemo`（上游基线 `2f57da1`）
- 分支：`whale/hardening`
- 提交：
  - `0b8d1b7` fix(mcp): harden evidence refs, strip local paths from tool outputs
  - `82ab5f8` fix(mcp): constant-time token check, loopback default bind

## 漏洞与修复对照

### 1. 任意文件读取（get_evidence_images / shot:base64 路径）

旧实现：`image_refs` 中 `shot:` + base64(path) 零校验直接 `File(path).readBytes()`，
LAN 内持 Bearer token 者可读 App 沙盒任意 ≤8MB 文件。

新实现（`McpEvidenceSecurity.kt` + `ScreenMemoMcpRepository.kt`）：

- 引用格式改为不透明 id：
  - `shot:v2.s<segment_samples 行 id>.<hmac>`（get_segment / list_recent_dynamics /
    search_dynamics / get_dynamic_context 的 samples）
  - `shot:v2.h<shots_YYYYMM 行 id>.<hmac>`（search_screenshots）
  - `hmac = HMAC-SHA256(secret, "v2|<kind>|<rowId>|<canonicalPath>")` 前 16 字节 hex。
  - `secret`：首次使用生成 32 字节 SecureRandom，hex 存 SharedPreferences
    （`screenmemo_mcp` / `evidence_secret`），进程内缓存。
- 解码校验链（三项全过才读文件）：
  1. 严格格式 + HMAC 常量时间校验；
  2. 按行 id 回查 DB（samples 查 master 库 `segment_samples`；shots 遍历已注册分片
     月表按 `id` 收集候选，HMAC 覆盖 canonicalPath 消歧）；
  3. `File.canonicalPath` 必须位于 `filesDir/output/screen` 之下（挡 `..` 与符号链接）。
- 旧 base64 格式一律拒绝（正则不含 `.` 不匹配，直接判 invalid）。
- `segment_id` 入参路径保留，但同样统一过 canonicalPath 根目录校验。
- 保留 8MB 单文件上限与 limit≤5；被拒绝的 ref 计入 `rejected_refs` 字段。

### 2. 信息泄露（file_path / database.path / lanIp）

- `getSamples`、`searchDocsInternal`、`searchScreenshotsInternal`、
  `getEvidenceImages` 输出中的 `file_path` 全部移除（image_ref 保留为新 opaque 格式）。
- `screenmemo_status`：`database.path` 移除；service map 移除 `lanIp`/`endpoint`，
  token 仍为 `***`。
- Flutter UI 需要的 `lanIp`/`endpoint`/`token` 走 method channel
  （`getMcpServerStatus`，`McpServerService.getStatus`）保留不变，与 MCP 输出隔离。
- 已确认 Dart 侧无任何代码消费 MCP 工具输出的 `file_path`（lib 内所有 `file_path`
  均为本地 DB/内部工具），无需额外改造。

### 3. 鉴权弱点（0.0.0.0 绑定 + 非常量时间比较）

- Bearer 比较改为：双侧 SHA-256 归一化长度后 `MessageDigest.isEqual`（常量时间）。
- 默认绑定 `127.0.0.1`；新增「局域网模式」开关（SharedPreferences `screenmemo_mcp`
  键 `mcp_bind_lan`，默认 false），显式开启并经风险确认对话框后才绑 `0.0.0.0`。
- 运行中切换开关通过 `ACTION_RESTART` 立即重启服务应用新绑定。
- `getStatus` 的 `endpoint` 随绑定模式变化：回环模式固定 `http://127.0.0.1:37621/mcp`。
- 新增 method channel `setMcpBindLan`；设置页新增开关卡片 + 风险警示块，
  l10n 覆盖 en/zh/ja/ko（arb + 已签入的 generated dart 同步手改，未运行 gen-l10n）。

## 改动文件清单

Android（main）：
- `mcp/McpEvidenceSecurity.kt`（新增，纯 JVM）
- `mcp/ScreenMemoMcpRepository.kt`
- `mcp/McpHttpServer.kt`
- `mcp/McpServerService.kt`
- `mcp/McpToolRegistry.kt`（仅 image_refs 描述文案）
- `channel/ExtractedMainMethodHandler.kt`（`setMcpBindLan`）

Android（test）：
- `mcp/McpEvidenceSecurityTest.kt`（新增，18 用例）
- `mcp/McpHttpServerDirectToolTest.kt`（+isAuthorized 行为、+status 无敏感字段回归）

Flutter：
- `lib/features/mcp/application/mcp_service.dart`（bindLan 字段 + setBindLan）
- `lib/features/settings/presentation/pages/settings_page_mcp_part.dart`（局域网模式开关）
- `lib/l10n/app_{en,zh,ja,ko}.arb`、`app_localizations{,_en,_zh,_ja,_ko}.dart`

未触碰：版本号、签名、包名、gradle 配置（`gradle-wrapper.properties` /
`gradle.properties` / `settings.gradle.kts` / `build.gradle.kts` 均未提交）。
工作区中 `analysis_options.yaml`、`pubspec.lock`、gradle 文件的环境性改动未入库。

## 测试方式

- 已在容器内用 gradle 缓存中的 kotlin-compiler-embeddable 2.2.0 对
  `McpEvidenceSecurity.kt` + 测试做独立 JVM 编译并运行：**18/18 通过**
  （编解码往返、伪造/错密钥/换路径/换行 id 拒绝、旧格式拒绝、`..` 与符号链接逃逸拒绝、
  同前缀兄弟目录拒绝、token 等长/不等长/空前缀行为）。
- 整个 `mcp` 包 5 个文件已用 android-36 android.jar + androidx.core 做类型检查：通过。
- `dart analyze` 全部改动 Dart 文件：No issues。`tool/i18n_audit.dart --check`：通过。
- 未运行（任务约束，后台有 flutter build）：`./gradlew :app:testDebugUnitTest`、
  `flutter test`。建议合入前补跑，重点：`McpEvidenceSecurityTest`、
  `McpHttpServerDirectToolTest`、`i18n_audit_test`、`codebase_structure_test`。

## 取舍与剩余风险

1. shots 行 id 仅分片月表内唯一，引用不编码分片身份；解码按 id 全分片收集候选后由
   HMAC(canonicalPath) 消歧。分片合并/重建会使旧 `h` 引用失效（报 invalid），属可接受
   的失效语义（引用本为短期证据句柄）。
2. 历史 DB 若存有指向旧外部存储目录的 file_path（迁移前数据），根目录校验会拒绝其
   图片读取；文本内容不受影响。
3. `h` 引用解码需遍历分片库（每次 ≤5 个 ref、每表 PK 点查），量级可接受。
4. 绑定地址在「设置→MCP」页显式切换；开启后 UI 常驻红色风险警示。回环模式下
   `adb forward` 仍是可用的本机调试通道（有意保留）。
5. `MessageDigest.isEqual` 前先做 SHA-256，规避长度侧信道与提前退出差异。
6. MCP 无 TLS（沿用上游设计）：局域网模式仍依赖 token 保密；token 重置入口不变。
7. generated localization 为手改（无法运行 gen-l10n）；下次 `flutter gen-l10n` 会按
   arb 重生成同样内容，键序可能略有差异，无碍。

## 上游回馈建议

- 三个修复点彼此独立，可按两个 commit 直接 cherry-pick 回馈上游。
- 建议上游额外考虑：MCP 端口可配置化、token 展示改为一次性复制、
  `h` 引用内嵌分片身份以避免全分片扫描（会破坏现有"纯 id"格式，需版本化为 v3）。

## 附：flutter test 全量基线（2026-10-31，N100，Flutter 3.47.9）

- 环境修复：需 `libsqlite3-dev`（sqflite_ffi 加载系统 libsqlite3.so）。
- 本分支（whale/hardening）：**256 过 / 5 失败**。
- 失败项归因（与上游 2f57da1 对照实测）：
  - `codebase_structure_test`（行数门禁）：上游同样失败，上游既有问题。
  - `segment_status_page_test`×3、`screenshot_viewer_page_test`×1：上游在本工具链下**连编译都过不了**（新 Flutter 不兼容），本分支可运行但行为有差异；与 WM-03 改动面（MCP/证据/绑定）无关。
- 结论：WM-03 加固未引入任何测试回归。
