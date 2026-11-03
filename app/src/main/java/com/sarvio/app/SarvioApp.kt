package com.sarvio.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.sarvio.app.data.AppDatabase
import com.sarvio.app.data.SettingsRepository
import com.sarvio.app.download.DownloadService
import com.sarvio.app.download.Notifications
import com.sarvio.app.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SarvioApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.get(this)
        settings = SettingsRepository(this)
        Notifications.createChannels(this)

        appScope.launch(Dispatchers.IO) {
            Engine.init(this@SarvioApp)

            // Resume anything that was interrupted by a crash or reboot.
            db.downloads().requeueInterrupted()
            if (db.downloads().countUnfinished() > 0) DownloadService.start(this@SarvioApp)

            // Keep yt-dlp fresh (sites change often). Once per day at most.
            val s = settings.current()
            val day = 24 * 60 * 60 * 1000L
            if (s.autoUpdateEngine && System.currentTimeMillis() - s.lastEngineUpdate > day) {
                val result = Engine.update(this@SarvioApp)
                if (!result.startsWith("Update failed")) settings.setLastUpdate(System.currentTimeMillis())
            }
        }
    }

    /** Lets thumbnails render frames from local video files and MediaStore URIs. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: SarvioApp
            private set
    }
}
