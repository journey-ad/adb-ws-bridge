plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** 只取标准输出，git 在无匹配对象时把提示写进标准错误，混进来会污染版本名 */
fun git(args: List<String>): String = runCatching {
    ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .start()
        .inputStream.bufferedReader().readText().trim()
}.getOrDefault("")

fun getGitHash(): String {
    val hash = git(listOf("rev-parse", "--short=7", "HEAD"))
    return hash.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) } ?: "unknown"
}

fun getBaseVersion(): String {
    val tag = git(listOf("describe", "--tags", "--abbrev=0")).removePrefix("v")
    return tag.takeIf { it.matches(Regex("\\d+(\\.\\d+)*")) } ?: "0.1.0"
}

android {
    namespace = "re.ovo.adbbridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "re.ovo.adbbridge"
        minSdk = 30
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String ?: getBaseVersion())
            .removePrefix("v") + (project.findProperty("versionSuffix") as? String ?: "") + " (${getGitHash()})"
    }

    // 打包签名取自仓库根的 adbbridge.keystore，密码走环境变量或 gradle 属性，未配置时产出的 APK 保持未签名
    val keystoreFile = rootProject.file("adbbridge.keystore")
    val storePwd = System.getenv("KEYSTORE_PASSWORD") ?: providers.gradleProperty("KEYSTORE_PASSWORD").orNull
    val keyPwd = System.getenv("KEY_PASSWORD") ?: providers.gradleProperty("KEY_PASSWORD").orNull
    val signingKeyAlias = System.getenv("KEY_ALIAS") ?: providers.gradleProperty("KEY_ALIAS").orNull ?: "adbbridge"
    val hasSigningConfig = keystoreFile.exists() && !storePwd.isNullOrEmpty() && !keyPwd.isNullOrEmpty()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePwd
                keyAlias = signingKeyAlias
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.md"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
