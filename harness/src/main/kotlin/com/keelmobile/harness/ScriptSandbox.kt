package com.keelmobile.harness

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.UUID

/**
 * Sandboxed in-process JavaScript runner for `run_js` powered by Mozilla Rhino:
 * - Zero Java reflection / OS / network / filesystem access (strict ClassShutter)
 * - Hard instruction-count & wall-clock timeout via observeInstructionCount
 * - Budgeted `keel.readFile`, `keel.listFiles`, `keel.writeFile`, `keel.deleteFile`, `console.log`
 * - Supports `await keel.*` syntax (models trained on Keel Mobile's async prompt)
 * - Writes never modify the workspace directly: they return ProposedEdit objects for user approval.
 */
class ScriptSandbox(
    private val workspace: TaskWorkspace,
    private val allowWrites: Boolean = true,
    private val maxHostCalls: Int = 64,
    private val maxReadBytes: Int = 512 * 1024,
    private val maxWriteBytes: Int = 512 * 1024,
    private val maxOutputChars: Int = 12_000,
    private val timeoutMs: Long = 5_000L
) {
    data class ScriptResult(
        val output: String,
        val returnValue: String?,
        val proposals: List<ProposedEdit>
    )

    private class TimeoutError : Error("Script exceeded time or instruction limit.")

    private class SandboxedContextFactory(private val deadlineMs: Long) : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.isInterpretedMode = true // Interpreted mode enables instruction observer on Android & JVM
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 10_000
            cx.setClassShutter(ClassShutter { false }) // Deny ALL Java classes
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw TimeoutError()
            }
        }
    }

    fun run(code: String): ScriptResult {
        val logs = StringBuilder()
        val proposals = mutableListOf<ProposedEdit>()
        val stagedOverlay = mutableMapOf<String, String?>() // null = deleted
        var hostCalls = 0
        var readBytes = 0
        var writeBytes = 0

        fun chargeCall() {
            hostCalls++
            if (hostCalls > maxHostCalls) {
                throw IllegalStateException("Script exceeded max host call budget ($maxHostCalls).")
            }
        }

        fun appendLog(text: String) {
            if (logs.length >= maxOutputChars) return
            val room = maxOutputChars - logs.length
            logs.append(text.take(room))
            if (logs.length < maxOutputChars) logs.append('\n')
        }

        val hostBridge = object {
            fun readFile(path: String): String {
                chargeCall()
                val clean = TaskWorkspace.normalizePath(path)
                    ?: throw IllegalArgumentException("Invalid path: $path")
                val text = if (clean in stagedOverlay) {
                    stagedOverlay[clean] ?: throw IllegalArgumentException("File was deleted: $clean")
                } else {
                    workspace.readText(clean) ?: throw IllegalArgumentException("File not found: $clean")
                }
                readBytes += text.toByteArray(Charsets.UTF_8).size
                if (readBytes > maxReadBytes) {
                    throw IllegalStateException("Script exceeded read byte budget ($maxReadBytes bytes).")
                }
                return text
            }

            fun listFiles(prefix: String?): Array<String> {
                chargeCall()
                val norm = prefix?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != "/" }
                    ?.let { TaskWorkspace.normalizePath(it) ?: return emptyArray() }
                    ?: ""
                val all = workspace.listAllPaths().toMutableSet()
                for ((k, v) in stagedOverlay) {
                    if (v == null) all.remove(k) else all.add(k)
                }
                return all.filter { norm.isEmpty() || it == norm || it.startsWith("$norm/") }
                    .sorted()
                    .toTypedArray()
            }

            fun writeFile(path: String, content: String) {
                chargeCall()
                if (!allowWrites) throw SecurityException("Script writes are disabled in read-only mode.")
                val clean = TaskWorkspace.normalizePath(path)
                    ?: throw IllegalArgumentException("Invalid path: $path")
                val bytes = content.toByteArray(Charsets.UTF_8).size
                writeBytes += bytes
                if (writeBytes > maxWriteBytes) {
                    throw IllegalStateException("Script exceeded write byte budget ($maxWriteBytes bytes).")
                }
                val existing = workspace.readEntry(clean)
                val edit = if (existing == null) {
                    workspace.planEdit(
                        id = UUID.randomUUID().toString(),
                        operation = EditOperation.CREATE,
                        path = clean,
                        newText = content
                    )
                } else {
                    val currentText = existing.text ?: throw IllegalArgumentException("Cannot edit binary file: $clean")
                    ProposedEdit(
                        id = UUID.randomUUID().toString(),
                        operation = EditOperation.REPLACE,
                        path = clean,
                        oldText = currentText,
                        newText = content,
                        baseSha1 = GitBlob.sha1Hex(existing.bytes),
                        diffPreview = TaskWorkspace.unifiedDiff(clean, currentText, content)
                    )
                }
                // Replace any earlier proposal for the same path from this script run
                proposals.removeAll { it.path == clean }
                proposals += edit
                stagedOverlay[clean] = content
            }

            fun deleteFile(path: String) {
                chargeCall()
                if (!allowWrites) throw SecurityException("Script writes are disabled in read-only mode.")
                val clean = TaskWorkspace.normalizePath(path)
                    ?: throw IllegalArgumentException("Invalid path: $path")
                val edit = workspace.planEdit(
                    id = UUID.randomUUID().toString(),
                    operation = EditOperation.DELETE,
                    path = clean
                )
                proposals.removeAll { it.path == clean }
                proposals += edit
                stagedOverlay[clean] = null
            }

            fun log(message: String) {
                appendLog(message)
            }
        }

        // Convert `await keel.xyz(...)` cleanly into synchronous sandbox calls while
        // preserving full ES6 syntax for calculations, loops, regex, JSON, Map/Set, etc.
        val preparedCode = rewriteTopLevelAwait(code)

        val factory = SandboxedContextFactory(System.currentTimeMillis() + timeoutMs)
        val cx = factory.enterContext()
        try {
            val scope: Scriptable = cx.initSafeStandardObjects()

            fun jsFunc(fn: (Array<out Any?>) -> Any?): BaseFunction = object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? = fn(args)
            }

            // Register strictly scoped host functions via Rhino BaseFunction (zero Java reflection exposed)
            val keelObj = cx.newObject(scope)
            ScriptableObject.putProperty(keelObj, "readFile", jsFunc { args ->
                val p = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                hostBridge.readFile(p)
            })
            ScriptableObject.putProperty(keelObj, "listFiles", jsFunc { args ->
                val prefix = if (args.isNotEmpty() && args[0] != null && args[0] !== Context.getUndefinedValue()) {
                    Context.toString(args[0])
                } else ""
                val items = hostBridge.listFiles(prefix)
                cx.newArray(scope, items.map { it as Any }.toTypedArray())
            })
            ScriptableObject.putProperty(keelObj, "writeFile", jsFunc { args ->
                val p = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                val content = if (args.size > 1) Context.toString(args[1]) else ""
                hostBridge.writeFile(p, content)
                Context.getUndefinedValue()
            })
            ScriptableObject.putProperty(keelObj, "deleteFile", jsFunc { args ->
                val p = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                hostBridge.deleteFile(p)
                Context.getUndefinedValue()
            })
            ScriptableObject.putProperty(scope, "keel", keelObj)

            val consoleObj = cx.newObject(scope)
            ScriptableObject.putProperty(consoleObj, "log", jsFunc { args ->
                val msg = args.joinToString(" ") { arg ->
                    if (arg == null) "null"
                    else if (arg === Context.getUndefinedValue()) "undefined"
                    else Context.toString(arg)
                }
                hostBridge.log(msg)
                Context.getUndefinedValue()
            })
            ScriptableObject.putProperty(scope, "console", consoleObj)
            cx.evaluateString(scope, "Object.freeze(keel); Object.freeze(console);", "keel-freeze.js", 1, null)

            val wrapped = "(function() {\n$preparedCode\n})()"
            val result = cx.evaluateString(scope, wrapped, "user-script.js", 1, null)
            val returnVal = when {
                result == null || result === Context.getUndefinedValue() -> null
                else -> Context.toString(result).take(maxOutputChars)
            }
            val outputText = buildString {
                if (logs.isNotEmpty()) append(logs.toString().trimEnd('\n'))
                if (returnVal != null) {
                    if (isNotEmpty()) append('\n')
                    append(returnVal)
                }
                if (proposals.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("[Proposed ${proposals.size} file change(s) for user review: ${proposals.joinToString(", ") { it.path }}]")
                }
            }.ifEmpty { "(completed with no output)" }

            return ScriptResult(outputText, returnVal, proposals.toList())
        } catch (e: TimeoutError) {
            throw IllegalStateException(e.message ?: "Script timed out.")
        } finally {
            Context.exit()
        }
    }

    companion object {
        private val AWAIT_REGEX = Regex("""\bawait\s+""")

        /**
         * Rhino ES6 runs synchronously; stripping `await ` lets models write
         * `const text = await keel.readFile("a.txt")` identically to iOS WKWebView.
         */
        fun rewriteTopLevelAwait(code: String): String = AWAIT_REGEX.replace(code, "")
    }
}
