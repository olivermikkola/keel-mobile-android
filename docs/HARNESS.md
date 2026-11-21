# Keel Harness on Android

Keel Mobile (Android) shares the same small-model harness techniques as desktop
Keel (`olivermikkola/keel`) and pairs them with Android-native platform services
(Vulkan GPU offload, `InferenceForegroundService`, `WorkManager` schedules,
Storage Access Framework local folders, and native `mtmd` / embeddings).

The core harness lives in `:harness` as a pure Kotlin/JVM module with zero
Android framework dependencies.

| Technique | Module file | Behaviour |
| --- | --- | --- |
| Repeat-tool reminder | `Guards.kt` (`RepeatGuard`) | Escalating reminder at 2, 4, and 7 identical consecutive calls; overlapping `read_file` range warnings; invalidated on edits |
| Tolerant tool-call recovery | `ToolParsing.kt` (`ToolCallText`, `NativeToolCall`) | Strips leaked `</think>` reasoning, removes surrounding Markdown fences and trailing prose, wraps bare `<function=`, repairs missing `</tool_call>`, normalizes MiniCPM5 XML + CDATA; strict `DirectorCall.parse` still validates |
| Lazy GBNF tool grammar | `ToolCallGrammar.kt` | Builds exact llama.cpp GBNF grammar + trigger patterns from the prompt's `<tools>` block for Qwen (Bonsai/LFM3) and MiniCPM5 native XML using an Aho-Corasick exclusion automaton |
| Token-loop stop | `Guards.kt` (`RepetitionGuard`) | Aborts streaming generation early when a small model falls into a repeating text loop |
| Environment circuit breaker | `Guards.kt` (`CircuitBreaker`) | Trips after 3 consecutive tool failures with actionable advice; resets on success |
| Context compaction | `Guards.kt` (`ContextBudget`, `ToolResultPruner`) | Compacts at 75% of context window (aim 50% eager / 30% `STABLE_PREFIX`), preserves the first task message and the 3 most recent steps, desktop pruner defaults 8192/4096/1024 |
| Virtual disk & diffing | `TaskWorkspace.kt` | SQLite-backed base + overlay, Git blob SHA-1 verification, in-memory gzip tar import, case-sensitive exact keys, exact/case-insensitive/camelCase-word search, byte-stable `repoMap`, unified diff planner with stale-base rejection, and `diffAgainstBase()` for GitHub PR publishing |
| Sandboxes | `SqlSandbox.kt`, `ScriptSandbox.kt` | Per-task locked-down SQLite scratch DB with auto-mounted CSV tables + `files` view; Rhino ES6 JS sandbox with `ClassShutter { false }` and hard instruction-count timeout |
| Android-open policies | `AndroidOpenFeatures.kt` | Pure-Kotlin validation and policy rules for unattended `WorkManager` schedules (`ScheduledTask`), SAF folder sync (`LocalFolderPolicy`), and branch/PR publishing (`GitHubPublishPlanner`) |
| Explicit approvals | `AgentLoop.kt` | File proposals (and SAF folder write-back), `web_search`, `remember`, and GitHub PR publishing never execute on model output alone |
