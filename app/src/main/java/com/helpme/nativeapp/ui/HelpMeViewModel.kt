package it.alsicuro.virtualars.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.alsicuro.virtualars.data.AppPreferences
import it.alsicuro.virtualars.data.AppSettings
import it.alsicuro.virtualars.data.SosContact
import it.alsicuro.virtualars.services.EmergencyActionService
import it.alsicuro.virtualars.services.EmergencyPlatform
import it.alsicuro.virtualars.services.EscortService
import it.alsicuro.virtualars.services.ShakeAlertService
import it.alsicuro.virtualars.services.SosTimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ViewModel centrale della UI.
// Tiene insieme stato persistente, validazioni dei permessi
// e avvio dei servizi Android che eseguono le azioni reali.
class HelpMeViewModel(private val application: Application) : ViewModel() {
    private val preferences = AppPreferences(application)
    private val _selectedTab = MutableStateFlow(BottomTab.Home)
    val selectedTab = _selectedTab.asStateFlow()

    // Stream "sempre caldo" usato dalle azioni sincrone del ViewModel.
    val settings: StateFlow<AppSettings> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    // Stream separato per la UI iniziale: evita di mostrare onboarding/dashboard
    // finché il DataStore non ha restituito il primo stato reale.
    val loadedSettings: StateFlow<AppSettings?> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun selectTab(tab: BottomTab) {
        _selectedTab.value = tab
    }

    fun markOnboardingSeen() {
        viewModelScope.launch { preferences.markOnboardingSeen() }
    }

    fun addContactFromUri(uriString: String) {
        viewModelScope.launch {
            try {
                // La lettura del contatto avviene fuori dal main thread
                // perché interroga il ContentResolver di sistema.
                val contact = withContext(Dispatchers.IO) {
                    resolveContactFromUri(application, uriString)
                }
                when {
                    contact == null -> toast("Non è stato possibile leggere il contatto selezionato")
                    contact.number.isBlank() -> toast("Il contatto selezionato non ha un numero di telefono")
                    settings.value.contacts.any { it.number == contact.number } -> toast("Questo contatto è già presente")
                    else -> {
                        preferences.addContact(contact)
                        toast("Contatto aggiunto correttamente")
                    }
                }
            } catch (_: SecurityException) {
                toast("Permesso contatti non disponibile. Riattivalo dalle impostazioni")
            } catch (_: Exception) {
                toast("Errore durante l'aggiunta del contatto")
            }
        }
    }

    fun removeContact(contact: SosContact) {
        viewModelScope.launch { preferences.removeContact(contact) }
    }

    fun moveContactUp(contact: SosContact) {
        viewModelScope.launch { preferences.moveContactUp(contact) }
    }

    fun moveContactDown(contact: SosContact) {
        viewModelScope.launch { preferences.moveContactDown(contact) }
    }

    fun triggerManualSos(reason: String = "SOS.") {
        // Il ViewModel valida i prerequisiti; l'invio reale avviene nel servizio foreground.
        if (!hasSosContacts()) {
            toast("Aggiungi almeno un contatto SOS")
            return
        }
        if (!hasAlertPermissions()) {
            toast("Concedi SMS e localizzazione dalle impostazioni")
            return
        }
        ContextCompat.startForegroundService(
            application,
            Intent(application, EmergencyActionService::class.java)
                .putExtra(EmergencyActionService.EXTRA_REASON, reason)
        )
    }

    fun setEscortMode(enabled: Boolean) {
        // Le modalità persistenti vengono sempre sincronizzate sia nello stato locale
        // sia nel ciclo di vita del servizio Android corrispondente.
        if (enabled) {
            if (!hasSosContacts()) {
                toast("Aggiungi almeno un contatto SOS")
                return
            }
            if (!hasAlertPermissions(includeBackgroundLocation = true)) {
                toast("Concedi i permessi necessari dalle impostazioni")
                return
            }
        }
        viewModelScope.launch { preferences.setEscortModeEnabled(enabled) }
        val intent = Intent(application, EscortService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(application, intent)
        } else {
            application.stopService(intent)
        }
    }

    fun setShakeMode(enabled: Boolean) {
        if (enabled) {
            if (!hasSosContacts()) {
                toast("Aggiungi almeno un contatto SOS")
                return
            }
            if (!hasAlertPermissions(includeBackgroundLocation = true)) {
                toast("Concedi i permessi necessari dalle impostazioni")
                return
            }
        }
        viewModelScope.launch { preferences.setShakeAlertEnabled(enabled) }
        val intent = Intent(application, ShakeAlertService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(application, intent)
        } else {
            application.stopService(intent)
        }
    }

    fun openPermissionsSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        })
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openNearbyPlace(query: String) {
        EmergencyPlatform(application).launchGoogleMaps(query)
    }

    fun sendSafeNow() {
        if (!hasSosContacts()) {
            toast("Aggiungi almeno un contatto SOS")
            return
        }
        if (!hasAlertPermissions()) {
            toast("Concedi SMS e localizzazione dalle impostazioni")
            return
        }
        viewModelScope.launch {
            val result = EmergencyPlatform(application).sendSafeNow()
            toast(
                if (result.status == "sent" || result.status == "partial") {
                    "Messaggio 'Sono al sicuro' inviato"
                } else {
                    result.details
                }
            )
        }
    }

    fun startSosTimer(seconds: Int = 10) {
        if (!hasSosContacts()) {
            toast("Aggiungi almeno un contatto SOS")
            return
        }
        if (!hasAlertPermissions(includeBackgroundLocation = true)) {
            toast("Concedi i permessi necessari dalle impostazioni")
            return
        }
        ContextCompat.startForegroundService(
            application,
            Intent(application, SosTimerService::class.java).apply {
                action = SosTimerService.ACTION_START
                putExtra(SosTimerService.EXTRA_SECONDS, seconds)
                putExtra(SosTimerService.EXTRA_REASON, "Timer scaduto.")
            }
        )
    }

    fun cancelSosTimer() {
        ContextCompat.startForegroundService(
            application,
            Intent(application, SosTimerService::class.java).apply {
                action = SosTimerService.ACTION_CANCEL
            }
        )
    }

    private fun resolveContactFromUri(context: Context, uriString: String): SosContact? {
        val uri = android.net.Uri.parse(uriString)
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val id = it.getString(0)
            val name = it.getString(1).orEmpty()
            val number = resolvePhoneNumber(context, id) ?: return null
            // Il numero viene normalizzato per evitare differenze cosmetiche
            // che romperebbero deduplica e invio SMS.
            return SosContact(name = name, number = sanitizeNumber(number))
        }
    }

    private fun resolvePhoneNumber(context: Context, contactId: String): String? {
        val cursor: Cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        ) ?: return null
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun sanitizeNumber(phone: String): String = phone.replace(Regex("[^+0-9]"), "")

    fun hasSosContacts(): Boolean = settings.value.contacts.isNotEmpty()

    fun updateSmsTemplate(value: String) {
        viewModelScope.launch {
            preferences.setSmsTemplate(value)
            toast("Messaggio SOS aggiornato")
        }
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(application.packageName)
    }

    fun hasAccelerometer(): Boolean {
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    private fun hasAlertPermissions(includeBackgroundLocation: Boolean = false): Boolean {
        // Questo controllo è volutamente stretto: le azioni SOS non partono
        // se mancano i permessi minimi necessari alla funzione richiesta.
        val requiredPermissions = mutableListOf(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (includeBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions += android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(application, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toast(text: String) {
        Toast.makeText(application, text, Toast.LENGTH_SHORT).show()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HelpMeViewModel(application) as T
    }
}

enum class BottomTab {
    Home,
    Contacts
}
