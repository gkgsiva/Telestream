package com.telestream.app

import android.app.Application
import com.telestream.app.data.Settings
import com.telestream.app.data.SyncManager
import com.telestream.app.telegram.TdClient
import com.telestream.app.telegram.TelegramRepository

/** Holds the app-wide singletons. Small enough that a DI framework is not worth it yet. */
class TelestreamApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var td: TdClient
        private set
    lateinit var repository: TelegramRepository
        private set
    lateinit var sync: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        td = TdClient(this, settings)
        repository = TelegramRepository(td)
        sync = SyncManager(this, td, settings)
    }

    companion object {
        lateinit var instance: TelestreamApp
            private set
    }
}
