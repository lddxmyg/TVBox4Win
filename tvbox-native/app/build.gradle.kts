plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.lddxmyg.tvbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.lddxmyg.tvbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "1.12"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")

    // HTTP（复刻 requests 层；TVBox 配置源常有坏证书，用宽松 TLS client）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 海报图加载
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // 播放内核：m3u8 / mp4 / 直链，支持自定义请求头
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // 爬虫运行时：原版 TVBox 同款（HarlonWang/quickjs-wrapper，Maven Central 预编译）
    implementation(files("libs/wrapper-java-2.4.5.jar"))
    implementation(files("libs/wrapper-android-2.4.5.aar"))

    // 移植自原版爬虫运行时的依赖
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.14.1")
}
