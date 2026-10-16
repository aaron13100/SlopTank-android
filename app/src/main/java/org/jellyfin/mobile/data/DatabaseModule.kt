package org.jellyfin.mobile.data

import androidx.room.Room
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(androidApplication(), JellyfinDatabase::class.java, "jellyfin")
            .addMigrations(
                JellyfinDatabase.MIGRATION_5_6,
                JellyfinDatabase.MIGRATION_7_8,
                JellyfinDatabase.MIGRATION_8_9,
            )
            .addCallback(JellyfinDatabase.JOURNAL_GUARD)
            .fallbackToDestructiveMigrationFrom(true, 1)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }
    single { get<JellyfinDatabase>().serverDao }
    single { get<JellyfinDatabase>().userDao }
    single { get<JellyfinDatabase>().downloadDao }
    single { get<JellyfinDatabase>().downloadFileDao }
}
