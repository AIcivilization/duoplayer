import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名密钥只放在本机 ~/.config/duoplayer/，绝不进仓库。
// 所有版本必须用同一把密钥签名，否则无法覆盖安装（自动更新会失败）。
val releaseKeyProps = File(System.getProperty("user.home"), ".config/duoplayer/keystore.properties")
val hasReleaseKey = releaseKeyProps.exists()

android {
    namespace = "com.zxdual.player"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.zxdual.player"
        // NW-ZX500 系列出厂/最终固件均为 Android 9 (API 28)
        minSdk = 28
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"

        ndk {
            // ZX500 是 64 位 ARM；保留 armeabi-v7a 以防万一
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val p = Properties().apply { releaseKeyProps.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有发布密钥时（别人克隆仓库自己编译）退回 debug 签名，只能自用、不能接收官方更新
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
