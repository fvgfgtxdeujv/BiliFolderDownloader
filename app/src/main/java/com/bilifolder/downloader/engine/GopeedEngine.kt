package com.bilifolder.downloader.engine

import android.content.Context
import android.os.Build
import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.data.model.EngineTask
import com.bilifolder.downloader.data.model.EngineTaskKind
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Gopeed 引擎（设计 4.2.2，需求 6、15、19）。
 *
 * 职责：
 * - 首次使用时把 assets/gopeed/gopeed-arm64 解压到 filesDir/gopeed/ 并赋予可执行权限
 * - 以子进程方式启动 headless 服务（`--address 127.0.0.1:<port> --token <token> --storage-dir <dir>`），
 *   通过 [GopeedTaskClient] 走 REST API 创建/查询/控制任务
 * - 进程退出时清理子进程
 *
 * 限速：Gopeed 当前版本已移除带宽限速，[supportsLimit] = false，[setLimit] 为空操作
 * （需求 5 第 6 条：设置页对 Gopeed 禁用限速项并提示）。
 */
class GopeedEngine(
    private val appContext: Context,
) : DownloadEngine {

    override val name: String = "Gopeed 引擎"

    override val supportsLimit: Boolean = false

    /** 序列化子进程启动/停止，避免并发重复启动 */
    private val lifecycleLock = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var taskClient: GopeedTaskClient? = null

    /** 进程输出消费线程（防管道阻塞） */
    private var outputPump: Thread? = null

    // ---------- 生命周期 ----------

    /** 解压内嵌二进制（幂等） */
    suspend fun ensureBinaryExtracted(): Boolean = withContext(Dispatchers.IO) {
        val target = binaryFile()
        if (target.exists() && target.length() > 0 && target.canExecute()) {
            LogUtil.d(TAG, "ensureBinaryExtracted: 已存在 ${target.absolutePath}")
            return@withContext true
        }
        try {
            target.parentFile?.mkdirs()
            val input: InputStream = appContext.assets.open(ASSET_PATH)
            val output = FileOutputStream(target)
            input.copyTo(output)
            output.flush()
            output.close()
            input.close()
            target.setExecutable(true, false)
            val ok = target.length() > 0
            LogUtil.d(TAG, "ensureBinaryExtracted: 解压完成 size=${target.length()}")
            ok
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureBinaryExtracted: 解压失败", e)
            false
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (!ensureBinaryExtracted()) return false
        return withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(binaryFile().absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    LogUtil.w(TAG, "isAvailable: --version 超时")
                    proc.destroy()
                    return@withContext false
                }
                val ok = proc.exitValue() == 0
                LogUtil.d(TAG, "isAvailable: exit=${proc.exitValue()} ok=$ok")
                ok
            } catch (e: IOException) {
                // 记录失败原因与设备 ABI，便于从导出日志直接定位
                // （exec 拒绝多为 noexec/SELinux 的 Permission denied，架构不符为 Exec format error）
                LogUtil.e(
                    TAG,
                    "isAvailable: 执行失败: ${e.message} abi=${Build.SUPPORTED_ABIS.contentToString()}",
                    e,
                )
                false
            }
        }
    }

    /**
     * 启动 headless 服务（幂等：进程存活时直接复用）。
     * @throws IOException 启动失败或健康检查超时
     */
    suspend fun start() = lifecycleLock.withLock {
        if (process?.isAlive == true) {
            LogUtil.d(TAG, "start: 进程已存活，复用")
            return@withLock
        }
        val binary = binaryFile()
        val port = (20000..60000).random()
        val token = UUID.randomUUID().toString().replace("-", "")
        val storageDir = File(appContext.filesDir, "gopeed-data").absolutePath
        val cmd = listOf(
            binary.absolutePath,
            "--address", "127.0.0.1:$port",
            "--token", token,
            "--storage-dir", storageDir,
        )
        LogUtil.d(TAG, "start: 启动 headless 端口=$port storage=$storageDir")
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            LogUtil.e(TAG, "start: 进程启动失败", e)
            throw IOException("Gopeed 进程启动失败: ${e.message}", e)
        }
        // 消费 stdout/stderr，避免管道写满阻塞子进程
        outputPump = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    // 日志仅用于排障；不包含令牌等敏感信息
                    LogUtil.d(TAG, "gopeed: $line")
                }
            } catch (e: IOException) {
                // 进程退出后管道关闭属正常
            }
        }.apply { isDaemon = true; start() }

        val client = GopeedTaskClient("http://127.0.0.1:$port", token)
        // 健康检查：最长 10s
        var ok = false
        repeat(50) {
            if (!proc.isAlive) return@repeat
            if (client.healthCheck()) {
                ok = true
                return@repeat
            }
            delay(200)
        }
        if (!ok) {
            LogUtil.e(TAG, "start: 健康检查超时")
            proc.destroy()
            process = null
            throw IOException("Gopeed 服务健康检查超时")
        }
        LogUtil.d(TAG, "start: headless 就绪")
        process = proc
        taskClient = client
    }

    /** 停止子进程（应用退出/引擎切换时调用） */
    override fun shutdown() {
        LogUtil.d(TAG, "shutdown: 停止子进程")
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                runCatching { p.waitFor(3, TimeUnit.SECONDS) }
                if (p.isAlive) p.destroyForcibly()
            }
        }
        process = null
        taskClient = null
    }

    // ---------- DownloadEngine 实现 ----------

    override suspend fun download(uri: String, savePath: String, fileName: String): EngineTask {
        LogUtil.d(TAG, "download: $fileName -> $savePath")
        start()
        val client = taskClient ?: throw IOException("Gopeed 引擎未就绪")
        val target = File(savePath)
        val dir = target.parent ?: throw IOException("无效保存路径: $savePath")
        val name = fileName.ifBlank { target.name }
        val taskId = client.createTask(uri, dir, name)
            ?: throw IOException("Gopeed 创建任务失败")
        LogUtil.d(TAG, "download: 任务创建成功 taskId=$taskId")
        return EngineTask(taskId, EngineTaskKind.FILE, savePath)
    }

    override suspend fun query(task: EngineTask): EngineProgress {
        val client = taskClient ?: return EngineProgress(0, 0, EngineStatus.ERROR)
        return client.queryTask(task.id)?.toEngineProgress()
            ?: EngineProgress(0, 0, EngineStatus.ERROR)
    }

    override suspend fun pause(task: EngineTask) {
        taskClient?.pauseTask(task.id)
    }

    override suspend fun resume(task: EngineTask) {
        taskClient?.resumeTask(task.id)
    }

    override suspend fun cancel(task: EngineTask) {
        // Gopeed 删除任务会清理分块数据；与内置引擎"保留残留"的差异已在设计 4.2.2 说明
        taskClient?.deleteTask(task.id)
    }

    /** Gopeed 不支持限速（需求 5 第 6 条） */
    override fun setLimit(bytesPerSec: Long) = Unit

    // ---------- 内部 ----------

    private fun binaryFile(): File = File(appContext.filesDir, BINARY_REL_PATH)

    private companion object {
        const val TAG = "GopeedEngine"
        const val ASSET_PATH = "gopeed/gopeed-arm64"
        const val BINARY_REL_PATH = "gopeed/gopeed-arm64"
    }
}
