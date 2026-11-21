package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.example.data.network.ArchiveOrgApiService
import com.example.data.network.UrlSecurityValidator
import com.example.data.network.WikimediaApiService
import com.example.data.preferences.userPrefsDataStore
import com.example.data.repository.DownloadRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.data.repository.VideoAnalyzerRepositoryImpl
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.VideoAnalyzerRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(urlValidator: UrlSecurityValidator): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                if (!urlValidator.isSafeRedirectTarget(req.url)) {
                    throw IOException("Blocked unsafe or non-HTTPS redirect target: ${req.url.host}")
                }
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideArchiveOrgApiService(okHttpClient: OkHttpClient, moshi: Moshi): ArchiveOrgApiService =
        Retrofit.Builder()
            .baseUrl("https://archive.org/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ArchiveOrgApiService::class.java)

    @Provides
    @Singleton
    fun provideWikimediaApiService(okHttpClient: OkHttpClient, moshi: Moshi): WikimediaApiService =
        Retrofit.Builder()
            .baseUrl("https://commons.wikimedia.org/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WikimediaApiService::class.java)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.userPrefsDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVideoAnalyzerRepository(impl: VideoAnalyzerRepositoryImpl): VideoAnalyzerRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
