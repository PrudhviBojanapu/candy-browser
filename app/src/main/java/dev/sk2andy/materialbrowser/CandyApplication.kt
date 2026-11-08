package dev.sk2andy.materialbrowser

import android.app.Application
import dev.sk2andy.materialbrowser.data.AppLogging

class CandyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initialize(this)
    }

    companion object {
        fun initialize(application: Application) {
            val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                application.packageName
            }
            if (processName == application.packageName) {
                AppLogging.initialize(application)
            }
        }
    }
}
