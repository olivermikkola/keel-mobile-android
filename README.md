<img src="docs/brand/mark.svg" width="64" height="64" alt="Keel jade and rust sail" />

# Keel Mobile (Android)

[![Android CI](https://github.com/olivermikkola/keel-mobile-android/actions/workflows/android.yml/badge.svg)](https://github.com/olivermikkola/keel-mobile-android/actions/workflows/android.yml)

**A standalone local AI agent for Android.**
The model runs directly on the phone through `llama.cpp` (native JNI for `arm64-v8a` and `x86_64`), and it can do real work: read and change files, search, query spreadsheets with SQL, run sandboxed JavaScript, preview websites and study decks offline, and ship approved work to GitHub.

No computer. No model server. No cloud model behind it.

[Harness architecture](docs/HARNESS.md) · [Building & installing](docs/BUILDING.md) · [Keel product vision](https://github.com/olivermikkola/keel/blob/main/docs/VISION.md)

## Why Keel Mobile

Most local-model apps stop at chat. Keel Mobile is built so a small model running on your own phone can actually finish tasks:

- **One agent, many tools.** Files, search, a SQL sandbox, a JavaScript sandbox, web pages, MCP connectors, and GitHub all run through the same bounded tool loop.
- **Not an IDE or a git client.** You state a goal, watch live step activity, inspect diffs when a decision matters, and tap **Apply** or **Discard**. Git branches and archives stay plumbing.
- **Small models, made reliable.** Carries the Keel harness techniques to Android: lazy GBNF tool-call grammars, tolerant tool-call recovery, repeat-call reminders, failure circuit breaking, and prefix-stable context compaction.
- **Local means local.** If the on-device model cannot run, Keel says so and stops. It never silently falls back to a cloud provider.

## How it works

| Piece | What it does |
| --- | --- |
| **Native runtime** | Pinned upstream `llama.cpp` `b12367` compiled with Android NDK into `libkeel_llama.so` (`arm64-v8a` + `x86_64`), with silent logging (no prompt leaks to logcat), append-only KV prefix caching, `Q4_0` KV cache for Bonsai 2, and lazy GBNF tool-call grammars |
| **Models** | Resumable HTTP `Range` downloads with SHA-256 verification and foreground notification progress: `Bonsai 2 · 4B` / `9B`, `LFM3 · 1.7B`, `LFM3 VL · 2B`, `MiniCPM5 · 2B`, plus `Search by meaning` (`embeddinggemma-300M`) and `Decide · d1 2B`. Also imports custom `.gguf` files from device storage or Hugging Face links with bounded GGUF header inspection (`<= 256 KB`) and RAM fit checks |
| **Harness (`:harness`)** | Pure Kotlin/JVM module with zero Android dependencies: native Bonsai/LFM3/MiniCPM5 templates, lazy GBNF grammar builder, `ToolCallText` recovery, `RepeatGuard` (2/4/7 + overlapping read detection), `CircuitBreaker`, `RepetitionGuard`, and `ContextBudget` compaction |
| **Workspace** | Every chat gets its own SQLite virtual disk (`TaskWorkspace`): exact case-sensitive byte-key paths (no path traversal), Git blob SHA-1 verification, in-memory gzip tarball import, multi-mode search (exact, case-insensitive, camelCase words), and byte-stable `repoMap` |
| **Sandboxes** | `run_sql` runs against a locked-down per-task SQLite scratch database with every `.csv` file auto-mounted as a table (`SELECT * FROM sales.csv`) and a read-only `files` view. `run_js` runs inside Mozilla Rhino ES6 with `ClassShutter { false }` (zero Java/OS/network access), instruction-count timeouts, and writes routed to pending proposals |
| **Apps & Viewers** | **Websites**, **Sheets**, **Docs**, **Study** (`Q:`/`A:` flashcards & multiple-choice quizzes), and **Slides** presets with an offline CSP-hardened `WebView` previewer, Android `PrintManager` PDF export, and file sharing |
| **Approvals & Security** | Applying file edits, running Brave `web_search`, saving memory facts (`remember`), and GitHub publishing each require an explicit tap. Secrets stay encrypted in Android Keystore (`allowBackup="false"`). Low memory (`onTrimMemory`) and severe thermal throttling fail closed and unload the model |

## Pinned model catalog

| Model | Download | Quantization | Context | Notes |
| --- | ---: | --- | ---: | --- |
| **Bonsai 2 · 4B** | 1.21 GB | `Q2_0` | 16K | Default Work model, `Q4_0` KV cache |
| **Bonsai 2 · 9B** | 2.56 GB | `Q2_0` | 32K | Larger autonomous tasks (`12 GB+` RAM recommended) |
| **LFM3 · 1.7B** | 1.10 GB | `Q4_K_M` | 32K | Fast everyday text & tools |
| **LFM3 VL · 2B** | 1.30 GB | `Q4_K_M` + `Q8_0` | 32K | Text, tools, and vision projector |
| **MiniCPM5 · 2B** | 1.56 GB | `Q4_K_M` | 16K | Native XML tool calling with CDATA |

Utility models: **Search by meaning** (`embeddinggemma-300M-Q8_0`, 334 MB) and **Decide · d1 2B** (`d1-2B-Q4_K_M`, 1.10 GB).

## Status & verification

**Pre-alpha.** What is verified today:

- **Harness unit tests (`:harness:test`):** 13/13 passing on JVM (prompts, lazy GBNF grammars, tool recovery, guards/compaction, SQLite virtual disk & tar import, SQL sandbox, Rhino JS sandbox, viewers, and `AgentLoop`).
- **Android unit & Compose UI tests (`:app:testDebugUnitTest`):** 5/5 passing with MockWebServer (resumable `Range` download, ignored-`Range` restart, SHA-256 rejection, GGUF import) and Robolectric Jetpack Compose UI (CSV attach → `run_sql` → `create_file` proposal card → explicit **Apply** → Library → Mode toggle → Models → thermal fail-closed).
- **Native Android APK (`:app:assembleDebug`):** Builds a signed debug APK bundling `lib/arm64-v8a/libkeel_llama.so` and `lib/x86_64/libkeel_llama.so`.
- **Live local-model JNI smoke tests** (same `app/src/main/cpp/keel_llama_jni.cpp` compiled for Linux x86_64 host, 8 CPU threads, running `LiveNativeModelSmokeTest` on `sales.csv`):

| Model | Tool executed | Prefix cache hit | Step-2 prefill | Decode speed | Answer |
| --- | --- | --- | ---: | ---: | :---: |
| `LFM3-1.7B-Q4_K_M` | `run_sql` (`SELECT SUM(qty * price) FROM sales.csv`) | 1,130 / 1,156 tokens | 123 ms | 51.5 tok/s | `45` (exact) |
| `Ternary-Bonsai-2-4B-Q2_0` (`Q4_0` KV) | `run_sql` (`SELECT SUM(qty * price) AS total_revenue FROM sales.csv`) | 1,228 / 1,254 tokens | 511 ms | 27.2 tok/s | `45` (exact) |

**Not yet measured:** Physical Android phone speed, memory ceilings, thermal throttling, and battery drain. Linux host JNI and Robolectric numbers are not physical handset proof.

## Build & install

Requires JDK 21 and Android SDK (API 35, Build-Tools `35.0.0`, NDK `27.2.12479018`, CMake `3.31.6`).

```sh
# 1. Fetch (or link) pinned llama.cpp b12367
bash scripts/prepare-llama.sh

# 2. Run pure Kotlin harness tests
./gradlew :harness:test

# 3. Run Android unit & Robolectric Compose UI tests
./gradlew :app:testDebugUnitTest

# 4. Build the debug APK with native arm64-v8a & x86_64 llama.cpp JNI
./gradlew :app:assembleDebug

# 5. Install on a USB-connected Android phone (USB debugging enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [docs/BUILDING.md](docs/BUILDING.md) for SDK setup and live GGUF JNI smoke-test instructions.

## Repository layout

```text
harness/                    Pure Kotlin/JVM Keel harness (prompts, grammars, virtual disk, sandboxes, agent loop)
app/src/main/cpp/           Native C++17 llama.cpp b12367 JNI bridge (libkeel_llama.so)
app/src/main/kotlin/        Native Android Jetpack Compose + Material 3 app
scripts/prepare-llama.sh    Pinned llama.cpp b12367 fetch/link helper
docs/                       Harness parity, building guide, and brand mark
.github/workflows/          GitHub Actions Android CI (tests + native debug APK)
```

## Rules

- **Local means local.** No silent fallback to any hosted or paid model.
- **You approve, the model asks.** Nothing modifies a file, searches the web, saves memory, or publishes to GitHub because the model asked for it.
- **Secrets stay in Android Keystore.** Excluded from cloud/device backups and never placed in prompts, URLs, or logs.

## The Keel family

One engine, focused surfaces:

| Product | What it is |
| --- | --- |
| [Keel](https://github.com/olivermikkola/keel) | CLI + TUI coding agent for small local models |
| [Keel Desktop](https://github.com/olivermikkola/keel-desktop) | Everyday local-AI app for macOS, Linux, and Windows |
| **Keel Mobile (Android)** (this repo) | Standalone on-device Android agent via `llama.cpp` JNI |
| [Keel Console](https://github.com/olivermikkola/keel-xbox) | Living-room app for Xbox / TV |
| [Keel Arena](https://github.com/olivermikkola/keel-arena) | Public benchmarks and scorecards |

Copyright (c) 2026 Oliver Mikkola. All rights reserved. See [LICENSE](LICENSE).
