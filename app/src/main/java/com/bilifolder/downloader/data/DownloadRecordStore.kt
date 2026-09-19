package com.bilifolder.downloader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bilifolder.downloader.data.model.DownloadEngineType
import com.bilifolder.downloader.data.model.TaskHistory
import com.bilifolder.downloader.data.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 应用级 DataStore（Preferences）单例扩展属性 */
private val Context.dataStore by preferencesDataStore(name = "download_records")

/**
 * 应用设置与任务记录存储（设计 9.5 存储层表）。
 *
 * 使用 DataStore(Preferences)：已下载 bvid 集合、下载设置、
 * 引擎类型、WebDAV 配置、任务历史、首启合规标记。
 */
class DownloadRecordStore(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val DOWNLOADED_BVIDS = stringSetPreferencesKey("downloaded_bvids")
        val LIMIT_KBPS = intPreferencesKey("limit_kbps")          // 0 = 不限速，默认 100
        val QUALITY = intPreferencesKey("quality")                // 默认 80 (1080P)
        val DELETE_AFTER_DOWNLOAD = booleanPreferencesKey("delete_after_download")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MOBILE_DATA_PROMPT = booleanPreferencesKey("mobile_data_prompt")
        val RETRY_COUNT = intPreferencesKey("retry_count")
        val ENGINE_TYPE = stringPreferencesKey("engine_type")
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        val TASK_HISTORY = stringPreferencesKey("task_history")
        val ONBOARDING_AGREED = booleanPreferencesKey("onboarding_agreed")
        val LAST_MID = longPreferencesKey("last_mid")
        val DEBUG_LOG_ENABLED = booleanPreferencesKey("debug_log_enabled")
    }

    // ---------- 已下载 bvid 集合（需求 8 去重） ----------

    val downloadedBvids: Flow<Set<String>> = appContext.dataStore.data.map { it[Keys.DOWNLOADED_BVIDS] ?: emptySet() }

    suspend fun addDownloadedBvids(bvids: Collection<String>) {
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.DOWNLOADED_BVIDS] ?: emptySet()
            prefs[Keys.DOWNLOADED_BVIDS] = current + bvids
        }
    }

    // ---------- 下载设置 ----------

    val settings: Flow<DownloadSettings> = appContext.dataStore.data.map { p ->
        DownloadSettings(
            limitKbps = p[Keys.LIMIT_KBPS] ?: 100,
            quality = p[Keys.QUALITY] ?: 80,
            deleteAfterDownload = p[Keys.DELETE_AFTER_DOWNLOAD] ?: false,
            wifiOnly = p[Keys.WIFI_ONLY] ?: false,
            mobileDataPrompt = p[Keys.MOBILE_DATA_PROMPT] ?: true,
            retryCount = p[Keys.RETRY_COUNT] ?: 2,
        )
    }

    suspend fun updateSettings(block: suspend (DownloadSettings) -> DownloadSettings) {
        val current = settings.first()
        val updated = block(current)
        appContext.dataStore.edit { p ->
            p[Keys.LIMIT_KBPS] = updated.limitKbps
            p[Keys.QUALITY] = updated.quality
            p[Keys.DELETE_AFTER_DOWNLOAD] = updated.deleteAfterDownload
            p[Keys.WIFI_ONLY] = updated.wifiOnly
            p[Keys.MOBILE_DATA_PROMPT] = updated.mobileDataPrompt
            p[Keys.RETRY_COUNT] = updated.retryCount
        }
    }

    // ---------- 引擎类型（需求 19） ----------

    val engineType: Flow<DownloadEngineType> = appContext.dataStore.data.map { p ->
        runCatching { DownloadEngineType.valueOf(p[Keys.ENGINE_TYPE] ?: "") }
            .getOrDefault(DownloadEngineType.BUILTIN)
    }

    suspend fun setEngineType(type: DownloadEngineType) {
        appContext.dataStore.edit { it[Keys.ENGINE_TYPE] = type.name }
    }

    // ---------- WebDAV 配置（需求 20） ----------

    val webDavConfig: Flow<WebDavConfig> = appContext.dataStore.data.map { p ->
        p[Keys.WEBDAV_CONFIG]?.let { raw ->
            runCatching { json.decodeFromString(WebDavConfig.serializer(), raw) }.getOrNull()
        } ?: WebDavConfig()
    }

    suspend fun setWebDavConfig(config: WebDavConfig) {
        appContext.dataStore.edit { it[Keys.WEBDAV_CONFIG] = json.encodeToString(WebDavConfig.serializer(), config) }
    }

    // ---------- 任务历史（需求 18） ----------

    val taskHistory: Flow<List<TaskHistory>> = appContext.dataStore.data.map { p ->
        p[Keys.TASK_HISTORY]?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(TaskHistory.serializer()), raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun addTaskHistory(history: TaskHistory) {
        appContext.dataStore.edit { p ->
            val current = p[Keys.TASK_HISTORY]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(TaskHistory.serializer()), raw) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(history) + current).take(50)
            p[Keys.TASK_HISTORY] = json.encodeToString(ListSerializer(TaskHistory.serializer()), updated)
        }
    }

    /** 按时间戳删除单条历史记录 */
    suspend fun deleteTaskHistory(timestamp: Long) {
        appContext.dataStore.edit { p ->
            val current = p[Keys.TASK_HISTORY]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(TaskHistory.serializer()), raw) }.getOrNull()
            } ?: emptyList()
            val updated = current.filterNot { it.timestamp == timestamp }
            p[Keys.TASK_HISTORY] = json.encodeToString(ListSerializer(TaskHistory.serializer()), updated)
        }
    }

    // ---------- 首启合规（需求 17） ----------

    val onboardingAgreed: Flow<Boolean> = appContext.dataStore.data.map { it[Keys.ONBOARDING_AGREED] ?: false }

    suspend fun setOnboardingAgreed(agreed: Boolean) {
        appContext.dataStore.edit { it[Keys.ONBOARDING_AGREED] = agreed }
    }

    // ---------- 上次输入 MID ----------

    val lastMid: Flow<Long> = appContext.dataStore.data.map { it[Keys.LAST_MID] ?: 0L }

    suspend fun setLastMid(mid: Long) {
        appContext.dataStore.edit { it[Keys.LAST_MID] = mid }
    }

    // ---------- 调试日志开关（正式版设置页） ----------

    val debugLogEnabled: Flow<Boolean> = appContext.dataStore.data.map { it[Keys.DEBUG_LOG_ENABLED] ?: false }

    suspend fun setDebugLogEnabled(enabled: Boolean) {
        appContext.dataStore.edit { it[Keys.DEBUG_LOG_ENABLED] = enabled }
    }
}

/**
 * 下载设置聚合（限速/清晰度/删除/仅 WiFi/移动数据提醒/重试）。
 */
data class DownloadSettings(
    val limitKbps: Int = 100,
    val quality: Int = 80,
    val deleteAfterDownload: Boolean = false,
    val wifiOnly: Boolean = false,
    val mobileDataPrompt: Boolean = true,
    val retryCount: Int = 2,
)
