package org.mediasoup.droid.demo

import android.app.Application
import io.github.crow_misia.mediasoup.MediasoupClient
import io.github.crow_misia.webrtc.log.LogHandler
import org.webrtc.Logging
import timber.log.Timber
import timber.log.Timber.DebugTree

class Application : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(DebugTree())

        MediasoupClient.initialize(
            context = this,
            logHandler = object : LogHandler {
                override fun log(
                    priority: Int,
                    tag: String?,
                    t: Throwable?,
                    message: String?,
                    vararg args: Any?
                ) {
                    tag?.also { Timber.tag(it) }
                    Timber.log(priority, t, message, *args)
                }
            },
            loggableSeverity = Logging.Severity.LS_INFO
        )
    }
}
