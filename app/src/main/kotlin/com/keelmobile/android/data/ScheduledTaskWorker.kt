package com.keelmobile.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.keelmobile.android.MainActivity
import com.keelmobile.android.inference.AndroidLocalInference
import com.keelmobile.harness.AgentLoop
import com.keelmobile.harness.AgentPrompt
import com.keelmobile.harness.ChatMode
import com.keelmobile.harness.CustomInstructions
import com.keelmobile.harness.HarnessMessage
import com.keelmobile.harness.InferenceCancellation
import com.keelmobile.harness.ScheduleInterval
import com.keelmobile.harness.ScheduledTask
import com.keelmobile.harness.SqlSandbox
import com.keelmobile.harness.ThermalLevel
import com.keelmobile.harness.ThermalPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Persists user-created scheduled tasks and syncs them with Android WorkManager
 * for true unattended on-device background runs.
 */
class ScheduledTaskStore(private val rootDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(rootDir, "scheduled-tasks.json")

    init {
        rootDir.mkdirs()
    }

    fun load(): List<ScheduledTask> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ScheduledTask>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(tasks: List<ScheduledTask>) {
        val capped = tasks.take(ScheduledTask.MAX_SCHEDULED)
        val tmp = File(rootDir, "scheduled-tasks.json.tmp")
        tmp.writeText(json.encodeToString(capped))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun syncWithWorkManager(context: Context, task: ScheduledTask) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val workName = "keel_scheduled_${task.id}"
        if (!task.enabled) {
            wm.cancelUniqueWork(workName)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val input = Data.Builder()
            .putString(ScheduledTaskWorker.KEY_SCHEDULE_ID, task.id)
            .build()

        if (task.interval == ScheduleInterval.ONE_SHOT) {
            val req = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setInputData(input)
                .build()
            wm.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, req)
        } else {
            val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(task.interval.hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(input)
                .build()
            wm.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    fun cancelInWorkManager(context: Context, taskId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork("keel_scheduled_$taskId") }
    }
}

/**
 * Unattended WorkManager worker that runs a scheduled prompt strictly on-device,
 * saves the result into TaskStore, and notifies the user with a tap-to-open chat link.
 */
class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val storeDir = File(applicationContext.filesDir, "keel-store")
        val schedStore = ScheduledTaskStore(storeDir)
        val all = schedStore.load()
        val sched = all.firstOrNull { it.id == scheduleId && it.enabled } ?: return Result.success()

        // Respect device thermal headroom before running unattended inference
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermal = when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SERIOUS
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                else -> ThermalLevel.NOMINAL
            }
            if (ThermalPolicy.shouldStop(thermal)) {
                updateStatus(schedStore, scheduleId, "Skipped: device too warm")
                return Result.retry()
            }
        }

        val app = applicationContext as? com.keelmobile.android.KeelApplication
        if (app?.controller?.state?.value?.isGenerating == true) {
            updateStatus(schedStore, scheduleId, "Deferred: active chat in progress")
            return Result.retry()
        }

        val modelLib = ModelLibraryManager(File(applicationContext.filesDir, "models"))
        val model = modelLib.state.value.selectedModel
        if (model.id !in modelLib.state.value.installedIds) {
            updateStatus(schedStore, scheduleId, "Skipped: no local model installed")
            return Result.success()
        }

        val taskStore = TaskStore(storeDir)
        val prefs = taskStore.loadPreferences()
        val tasks = taskStore.loadTasks().toMutableList()
        val chatTask = tasks.firstOrNull { it.id == sched.linkedTaskId } ?: SavedTask(
            title = "Scheduled: ${sched.title}",
            mode = ChatMode.CHAT
        ).also { tasks.add(0, it) }

        val inference = AndroidLocalInference()
        try {
            taskStore.openWorkspace(chatTask.id).use { ws ->
                SqlSandbox(AndroidSqliteDb.openInMemory()).use { sql ->
                    val loop = AgentLoop(inference, ws, sql)
                    val sys = CustomInstructions.apply(
                        AgentPrompt.local(mode = ChatMode.CHAT),
                        prefs.customInstructions
                    )
                    val history = mutableListOf(HarnessMessage(HarnessMessage.Role.USER, sched.prompt))
                    val run = loop.runTurn(
                        model = model,
                        modelPath = modelLib.modelFilePath(model).absolutePath,
                        systemPrompt = sys,
                        history = history,
                        mode = ChatMode.CHAT,
                        thinking = false,
                        allowedUrls = mutableSetOf(),
                        cancellation = InferenceCancellation()
                    )

                    val updatedChat = chatTask.copy(
                        messages = chatTask.messages + listOf(
                            SavedMessage(role = "user", text = sched.prompt),
                            SavedMessage(role = "assistant", text = run.finalReply.ifBlank { "(Done)" })
                        ),
                        updatedAtMs = System.currentTimeMillis()
                    )
                    val nextTasks = tasks.map { if (it.id == updatedChat.id) updatedChat else it }
                    taskStore.saveTasks(nextTasks)
                    updateStatus(
                        schedStore,
                        scheduleId,
                        "Ran successfully",
                        linkedTaskId = updatedChat.id,
                        disableIfOneShot = sched.interval == ScheduleInterval.ONE_SHOT
                    )
                    notifyDone(sched.title, run.finalReply.take(140), updatedChat.id)
                }
            }
            return Result.success()
        } catch (e: Exception) {
            updateStatus(schedStore, scheduleId, "Error: ${e.message?.take(60) ?: "failed"}")
            return Result.retry()
        } finally {
            inference.unload()
        }
    }

    private fun updateStatus(
        store: ScheduledTaskStore,
        id: String,
        status: String,
        linkedTaskId: String? = null,
        disableIfOneShot: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val updated = store.load().map { s ->
            if (s.id != id) s else s.copy(
                lastRunEpochMs = now,
                lastStatus = status,
                linkedTaskId = linkedTaskId ?: s.linkedTaskId,
                enabled = if (disableIfOneShot) false else s.enabled
            )
        }
        store.save(updated)
    }

    private fun notifyDone(title: String, preview: String, taskId: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled Tasks", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_TASK
            putExtra(EXTRA_TASK_ID, taskId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, taskId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Keel · $title")
            .setContentText(preview.ifBlank { "Scheduled task completed locally." })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(taskId.hashCode(), n) }
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val CHANNEL_ID = "keel_scheduled_tasks"
        const val ACTION_OPEN_TASK = "com.keelmobile.android.OPEN_TASK"
        const val EXTRA_TASK_ID = "task_id"
    }
}
