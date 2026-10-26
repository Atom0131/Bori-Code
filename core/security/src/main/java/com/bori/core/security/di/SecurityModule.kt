package com.bori.core.security.di

import android.content.Context
import com.bori.core.security.KeyVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideKeyVault(@ApplicationContext context: Context): KeyVault =
        KeyVault(context)
}
