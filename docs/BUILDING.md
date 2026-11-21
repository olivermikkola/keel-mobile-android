# Building Keel Mobile (Android)

## Prerequisites

- **JDK 21** (e.g., Eclipse Temurin 21)
- **Android SDK** with:
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - `platform-tools`
  - `ndk;27.2.12479018`
  - `cmake;3.31.6`

Point Gradle at your Android SDK via `ANDROID_HOME` or `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

## Preparing `llama.cpp`

Keel Mobile pins upstream `llama.cpp` `b12367` (supporting Bonsai 2 `Q2_0`, LFM3,
MiniCPM5, and EmbeddingGemma):

```sh
bash scripts/prepare-llama.sh
```

If `third_party/llama.cpp` is present, Gradle automatically builds
`libkeel_llama.so` for `arm64-v8a` and `x86_64` via CMake + NDK and bundles it
inside the APK.

## Commands

```sh
# Fast pure-JVM harness test suite (prompts, grammar, SQLite virtual disk, SQL/JS sandboxes, agent loop)
./gradlew :harness:test

# Android unit tests (resumable downloads, SHA-256 verification, GGUF import)
# and Robolectric Compose UI tests
./gradlew :app:testDebugUnitTest

# Build installable debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug
```

## Installing on an Android phone

Enable **USB debugging** in Android Developer Options, connect your phone, and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
