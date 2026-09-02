package com.echopanel.app.di

import android.content.Context
import com.echopanel.app.data.agora.AgoraCallRepositoryImpl
import com.echopanel.app.data.repository.InterviewRepositoryImpl
import com.echopanel.app.domain.repository.AgoraCallRepository
import com.echopanel.app.domain.repository.InterviewRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInterviewRepository(
        impl: InterviewRepositoryImpl,
    ): InterviewRepository

    companion object {
        @Provides
        @Singleton
        fun provideAgoraCallRepository(
            @ApplicationContext context: Context,
        ): AgoraCallRepository = AgoraCallRepositoryImpl(context)
    }
}
