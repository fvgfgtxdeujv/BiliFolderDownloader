# debug 变体专属 R8 规则：只裁剪（shrink），不混淆、不优化。
#
# 目标：开发版 APK 与 release 一样裁剪掉死代码和无用资源，
# 但保留原始类名/方法名与字节码结构，保证：
#   - 断点调试、单步跟踪行为不变
#   - 崩溃堆栈直接可读，无需 retrace
# 关闭项（与 proguard-android-optimize.txt 中的优化配置不冲突，此处显式关掉）：
-dontobfuscate
-dontoptimize
