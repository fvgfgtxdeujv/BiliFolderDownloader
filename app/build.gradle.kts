import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Tinker 插件类加入脚本编译 classpath（仓库继承根 buildscript），使下方 configure<TinkerPatchExtension> 可静态访问
buildscript {
    dependencies {
        classpath("com.tencent.tinker:tinker-patch-gradle-plugin:1.9.15.2")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Tinker 热修复补丁插件（无 marker，用字符串 id 应用；须在 application 插件之后）
apply(plugin = "com.tencent.tinker.patch")

android {
    namespace = "com.bilifolder.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bilifolder.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // 开发版同样裁剪（dex 死代码），但不做混淆与字节码优化：
            // -dontobfuscate 保留原名、-dontoptimize 保持字节码结构，便于断点调试与堆栈直接可读
            isMinifyEnabled = true
            // Tinker 硬性要求：资源裁剪必须关闭，否则补丁 diff 与已装包错位
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro",
            )
        }
        release {
            // R8 代码裁剪（混淆 + 优化全开，体积最小）
            isMinifyEnabled = true
            // Tinker 硬性要求：资源裁剪必须关闭，否则补丁 diff 与已装包错位
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            // BC 的 PQC 抗量子算法参数（本项目仅用 CMS/RSA/AES-GCM，PQC 参数纯浪费 ~1.2MB）
            excludes += "org/bouncycastle/pqc/**"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// ===== release mapping 归档（防止被后续构建覆盖，崩溃堆栈恢复用，配合 tools/retrace.py） =====
tasks.register("archiveReleaseMapping") {
    group = "reporting"
    description = "将当前 release 构建的 mapping.txt 归档为 mapping-<versionName>-<时间戳>.txt，防止被后续构建覆盖"
    doLast {
        val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        check(mappingFile.isFile) {
            "mapping 文件不存在：$mappingFile，请先运行 assembleRelease"
        }
        val version = project.extensions.getByType(com.android.build.gradle.AppExtension::class.java)
            .defaultConfig.versionName ?: "unknown"
        val stamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val archiveDir = layout.buildDirectory.dir("outputs/mapping/archive").get().asFile
        archiveDir.mkdirs()
        val dest = File(archiveDir, "mapping-${version}-${stamp}.txt")
        mappingFile.copyTo(dest, overwrite = true)
        logger.lifecycle("release mapping 已归档：${dest.absolutePath}")
    }
}

// assembleRelease 执行成功后自动触发归档（assembleRelease 注册较晚，用 matching 延迟挂接）
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("archiveReleaseMapping")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // Tinker 热修复运行时（含 loader，提供 TinkerApplication / ApplicationLike / 补丁合成）
    implementation(libs.tinker.android.lib)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ===== Tinker 热修复补丁配置 =====
// 注意：tinker-patch-gradle-plugin 的扩展是 Groovy 动态 DSL，子扩展（buildConfig 等）
// 通过 GroovyObject 动态属性暴露；Kotlin 脚本里用其公开的 getProperty/setProperty 访问。
//
// tinkerId 标识"线上在用的版本"，生成补丁时 new 包的 tinkerId 必须与线上 old 包不同。
// 默认取 versionName；打补丁时用 -PtinkerId=<新版本标识> 覆盖（仅影响补丁，不改 APK 版本号）。
// 已安装包的 tinkerId 由构建期写死进包内，不能事后修改，因此"每次发布"必须更换 tinkerId。
configure<com.tencent.tinker.build.gradle.extension.TinkerPatchExtension> {
    setProperty("tinkerEnable", true)
    // 发布前先按 warning 逐项核对（签名、混淆、资源等），开发联调阶段放开不阻断
    setProperty("ignoreWarning", true)
    val buildConfig = getProperty("buildConfig") as
        com.tencent.tinker.build.gradle.extension.TinkerBuildConfigExtension
    val versionName = project.extensions
        .getByType(com.android.build.gradle.AppExtension::class.java)
        .defaultConfig.versionName ?: "1.0.0"
    val patchTinkerId = (project.findProperty("tinkerId") as String?) ?: versionName
    buildConfig.setProperty("tinkerId", patchTinkerId)
    // release 混淆固定：把上一版归档 mapping（tools 下 archiveReleaseMapping 产出）填到
    // applyMapping 可让新旧包混淆名一致，提升补丁 diff 质量；文件缺失时插件自动忽略。
}
