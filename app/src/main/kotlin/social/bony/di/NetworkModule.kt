package social.bony.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import social.bony.nostr.relay.RelayConnection
import social.bony.nostr.relay.RelayPool
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = RelayConnection.defaultClient()

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideRelayPool(
        scope: CoroutineScope,
        client: OkHttpClient,
    ): RelayPool = RelayPool(
        scope = scope,
        connectionFactory = { url -> RelayConnection(url, client) },
    )
}
