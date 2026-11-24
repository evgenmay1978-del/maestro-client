import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

// Срез 1: движок sing-box (libbox.aar) подключается, только если AAR уже собран
// (см. docs: сборка libbox — Go 1.25.11 + NDK 28 + sing-box v1.14.4, рецепт из
// .github/workflows/libbox.yml репозитория Maestro). Без AAR собирается stub-движок,
// а реализация SingBoxEngine лежит в src/libbox/java и в сборку не входит.
val libboxAar = file("libs/libbox.aar")
val hasLibbox = libboxAar.exists()

android {
    namespace = "com.maestrovpn.client"
    compileSdk = 36
    // ndkVersion намеренно не задаётся: нативного кода для сборки нет
    // (jniLibs — готовые .so Xray-раннера); NDK нужен только для сборки libbox.aar
    // (см. .github/workflows/android.yml, job libbox).

    defaultConfig {
        applicationId = "com.maestrovpn.client"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-slice2"
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

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            if (hasLibbox) {
                // Обе директории: AGP 9 с built-in Kotlin берёт Kotlin-исходники
                // из kotlin.srcDirs, а не только из java.srcDirs — без второй строки
                // файлы src/libbox/java не попадают в compileDebugKotlin
                // (доказано логом --info: "Kotlin source files" без libbox).
                java.srcDirs("src/libbox/java")
                kotlin.srcDirs("src/libbox/java")
            }
        }
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
    // Ядро среза 2 (версии — как у MaestroVPN 1.0.212): Room (профили/настройки),
    // WorkManager (автообновление подписок), LiveData (статус службы), preference-ktx
    // (PreferenceDataStore для RoomPreferenceDataStore).
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    ksp("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    if (hasLibbox) {
        implementation(files(libboxAar))
    }
}
