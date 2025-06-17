package com.kotlinx.inspireface.db

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class User(val id: Int, val name: String)

class FaceDatabaseHelper(context: Application, val dbPath: String = context.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" }) : SQLiteOpenHelper(context, dbPath, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        // 创建 face_info 表，包含 id 和 name 字段
        db.execSQL("CREATE TABLE face_info (id INTEGER PRIMARY KEY, name TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 数据库升级逻辑，当前为空
    }

    // 插入或更新名称
    fun insertName(id: Int, name: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
        }
        // use 会自动关闭数据库连接
        writableDatabase.use { db ->
            db.insertWithOnConflict("face_info", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // 根据 id 查询名称
    fun queryName(id: Int): String {
        var name = "未知"
        readableDatabase.use { db ->
            db.rawQuery("SELECT name FROM face_info WHERE id = ?", arrayOf(id.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                }
            }
        }
        return name
    }

    // 根据 id 删除名称
    fun deleteName(id: Int) {
        writableDatabase.use { db ->
            db.delete("face_info", "id = ?", arrayOf(id.toString()))
        }
    }

    // 获取所有用户信息
    fun getAllUsers(): List<User> {
        val users = mutableListOf<User>()
        readableDatabase.use { db ->
            db.rawQuery("SELECT id, name FROM face_info ORDER BY id ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val name = cursor.getString(1)
                    users.add(User(id, name))
                }
            }
        }
        return users
    }

    // 根据 id 修改名称
    fun updateName(id: Int, newName: String) {
        val values = ContentValues().apply {
            put("name", newName)
        }
        // use 会自动关闭数据库连接
        writableDatabase.use { db ->
            db.update("face_info", values, "id = ?", arrayOf(id.toString()))
        }
    }
}