plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Must track the Kotlin version exactly: it is a compiler plugin, not a library.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
