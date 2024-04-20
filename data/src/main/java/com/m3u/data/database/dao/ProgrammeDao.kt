package com.m3u.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.ProgrammeSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
 interface ProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(programme: Programme)

    @Query(
        """
            SELECT playlist_url, MAX("end") AS "end" from programmes GROUP BY playlist_url
        """
    )
    fun observeSnapshotsGroupedByPlaylistUrl(): Flow<List<ProgrammeSnapshot>>

    @Query("SELECT * FROM programmes WHERE stream_id = :streamId ORDER BY start")
    fun pagingAllByStreamId(streamId: Int): PagingSource<Int, Programme>

    @Query("DELETE FROM programmes WHERE playlist_url = :playlistUrl AND `end` >= :after")
    suspend fun clearByPlaylistUrlAndLeftRange(playlistUrl: String, after: Long)

    @Query("SELECT * FROM programmes ORDER BY start")
    fun observeAll(): Flow<List<Programme>>
}