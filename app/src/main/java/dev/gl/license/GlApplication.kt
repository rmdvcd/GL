package dev.gl.license

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import dev.gl.license.core.AppRuntime

@HiltAndroidApp
class GlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRuntime.ensureInitialized()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
    }
}
