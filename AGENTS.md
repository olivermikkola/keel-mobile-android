# AGENTS.md — Keel Mobile (Android)

Plain language. Oliver is not a Linux specialist: give exact commands and say
what they do.

## What this is
An Android-native local AI agent. The model runs on the phone through `llama.cpp`
(native JNI: `arm64-v8a` with Vulkan GPU + `mtmd` vision + embeddings, and `x86_64`
for emulators) and acts through tools: files, real Android SAF folders, search,
sandboxed SQL, sandboxed JavaScript, local semantic recall, web pages, MCP
connectors, unattended `WorkManager` schedules, and GitHub PR publishing.
A standalone product in the Keel family — not a cloud client, not a remote
control for a computer, and not an IDE.

## What this is not
- **Not an IDE or a git client.** Do not build file-tree editors, branch
  pickers, commit screens or diff tooling as places the user lives in. The user
  states a goal, follows activity and approves. A diff is shown when a decision
  needs it. Git, branches and pull requests are plumbing.
- **Not a remote control for a computer.** No core feature may require a paired
  host.
- **Not a cloud client.** No silent fallback to a paid or hosted model.

When writing copy, UI text or docs, lead with what the agent can do, not with
repositories, commits and PRs.

## Adding a capability
A capability is a tool the model can call. Before proposing a new screen or
mode, ask whether it is a tool. A tool needs:
- a strict call format and a clear, short result for a small model;
- named limits for time, size and output;
- no reach outside the task (no app files, no network unless that is the tool);
- tests in `harness/src/test/` first, Android wiring in `app/` second.

## Boundaries that do not move
- **No shell, no subprocess, no compiler.** Code runs in in-process sandboxes
  (`SqlSandbox`, `ScriptSandbox`). Anything that needs a build or a test suite
  runs on GitHub Actions.
- **Approvals are explicit.** Model output is input to a tool request, never
  authorization. Changing files, searching the web, saving memory, calling
  non-read-only MCP tools and publishing to GitHub each need an explicit UI
  decision.
- **Secrets stay in Android Keystore.** No token in a URL, a prompt, a log,
  backup (`android:allowBackup="false"`), or git. Never print a token or key in
  chat or terminal output.
- **Fail closed under pressure.** Low memory (`onTrimMemory`) or severe thermal
  throttling stops generation and unloads the model cleanly; never retry on a
  cloud provider.

## Working here
- The harness (`harness/`) is pure Kotlin/JVM with no Android framework imports,
  so `./gradlew :harness:test` runs fast on Linux, macOS and CI.
- The native `llama.cpp` runtime pins upstream `b12367` (`scripts/prepare-llama.sh`)
  and compiles into `libkeel_llama.so` via CMake + Android NDK.
- Never claim physical Android device speed, heat or battery from JVM tests or
  an x86_64 emulator. Say clearly what a run proved and what it did not.
- Headless only for builds, tests and emulators; never open GUI windows on the
  desktop.
- Keep build caches and scratch files out of `/tmp` (RAM tmpfs on Beelink).

## Checks
```sh
# Pure Kotlin harness tests (fast, JVM)
./gradlew :harness:test

# Android unit + Robolectric Compose UI tests
./gradlew :app:testDebugUnitTest

# Full debug APK build (including native llama.cpp JNI when prepared)
./gradlew :app:assembleDebug
```
