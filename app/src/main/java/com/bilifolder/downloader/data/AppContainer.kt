package com.bilifolder.downloader.data

import android.content.Context
import com.bilifolder.downloader.download.DownloadManager
import com.bilifolder.downloader.engine.GopeedEngine
import com.bilifolder.downloader.engine.OkHttpDownloadEngine
import com.bilifolder.downloader.util.LogEncryptor
import com.bilifolder.downloader.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用级依赖容器（手动 DI）。各组件在此构建并共享单例。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        configureDebugLogging()
        LogUtil.d(TAG, "AppContainer 初始化")
    }

    /**
     * 容器构造完成后调用（BiliApp.onCreate）。
     * 注意：不要在构造函数里 launch 协程——构造期间 by lazy 字段尚未赋值，
     * 协程在 IO 线程抢先运行会触发 getRecordStore() 的 delegate NPE。
     */
    fun start() {
        scope.launch {
            val enabled = recordStore.debugLogEnabled.first()
            LogUtil.setDebugOverride(enabled)
            LogUtil.d(TAG, "调试日志开关恢复：$enabled")
        }
    }

    /**
     * 正式版调试日志（同步初始化，构造期间调用）：
     * 初始化日志数据库 + 加载加密证书/公钥。
     * 密钥文件由开发者自行生成后放入 assets：
     * - `log_encryption_cert.pem`（首选）：`openssl req -new -x509 -key private_key.pem -out log_encryption_cert.pem -days 365 -subj "/CN=..."` 的自签名证书
     * - `rsa_public_key.pem`（回退）：`openssl rsa -in private_key.pem -pubout -out rsa_public_key.pem` 的裸公钥
     * 均未放置时加密器为 null，正式版导出功能不可用（不泄露明文）。
     */
    private fun configureDebugLogging() {
        // 日志目录：外部应用专属目录 logs/，外部存储不可用时回退内部 filesDir/logs
        val external = appContext.getExternalFilesDir(null)
        val logsDir = if (external != null) File(external, "logs") else File(appContext.filesDir, "logs")
        // 日志入库 SQLite，保留最近 30 分钟，每 30 分钟清理一次
        LogUtil.initLogStore(appContext, logsDir)

        // 优先加载证书（含有效期等语义），失败回退裸公钥
        val encryptor = readAsset(ASSET_LOG_CERT)?.let { LogEncryptor.fromPem(it) }
            ?: readAsset(ASSET_RSA_PUBLIC_KEY)?.let { LogEncryptor.fromPem(it) }
        LogUtil.setEncryptor(encryptor)
        if (encryptor != null) {
            LogUtil.d(TAG, "调试日志加密证书/公钥已加载")
        }
    }

    /** 读取 assets 文本文件；不存在或读取失败返回 null */
    private fun readAsset(name: String): String? = runCatching {
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    val cookieStore: CookieStore by lazy { CookieStore(appContext) }

    val recordStore: DownloadRecordStore by lazy { DownloadRecordStore(appContext) }

    val storageManager: StorageManager by lazy { StorageManager(appContext) }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    val biliApiClient: BiliApiClient by lazy { BiliApiClient(cookieStore) }

    val okHttpEngine: OkHttpDownloadEngine by lazy { OkHttpDownloadEngine() }

    val gopeedEngine: GopeedEngine by lazy { GopeedEngine(appContext) }

    val webDavClient: WebDavClient by lazy { WebDavClient() }

    val library: VideoLibrary by lazy { VideoLibrary(storageManager) }

    val downloadManager: DownloadManager by lazy {
        LogUtil.d(TAG, "初始化 downloadManager")
        DownloadManager(
            biliApiClient = biliApiClient,
            recordStore = recordStore,
            cookieStore = cookieStore,
            networkMonitor = networkMonitor,
            storageManager = storageManager,
            okHttpEngine = okHttpEngine,
            gopeedEngine = gopeedEngine,
            webDavClient = webDavClient,
        )
    }

    private companion object {
        const val TAG = "AppContainer"
        const val ASSET_LOG_CERT = "log_encryption_cert.pem"
        const val ASSET_RSA_PUBLIC_KEY = "rsa_public_key.pem"
    }
}
