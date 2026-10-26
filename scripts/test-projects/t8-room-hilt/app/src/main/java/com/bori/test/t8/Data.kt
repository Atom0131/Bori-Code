package com.bori.test.t8

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.squareup.moshi.JsonClass

@Entity
data class Item(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Dao
interface ItemDao {
    @Insert
    fun insert(item: Item): Long

    @Query("SELECT * FROM Item ORDER BY id")
    fun all(): List<Item>
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
}

@JsonClass(generateAdapter = true)
data class Message(val text: String, val count: Int)
