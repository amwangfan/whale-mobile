# 反射装配的 v2 模块（NodeRuntimeV2.registerAgentHandlers 经 Class.forName 探测）：
# R8 无法静态发现，必须显式保留，否则 release 构建丢失执行器。
-keep class com.wangfanlab.whale.modules.agent2.** { *; }
-keep class com.wangfanlab.whale.modules.recording.** { *; }
