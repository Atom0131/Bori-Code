package com.bori.core.project.di

import android.content.Context
import androidx.room.Room
import com.bori.core.project.db.ProjectDao
import com.bori.core.project.db.ProjectDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProjectModule {

    @Provides
    @Singleton
    fun provideProjectDatabase(@ApplicationContext context: Context): ProjectDatabase =
        Room.databaseBuilder(context, ProjectDatabase::class.java, "project_db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideProjectDao(db: ProjectDatabase): ProjectDao = db.projectDao()
}
