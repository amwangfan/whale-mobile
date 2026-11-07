/// 角色（Persona）模型。
///
/// 数据源：Core 工作台 `GET /api/studio/personas`（极简视图，不回显
/// external_prompt 等内部文本）。会话绑定经
/// `PUT /api/studio/conversations/{key}/persona`。
library;

class Persona {
  const Persona({
    required this.id,
    required this.title,
    required this.name,
    this.summary,
    this.temperament,
    this.category,
    this.builtin = false,
  });

  final String id;
  final String title;
  final String name;
  final String? summary;
  final String? temperament;
  final String? category;

  /// 内置角色（不可删改）。
  final bool builtin;

  factory Persona.fromJson(Map<String, dynamic> json) {
    return Persona(
      id: json['id'] as String? ?? '',
      title: json['title'] as String? ?? '',
      name: json['name'] as String? ?? '',
      summary: json['summary'] as String?,
      temperament: json['temperament'] as String?,
      category: json['category'] as String?,
      builtin: json['builtin'] as bool? ?? false,
    );
  }

  /// 列表展示名（title 优先，退化 id）。
  String get displayName => title.isNotEmpty ? title : id;
}
