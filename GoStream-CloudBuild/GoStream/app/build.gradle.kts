plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gostream.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gostream.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Maintained FFmpegKit-compatible Android build. Used to remux GoPro H.264/MPEG-TS to RTMP/FLV.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
}
