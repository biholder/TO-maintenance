// Android-модуль подключается, только если установлен Android SDK:
// без него можно собирать и тестировать :core (парсеры и анализ).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (hasAndroidSdk) google()
        mavenCentral()
    }
}
rootProject.name = "PLOV"

include(":core")
if (hasAndroidSdk) include(":app")
