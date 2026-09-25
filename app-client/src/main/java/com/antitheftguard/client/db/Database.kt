package com.antitheftguard.client.db

import android.content.Context
import androidx.room.*

@Entity(tableName = "gps_points")
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double, val lng: Double,
    val accuracy: Float, val speed: Float,
    val timestamp: Long, val batteryLevel: Int,
    val synced: Boolean = false
)

@Dao
interface GpsPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: GpsPointEntity)

    @Query("SELECT * FROM gps_points WHERE synced = 0")
    suspend fun getUnsynced(): List<GpsPointEntity>

    @Query("UPDATE gps_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM gps_points WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Database(entities = [GpsPointEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gpsPointDao(): GpsPointDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "antitheft_db"
                ).build().also { instance = it }
            }
        }
    }
}
