# Building Keel Mobile (Android)

## Prerequisites

- **JDK 21** (e.g. Eclipse Temurin 21)
- **Android SDK** packages (Android 9+ / `minSdk = 28`, `compileSdk = 35`):
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - `platform-tools`
  - `ndk;27.2.12479018`
  - `cmake;3.31.6`
- **Vulkan & SPIR-V headers** (Linux build host, for `arm64-v8a` Vulkan GPU backend):
  - Debian/Ubuntu: `sudo apt-get install libvulkan-dev spirv-headers`
  - If these headers are absent, Gradle automatically falls back to CPU-only compilation (`KEEL_ENABLE_VULKAN=OFF`).

Point Gradle at your Android SDK via `ANDROID_HOME` or a local (gitignored) `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

## 1. Prepare pinned `llama.cpp` (`b12367`)

Keel Mobile pins upstream `llama.cpp` `b12367` (supporting Bonsai 2 `Q2_0`, LFM3, LFM3 VL `mtmd`, MiniCPM5, and EmbeddingGemma):

```sh
bash scripts/prepare-llama.sh
```

When `third_party/llama.cpp` is present, Gradle builds `libkeel_llama.so` via CMake + Android NDK:
- `arm64-v8a`: `llama.cpp` + `mtmd` vision + `ggml-vulkan` GPU backend (when Vulkan/SPIR-V headers are installed)
- `x86_64`: `llama.cpp` + `mtmd` vision (CPU backend for emulators)

## 2. Run tests and build the APK

Ensure Android SDK's `ninja` is on `PATH` when building with Vulkan GPU shaders enabled (`vulkan-shaders-gen` invokes `ninja` as a host sub-build):

```sh
# Pure Kotlin harness tests (fast JVM suite)
./gradlew :harness:test

# Android unit tests (resumable Range downloads, SHA-256 checks, GGUF import, GitHub PR publisher)
# and Robolectric Jetpack Compose UI tests
./gradlew :app:testDebugUnitTest

# Build installable debug APK (app/build/outputs/apk/debug/app-debug.apk)
PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH" ./gradlew :app:assembleDebug
```

## 3. Install on an Android phone

Enable **USB debugging** in Android Developer Options on an Android 9+ (`arm64-v8a`) phone, connect over USB, and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Optional: Live local GGUF smoke test on Linux

You can compile the exact same C++ JNI wrapper (`app/src/main/cpp/keel_llama_jni.cpp`) as a host Linux shared library and run `LiveNativeModelSmokeTest` against any local `.gguf` file:

```sh
cmake -S app/src/main/cpp -B native-build/host-jni \
  -DKEEL_LLAMA_SRC="$(pwd)/third_party/llama.cpp" \
  -DCMAKE_BUILD_TYPE=Release \
  -DKEEL_ENABLE_VULKAN=OFF
cmake --build native-build/host-jni --target keel_llama -j

KEEL_JNI_LIB_DIR="$(pwd)/native-build/host-jni" \
KEEL_LIVE_GGUF="/path/to/LFM3-1.7B-Q4_K_M.gguf" \
KEEL_LIVE_MODEL_ID=lfm3 \
./gradlew :app:testDebugUnitTest --tests com.keelmobile.android.LiveNativeModelSmokeTest --rerun-tasks
```
