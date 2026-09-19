package com.bilifolder.downloader.engine

import android.content.Context
import android.os.Build
import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineStatus
import com.bilifolder.downloader.data.model.EngineTask
import com.bilifolder.downloader.data.model.EngineTaskKind
import com.bilifolder.downloader.util.LogUtil
import com.gopeed.libgopeed.Libgopeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Gopeed 引擎（设计 4.2.2，需求 6、15、19）。
 *
 * 以进程内原生库方式运行：通过 gomobile 绑定（`com.gopeed.libgopeed.Libgopeed`）在
 * 当前进程内启动 Gopeed 运行时，随后经 [NativeGopeedTransport] 直接调用其内部 REST
 * dispatch 创建/查询/控制任务。
 *
 * 之所以不再以子进程方式执行外部二进制：Android 10+ 的 SELinux 禁止在应用数据目录
 * 执行可执行文件（`exec` 返回 EACCES），进程内 so 是唯一可行且更省资源的形态。
 *
 * 限速：Gopeed 当前版本已移除带宽限速，[supportsLimit] = false，[setLimit] 为空操作
 * （需求 5 第 6 条：设置页对 Gopeed 禁用限速项并提示）。
 */
class GopeedEngine(
    private val appContext: Context,
) : DownloadEngine {

    override val name: String = "Gopeed 引擎"

    override val supportsLimit: Boolean = false

    /** 序列化 native 启动/停止，避免并发重复启动 */
    private val lifecycleLock = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var taskClient: GopeedTaskClient? = null

    // ---------- 生命周期 ----------

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 触发绑定类初始化（其 static 块会 System.loadLibrary("gojni")）；
            // 加载成功即代表当前设备 ABI 可用。
            Libgopeed.touch()
            true
        } catch (t: Throwable) {
            // 记录失败原因与设备 ABI，便于从导出日志直接定位
            LogUtil.e(
                TAG,
                "isAvailable: native 库加载失败: ${t.message} abi=${Build.SUPPORTED_ABIS.contentToString()}",
                t,
            )
            false
        }
    }

    /**
     * 启动进程内运行时（幂等：已启动时直接复用）。
     * @throws IOException 启动失败
     */
    suspend fun start() = lifecycleLock.withLock {
        if (started) {
            LogUtil.d(TAG, "start: 进程内引擎已启动，复用")
            return@withLock
        }
        val storageDir = File(appContext.filesDir, STORAGE_DIR_REL).apply { mkdirs() }.absolutePath
        // storage=bolt 使任务列表持久化到 storageDir；其余地址/令牌由 native 模式内部管理
        val cfg = JSONObject()
            .put("storage", "bolt")
            .put("storageDir", storageDir)
            .toString()
        withContext(Dispatchers.IO) {
            try {
                Libgopeed.start(cfg)
            } catch (t: Throwable) {
                LogUtil.e(TAG, "start: native 启动失败", t)
                throw IOException("Gopeed 引擎启动失败: ${t.message}", t)
            }
        }
        taskClient = GopeedTaskClient(NativeGopeedTransport)
        started = true
        LogUtil.d(TAG, "start: 进程内引擎就绪 storage=$storageDir")
    }

    /** 停止进程内运行时（应用退出/引擎切换时调用） */
    override fun shutdown() {
        if (!started) return
        LogUtil.d(TAG, "shutdown: 停止进程内引擎")
        runCatching { Libgopeed.stop() }
            .onFailure { LogUtil.w(TAG, "shutdown: stop 异常", it) }
        started = false
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
        val taskId = client.createTask(uri, dir, name, MediaRequestHeaders.MAP)
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

    private companion object {
        const val TAG = "GopeedEngine"
        const val STORAGE_DIR_REL = "gopeed-data"
    }
}
