package it.alsicuro.virtualars.services

import android.content.Intent
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Servizio foreground usa-e-getta per il SOS manuale o attivato da widget.
// Parte, invia il messaggio e si chiude subito dopo.
class EmergencyActionService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val platform = EmergencyPlatform(applicationContext)
        startForeground(1001, platform.buildForegroundNotification("Al Sicuro SOS", "Invio richiesta di aiuto in corso"))
        scope.launch {
            platform.sendEmergency(intent?.getStringExtra(EXTRA_REASON) ?: "SOS.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "extra_reason"
    }
}
