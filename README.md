# Keel Mobile (Android)

A local AI agent for Android. The model runs on the phone through `llama.cpp`
(native JNI, `arm64-v8a` and `x86_64`), and it can do things: read and change
files, search, run SQL on spreadsheets, run sandboxed JavaScript, preview
websites and study decks offline, and ship work to GitHub.

No computer. No model server. No cloud model behind it.

## How it works

| Piece | What it does |
| --- | --- |
| **Model** | Pinned downloads with SHA-256 verification: `Bonsai 2 · 4B` / `9B`, `LFM3 · 1.7B`, `LFM3 VL · 2B`, and `MiniCPM5 · 2B`, plus `Search by meaning` (`embeddinggemma-300M`) and `Decide · d1 2B`. Also imports custom `.gguf` files from device storage or Hugging Face links with bounded GGUF header inspection and RAM fit checks |
| **Native runtime** | Pinned upstream `llama.cpp` `b12367` compiled with Android NDK (`libkeel_llama.so`), append-only KV prefix caching for hybrid attention/SSM models, `Q4_0` KV cache for Bonsai 2, and lazy GBNF tool-call grammars |
| **Harness** | Pure Kotlin/JVM module (`:harness`) carrying Keel's small-model reliability loop: lazy GBNF grammar generation, tolerant tool-call recovery, leaked-reasoning stripping, `RepeatGuard` (2/4/7 + overlapping reads), `CircuitBreaker`, `RepetitionGuard`, and `ContextBudget` compaction |
| **Workspace** | Each chat gets its own SQLite virtual disk (`TaskWorkspace`): exact byte-key paths (no path traversal, case-sensitive), Git blob SHA-1 verification, in-memory gzip tarball import, multi-mode search, and byte-stable repository maps |
| **Sandboxes** | `run_sql` runs against a locked-down per-task SQLite scratch database with every `.csv` file auto-mounted as a table (`SELECT * FROM sales.csv`) and a read-only `files` view. `run_js` runs inside Mozilla Rhino with a strict `ClassShutter` (zero Java/OS/network access), instruction-count timeouts, and writes routed to pending proposals |
| **Apps & Viewers** | Websites, Sheets, Docs, Study (`Q:`/`A:` flashcards & quizzes) and Slides presets with an offline CSP-hardened `WebView` previewer, Android `PrintManager` PDF export, and file sharing |
| **Approvals & Security** | Model output is a request, never permission. Applying file diffs, searching the web (Brave Search), saving memory facts, and GitHub actions each require an explicit UI tap. Secrets stay in Android Keystore (`android:allowBackup="false"`). Low memory (`onTrimMemory`) and severe thermal status fail closed and unload the model |

## Repository layout

| Path | What |
| --- | --- |
| `harness/` | Pure Kotlin/JVM Keel harness (`com.keelmobile.harness`), fast tests on Linux/macOS/CI |
| `app/src/main/cpp/` | Native C++17 `llama.cpp` JNI bridge (`libkeel_llama.so`) |
| `app/src/main/kotlin/` | Native Android Jetpack Compose + Material 3 app |
| `scripts/prepare-llama.sh` | Fetches or links pinned `llama.cpp` `b12367` |
| `.github/workflows/android.yml` | GitHub Actions CI for harness tests, Robolectric Compose UI tests, and APK build |

## Build and test

Requires JDK 21 and Android SDK (API 35, Build-Tools 35.0.0, NDK `27.2.12479018`, CMake `3.31.6`).

```sh
# 1. Prepare pinned llama.cpp b12367 (reuses ~/Projects/llama.cpp-b12367 if present)
bash scripts/prepare-llama.sh

# 2. Run pure Kotlin harness tests
./gradlew :harness:test

# 3. Run Android unit & Robolectric Jetpack Compose UI tests
./gradlew :app:testDebugUnitTest

# 4. Build the debug APK (includes native arm64-v8a & x86_64 libkeel_llama.so)
./gradlew :app:assembleDebug
```

See [docs/BUILDING.md](docs/BUILDING.md) and [docs/HARNESS.md](docs/HARNESS.md) for details.

## Status

Pre-alpha. Harness tests, resumable HTTP Range/SHA-256 download tests, Robolectric
Compose UI tests, native NDK compilation (`arm64-v8a` + `x86_64`), and real local
GGUF inference verification through the exact JNI bridge pass on Linux. Physical
Android phone measurements (speed, memory, heat, battery) are not yet recorded.

## The Keel family

One engine, focused surfaces:

| Product | What it is |
| --- | --- |
| **Keel** | CLI + TUI coding agent for small local models |
| **Keel Desktop** | Everyday local-AI app for macOS/Linux/Windows |
| **Keel Mobile (iOS)** | Standalone on-device iPhone agent on Metal |
| **Keel Mobile (Android)** (this repo) | Standalone on-device Android agent via `llama.cpp` JNI |
| **Keel Console** | Living-room app for Xbox / TV |
| **Keel Arena** | Public benchmarks and scorecards |

Copyright (c) 2026 Oliver Mikkola. All rights reserved. See [LICENSE](LICENSE).
