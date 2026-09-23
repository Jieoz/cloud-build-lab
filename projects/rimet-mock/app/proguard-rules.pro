# Keep the Xposed entry class and its callbacks — the framework loads it by name from
# META-INF/xposed/java_init.list, and reflection-based hooks reference method names.
-keep class com.jieoz.rimetmock.RimetMockModule { *; }
-keep class com.jieoz.rimetmock.ModuleUtils { *; }
-keepclassmembers class com.jieoz.rimetmock.** { *; }
