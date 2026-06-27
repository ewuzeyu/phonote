package site.wuzeyu.phonote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.fluid.afm.AFMInitializer

class PhonoteApp : Application() {
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        createNotificationChannel()
        AFMInitializer.init(this, null, SimpleImageHandler(), null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "HTTP Server", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Phonote HTTP Server" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "phonote_http_service"
    }
}
