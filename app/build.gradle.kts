import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// APP 版本名
val appVerName = "Beta-1.0.0"

// 读取本地签名配置。
val signingProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing.properties"))
        .asText.orNull?.reader()?.use { load(it) }
}

android {
    namespace = "com.winter.godot.tool"
    compileSdk {
        version = release(37)
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 自带的一些Compose依赖
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 测试相关依赖
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
