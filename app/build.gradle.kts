plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.prismdroid"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.prismdroid"
        minSdk = 30          // Android 11: memfd_create (JVM), Vulkan 1.1+ loader
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        // Azure app (public client) registered by YOU and approved by Mojang for
        // api.minecraftservices.com. Set msaClientId=... in ~/.gradle/gradle.properties.
        buildConfigField("String", "MSA_CLIENT_ID", "\"${project.findProperty("msaClientId") ?: ""}\"")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }

    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    packaging {
        // The JVM and LWJGL dlopen() libraries by absolute path, so they must exist on disk.
        jniLibs.useLegacyPackaging = true
    }
    androidResources { noCompress += listOf("xz", "tar") }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
