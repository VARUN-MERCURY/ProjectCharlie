# Keep JNI native methods
-keep class com.alpha.charlie.stt.WhisperEngine { *; }
-keep class com.alpha.charlie.llm.LlamaEngine { *; }
-keepclassmembers class * {
    native <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
