package andromedvn.heuristic.activity.tracker.utils

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object HatLogger {
    private const val MAX_BREADCRUMBS = 50
    private val breadcrumbs = ArrayDeque<String>(MAX_BREADCRUMBS)
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isCrashHandling = false
    private var appContext: Context? = null

    fun log(message: String) {
        if (isCrashHandling) return
        synchronized(breadcrumbs) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            if (breadcrumbs.size >= MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast("[$timestamp] $message")
        }
    }

    // NON-FATAL EVENT LOGGER: Appends to a persistent file for logic/UI diagnostics
    fun logError(tag: String, message: String, e: Throwable? = null) {
        log("ERROR [$tag]: $message")
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "diagnostic_events.txt")
            if (file.exists() && file.length() > 1024 * 1024) file.delete()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] [$tag] $message\n")
            e?.let { file.appendText("${it.stackTraceToString()}\n") }
        } catch (ex: Exception) {}
    }

    // V.11.12 BACKGROUND LEDGER: Persists silent worker actions that don't belong in the UI breadcrumbs
    fun logBackgroundEvent(tag: String, message: String) {
        log("BACKGROUND [$tag]: $message")
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "background_worker_ledger.txt")
            // Limit to ~1MB to prevent stealth storage bloat
            if (file.exists() && file.length() > 1024 * 1024) file.delete()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] [$tag] $message\n")
        } catch (ex: Exception) {}
    }

    fun setupCrashHandler(context: Context) {
        if (defaultHandler != null) return
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isCrashHandling) return@setDefaultUncaughtExceptionHandler
            isCrashHandling = true
            
            try {
                val logFileName = saveCrashLog(context, thread, throwable)
                val intent = Intent(context, andromedvn.heuristic.activity.tracker.CrashRecoveryActivity::class.java).apply {
                    putExtra("LOG_FILE", logFileName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                exitProcess(1)
            }
        }
        log("Crash handler initialized. Escape Hatch armed.")
    }

    private fun saveCrashLog(context: Context, thread: Thread, throwable: Throwable): String {
        val dir = File(context.filesDir, "crash_logs")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "crash_$timestamp.txt"
        val file = File(dir, fileName)
        
        PrintWriter(file).use { writer ->
            writer.println("--- HAT FATAL EXCEPTION LOG ---")
            writer.println("Device OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            writer.println("Manufacturer: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            writer.println("Thread: ${thread.name}")
            writer.println("Time: $timestamp")
            writer.println("\n--- BREADCRUMBS (Last $MAX_BREADCRUMBS) ---")
            synchronized(breadcrumbs) { breadcrumbs.forEach { writer.println(it) } }
            writer.println("\n--- STACKTRACE ---")
            throwable.printStackTrace(writer)
        }
        return fileName
    }

    fun getCrashLogs(context: Context): List<File> {
        val dir = File(context.filesDir, "crash_logs")
        return if (dir.exists()) dir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList() else emptyList()
    }
    
    fun getDiagnosticLogs(context: Context): List<File> {
        val diagFile = File(context.filesDir, "diagnostics/diagnostic_events.txt")
        val bgFile = File(context.filesDir, "diagnostics/background_worker_ledger.txt")
        return listOfNotNull(
            diagFile.takeIf { it.exists() },
            bgFile.takeIf { it.exists() }
        )
    }

    fun clearLogs(context: Context) {
        val crashDir = File(context.filesDir, "crash_logs")
        if (crashDir.exists()) crashDir.listFiles()?.forEach { it.delete() }
        val diagDir = File(context.filesDir, "diagnostics")
        if (diagDir.exists()) diagDir.listFiles()?.forEach { it.delete() }
        log("All telemetry logs cleared")
    }
}
