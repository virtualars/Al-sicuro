package it.alsicuro.virtualars.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets

// DataStore è la fonte unica dello stato persistente dell'app:
// onboarding, contatti SOS, template SMS, log eventi e toggle dei servizi.
private val Context.dataStore by preferencesDataStore("helpme_preferences")

data class SosContact(
    val name: String,
    val number: String
) {
    // I contatti vengono serializzati in modo semplice perché qui basta
    // persistere una lista ordinata locale senza introdurre un database.
    fun encode(): String = "$name***$number"

    companion object {
        fun decode(raw: String): SosContact? {
            val pieces = raw.split("***")
            if (pieces.size != 2) return null
            return SosContact(pieces[0], pieces[1])
        }
    }
}

data class AppSettings(
    val onboardingSeen: Boolean = false,
    val runtimePermissionsHandled: Boolean = false,
    val escortModeEnabled: Boolean = false,
    val shakeAlertEnabled: Boolean = false,
    val sosTimerActive: Boolean = false,
    val contacts: List<SosContact> = emptyList(),
    val eventLog: List<AppEvent> = emptyList(),
    val smsTemplate: String = AppPreferences.DEFAULT_SMS_TEMPLATE
)

data class AppEvent(
    val timestamp: Long,
    val type: String,
    val status: String,
    val details: String
) {
    // Il log eventi viene salvato come stringa compatta in DataStore.
    // Le singole parti vengono codificate in Base64 per evitare collisioni
    // con i separatori usati nella serializzazione.
    fun encode(): String = listOf(
        timestamp.toString(),
        encodePart(type),
        encodePart(status),
        encodePart(details)
    ).joinToString("::")

    companion object {
        fun decode(raw: String): AppEvent? {
            val parts = raw.split("::")
            if (parts.size != 4) return null
            return AppEvent(
                timestamp = parts[0].toLongOrNull() ?: return null,
                type = decodePart(parts[1]),
                status = decodePart(parts[2]),
                details = decodePart(parts[3])
            )
        }

        private fun encodePart(value: String): String =
            Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        private fun decodePart(value: String): String =
            String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8)
    }
}

class AppPreferences(private val context: Context) {
    private val onboardingSeen = booleanPreferencesKey("onboarding_seen")
    private val runtimePermissionsHandled = booleanPreferencesKey("runtime_permissions_handled")
    private val escortModeEnabled = booleanPreferencesKey("escort_mode_enabled")
    private val shakeAlertEnabled = booleanPreferencesKey("shake_alert_enabled")
    private val sosTimerActive = booleanPreferencesKey("sos_timer_active")
    private val contacts = stringPreferencesKey("sos_contacts_ordered")
    private val eventLog = stringPreferencesKey("event_log")
    private val smsTemplate = stringPreferencesKey("sms_template")

    // settingsFlow espone sempre una fotografia completa dello stato utente
    // in modo semplice da osservare da ViewModel e servizi.
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            onboardingSeen = prefs[onboardingSeen] ?: false,
            runtimePermissionsHandled = prefs[runtimePermissionsHandled] ?: false,
            escortModeEnabled = prefs[escortModeEnabled] ?: false,
            shakeAlertEnabled = prefs[shakeAlertEnabled] ?: false,
            sosTimerActive = prefs[sosTimerActive] ?: false,
            contacts = decodeContacts(prefs[contacts]),
            eventLog = decodeEvents(prefs[eventLog]).sortedByDescending { it.timestamp },
            smsTemplate = prefs[smsTemplate] ?: DEFAULT_SMS_TEMPLATE
        )
    }

    suspend fun markOnboardingSeen() {
        context.dataStore.edit { it[onboardingSeen] = true }
    }

    suspend fun markRuntimePermissionsHandled() {
        context.dataStore.edit { it[runtimePermissionsHandled] = true }
    }

    suspend fun setEscortModeEnabled(value: Boolean) {
        context.dataStore.edit { it[escortModeEnabled] = value }
    }

    suspend fun setShakeAlertEnabled(value: Boolean) {
        context.dataStore.edit { it[shakeAlertEnabled] = value }
    }

    suspend fun setSosTimerActive(value: Boolean) {
        context.dataStore.edit { it[sosTimerActive] = value }
    }

    suspend fun addContact(contact: SosContact) {
        context.dataStore.edit { prefs ->
            val updated = decodeContacts(prefs[contacts]).toMutableList()
            updated.add(contact)
            prefs[contacts] = encodeContacts(updated)
        }
    }

    suspend fun removeContact(contact: SosContact) {
        context.dataStore.edit { prefs ->
            val updated = decodeContacts(prefs[contacts]).toMutableList()
            updated.removeAll { it.number == contact.number }
            prefs[contacts] = encodeContacts(updated)
        }
    }

    suspend fun moveContactUp(contact: SosContact) {
        context.dataStore.edit { prefs ->
            val updated = decodeContacts(prefs[contacts]).toMutableList()
            val index = updated.indexOfFirst { it.number == contact.number }
            if (index > 0) {
                val item = updated.removeAt(index)
                updated.add(index - 1, item)
                prefs[contacts] = encodeContacts(updated)
            }
        }
    }

    suspend fun moveContactDown(contact: SosContact) {
        context.dataStore.edit { prefs ->
            val updated = decodeContacts(prefs[contacts]).toMutableList()
            val index = updated.indexOfFirst { it.number == contact.number }
            if (index >= 0 && index < updated.lastIndex) {
                val item = updated.removeAt(index)
                updated.add(index + 1, item)
                prefs[contacts] = encodeContacts(updated)
            }
        }
    }

    suspend fun setSmsTemplate(value: String) {
        context.dataStore.edit { it[smsTemplate] = value.ifBlank { DEFAULT_SMS_TEMPLATE } }
    }

    suspend fun appendEvent(event: AppEvent) {
        context.dataStore.edit { prefs ->
            val updated = decodeEvents(prefs[eventLog]).toMutableList()
            updated.add(0, event)
            // Lo storico è volutamente limitato: basta per UI e diagnostica locale
            // senza far crescere all'infinito il payload salvato in DataStore.
            prefs[eventLog] = updated.take(30).joinToString("||") { it.encode() }
        }
    }

    companion object {
        const val DEFAULT_SMS_TEMPLATE = "SOS. {reason} {link}"

        private fun decodeEvents(raw: String?): List<AppEvent> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split("||").mapNotNull(AppEvent::decode)
        }

        private fun decodeContacts(raw: String?): List<SosContact> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split("||").mapNotNull(SosContact::decode)
        }

        private fun encodeContacts(contacts: List<SosContact>): String {
            return contacts.joinToString("||") { it.encode() }
        }
    }
}
