import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

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
            // 开发版同样裁剪（dex 死代码 + 无用资源），但不做混淆与字节码优化：
            // -dontobfuscate 保留原名、-dontoptimize 保持字节码结构，便于断点调试与堆栈直接可读
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro",
            )
        }
        release {
            // R8 代码裁剪 + 资源裁剪（混淆 + 优化全开，体积最小）
            isMinifyEnabled = true
            isShrinkResources = true
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
