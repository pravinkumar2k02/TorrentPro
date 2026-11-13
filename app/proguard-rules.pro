-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Application models
-keep class com.torrentpro.app.data.model.** { *; }
-keepclassmembers class com.torrentpro.app.data.model.** { *; }
