package com.bilifolder.downloader.util

import android.content.Context
import android.util.Log
import com.bilifolder.downloader.BuildConfig
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 统一日志工具。
 *
 * 存储：详细日志以明文写入 SQLite（[LogStore]），数据库只保留最近 30 分钟，
 * 由守护线程每 30 分钟清理一次过期行。
 *
 * 输出规则：
 * - **logcat**：仅开发版（[BuildConfig.DEBUG]）输出明文；正式版完全禁止 logcat。
 * - **导出文件**：设置页「导出日志文件」默认导出最近 30 分钟，拼接为完整 txt。
 *   开发版导出明文 txt；正式版将整个 txt 一次性经 [LogEncryptor] 加密
 *   （CMS AuthEnvelopedData / AES-256-GCM，`openssl cms -encrypt -aes-256-gcm` 等价），
 *   输出 openssl SMIME 多行文本，`openssl cms -decrypt -in 文件 -inkey 私钥` 直接可解；
 *   未配置证书/公钥时正式版导出返回 null。
 *
 * 纯 JVM 单元测试环境（未加载 Robolectric）下 `android.util.Log` 不可用，
 * logcat 通道静默降级，DB 未初始化时导出返回 null。
 */
object LogUtil {

    /** 数据库保留时长 / 清理周期（毫秒，30 分钟） */
    const val RETENTION_MS = 30 * 60 * 1000L

    /** 导出默认时间范围（分钟，最近 30 分钟） */
    const val DEFAULT_EXPORT_MINUTES = 30

    /** 导出文件名（固定名，每次覆盖） */
    const val EXPORT_FILE_NAME = "bili_debug_export.txt"

    /** 正式版调试日志开关（由设置页持久化 + App 启动时恢复） */
    @Volatile
    private var debugOverride = false

    /** 正式版日志加密器；未配置公钥时为 null */
    @Volatile
    private var encryptor: LogEncryptor? = null

    /** 日志数据库 */
    @Volatile
    private var logStore: LogStore? = null

    /** 导出目录（getExternalFilesDir/logs，外部不可用时 filesDir/logs） */
    @Volatile
    private var logsDir: File? = null

    /** 正式版设置页开关 */
    fun setDebugOverride(enabled: Boolean) {
        debugOverride = enabled
    }

    /** 配置日志加密公钥（App 启动时从 assets 加载） */
    fun setEncryptor(encryptor: LogEncryptor?) {
        this.encryptor = encryptor
    }

    /**
     * 初始化日志数据库（App 启动时调用一次），并启动每 30 分钟的过期清理。
     * 重复调用会关闭旧库并重建（测试场景）。
     */
    fun initLogStore(context: Context, dir: File) {
        logStore?.shutdown()
        logStore = runCatching { LogStore(context.applicationContext) }.getOrNull()
        logsDir = dir
        if (logStore != null) {
            insertLine(Log.INFO, TAG, "日志数据库已初始化：${dir.absolutePath}")
            startCleanupThread()
        }
    }

    /**
     * 导出日志文件（默认最近 30 分钟），返回文件；无日志或未初始化返回 null。
     * 每次覆盖同名文件。
     * - 开发版：写明文 txt
     * - 正式版：把完整 txt 整体做一次 AES-256-GCM(RSA) 加密，文件内容为 openssl SMIME 文本
     */
    fun exportLogs(minutes: Int = DEFAULT_EXPORT_MINUTES): File? {
        val store = logStore ?: return null
        val dir = logsDir ?: return null
        val since = System.currentTimeMillis() - minutes * 60_000L
        val rows = store.querySince(since)
        if (rows.isEmpty()) return null
        val fullText = buildString {
            append("=== bili debug log export (last $minutes min, ${rows.size} lines) ===\n")
            rows.forEach { append(it).append('\n') }
        }
        val file = File(dir, EXPORT_FILE_NAME)
        runCatching {
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                if (BuildConfig.DEBUG) {
                    // 开发版：明文 txt
                    w.write(fullText)
                } else {
                    // 正式版：整个 txt 一次加密（openssl cms -encrypt -aes-256-gcm 等价），
                    // 输出 openssl cms -decrypt 默认可直接读取的 SMIME 多行文本
                    val enc = encryptor ?: return null
                    w.write(encryptExport(fullText, enc) ?: return null)
                }
            }
        }.getOrNull() ?: return null
        return file
    }

    /** 正式版导出核心：整个文本一次加密为 openssl SMIME（独立出来便于单测直连） */
    internal fun encryptExport(fullText: String, enc: LogEncryptor? = encryptor): String? =
        enc?.encrypt(fullText)

    fun v(tag: String, msg: String) = log(Log.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    private fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (!isDetailedEnabled()) return
        insertLine(level, tag, msg)
        runCatching { writeLogcat(level, tag, msg, tr) }
    }

    /** 数据库明文存储，导出的正式版文件整体加密 */
    private fun insertLine(level: Int, tag: String, msg: String) {
        val store = logStore ?: return
        val line = "[${timeStamp()}] [${LEVEL_NAMES[level]}] $tag: $msg"
        store.insert(System.currentTimeMillis(), line)
    }

    /** logcat：仅开发版明文；正式版禁止。纯 JVM 测试环境静默降级。 */
    private fun writeLogcat(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (!BuildConfig.DEBUG) return
        try {
            val full = if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg
            Log.println(level, tag, full)
        } catch (_: Throwable) {
            // 纯 JVM 测试环境无 android Log
        }
    }

    private fun isDetailedEnabled(): Boolean = BuildConfig.DEBUG || debugOverride

    /** 守护线程：每 30 分钟清理一次，只保留最近 30 分钟日志 */
    private fun startCleanupThread() {
        if (cleanupStarted) return
        cleanupStarted = true
        Thread {
            while (true) {
                val store = logStore ?: break
                runCatching { store.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS) }
                try {
                    Thread.sleep(RETENTION_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "log-db-cleanup"
            start()
        }
    }

    private fun timeStamp(): String = LocalTime.now().format(TIME_FMT)

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val LEVEL_NAMES: Map<Int, String> = mapOf(
        Log.VERBOSE to "V",
        Log.DEBUG to "D",
        Log.INFO to "I",
        Log.WARN to "W",
        Log.ERROR to "E",
    )

    private const val TAG = "LogUtil"

    @Volatile
    private var cleanupStarted = false
}
