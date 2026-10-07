import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 自用签名配置。文件缺失时静默降级为未签名 release + 已签名的 debug，
// 保证在没有 keystore.properties 的环境（例如换机）也能构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

// ── 学校配置 ──────────────────────────────────────────────────
// 仓库是公开的，所以「哪所学校」这件事只存在于本机 school.properties 里
// （已被 .gitignore 忽略，模板见 school.properties.example），构建时注入。
val schoolProps = Properties().apply {
    rootProject.file("school.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun schoolValue(key: String): String = schoolProps.getProperty(key, "").trim()

/**
 * 生成 `network_security_config.xml`。
 *
 * 教务系统只提供 http 时，必须把它的域名写进这个 XML 才放行明文 ——
 * 而**它不能留在仓库里**：写进去等于公布「这个人在哪所学校」。
 * 所以改成构建时生成，仓库里那份静态文件已删除。
 *
 * ⚠️ 静态那份必须删掉，否则同名资源会冲突（`Duplicate resources`），
 * 而报错只说"资源重复"，不会告诉你是这份生成的。
 *
 * 没配 `school.properties` 时只生成局域网那一条，构建照样通过 ——
 * 只是教务导入连不上，那正是"没配置"应有的表现。
 */
abstract class GenerateNetworkConfig : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val cleartextDomains: ListProperty<String>

    @TaskAction
    fun generate() {
        val xmlDir = outputDir.get().asFile.resolve("xml").apply { mkdirs() }
        val domains = cleartextDomains.get().map { it.trim() }.filter { it.isNotEmpty() }

        val domainConfig = if (domains.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("""    <domain-config cleartextTrafficPermitted="true">""")
                domains.forEach { appendLine("""        <domain includeSubdomains="true">$it</domain>""") }
                appendLine("    </domain-config>")
            }
        }

        xmlDir.resolve("network_security_config.xml").writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine("<!-- 由 app/build.gradle.kts 生成，不要手改：仓库里不放学校域名 -->")
                appendLine("<network-security-config>")
                appendLine("""    <base-config cleartextTrafficPermitted="false" />""")
                append(domainConfig)
                appendLine("</network-security-config>")
            },
            Charsets.UTF_8,
        )
    }
}

val generateNetworkConfig by tasks.registering(GenerateNetworkConfig::class) {
    outputDir.set(layout.buildDirectory.dir("generated/schoolRes"))
    // 学校域名 + 局域网地址（后者只用于 tools/release/serve.ps1 测自动更新）
    cleartextDomains.set(
        (schoolValue("school.cleartext.domains") + "," + schoolValue("school.cleartext.lanIps"))
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() },
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateNetworkConfig,
            GenerateNetworkConfig::outputDir,
        )
    }
}

android {
    namespace = "com.shangkele.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.shangkele.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 40
        versionName = "0.10.7-w6"
        // ONNX Runtime 自带 4 个 ABI 的 .so，全打进去 APK 会白胖 3 倍。
        // 目标机只有荣耀 200（arm64-v8a），所以只留这一个。
        ndk {
            abiFilters += "arm64-v8a"
        }
        // TODO(W4)：接入端侧模型后加回 ndk.abiFilters += "arm64-v8a"，只打荣耀 200 的架构
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:jwgl"))
    implementation(project(":core:context"))
    implementation(project(":core:update"))
    implementation(project(":feature:schedule"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:notes"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // 桌面小组件
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
