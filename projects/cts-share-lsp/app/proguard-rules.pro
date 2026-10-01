# Shrink only: drop unused library code, never rename or rewrite module code.
# The framework loads CtsShareModule by name (META-INF/xposed/java_init.list) and
# ShareBootstrap is reached reflectively from inside Google's process.
-dontobfuscate
-keep class com.jieoz.ctsshare.** { *; }
# XposedService client: binder/AIDL stubs are resolved across processes.
-keep class io.github.libxposed.** { *; }
# libxposed api is compileOnly (provided by LSPosed at runtime).
-dontwarn io.github.libxposed.api.**
