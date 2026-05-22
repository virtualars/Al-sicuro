package it.alsicuro.virtualars.services

import android.content.Intent
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Mantiene attiva la modalità "percorso sicuro":
// invia un primo messaggio e poi aggiornamenti periodici finché il servizio resta attivo.
class EscortService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val platform = EmergencyPlatform(applicationContext)
        startForeground(1002, platform.buildForegroundNotification("Al Sicuro Percorso Sicuro", "Condivisione posizione ogni 60 secondi"))
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                platform.sendEscortStarted()
                while (isActive) {
                    // L'intervallo è volutamente semplice e costante:
                    // l'obiettivo è avere un heartbeat affidabile, non tracking fitto.
                    delay(60_000)
                    platform.sendEscortUpdate()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch { EmergencyPlatform(applicationContext).sendEscortCompleted() }
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
