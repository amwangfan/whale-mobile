/// 角色（Persona）模型（v3：`personas.list` 返回 PersonaCard 形状）。
///
/// Core PersonaCard：`{id, name, tagline, description, personality, style,
/// scenario, greeting, examples, boundaries, avatar, color, builtin, extra}`。
/// 会话绑定经 `conversations.update {id, persona_id}`。
library;

class Persona {
  const Persona({
    required this.id,
    required this.name,
    this.tagline = '',
    this.description = '',
    this.personality = '',
    this.avatar = '',
    this.color = '',
    this.builtin = false,
  });

  final String id;
  final String name;
  final String tagline;
  final String description;
  final String personality;

  /// emoji 或 data: URL。
  final String avatar;
  final String color;

  /// 内置角色（不可删改）。
  final bool builtin;

  factory Persona.fromJson(Map<String, dynamic> json) {
    return Persona(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      tagline: json['tagline'] as String? ?? '',
      description: json['description'] as String? ?? '',
      personality: json['personality'] as String? ?? '',
      avatar: json['avatar'] as String? ?? '',
      color: json['color'] as String? ?? '',
      builtin: json['builtin'] as bool? ?? false,
    );
  }

  /// 列表展示名（name 优先，退化 id）。
  String get displayName => name.isNotEmpty ? name : id;
}
