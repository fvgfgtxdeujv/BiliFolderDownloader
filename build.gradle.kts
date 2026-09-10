// Tinker 补丁插件未发布 Gradle Plugin Marker，只能走 buildscript classpath 老式路线
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.tencent.tinker:tinker-patch-gradle-plugin:1.9.15.2")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
