import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置从 keystore.properties 读取（该文件不入库）。
// 读不到就退回 debug 签名 —— 这样别人 clone 下来不改任何东西也能直接编译。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// 本机私有配置（SDK 路径、局域网更新地址），该文件同样不入库。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 局域网更新服务地址，例如 http://192.168.1.100:8765
// 只有本机开发时才需要在 local.properties 里配这一行，不配就只走公网源。
val lanUpdateUrl = localProps.getProperty("lanUpdateUrl") ?: ""

android {
    namespace = "com.example.airforce"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.airforce"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        buildConfigField("String", "LAN_UPDATE_URL", "\"$lanUpdateUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
