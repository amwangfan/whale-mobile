import 'package:flutter_test/flutter_test.dart';

import 'package:whale_mobile/features/chat/models/attachment.dart';
import 'package:whale_mobile/features/chat/models/message.dart';

void main() {
  group('AttachmentRef 模型解析（contracts/v2 §11）', () {
    test('完整字段解析', () {
      final ref = AttachmentRef.fromJson(const {
        'attachment_id': 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
        'type': 'image',
        'mime': 'image/webp',
        'size': 123456,
        'sha256': '9f86d0',
        'width': 512,
        'height': 512,
        'duration_ms': null,
      });
      expect(ref.attachmentId, 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC');
      expect(ref.type, AttachmentType.image);
      expect(ref.mime, 'image/webp');
      expect(ref.size, 123456);
      expect(ref.width, 512);
      expect(ref.durationMs, isNull);
      expect(ref.hasValidId, isTrue);
    });

    test('缺省字段容错 + 未知 type 落 file', () {
      final ref = AttachmentRef.fromJson(const {
        'attachment_id': 'att_X',
        'type': 'something_else',
        'mime': 'application/octet-stream',
        'size': 1,
      });
      expect(ref.type, AttachmentType.file);
      expect(ref.width, isNull);
      expect(ref.hasValidId, isFalse); // 非法 id 可识别
    });

    test('toJson 严格 schema 子集（不带 null 键）', () {
      const ref = AttachmentRef(
        attachmentId: 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
        type: AttachmentType.video,
        mime: 'video/mp4',
        size: 10,
        durationMs: 3000,
      );
      final json = ref.toJson();
      expect(json.keys, containsAll(
          ['attachment_id', 'type', 'mime', 'size', 'duration_ms']));
      expect(json.containsKey('width'), isFalse);
      expect(json.containsKey('sha256'), isFalse);
      // 消息引用只带 id（不反复传元数据）
      expect(ref.toMessageRef(),
          {'attachment_id': 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC'});
    });

    test('type 枚举四值齐全', () {
      expect(AttachmentType.fromWire('image'), AttachmentType.image);
      expect(AttachmentType.fromWire('video'), AttachmentType.video);
      expect(AttachmentType.fromWire('audio'), AttachmentType.audio);
      expect(AttachmentType.fromWire('file'), AttachmentType.file);
    });
  });

  group('ChatMessage 历史解析', () {
    test('历史回落（via=history）无 id/时间戳不伪造', () {
      final m = ChatMessage.fromJson(const {
        'message_id': null,
        'role': 'assistant',
        'text': '历史',
        'created_at': null,
        'attachments': [],
        'via': 'history',
      });
      expect(m.messageId, isNull);
      expect(m.createdAt, isNull);
      expect(m.via, 'history');
    });

    test('带附件的消息解析', () {
      final m = ChatMessage.fromJson(const {
        'message_id': 'evt_01',
        'role': 'user',
        'text': '看图',
        'created_at': 1794052800000,
        'client_message_id': 'mob_1_1',
        'attachments': [
          {
            'attachment_id': 'att_01J9XK3EM8TQ2ZP0VN6W4RA7YC',
            'type': 'image',
            'mime': 'image/png',
            'size': 5,
          }
        ],
      });
      expect(m.attachments.length, 1);
      expect(m.clientMessageId, 'mob_1_1');
      expect(m.sendState, MessageSendState.settled);
    });
  });
}
