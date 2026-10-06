package com.shangkele.core.database.di

import android.content.Context
import androidx.room.Room
import com.shangkele.core.database.ALL_MIGRATIONS
import com.shangkele.core.database.ShangKeLeDatabase
import com.shangkele.core.database.dao.ChangeLogDao
import com.shangkele.core.database.dao.CourseDao
import com.shangkele.core.database.dao.NoteDao
import com.shangkele.core.database.dao.SemesterDao
import com.shangkele.core.database.dao.TimeSlotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShangKeLeDatabase =
        Room.databaseBuilder(context, ShangKeLeDatabase::class.java, ShangKeLeDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideSemesterDao(db: ShangKeLeDatabase): SemesterDao = db.semesterDao()

    @Provides
    fun provideCourseDao(db: ShangKeLeDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideTimeSlotDao(db: ShangKeLeDatabase): TimeSlotDao = db.timeSlotDao()

    @Provides
    fun provideChangeLogDao(db: ShangKeLeDatabase): ChangeLogDao = db.changeLogDao()

    @Provides
    fun provideNoteDao(db: ShangKeLeDatabase): NoteDao = db.noteDao()
}
