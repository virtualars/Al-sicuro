package it.alsicuro.virtualars

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

// Application usata per inizializzare i componenti globali Android.
// In questo progetto serve soprattutto per il canale notifiche dei servizi foreground.
class HelpMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Al Sicuro",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifiche di emergenza, percorso sicuro e scuotimento."
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "helpme_alerts"
    }
}
