plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.godotengine.godot"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "FLAVOR", "\"template\"")
        //noinspection ChromeOsAbiSupport
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets["main"].apply {
        java.srcDir("vendor/java")
        kotlin.directories.add("vendor/java")
        res.srcDir("vendor/res")
        aidl.srcDir("vendor/aidl")
        jniLibs.srcDir("vendor/jniLibs")
        assets.setSrcDirs(listOf("vendor/assets", layout.buildDirectory.dir("generated/gdreAssets")))
    }
}

// 固定版本的 Godot Android 桥接依赖。
dependencies {
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
}

// 下载并校验官方原生核心，源码与缓存均可重建。
val prepareGdre = tasks.register<Exec>("prepareGdre") {
    workingDir(rootDir)
    commandLine("python", "tools/prepare_gdre.py")
    inputs.file(rootProject.file("tools/prepare_gdre.py"))
    inputs.file(rootProject.file("gradle/libs.versions.toml"))
    outputs.dir(layout.projectDirectory.dir("vendor"))
}
tasks.named("preBuild") { dependsOn(prepareGdre) }

// 使用同版本 GDRE 将工具脚本编译为字节码，安装包不包含明文源码。
val compileGdreScripts = tasks.register<Exec>("compileGdreScripts") {
    workingDir(rootDir)
    commandLine("python", "tools/compile_gdre_scripts.py", "--output",
        layout.buildDirectory.dir("generated/gdreAssets").get().asFile.absolutePath)
    inputs.file(rootProject.file("tools/compile_gdre_scripts.py"))
    inputs.file(rootProject.file("tools/prepare_gdre.py"))
    inputs.dir(layout.projectDirectory.dir("src/main/assets"))
    outputs.dir(layout.buildDirectory.dir("generated/gdreAssets"))
}
tasks.named("preBuild") { dependsOn(compileGdreScripts) }
