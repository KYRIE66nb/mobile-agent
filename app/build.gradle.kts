import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localProperty(name: String, fallback: String = ""): String =
    localProperties.getProperty(name, fallback)

fun localPropertyOrNull(name: String): String? =
    localProperties.getProperty(name)?.takeIf(String::isNotBlank)

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseStorePath = localPropertyOrNull("signing.storeFile")
val releaseStorePassword = localPropertyOrNull("signing.storePassword")
val releaseKeyAlias = localPropertyOrNull("signing.keyAlias")
val releaseKeyPassword = localPropertyOrNull("signing.keyPassword")
val releaseSigningValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() } &&
    rootProject.file(releaseStorePath.orEmpty()).isFile

check(!releaseSigningRequested || hasReleaseSigning) {
    "Release 签名配置不完整或签名文件不存在，请检查 local.properties 中的 signing.* 配置"
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "xyz.chouxuewei.mobile_agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.chouxuewei.mobile_agent"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStorePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "MODEL_BASE_URL", localProperty("model.baseUrl").asBuildConfigString())
            buildConfigField("String", "MODEL_NAME", localProperty("model.name").asBuildConfigString())
            buildConfigField("String", "MODEL_API_KEY", localProperty("model.apiKey").asBuildConfigString())
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "MODEL_BASE_URL", "\"\"")
            buildConfigField("String", "MODEL_NAME", "\"\"")
            buildConfigField("String", "MODEL_API_KEY", "\"\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":device"))
    implementation(project(":model"))
    implementation(project(":data"))
    implementation(project(":tools"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.libsu.service)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
