package it.alsicuro.virtualars.services

import android.content.Intent
import androidx.lifecycle.LifecycleService
import it.alsicuro.virtualars.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Countdown foreground per l'invio ritardato del SOS.
// Viene usato come "timer di sicurezza": se non viene annullato, invia l'allarme.
class SosTimerService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        return when (action) {
            ACTION_CANCEL -> {
                scope.launch {
                    AppPreferences(applicationContext).setSosTimerActive(false)
                    EmergencyPlatform(applicationContext).logInfo("timer", "Timer SOS annullato")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                START_NOT_STICKY
            }
            else -> {
                val seconds = intent?.getIntExtra(EXTRA_SECONDS, 10) ?: 10
                val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Timer scaduto."
                val platform = EmergencyPlatform(applicationContext)
                startForeground(
                    1004,
                    platform.buildForegroundNotification("Al Sicuro Timer SOS", "Invio SOS tra $seconds secondi")
                )
                scope.launch {
                    AppPreferences(applicationContext).setSosTimerActive(true)
                    platform.logInfo("timer", "Timer SOS attivato per $seconds secondi")
                    // Il timer è lineare e locale: la notifica foreground
                    // rende esplicito all'utente che il conto alla rovescia è attivo.
                    delay(seconds * 1_000L)
                    platform.sendEmergency(reason, type = "timer")
                    AppPreferences(applicationContext).setSosTimerActive(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_CANCEL = "action_cancel"
        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_REASON = "extra_reason"
    }
}
