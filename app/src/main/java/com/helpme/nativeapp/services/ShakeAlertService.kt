package it.alsicuro.virtualars.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import it.alsicuro.virtualars.HelpMeApplication
import it.alsicuro.virtualars.MainActivity
import it.alsicuro.virtualars.data.AppPreferences
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Servizio foreground che ascolta l'accelerometro anche a schermo spento
// e scatena un SOS se viene rilevato uno scuotimento sopra soglia.
class ShakeAlertService : LifecycleService(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTrigger = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HelpMe:ShakeAlertWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                AppPreferences(applicationContext).setShakeAlertEnabled(false)
                EmergencyPlatform(applicationContext).logInfo("shake", "SOS con scuotimento disattivato dalla notifica")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildShakeNotification())
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                // Qui interessa tenere viva la CPU, non forzare lo schermo acceso.
                lock.acquire(10 * 60 * 60 * 1000L)
            }
        }
        sensorManager.unregisterListener(this)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        val totalForce = sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
        val now = System.currentTimeMillis()
        // Soglia e cooldown riducono i falsi positivi:
        // il gesto deve essere netto e non ripetersi in pochi secondi.
        if (totalForce > 18 && now - lastTrigger > 7_000) {
            lastTrigger = now
            scope.launch {
                EmergencyPlatform(applicationContext).sendEmergency("Allarme attivato con scuotimento.")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun buildShakeNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            301,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val disableIntent = PendingIntent.getService(
            this,
            302,
            Intent(this, ShakeAlertService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        return NotificationCompat.Builder(this, HelpMeApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS con scuotimento attivo")
            .setContentText("Al Sicuro resta in ascolto anche a schermo spento")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Al Sicuro resta in ascolto anche a schermo spento. Tocca Apri app per tornare all'app oppure Disattiva per fermare subito il servizio.")
            )
            .setPriority(NotificationManager.IMPORTANCE_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_view, "Apri app", openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disattiva", disableIntent)
            .build()
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        const val ACTION_STOP = "it.alsicuro.virtualars.action.STOP_SHAKE_ALERT"
        private const val NOTIFICATION_ID = 1003
    }
}
