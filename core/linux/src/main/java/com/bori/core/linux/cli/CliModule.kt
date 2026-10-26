package com.bori.core.linux.cli

import com.bori.core.ai.provider.CliProviderClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Offers the coding CLIs to ModelRouter (see CliClientsModule in :core:ai). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CliModule {
    @Binds @IntoSet
    abstract fun claudeCode(client: ClaudeCodeClient): CliProviderClient

    @Binds @IntoSet
    abstract fun antigravity(client: AntigravityClient): CliProviderClient
}
