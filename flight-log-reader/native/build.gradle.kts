buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        if (hasAndroidSdk) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (hasAndroidSdk) {
            classpath("com.android.tools.build:gradle:8.5.2")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        }
    }
}
