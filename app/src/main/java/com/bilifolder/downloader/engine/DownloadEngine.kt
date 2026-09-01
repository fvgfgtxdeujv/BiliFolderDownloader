package com.bilifolder.downloader.engine

import com.bilifolder.downloader.data.model.EngineProgress
import com.bilifolder.downloader.data.model.EngineTask

/**
 * 下载引擎统一接口（设计 4.2，需求 19）。
 *
 * 两个实现（内置下载器 / Gopeed 引擎）对上层暴露同一语义，
 * [EngineTask]、[EngineProgress] 归一化后由 DownloadManager 使用。
 */
interface DownloadEngine {

    /** 引擎显示名："内置下载器" / "Gopeed 引擎" */
    val name: String

    /**
     * 是否支持带宽限速。
     * - 内置引擎：true（令牌桶限速）。
     * - Gopeed 引擎：false（v1.9.3 及 master 已移除限速能力，需求 5 第 6 条）。
     */
    val supportsLimit: Boolean

    /**
     * 引擎是否可用。
     * - 内置引擎：始终 true。
     * - Gopeed 引擎：二进制检测通过（存在、可执行、可运行）才返回 true。
     */
    suspend fun isAvailable(): Boolean

    /**
     * 创建单个文件的下载任务。
     * @param uri      下载 URL
     * @param savePath 目标文件完整路径
     * @param fileName 目标文件名（供引擎目录化 API 使用）
     */
    suspend fun download(uri: String, savePath: String, fileName: String): EngineTask

    /** 查询任务进度 */
    suspend fun query(task: EngineTask): EngineProgress

    /** 暂停任务（保留已下载进度） */
    suspend fun pause(task: EngineTask)

    /** 恢复任务 */
    suspend fun resume(task: EngineTask)

    /** 取消任务（保留已下载文件，用于断点续传） */
    suspend fun cancel(task: EngineTask)

    /** 设置全局带宽限速（bytes/sec，0 = 不限速） */
    fun setLimit(bytesPerSec: Long)

    /** 释放引擎资源（停止子进程等） */
    fun shutdown()
}
