plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val llamaSrcDir = file("${rootProject.projectDir}/third_party/llama.cpp")
val enableNativeLlama = llamaSrcDir.resolve("CMakeLists.txt").exists()

android {
    namespace = "com.keelmobile.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.keelmobile.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "NATIVE_LLAMA_ENABLED", enableNativeLlama.toString())

        if (enableNativeLlama) {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DKEEL_LLAMA_SRC=${llamaSrcDir.absolutePath}",
                        "-DLLAMA_BUILD_COMMON=OFF",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_BUILD_TOOLS=OFF",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_SERVER=OFF",
                        "-DLLAMA_CURL=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DGGML_LLAMAFILE=OFF",
                        "-DGGML_NATIVE=OFF"
                    )
                    cppFlags += listOf("-std=c++17", "-O3")
                }
            }
        }
    }

    if (enableNativeLlama) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                System.getenv("KEEL_JNI_LIB_DIR")?.let { dir ->
                    test.jvmArgs("-Djava.library.path=$dir")
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":harness"))

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
