import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/attachment.dart';
import 'chat_api.dart';
import 'device_config.dart';

/// 聊天流式事件（规划 §5.5 词汇表 + Core mobile_chat.chat_stream_v2 实际帧）。
///
/// 事件词汇表：phase / delta / tool.started / tool.progress / tool.result /
/// attachment.created / done / error；另加本地合成事件 aborted（断线，
/// 非服务端语义，用于把「流中断」显式标失败，绝不伪装成完成）。
class ChatStreamEvent {
  const ChatStreamEvent._(
    this.type, {
    this.phase,
    this.text,
    this.toolCallId,
    this.toolName,
    this.summary,
    this.progress,
    this.receipt,
    this.attachment,
    this.reply,
    this.status,
    this.taskId,
    this.duplicate,
    this.errorCode,
    this.userMessageId,
    this.assistantMessageId,
  });

  /// phase / delta / tool.started / tool.progress / tool.result /
  /// attachment.created / done / error / aborted（本地合成）。
  final String type;

  // phase
  final String? phase;

  // delta
  final String? text;

  // tool.*
  final String? toolCallId;
  final String? toolName;
  final String? summary;
  final int? progress;
  final String? receipt;

  // attachment.created
  final AttachmentRef? attachment;

  // done
  final String? reply;
  final String? status;
  final String? taskId;
  final bool? duplicate;
  final String? userMessageId;
  final String? assistantMessageId;

  // error / aborted
  final String? errorCode;

  bool get isTerminal =>
      type == 'done' || type == 'error' || type == 'aborted';

  /// 从 SSE `data:` JSON 解析。未知 type 返回 null（前向兼容忽略）。
  static ChatStreamEvent? fromJson(Map<String, dynamic> json) {
    final type = json['type'] as String? ?? '';
    switch (type) {
      case 'phase':
        return ChatStreamEvent._('phase', phase: json['phase'] as String?);
      case 'delta':
        return ChatStreamEvent._('delta', text: json['text'] as String? ?? '');
      case 'tool.started':
        return ChatStreamEvent._(
          'tool.started',
          toolCallId: json['tool_call_id'] as String?,
          toolName: json['tool'] as String? ?? json['tool_name'] as String?,
        );
      case 'tool.progress':
        return ChatStreamEvent._(
          'tool.progress',
          toolCallId: json['tool_call_id'] as String?,
          summary: json['summary'] as String? ?? json['message'] as String?,
          progress: (json['progress'] as num?)?.toInt(),
        );
      case 'tool.result':
        return ChatStreamEvent._(
          'tool.result',
          toolCallId: json['tool_call_id'] as String?,
          toolName: json['tool'] as String? ?? json['tool_name'] as String?,
          summary: json['summary'] as String?,
          receipt: json['receipt'] as String?,
        );
      case 'attachment.created':
        final ref = json['attachment'] ?? json['ref'];
        return ChatStreamEvent._(
          'attachment.created',
          attachment: ref is Map
              ? AttachmentRef.fromJson(ref.cast<String, dynamic>())
              : null,
        );
      case 'done':
        final ids = json['message_ids'];
        return ChatStreamEvent._(
          'done',
          reply: json['reply'] as String?,
          status: json['status'] as String?,
          taskId: json['task_id'] as String?,
          duplicate: json['duplicate'] as bool?,
          userMessageId: ids is Map ? ids['user'] as String? : null,
          assistantMessageId:
              ids is Map ? ids['assistant'] as String? : null,
        );
      case 'error':
        return ChatStreamEvent._(
          'error',
          errorCode: json['error'] as String? ?? json['code'] as String?,
        );
      default:
        return null; // 未知事件静默忽略（前向兼容）
    }
  }
}

/// 流式异常（连接级失败：HTTP 非 200、网络错误）。
class ChatStreamException implements Exception {
  const ChatStreamException(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => '[$code] $message';
}

/// 手写 SSE 增量解析（dart:io 字节流 → 帧）。
///
/// 规则（W3C SSE 子集）：
/// - 帧以空行结尾；`data:` 行累积（多行 data 以 \n 拼接）；
/// - `event:` / `id:` / 注释（: 开头）/ 未知字段忽略（Core 只发 data）；
/// - 输入字节流可在任意位置切断（按行缓冲）。
Stream<Map<String, dynamic>> parseSseJson(Stream<List<int>> bytes) async* {
  final dataLines = <String>[];
  var lineBuf = <int>[];

  // 处理一整行（不含换行符）；返回解析出的帧（0 或 1 个）。
  List<Map<String, dynamic>> handleLine(String line) {
    if (line.isEmpty) {
      // 空行 = 帧结束
      if (dataLines.isNotEmpty) {
        final data = dataLines.join('\n');
        dataLines.clear();
        try {
          final obj = jsonDecode(data);
          if (obj is Map) {
            return [obj.cast<String, dynamic>()];
          }
        } catch (_) {
          // 非 JSON 帧（如 keepalive）：忽略
        }
      }
      return const [];
    }
    if (line.startsWith(':')) return const []; // 注释
    final colon = line.indexOf(':');
    final field = colon < 0 ? line : line.substring(0, colon);
    var value = colon < 0 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    if (field == 'data') dataLines.add(value);
    return const [];
  }

  await for (final chunk in bytes) {
    for (final b in chunk) {
      if (b == 0x0A) {
        // \n；去掉尾部 \r
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
    dataLines.clear();
    try {
      final obj = jsonDecode(data);
      if (obj is Map) yield obj.cast<String, dynamic>();
    } catch (_) {/* 忽略残帧 */}
  }
}

/// SSE 聊天流客户端（dart:io HttpClient 手写；不走 EventSource 依赖）。
///
/// 断线语义（规划 §5.7 + 任务约束）：连接在收到 done/error 前关闭 →
/// 补发本地合成 `aborted` 事件，由控制器把该轮标记失败；**不重发请求**。
class ChatStreamClient {
  ChatStreamClient({String? baseUrl, String? token, HttpClient? httpClient})
      : _baseUrlOverride = baseUrl,
        _tokenOverride = token,
        _http = httpClient ?? HttpClient();

  final String? _baseUrlOverride;
  final String? _tokenOverride;
  final HttpClient _http;

  /// 发起流式回复。请求体：
  /// `{conversation_id, client_message_id, text, attachments:[{attachment_id}]}`。
  ///
  /// [openStream] 测试注入点：直接给假字节流（跳过网络）。
  Stream<ChatStreamEvent> streamReply({
    required String conversationId,
    required String clientMessageId,
    required String text,
    List<AttachmentRef> attachments = const [],
    Stream<List<int>> Function()? openStream,
  }) async* {
    final Stream<List<int>> byteStream;
    if (openStream != null) {
      byteStream = openStream();
    } else {
      byteStream = await _open(
        conversationId: conversationId,
        clientMessageId: clientMessageId,
        text: text,
        attachments: attachments,
      );
    }
    var terminal = false;
    try {
      await for (final json in parseSseJson(byteStream)) {
        final event = ChatStreamEvent.fromJson(json);
        if (event == null) continue;
        if (event.isTerminal) terminal = true;
        yield event;
        if (terminal) return; // done/error 后正常收尾
      }
    } on Exception catch (e) {
      // 流中途异常（连接重置等）→ 显式失败事件
      yield ChatStreamEvent._('aborted', errorCode: 'stream_aborted: $e');
      return;
    }
    if (!terminal) {
      // 连接在终态前被关闭（断线）：标记失败，不静默、不重发
      yield const ChatStreamEvent._('aborted',
          errorCode: 'stream_disconnected');
    }
  }

  Future<Stream<List<int>>> _open({
    required String conversationId,
    required String clientMessageId,
    required String text,
    required List<AttachmentRef> attachments,
  }) async {
    final base = _baseUrlOverride ?? await DeviceConfig.baseUrl();
    final token = _tokenOverride ?? await DeviceConfig.apiToken();
    final uri = Uri.parse('$base/api/v2/chat/stream');
    final HttpClientRequest req;
    try {
      req = await _http.postUrl(uri).timeout(ChatApi.timeout);
      req.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      req.headers.set(
          HttpHeaders.contentTypeHeader, 'application/json; charset=utf-8');
      if (token.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      }
      req.write(jsonEncode(<String, dynamic>{
        'conversation_id': conversationId,
        'client_message_id': clientMessageId,
        'text': text,
        if (attachments.isNotEmpty)
          'attachments': attachments.map((a) => a.toMessageRef()).toList(),
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
      // 错误仍是 JSON 信封（附件缺失 404/410 等在 SSE 开始前返回）
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
      throw ChatStreamException('http_${res.statusCode}', 'HTTP ${res.statusCode}: $body');
    }
    return res;
  }

  void close() => _http.close();
}
