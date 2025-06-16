package com.kotlinx.inspireface.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.kotlinx.inspireface.User

class FaceDatabaseHelper(context: Context) : SQLiteOpenHelper(context, (context.getExternalFilesDir("")?.absolutePath ?: context.filesDir.path) + "/face_name.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE face_info (id INTEGER PRIMARY KEY, name TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertName(id: Int, name: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
        }
        //use 会自动关闭连接
        writableDatabase.use { db ->
            db.insertWithOnConflict("face_info", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

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

    fun deleteName(id: Int) {
        writableDatabase.use { db ->
            db.delete("face_info", "id = ?", arrayOf(id.toString()))
        }
    }

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
}
