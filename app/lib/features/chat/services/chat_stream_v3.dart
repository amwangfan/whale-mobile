import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/run.dart';
import 'device_config.dart';
import 'run_feed.dart';

/// v3 弱网/无长连退路：`POST /api/chat/stream`（SSE `run/step/delta/done`，
/// 同一份 Run；契约 §1「流式输出」行 + PROTOCOL §1）。
///
/// 帧形（core server/http.py chat_stream）：
/// ```
/// event: run\ndata: {"run_id","conversation_id"}\n\n
/// event: step\ndata: {step}\n\n
/// event: delta\ndata: {"text"}\n\n
/// event: done\ndata: {"run_id","status","text","conversation_id","error"}\n\n
/// ```
///
/// 断线语义：连接在 done 前关闭 → 补发本地合成 [RunFeedAborted]，由控制器
/// 把该轮显式标失败（不重发）。
class ChatStreamV3 {
  ChatStreamV3({String? baseUrl, String? token, HttpClient? httpClient})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final HttpClient _http;

  static const Duration connectTimeout = Duration(seconds: 15);

  /// 发起一轮流式对话。[openStream] 为测试注入点（直接给假字节流）。
  Stream<RunFeedEvent> streamTurn({
    required String text,
    String? conversationId,
    List<String> images = const [],
    String channel = 'mobile',
    Stream<List<int>> Function()? openStream,
  }) async* {
    final Stream<List<int>> byteStream;
    try {
      byteStream = openStream != null
          ? openStream()
          : await _open(
              text: text, conversationId: conversationId, images: images, channel: channel);
    } on ChatStreamException catch (e) {
      yield RunFeedAborted('${e.code}: ${e.message}');
      return;
    }
    var terminal = false;
    try {
      await for (final frame in parseSseFrames(byteStream)) {
        final ev = _toFeedEvent(frame.$1, frame.$2);
        if (ev == null) continue;
        if (ev is RunFeedTerminal || ev is RunFeedAborted) terminal = true;
        yield ev;
        if (terminal) return;
      }
    } on Exception catch (e) {
      yield RunFeedAborted('stream_aborted: $e');
      return;
    }
    if (!terminal) {
      // 连接在终态前被关闭（断线）：标记失败，不静默、不重发
      yield const RunFeedAborted('stream_disconnected');
    }
  }

  RunFeedEvent? _toFeedEvent(String event, Map<String, dynamic> data) {
    switch (event) {
      case 'run':
        return RunFeedStarted(RunRecord(
          id: data['run_id'] as String? ?? '',
          conversationId: data['conversation_id'] as String?,
        ));
      case 'step':
        return RunFeedStep(RunStep.fromJson(data));
      case 'delta':
        return RunFeedDelta(
          runId: '', // SSE 退路单轮单 run；控制器按当前在飞 run 归并
          delta: data['text'] as String? ?? '',
        );
      case 'done':
        return RunFeedTerminal(RunRecord(
          id: data['run_id'] as String? ?? '',
          conversationId: data['conversation_id'] as String?,
          status: data['status'] as String? ?? 'completed',
          output: data['text'] as String? ?? '',
          error: data['error'] as String? ?? '',
        ));
      default:
        return null; // 未知事件静默忽略（前向兼容）
    }
  }

  Future<Stream<List<int>>> _open({
    required String text,
    String? conversationId,
    required List<String> images,
    required String channel,
  }) async {
    final base = _baseUrlOverride ?? await DeviceConfig.baseUrl();
    final token = _tokenOverride ?? await DeviceConfig.apiToken();
    final uri = Uri.parse('$base/api/chat/stream');
    final HttpClientRequest req;
    try {
      req = await _http.postUrl(uri).timeout(connectTimeout);
      req.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      req.headers
          .set(HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      req.write(jsonEncode(<String, dynamic>{
        'text': text,
        if (conversationId != null) 'conversation_id': conversationId,
        'channel': channel,
        if (images.isNotEmpty) 'images': images,
      }));
    } on Exception catch (e) {
      throw ChatStreamException('network_error', '$e');
    }
    final HttpClientResponse res;
    try {
      // 流式响应：不给整体超时（长回复合法）；建连超时在上面已覆盖。
      res = await req.close();
    } on Exception catch (e) {
      throw ChatStreamException('network_error', '$e');
    }
    if (res.statusCode != 200) {
      // 错误仍是 JSON 信封
      final body = await utf8.decodeStream(res);
      try {
        final json = (jsonDecode(body) as Map).cast<String, dynamic>();
        final err = json['error'];
        if (err is Map) {
          throw ChatStreamException(
            err['code'] as String? ?? 'http_${res.statusCode}',
            err['message'] as String? ?? 'HTTP ${res.statusCode}',
          );
        }
      } catch (e) {
        if (e is ChatStreamException) rethrow;
      }
      throw ChatStreamException(
          'http_${res.statusCode}', 'HTTP ${res.statusCode}: $body');
    }
    return res;
  }

  void close() => _http.close();
}

/// 流式异常（连接级失败：HTTP 非 200、网络错误）。
class ChatStreamException implements Exception {
  const ChatStreamException(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => '[$code] $message';
}

/// SSE 帧 = (event, data-JSON)。手写增量解析（dart:io 字节流 → 帧）：
/// - 帧以空行结尾；`data:` 行累积（多行以 \n 拼接）；
/// - `event:` 捕获事件名（缺省 "message"）；`id:`/注释（: 开头）/未知字段忽略；
/// - 输入字节流可在任意位置切断（按行缓冲）。
Stream<(String, Map<String, dynamic>)> parseSseFrames(
    Stream<List<int>> bytes) async* {
  final dataLines = <String>[];
  var eventName = 'message';
  var lineBuf = <int>[];

  // 处理一整行（不含换行符）；返回解析出的帧（0 或 1 个）。
  List<(String, Map<String, dynamic>)> handleLine(String line) {
    if (line.isEmpty) {
      if (dataLines.isNotEmpty) {
        final data = dataLines.join('\n');
        final name = eventName;
        dataLines.clear();
        eventName = 'message';
        try {
          final obj = jsonDecode(data);
          if (obj is Map) {
            return [(name, obj.cast<String, dynamic>())];
          }
        } catch (_) {
          // 非 JSON 帧（如 keepalive）：忽略
        }
      }
      eventName = 'message';
      return const [];
    }
    if (line.startsWith(':')) return const []; // 注释/keepalive
    final colon = line.indexOf(':');
    final field = colon < 0 ? line : line.substring(0, colon);
    var value = colon < 0 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    if (field == 'data') {
      dataLines.add(value);
    } else if (field == 'event') {
      eventName = value;
    }
    return const [];
  }

  await for (final chunk in bytes) {
    for (final b in chunk) {
      if (b == 0x0A) {
        var s = utf8.decode(lineBuf, allowMalformed: true);
        if (s.endsWith('\r')) s = s.substring(0, s.length - 1);
        lineBuf = <int>[];
        for (final frame in handleLine(s)) {
          yield frame;
        }
      } else {
        lineBuf.add(b);
      }
    }
  }
  // 连接关闭：冲刷残余（无尾换行的最后一帧）
  if (lineBuf.isNotEmpty) {
    var s = utf8.decode(lineBuf, allowMalformed: true);
    if (s.endsWith('\r')) s = s.substring(0, s.length - 1);
    for (final frame in handleLine(s)) {
      yield frame;
    }
  }
  if (dataLines.isNotEmpty) {
    final data = dataLines.join('\n');
    final name = eventName;
    try {
      final obj = jsonDecode(data);
      if (obj is Map) yield (name, obj.cast<String, dynamic>());
    } catch (_) {/* 忽略残帧 */}
  }
}
