plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.shangkele.core.database"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * 导出 Room 的 schema 到 `schemas/`。
 *
 * 从写第一个 Migration 开始就必须开：手写的 `CREATE TABLE` 只要和 Room
 * 自己生成的定义有一处对不上（列类型、可空性、外键动作、索引名），
 * 老用户机器上打开数据库时会直接抛 `Migration didn't properly handle`，
 * 而这个错误**只有装机升级才复现**，新装的机器永远看不到。
 *
 * 有了导出的 json，就能把迁移 SQL 和 Room 的期望逐字比一遍（见 core/database/schemas/）。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    // 摘要的列表字段（要点/术语/待办）以 JSON 数组文本入库，映射时要解析回来
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
