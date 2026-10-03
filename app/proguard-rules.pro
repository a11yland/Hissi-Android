# App-specific R8 keep rules. Library needs (Ktor, kotlinx.serialization,
# Compose, Maps) are covered by their bundled consumer rules — add rules here
# only when a release build demonstrably breaks without them.

# WorkManager instantiates its Room database reflectively; R8 full mode strips
# the generated no-arg constructor without this (startup crash:
# NoSuchMethodException androidx.work.impl.WorkDatabase_Impl.<init>).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
