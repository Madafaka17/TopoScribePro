package com.toposcribe.app.di

import android.content.Context
import androidx.room.Room
import com.toposcribe.data.backup.ProjectBackupService
import com.toposcribe.data.local.ALL_MIGRATIONS
import com.toposcribe.data.local.PreMigrationBackup
import com.toposcribe.data.local.TopoScribeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TopoScribeDatabase {
        // Copia de seguridad del archivo ANTES de migrar una base de una versión anterior.
        PreMigrationBackup.backupIfNeeded(context)
        return Room.databaseBuilder(context, TopoScribeDatabase::class.java, TopoScribeDatabase.DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS) // sin migración destructiva: nunca se borran proyectos
            .build()
    }

    @Provides
    @Singleton
    fun provideBackupService(@ApplicationContext context: Context, db: TopoScribeDatabase): ProjectBackupService =
        ProjectBackupService(
            db = db,
            photosDir = File(context.filesDir, "photos"),
            annexesDir = File(context.filesDir, "annexes"),
            appVersion = com.toposcribe.app.BuildConfig.VERSION_NAME,
        )
}
