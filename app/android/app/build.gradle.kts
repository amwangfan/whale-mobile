import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release 签名：存在 android/key.properties（不入库）则用正式签名，否则回退 debug 签名
// （CI/开源构建无密钥也能编 release 包）。
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("key.properties")
val keystoreReady = keystorePropsFile.exists()
if (keystoreReady) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.wangfanlab.whale"
    // file_picker/flutter_plugin_android_lifecycle 要求 compileSdk 36+
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        // CapabilityRegistry 按 BuildConfig.FLAVOR 区分 daily/root 能力
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 主线二（双 APK）：同一代码库构建日常版/Root 版。Root 版独有能力
    // （Root Shell、设备控制、MediaProjection 录制等）只在 root 变体注册，
    // 不靠 UI 隐藏（contracts/v2 §5.2 CapabilityRegistry）。
    flavorDimensions += "edition"
    productFlavors {
        create("daily") {
            dimension = "edition"
            // 保留已发布的正式 applicationId
        }
        create("root") {
            dimension = "edition"
            applicationIdSuffix = ".root"
            versionNameSuffix = "-root"
        }
    }

    defaultConfig {
        applicationId = "com.wangfanlab.whale"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // 传输层要求 minSdk 26（Keystore AES/GCM + 无 StrongBox 时的软件兜底路径）。
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Flutter release 默认 R8 收缩；反射装配的模块须显式 keep
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // 传输层（transport/）依赖：
    // - BouncyCastle：Ed25519 轻量级 API（Android Keystore 不支持 Ed25519，见 DeviceIdentity）
    // - OkHttp：WSS 客户端
    // - Gson：协议信封 JSON 编解码（纯 JVM，可在本地单元测试中运行）
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // androidx.core：通知监听模块的 NotificationManagerCompat.getEnabledListenerPackages
    // （Flutter embedding 本就传递依赖；显式声明以锁定编译期 API）
    implementation("androidx.core:core:1.13.1")
    // notification2：ActionReceiver 内 goAsync 协程（goAsync 窗口内完成 Outbox 上行尝试）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // SM-02：ML Kit 中文 OCR（本地识别，与 ScreenMemo 同款依赖；不上云）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // SEC-01：队列加密迁移的本地仪器级测试（SQLite/Context 由 Robolectric 提供）
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

flutter {
    source = "../.."
}
