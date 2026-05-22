package it.alsicuro.virtualars.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import it.alsicuro.virtualars.HelpMeApplication
import it.alsicuro.virtualars.MainActivity
import it.alsicuro.virtualars.R
import it.alsicuro.virtualars.data.AppEvent
import it.alsicuro.virtualars.data.AppPreferences
import kotlinx.coroutines.flow.first

// Layer centrale delle azioni di emergenza:
// compone SMS, recupera la posizione, registra eventi e crea notifiche coerenti.
class EmergencyPlatform(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val fusedLocation = LocationServices.getFusedLocationProviderClient(context)

    suspend fun sendEmergency(reason: String, type: String = "sos"): EmergencyResult {
        // Prima di inviare si controllano duplicati ravvicinati,
        // contatti configurati e permessi minimi necessari.
        val throttleCheck = checkThrottle(type)
        if (throttleCheck != null) return throttleCheck

        val contacts = preferences.settingsFlow.first().contacts
        if (contacts.isEmpty()) {
            return result(type, "failed", "Nessun contatto SOS salvato")
        }
        if (!hasSmsPermission()) {
            return result(type, "failed", "Permesso SMS non concesso")
        }
        val location = getCurrentLocation()
        if (location == null && !hasLocationPermission()) {
            return result(type, "failed", "Permesso posizione non concesso")
        }
        val message = buildMessage(reason, location)
        return try {
            val smsManager = resolveSmsManager()
            contacts.forEach { contact ->
                sendSmsMessage(smsManager, contact.number, message)
            }
            result(
                type = type,
                status = if (location != null) "sent" else "partial",
                details = if (location != null) {
                    "Messaggio inviato a ${contacts.size} contatti con posizione"
                } else {
                    "Messaggio inviato a ${contacts.size} contatti senza posizione"
                }
            )
        } catch (e: Exception) {
            result(type, "failed", "Invio SMS fallito: ${e.message ?: "errore sconosciuto"}")
        }
    }

    suspend fun sendEscortStarted() = sendEmergency("Rientro a casa.", type = "escort_start")

    suspend fun sendEscortUpdate() = sendEmergency("Aggiornamento percorso.", type = "escort_update")

    suspend fun sendEscortCompleted() = sendSafeNow()

    suspend fun sendSafeNow(): EmergencyResult {
        return sendEmergency("Ora sono al sicuro.", type = "safe")
    }

    suspend fun logInfo(type: String, details: String) {
        preferences.appendEvent(
            AppEvent(
                timestamp = System.currentTimeMillis(),
                type = type,
                status = "info",
                details = details
            )
        )
    }

    private suspend fun getCurrentLocation(): Location? {
        if (
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            // Qui basta una posizione current best effort: se non arriva,
            // l'SMS parte comunque senza coordinate.
            fusedLocation.getCurrentLocation(CurrentLocationRequest.Builder().build(), null).await()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buildMessage(reason: String, location: Location?): String {
        // Il template è personalizzabile da impostazioni ma resta compatto:
        // il progetto privilegia un singolo SMS quando possibile.
        val template = preferences.settingsFlow.first().smsTemplate
        val link = if (location != null) {
            "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "Posizione non disponibile"
        }
        return template
            .replace("{reason}", reason)
            .replace("{link}", link)
            .replace("{newline}", "\n")
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasSmsPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun result(type: String, status: String, details: String): EmergencyResult {
        val event = AppEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            status = status,
            details = details
        )
        preferences.appendEvent(event)
        return EmergencyResult(type, status, details)
    }

    private suspend fun checkThrottle(type: String): EmergencyResult? {
        val lastEvent = preferences.settingsFlow.first().eventLog.firstOrNull()
        val now = System.currentTimeMillis()
        return if (lastEvent != null && lastEvent.type == type && lastEvent.status == "sent" && now - lastEvent.timestamp < 10_000) {
            result(type, "blocked", "Invio bloccato per evitare duplicati troppo ravvicinati")
        } else {
            null
        }
    }

    fun buildForegroundNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        return NotificationCompat.Builder(context, HelpMeApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationManager.IMPORTANCE_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    fun launchGoogleMaps(query: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun resolveSmsManager(): SmsManager {
        // Si prova prima la sottoscrizione SMS predefinita del device,
        // poi i fallback disponibili in base alla versione Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)?.let { return it }
        }

        return SmsManager.getDefault()
    }

    private fun sendSmsMessage(smsManager: SmsManager, rawNumber: String, message: String) {
        val destination = PhoneNumberUtils.stripSeparators(rawNumber).ifBlank { rawNumber.trim() }
        val parts = smsManager.divideMessage(message)
        // I messaggi lunghi vengono inviati come multipart per evitare errori
        // nei client SMS che non gradiscono payload oltre il singolo segmento.
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(destination, null, ArrayList(parts), null, null)
        } else {
            smsManager.sendTextMessage(destination, null, message, null, null)
        }
    }

    private fun pendingImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}

data class EmergencyResult(
    val type: String,
    val status: String,
    val details: String
)
