package com.bori.core.ai.di

import com.bori.core.ai.provider.CliProviderClient
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Declares the set of coding-CLI clients, so it may be empty: :core:linux adds to it, Play has none. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CliClientsModule {
    @Multibinds
    abstract fun cliClients(): Set<CliProviderClient>
}
