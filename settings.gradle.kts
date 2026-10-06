// 说明：本机 dl.google.com 直连超时，仓库统一走阿里云镜像，google()/mavenCentral() 作为兜底。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ShangKeLe"

include(":app")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:jwgl")
include(":core:context")
include(":core:ai")
include(":core:update")
include(":feature:schedule")
include(":feature:onboarding")
include(":feature:settings")
include(":feature:notes")
