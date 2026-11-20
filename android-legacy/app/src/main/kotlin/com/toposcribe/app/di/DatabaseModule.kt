package com.toposcribe.app.di

import android.content.Context
import androidx.room.Room
import com.toposcribe.data.local.TopoScribeDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): TopoScribeDatabase =
        Room.databaseBuilder(context, TopoScribeDatabase::class.java, TopoScribeDatabase.DATABASE_NAME)
            .build()
}
