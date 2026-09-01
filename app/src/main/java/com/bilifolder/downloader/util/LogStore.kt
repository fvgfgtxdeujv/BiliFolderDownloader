package com.bilifolder.downloader.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 调试日志数据库存储（SQLite，应用私有）。
 *
 * 日志行按时间戳入表；数据库只保留最近约 [LogUtil.RETENTION_MS]（30 分钟）的日志，
 * 由定时清理删除过期行。所有操作经 [lock] 串行执行，避免多线程并发访问 SQLite。
 */
class LogStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_TS INTEGER NOT NULL, " +
                "$COL_CONTENT TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_logs_ts ON $TABLE($COL_TS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 首版无迁移
    }

    /** 追加一条日志（明文） */
    fun insert(ts: Long, content: String) {
        synchronized(lock) {
            runCatching {
                writableDatabase.insert(
                    TABLE, null, ContentValues().apply {
                        put(COL_TS, ts)
                        put(COL_CONTENT, content)
                    },
                )
            }
        }
    }

    /** 查询 ts >= [sinceMillis] 的日志行（时间升序） */
    fun querySince(sinceMillis: Long): List<String> = synchronized(lock) {
        val rows = mutableListOf<String>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT $COL_CONTENT FROM $TABLE WHERE $COL_TS >= ? ORDER BY $COL_TS ASC, id ASC",
                arrayOf(sinceMillis.toString()),
            ).use { c ->
                while (c.moveToNext()) rows.add(c.getString(0))
            }
        }
        rows
    }

    /** 删除 ts < [beforeMillis] 的过期日志 */
    fun deleteOlderThan(beforeMillis: Long) {
        synchronized(lock) {
            runCatching {
                writableDatabase.delete(TABLE, "$COL_TS < ?", arrayOf(beforeMillis.toString()))
            }
        }
    }

    fun shutdown() {
        runCatching { close() }
    }

    private companion object {
        const val DB_NAME = "debug_logs.db"
        const val DB_VERSION = 1
        const val TABLE = "logs"
        const val COL_TS = "ts"
        const val COL_CONTENT = "content"
    }
}
