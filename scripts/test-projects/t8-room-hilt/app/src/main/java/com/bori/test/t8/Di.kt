package com.bori.test.t8

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class T8App : Application()

@Singleton
class Greeter @Inject constructor() {
    fun greet(name: String) = "Hola, $name"
}

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
}
