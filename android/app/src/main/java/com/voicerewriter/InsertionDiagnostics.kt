package com.voicerewriter

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class InsertionReport(
    val id: String,
    val timestamp: Long,
    val appPackage: String,
    val outcome: String,
    val environment: String,
    val initialFocus: String,
    val events: List<String>,
    val primaryFailure: String? = null,
) {
    fun json(): String = JSONObject().put("id", id).put("timestamp", timestamp)
        .put("package", appPackage).put("outcome", outcome).put("environment", environment)
        .put("initialFocus", initialFocus).put("events", JSONArray(events))
        .put("primaryFailure", primaryFailure ?: JSONObject.NULL).toString()

    fun display(): String = "${java.util.Date(timestamp)}\nApp: $appPackage\nOutcome: $outcome\n$environment\n" +
        "Direct insertion: ${primaryFailure ?: "no recorded primary-method failure"}\n" +
        "Before opening dictation: $initialFocus\n\n" + events.joinToString("\n")

    companion object {
        fun parse(text: String): InsertionReport {
            val o = JSONObject(text)
            val events = o.getJSONArray("events")
            return InsertionReport(o.getString("id"), o.getLong("timestamp"), o.getString("package"),
                o.getString("outcome"), o.getString("environment"), o.getString("initialFocus"),
                (0 until events.length()).map { events.getString(it) },
                if (o.isNull("primaryFailure")) null else o.getString("primaryFailure"))
        }
    }
}

/** One immutable file per failure. Oldest reports across ALL apps are evicted first. */
internal class RollingInsertionLog(
    private val directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val maxReports: Int = 100,
) {
    @Synchronized fun append(report: InsertionReport) {
        directory.mkdirs()
        var trimmed = report.copy(appPackage = report.appPackage.take(256),
            outcome = report.outcome.take(128), environment = report.environment.take(512),
            initialFocus = report.initialFocus.take(2048))
        var bytes = trimmed.json().toByteArray(Charsets.UTF_8)
        val reportLimit = minOf(32L * 1024, maxBytes)
        while (bytes.size > reportLimit && trimmed.events.isNotEmpty()) {
            // Preserve the opening decisions while dropping older tail events.
            val events = if (trimmed.events.size > 9) trimmed.events.take(9) + trimmed.events.drop(10)
                else trimmed.events.dropLast(1)
            trimmed = trimmed.copy(events = events)
            bytes = trimmed.json().toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > reportLimit) return
        val files = files().toMutableList()
        var total = files.sumOf { it.length() }
        while (files.isNotEmpty() && (total + bytes.size > maxBytes || files.size >= maxReports)) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (!oldest.delete()) return // Do not exceed the cap if eviction fails.
            total -= size
        }
        val target = File(directory, "${report.timestamp}-${UUID.randomUUID()}.json")
        try { target.writeBytes(bytes) } catch (e: Exception) { target.delete(); throw e }
    }

    @Synchronized fun reports(): List<InsertionReport> = files().mapNotNull {
        runCatching { InsertionReport.parse(it.readText()) }.getOrNull()
    }.sortedByDescending { it.timestamp }

    @Synchronized fun clear(appPackage: String? = null) {
        files().forEach { file ->
            if (appPackage == null || runCatching {
                    InsertionReport.parse(file.readText()).appPackage == appPackage
                }.getOrDefault(false)) file.delete()
        }
    }

    private fun files(): List<File> = directory.listFiles()?.filter { it.isFile }
        ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
}

/** Request-local metadata ring. Successful, verified requests never reach disk. */
internal class InsertionTrace(
    val appPackage: String,
    private val initialFocus: String,
    private val environment: String,
    private val persist: (InsertionReport) -> Unit,
) {
    private val id = UUID.randomUUID().toString()
    private val started = System.currentTimeMillis()
    private val events = ArrayDeque<String>()
    private val firstEvents = mutableListOf<String>()
    private val states = linkedMapOf<String, String>()
    private var chars = 0
    private var dropped = 0
    private var suppressed = 0
    private var finished = false
    private var primaryFailure: String? = null
    var problem: String? = null

    fun add(message: String) {
        if (finished) return
        val event = "+${System.currentTimeMillis() - started}ms ${message.take(768)}"
        if (firstEvents.size < 8) {
            firstEvents.add(event)
            return
        }
        events.addLast(event); chars += event.length
        while (events.size > 64 || chars > 8192) {
            chars -= events.removeFirst().length; dropped++
        }
    }

    fun state(key: String, message: String) {
        if (finished) return
        val bounded = message.take(768)
        if (states[key] == bounded) { suppressed++; return }
        if (key !in states && states.size >= 64) states.remove(states.keys.first())
        states[key] = bounded
        add(bounded)
    }

    fun directFailed(reason: String) {
        if (primaryFailure == null) primaryFailure = reason.take(128)
        state("primary-failure", "Direct insertion failed/skipped reason=$reason")
    }

    fun finish(outcome: String?) {
        if (finished) return
        finished = true
        val failure = problem ?: outcome ?: primaryFailure?.let { "direct_entry_failed_then_recovered" } ?: return
        persist(InsertionReport(id, started, appPackage, failure, environment, initialFocus,
            listOf("Older tail events dropped: $dropped; unchanged snapshots suppressed: $suppressed") +
                firstEvents + events.toList(), primaryFailure))
    }
}

internal object InsertionDiagnostics {
    private const val PREFS = "insertion_diagnostics"
    private val lock = Any()
    private var store: RollingInsertionLog? = null
    private var generation = 0
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16), ThreadPoolExecutor.DiscardOldestPolicy())

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    private fun store(context: Context): RollingInsertionLog = store ?: RollingInsertionLog(
        File(context.noBackupFilesDir, "insertion_diagnostics"),
    ).also { store = it }

    fun begin(context: Context, targetPackage: String?, initialFocus: String): InsertionTrace? {
        val app = context.applicationContext
        if (!enabled(app)) return null
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        return InsertionTrace(targetPackage ?: "unknown", initialFocus,
            "OpenWispr $version; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}") { report ->
            val epoch = synchronized(lock) { generation }
            writer.execute {
                synchronized(lock) {
                    if (generation == epoch && enabled(app)) runCatching { store(app).append(report) }
                }
            }
        }
    }

    fun reports(context: Context): List<InsertionReport> = synchronized(lock) { store(context).reports() }

    fun clear(context: Context, appPackage: String? = null) = synchronized(lock) {
        generation++
        store(context).clear(appPackage)
    }
}
