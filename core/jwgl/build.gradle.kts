// 必须有这个 import：脚本里的 `java` 会被 Gradle 的 java 扩展遮蔽，
// 写成 `java.util.Properties()` 会报 "Unresolved reference: util"。
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ── 学校配置：只从本机的 school.properties 读，仓库里不留任何学校痕迹 ──
//
// 为什么不把这份读取放到根项目共享：Kotlin DSL 跨模块取值的写法比这十几行还长，
// 而且取不到时会静默变成空串 —— 那种失败没有任何提示，比重复几行危险得多。
//
// 模板见根目录 school.properties.example。
val schoolProps = Properties().also { props ->
    rootProject.file("school.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}
fun schoolValue(key: String): String = schoolProps.getProperty(key, "").trim()

// 没配 host 时用一个保留 TLD。**绝不能留空**：空 host 会拼出 "http://" 这种畸形 URL，
// 而 "*.invalid" 是 IANA 保留的、保证解析不到的域名 —— 连不上是明确的失败，
// 而不是"看起来能用但请求发到了莫名其妙的地方"。
val jwglHost = schoolValue("school.jwgl.host").ifEmpty { "jwgl.invalid" }
val casHost = schoolValue("school.cas.host").ifEmpty { "authserver.invalid" }

android {
    namespace = "com.shangkele.core.jwgl"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "JWGL_HOST", "\"$jwglHost\"")
        buildConfigField("String", "CAS_HOST", "\"$casHost\"")
        buildConfigField("String", "SCHOOL_DOMAIN_SUFFIX", "\"${schoolValue("school.domain.suffix")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 解析层是纯逻辑，不需要 Android 框架；这条只是防止后续误用框架类时直接崩测试进程
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 只用 JSON 树模型（parseToJsonElement），不引入 kotlinx-serialization 编译器插件，
    // 这样面对教务系统各种字段命名差异可以逐字段容错，构建也少一个插件。
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
