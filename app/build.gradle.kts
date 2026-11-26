import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

// Срез 7 (issue #8): единственное ядро — Xray-core, бинарь
// app/src/main/jniLibs/*/libmaestro_subxhttp.so. sing-box/libbox удалён
// полностью: XHTTP он не поддерживает ни в одной версии, а узлы Maestro
// и Akonit — VLESS/XHTTP/REALITY. Нативный JNI-лаунчер
// (src/main/cpp/xray_spawn.c) собирается через ndk-build — CMake не нужен.
android {
    namespace = "com.maestrovpn.client"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.maestrovpn.client"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-slice7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Своя deeplink-схема нового клиента (решение среза 1; поменять легко).
        buildConfigField("String", "DEEP_LINK_SCHEME", "\"maestro\"")
        ndk {
            // Только ARM, как у Maestro: x86/x86_64 — только для эмулятора, в срез 1 не входят.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // Версии — как у MaestroVPN 1.0.212 (app/build.gradle.kts), минимальный набор среза 1.
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // Room (настройки), LiveData (статус службы), preference-ktx
    // (PreferenceDataStore для RoomPreferenceDataStore). WorkManager удалён
    // в срезе 7: автообновление подписок через него было мёртвым кодом
    // (Remote-профили никто не создавал).
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    ksp("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1") {
        // Robolectric тянет uber-вариант Conscrypt без native-библиотеки под aarch64.
        exclude(group = "org.conscrypt", module = "conscrypt-openjdk-uber")
    }
    // Conscrypt с native-библиотекой под архитектуру хоста: aarch64 — телефон/контейнер,
    // x86_64 — GitHub Actions.
    val conscryptArch = if (System.getProperty("os.arch") == "aarch64") "linux-aarch_64" else "linux-x86_64"
    testRuntimeOnly("org.conscrypt:conscrypt-openjdk:2.10.0:$conscryptArch")
}
