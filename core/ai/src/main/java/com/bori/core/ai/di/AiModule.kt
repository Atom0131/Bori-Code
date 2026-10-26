package com.bori.core.ai.di

import android.content.Context
import com.bori.core.ai.local.ConnectivityChecker
import com.bori.core.ai.local.LocalGemmaClient
import com.bori.core.ai.local.ModelDownloadManager
import com.bori.core.ai.provider.AnthropicClient
import com.bori.core.ai.provider.DeepSeekClient
import com.bori.core.ai.provider.GeminiClient
import com.bori.core.ai.provider.KimiClient
import com.bori.core.ai.provider.KimiCodeClient
import com.bori.core.ai.provider.LocalServerClient
import com.bori.core.ai.provider.OpenAiClient
import com.bori.core.ai.provider.OpenRouterClient
import com.bori.core.security.KeyVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideDeepSeekClient(keyVault: KeyVault): DeepSeekClient =
        DeepSeekClient(keyVault)

    @Provides
    @Singleton
    fun provideOpenAiClient(keyVault: KeyVault): OpenAiClient =
        OpenAiClient(keyVault)

    @Provides
    @Singleton
    fun provideOpenRouterClient(keyVault: KeyVault): OpenRouterClient =
        OpenRouterClient(keyVault)

    @Provides
    @Singleton
    fun provideAnthropicClient(keyVault: KeyVault): AnthropicClient =
        AnthropicClient(keyVault)

    @Provides
    @Singleton
    fun provideGeminiClient(keyVault: KeyVault): GeminiClient =
        GeminiClient(keyVault)

    @Provides
    @Singleton
    fun provideKimiClient(keyVault: KeyVault): KimiClient =
        KimiClient(keyVault)

    @Provides
    @Singleton
    fun provideKimiCodeClient(keyVault: KeyVault): KimiCodeClient =
        KimiCodeClient(keyVault)

    @Provides
    @Singleton
    fun provideLocalServerClient(keyVault: KeyVault): LocalServerClient =
        LocalServerClient(keyVault)

    @Provides
    @Singleton
    fun provideModelDownloadManager(@ApplicationContext ctx: Context): ModelDownloadManager =
        ModelDownloadManager(ctx)

    @Provides
    @Singleton
    fun provideLocalGemmaClient(
        @ApplicationContext ctx: Context,
        downloadManager: ModelDownloadManager,
    ): LocalGemmaClient = LocalGemmaClient(ctx, downloadManager)

    @Provides
    @Singleton
    fun provideConnectivityChecker(@ApplicationContext ctx: Context): ConnectivityChecker =
        ConnectivityChecker(ctx)
}
