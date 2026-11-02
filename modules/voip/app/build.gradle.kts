plugins {
    id("com.android.application")
}

android {
    namespace = "com.wangfanlab.whale.voip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wangfanlab.whale.voip"
        minSdk = 26
        targetSdk = 34
        // Carried over from whale-io call-bridge AndroidManifest (unchanged).
        versionCode = 10
        versionName = "1.8.1-user"
    }

    compileOptions {
        // JDK 17 source/target compatibility; sources are Java-8-era syntax and
        // intentionally kept byte-for-byte (minus the mandated renames).
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
