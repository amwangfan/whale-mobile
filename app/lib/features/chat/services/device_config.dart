import 'package:shared_preferences/shared_preferences.dart';

/// 设备配置：Core REST 接入参数（base_url + 控制台 Bearer token）。
///
/// 读取顺序：
/// 1. SharedPreferences（用户在「我的 → Core 连接」里改过的值）；
/// 2. `--dart-define=WM_CORE_BASE_URL` / `WM_CORE_TOKEN`（联调注入，不入库）；
/// 3. 内置默认（Android 模拟器宿主机回环 10.0.2.2:8080）。
///
/// 注意：Core 管理 API 的 Bearer token 与 WSS 设备身份是两套凭据（协议 §4）；
/// 聊天面 REST 沿用控制台 Bearer（server.py 对 /api/* 统一拦截）。
class DeviceConfig {
  DeviceConfig._();

  static const String prefBaseUrl = 'whale_core_base_url';
  static const String prefToken = 'whale_core_api_token';

  /// 联调注入默认值（生产不内置任何凭据）。
  static const String defaultBaseUrl = String.fromEnvironment(
    'WM_CORE_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );
  static const String defaultToken = String.fromEnvironment(
    'WM_CORE_TOKEN',
    defaultValue: '',
  );

  /// 读取生效的 base_url（去掉尾部斜杠）。
  static Future<String> baseUrl() async {
    final prefs = await SharedPreferences.getInstance();
    final v = prefs.getString(prefBaseUrl)?.trim();
    if (v != null && v.isNotEmpty) return _strip(v);
    return _strip(defaultBaseUrl);
  }

  /// 读取生效的 API token（可能为空 → 请求会 401，UI 显式提示）。
  static Future<String> apiToken() async {
    final prefs = await SharedPreferences.getInstance();
    final v = prefs.getString(prefToken)?.trim();
    if (v != null && v.isNotEmpty) return v;
    return defaultToken;
  }

  static Future<void> save({String? baseUrl, String? token}) async {
    final prefs = await SharedPreferences.getInstance();
    if (baseUrl != null) {
      final v = baseUrl.trim();
      if (v.isEmpty) {
        await prefs.remove(prefBaseUrl);
      } else {
        await prefs.setString(prefBaseUrl, _strip(v));
      }
    }
    if (token != null) {
      final v = token.trim();
      if (v.isEmpty) {
        await prefs.remove(prefToken);
      } else {
        await prefs.setString(prefToken, v);
      }
    }
  }

  static String _strip(String url) =>
      url.endsWith('/') ? url.substring(0, url.length - 1) : url;
}
