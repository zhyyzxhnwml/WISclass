package com.shangkele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shangkele.core.database.entity.CampusPoiEntity
import kotlinx.coroutines.flow.Flow

/**
 * 校园地点（教室）的坐标。
 *
 * `campus_poi` 表从 v2 起就在 schema 里（含 `lat`/`lng`），但一直没有 DAO ——
 * 所以这里**不需要新的迁移**，加方法即可。
 *
 * 坐标不是靠地图服务灌进来的，而是**用户站在教室里按一下「到了」时采下来的**：
 * 不用申请地图 SDK 的 key、不用联网，而且数据一定对得上那间教室。
 */
@Dao
interface CampusPoiDao {

    @Query("SELECT * FROM campus_poi")
    fun observeAll(): Flow<List<CampusPoiEntity>>

    /**
     * 取**已经有两坐标值**的地点。
     *
     * 表里允许先只记楼栋/楼层（`lat`/`lng` 为 null），那种行拿去算距离没有意义，
     * 在这里就滤掉，别让调用方拿到一堆半成品。
     */
    @Query("SELECT * FROM campus_poi WHERE lat IS NOT NULL AND lng IS NOT NULL")
    suspend fun getLocated(): List<CampusPoiEntity>

    @Query("SELECT * FROM campus_poi WHERE roomKey = :roomKey LIMIT 1")
    suspend fun getByRoomKey(roomKey: String): CampusPoiEntity?

    @Insert
    suspend fun insert(poi: CampusPoiEntity): Long

    @Query("UPDATE campus_poi SET lat = :lat, lng = :lng WHERE roomKey = :roomKey")
    suspend fun updateLocation(roomKey: String, lat: Double, lng: Double)
}
