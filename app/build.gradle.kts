import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// APP 版本名
val appVerName = "1.1.0"

// 读取本地签名配置。
val signingProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing.properties"))
        .asText.orNull?.reader()?.use { load(it) }
}

android {
    namespace = "com.winter.godot.tool"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.winter.godot.tool"
        minSdk = 28
        targetSdk = 37
        versionCode = appVerCode()
        versionName = appVerName()

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

    }

    signingConfigs {
        configureEach {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            storeFile = signingProperties.getProperty("storeFile") ?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = libs.versions.cmake.get()
    }
}

// 校验并准备固定版本的 Capstone 源码。
val prepareCapstone = tasks.register<Exec>("prepareCapstone") {
    workingDir(rootDir)
    commandLine("python", "tools/prepare_capstone.py")
    inputs.file(rootProject.file("tools/prepare_capstone.py"))
    inputs.file(rootProject.file("gradle/libs.versions.toml"))
    outputs.dir(layout.projectDirectory.dir("vendor/capstone"))
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake")) dependsOn(prepareCapstone)
}

dependencies {
    // Godot 解包核心
    implementation(project(":gdre-core"))
    // Miuix 组件全家桶
    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    // Coil 3 异步加载图片
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Lottie 动画
    implementation(libs.lottie.compose)
    // MMKV 持久化
    implementation(libs.mmkv)
    // 摩西摩西
    implementation(libs.moshi)
    // 可取消的后台工作与生命周期状态收集。
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.foundation)
    // 自带的一些Compose依赖
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Android Studio 界面预览工具。
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * 生成 yyMMddNN 格式的版本号，NN是当日git提交次数，最大99
 * @return 版本号
 */
fun appVerCode(): Int {
    val today = LocalDate.parse(providers.exec {
        workingDir(rootDir)
        commandLine("git", "-c", "alias.app-build-date=!date +%Y-%m-%d", "app-build-date")
    }.standardOutput.asText.get().trim())
    val count = providers.exec {
        workingDir(rootDir)
        commandLine(
            "git", "rev-list", "--all", "--count",
            "--since-as-filter=${today}T00:00:00", "--until=${today}T23:59:59"
        )
    }.standardOutput.asText.get().trim().toInt().coerceAtMost(99)
    return today.format(DateTimeFormatter.ofPattern("yyMMdd")).toInt() * 100 + count
}

/**
 * 少打听为什么这么写..
 */
fun appVerName(): String {
    return appVerName
}
