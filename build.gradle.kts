// Maestro Client — корневой build-файл. Версии стека — те же, что у MaestroVPN 1.0.212
// (AGP 9.0.1 / Kotlin 2.3.10 / Gradle 9.3.1), чтобы переносимый код совпадал по API.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}
