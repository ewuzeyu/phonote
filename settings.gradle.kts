pluginManagement {
    repositories {

        maven{url=uri("https://maven.aliyun.com/repository/google")}
        maven{url=uri("https://maven.aliyun.com/repository/central")}

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Phonote"
include(":app")
include(":fluid-markdown")
include(":markwon-core")
include(":markwon-ext-latex")
include(":markwon-ext-strikethrough")
include(":markwon-ext-tables")
include(":markwon-ext-tasklist")
include(":markwon-image")
include(":markwon-html")
include(":markwon-inline-parser")
include(":markwon-syntax-highlight")
