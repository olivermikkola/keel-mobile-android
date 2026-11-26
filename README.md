<img src="docs/brand/mark.svg" width="64" height="64" alt="Keel jade and rust sail" />

# Keel Mobile (Android)

[![Android CI](https://github.com/olivermikkola/keel-mobile-android/actions/workflows/android.yml/badge.svg)](https://github.com/olivermikkola/keel-mobile-android/actions/workflows/android.yml)

**A standalone, Android-native local AI agent.**
The model runs directly on your phone through `llama.cpp` with **Vulkan GPU acceleration** (`arm64-v8a`, with automatic CPU fallback), native **multimodal vision** (`mtmd`), and **on-device embeddings**.

No computer. No model server. No cloud model behind it.

[Harness architecture](docs/HARNESS.md) · [Building & installing](docs/BUILDING.md) · [Keel product vision](https://github.com/olivermikkola/keel-code/blob/main/docs/VISION.md)

**Pre-alpha.** [Build the debug APK from source](#build--install) to try it on
Android 9 or later. The documented checks cover the JVM, emulator-style UI,
and a Linux host run of the native runtime. Physical phone performance and
battery use still need validation.

## Android capabilities

The Android app combines local inference and explicit approvals with Android system features:

- **Vulkan GPU acceleration (`arm64-v8a`) + live benchmark.** `libkeel_llama.so` bundles `ggml-vulkan` (270+ compiled SPIR-V shaders for Adreno, Mali, and Xclipse GPUs) with automatic CPU fallback, a runtime GPU toggle, and an in-app tok/s speed test.
- **Background replies that actually finish.** Long tool runs execute inside an Android `InferenceForegroundService` with a live notification and a working **Stop** button — no 30-second background suspension cliff.
- **Real unattended scheduled tasks.** Backed by Android `WorkManager` (`ScheduledTaskWorker`): tasks run on a real schedule (once, every 12 hours, daily, weekly) with battery and thermal checks and post a tap-to-open completion notification — no tap-to-run notification workaround.
- **Real local folders on your phone.** Pick any folder via Android Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` with persisted permission): Keel imports text/code/CSV/Markdown files into the task workspace and writes **explicitly approved** edits directly back to your phone's storage.
- **Photos & camera with native vision (`mtmd`).** Attach photos from Android Photo Picker or capture with the camera for `LFM3 VL · 2B`, encoded natively through `libmtmd.a`.
- **On-device semantic recall (`EmbeddingGemma 300M`).** Native `llama.cpp` embeddings (`nativeEmbed`) index your chats and applied files locally so the `recall` tool finds earlier work by meaning without leaving the phone.
- **Deep Android system integration.** Share text, links, images, or files directly into Keel from any app via the Android **Share Sheet** (`ACTION_SEND`); long-press the launcher icon for **App Shortcuts** (New Chat, Study Deck, Websites); export any conversation as Markdown; and publish approved GitHub workspace edits as a branch + Pull Request after explicit review.

## How it works

| Piece | What it does |
| --- | --- |
| **Native runtime** | Pinned upstream `llama.cpp` `b12367` compiled with Android NDK 27 into `libkeel_llama.so` (`arm64-v8a` with **Vulkan GPU + `mtmd` + embeddings**; `x86_64` CPU + `mtmd` + embeddings), silent logging (no prompt leaks to logcat), append-only KV prefix caching, `Q4_0` KV cache for Bonsai 2, and lazy GBNF tool-call grammars |
| **Models & RAM gating** | Resumable HTTP `Range` downloads with SHA-256 verification and foreground notification progress: `Bonsai 2 · 4B` / `9B`, `LFM3 · 1.7B`, `LFM3 VL · 2B`, `MiniCPM5 · 2B`, plus `Search by meaning` (`embeddinggemma-300M`) and `Decide · d1 2B`. Android RAM-aware fit checks (68% budget on `8–16 GB` phones) plus live RAM/storage stats, custom `.gguf` import, and Hugging Face link import |
| **Harness (`:harness`)** | Pure Kotlin/JVM module with zero Android dependencies: native Bonsai/LFM3/MiniCPM5 templates, lazy GBNF grammar builder, `ToolCallText` recovery, `RepeatGuard` (2/4/7 + overlapping read detection), `CircuitBreaker`, `RepetitionGuard`, `ContextBudget` compaction, and `GitHubPublishPlanner` |
| **Workspace & Local Folders** | SQLite virtual disk (`TaskWorkspace`) per chat with Git blob SHA-1 verification, gzip tar import, and `diffAgainstBase()`, paired with `LocalFolderBridge` (Android SAF) for real folder sync and approval-gated write-back |
| **Sandboxes** | `run_sql` runs against a locked-down per-task SQLite scratch DB with every `.csv` file auto-mounted as a table (`SELECT * FROM sales.csv`) and a read-only `files` view. `run_js` runs inside Mozilla Rhino ES6 with `ClassShutter { false }` (zero Java/OS/network access), instruction-count timeouts, and writes routed to pending proposals |
| **Apps & Viewers** | **Websites**, **Sheets**, **Docs**, **Study** (`Q:`/`A:` flashcards & multiple-choice quizzes), and **Slides** presets with an offline CSP-hardened `WebView` previewer, Android `PrintManager` PDF export, Markdown chat export, and file sharing |
| **Approvals & Security** | Applying file edits (including local folder write-back), running Brave `web_search`, saving memory facts (`remember`), and publishing a GitHub PR each require an explicit tap. Secrets stay encrypted in Android Keystore (`allowBackup="false"`). Low memory (`onTrimMemory`) and severe thermal throttling fail closed and unload the model |

## Pinned model catalog

| Model | Download | Quantization | Context | Notes |
| --- | ---: | --- | ---: | --- |
| **Bonsai 2 · 4B** | 1.21 GB | `Q2_0` | 16K | Default Work model, `Q4_0` KV cache |
| **Bonsai 2 · 9B** | 2.56 GB | `Q2_0` | 32K | Larger autonomous tasks (`8–12 GB+` Android RAM) |
| **LFM3 · 1.7B** | 1.10 GB | `Q4_K_M` | 32K | Fast everyday text & tools |
| **LFM3 VL · 2B** | 1.30 GB | `Q4_K_M` + `Q8_0` | 32K | Text, tools, and native `mtmd` vision projector |
| **MiniCPM5 · 2B** | 1.56 GB | `Q4_K_M` | 16K | Native XML tool calling with CDATA |

Utility models: **Search by meaning** (`embeddinggemma-300M-Q8_0`, 334 MB, native on-device embeddings) and **Decide · d1 2B** (`d1-2B-Q4_K_M`, 1.10 GB).

## Status & verification

**Pre-alpha (v0.2.0-alpha, Android 9+ / API 28+).** What is verified today:

- **Harness unit tests (`:harness:test`):** 18/18 passing on JVM (prompts, lazy GBNF grammars, tool recovery, guards/compaction, SQLite virtual disk & `diffAgainstBase`, SQL sandbox, Rhino JS sandbox, viewers, `AgentLoop`, `ScheduledTask`, `LocalFolderPolicy`, `GitHubPublishPlanner`, and Android RAM tiers).
- **Android unit & Compose UI tests (`:app:testDebugUnitTest`):** 7/7 passing (plus 1 opt-in live-GGUF test) with `MockWebServer` (resumable `Range` download, ignored-`Range` restart, SHA-256 rejection, GGUF import, full GitHub blobs → tree → commit → ref → Pull Request publish flow) and Robolectric Jetpack Compose UI (CSV attach → `run_sql` → `create_file` proposal card → explicit **Apply** → Library → Mode toggle → Models → Vulkan GPU toggle → Share Sheet intake → Markdown export → Scheduled Tasks CRUD → thermal fail-closed).
- **Native Android APK (`:app:assembleDebug`):** Builds a signed debug APK bundling `lib/arm64-v8a/libkeel_llama.so` (verified linked against `libvulkan.so` with `ggml-vulkan` + `mtmd` + `llama`) and `lib/x86_64/libkeel_llama.so` (`mtmd` + `llama`).
- **Live local-model JNI smoke tests** (same `app/src/main/cpp/keel_llama_jni.cpp` compiled for Linux x86_64 host, 8 CPU threads, running `LiveNativeModelSmokeTest` on `sales.csv`):

| Model | Tool executed | Prefix cache hit | Step-2 prefill | Decode speed | Answer |
| --- | --- | --- | ---: | ---: | :---: |
| `LFM3-1.7B-Q4_K_M` | `run_sql` (`SELECT SUM(qty * price) FROM sales.csv`) | 1,130 / 1,156 tokens | 128 ms | 51.2 tok/s | `45` (exact) |
| `Ternary-Bonsai-2-4B-Q2_0` (`Q4_0` KV) | `run_sql` (`SELECT SUM(qty * price) AS total_revenue FROM sales.csv`) | 1,228 / 1,254 tokens | 511 ms | 27.2 tok/s | `45` (exact) |

**Not yet measured:** Physical Android phone Vulkan GPU speed, Adreno/Mali driver behavior, memory ceilings, thermal throttling, and battery drain. Linux host JNI and Robolectric numbers are not physical handset proof.

## Build & install

Requires JDK 21, Android SDK (API 35, Build-Tools `35.0.0`, NDK `27.2.12479018`, CMake `3.31.6`), and on Linux `libvulkan-dev spirv-headers` for Vulkan GPU shader compilation.

```sh
# 1. Fetch (or link) pinned llama.cpp b12367
bash scripts/prepare-llama.sh

# 2. Run pure Kotlin harness tests
./gradlew :harness:test

# 3. Run Android unit & Robolectric Compose UI tests
./gradlew :app:testDebugUnitTest

# 4. Build the debug APK (ensure Android SDK's cmake/3.31.6/bin is on PATH for ninja)
PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH" ./gradlew :app:assembleDebug

# 5. Install on a USB-connected Android 9+ phone (USB debugging enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [docs/BUILDING.md](docs/BUILDING.md) for SDK setup, Vulkan requirements, and live GGUF JNI smoke-test instructions.

## Repository layout

```text
harness/                    Pure Kotlin/JVM Keel harness (prompts, grammars, virtual disk, sandboxes, agent loop)
app/src/main/cpp/           Native C++17 llama.cpp b12367 JNI bridge (Vulkan GPU + mtmd vision + embeddings)
app/src/main/kotlin/        Native Android Jetpack Compose + Material 3 app (WorkManager, SAF, Foreground Service)
scripts/prepare-llama.sh    Pinned llama.cpp b12367 fetch/link helper
docs/                       Harness architecture, building guide, and brand mark
.github/workflows/          GitHub Actions Android CI (tests + Vulkan/mtmd debug APK)
```

## Rules

- **Local means local.** No silent fallback to any hosted or paid model.
- **You approve, the model asks.** Nothing modifies a file, writes to a local folder, searches the web, saves memory, or publishes to GitHub because the model asked for it.
- **Secrets stay in Android Keystore.** Excluded from cloud/device backups and never placed in prompts, URLs, or logs.

## The Keel family

One engine, focused surfaces:

| Product | What it is |
| --- | --- |
| [Keel Code](https://github.com/olivermikkola/keel-code) | CLI + TUI coding agent for small local models |
| [Keel Desktop](https://github.com/olivermikkola/keel-desktop) | Everyday local-AI app for macOS, Linux, and Windows |
| **Keel Mobile (Android)** (this repo) | Standalone Android-native on-device agent via `llama.cpp` JNI + Vulkan |
| [Keel Console](https://github.com/olivermikkola/keel-xbox) | Living-room app for Xbox / TV |
| [Keel Arena](https://github.com/olivermikkola/keel-arena) | Public benchmarks and scorecards |

Copyright (c) 2026 Oliver Mikkola. All rights reserved. See [LICENSE](LICENSE).
