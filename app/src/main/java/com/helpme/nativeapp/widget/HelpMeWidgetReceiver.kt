package it.alsicuro.virtualars.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import it.alsicuro.virtualars.R
import it.alsicuro.virtualars.services.EmergencyActionService

// Widget Home con doppia conferma.
// Il primo tocco arma il SOS, il secondo entro pochi secondi lo invia davvero.
class HelpMeWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            clearArmedState(context, appWidgetId)
            updateWidget(context, appWidgetManager, appWidgetId, false)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_ARM_SOS -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    setArmedState(context, appWidgetId)
                    refreshWidget(context, appWidgetId, true)
                    // Il reset automatico evita invii accidentali se il secondo tocco non arriva.
                    scheduleReset(context, appWidgetId)
                }
            }

            ACTION_CONFIRM_SOS -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    cancelReset(context, appWidgetId)
                    clearArmedState(context, appWidgetId)
                    refreshWidget(context, appWidgetId, false)
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, EmergencyActionService::class.java)
                        .putExtra(EmergencyActionService.EXTRA_REASON, "SOS widget.")
                    )
                }
            }

            ACTION_RESET_CONFIRMATION -> {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    clearArmedState(context, appWidgetId)
                    refreshWidget(context, appWidgetId, false)
                }
            }
        }
    }

    private fun refreshWidget(context: Context, appWidgetId: Int, armed: Boolean = isArmed(context, appWidgetId)) {
        updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId, armed)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        armed: Boolean
    ) {
        // Il widget non contiene logica di business: mostra solo lo stato
        // e rilancia l'azione corretta al receiver tramite PendingIntent.
        val action = if (armed) ACTION_CONFIRM_SOS else ACTION_ARM_SOS
        val tapIntent = Intent(context, HelpMeWidgetReceiver::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val tapPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val views = RemoteViews(context.packageName, R.layout.helpme_widget)
        views.setOnClickPendingIntent(R.id.widgetSosButton, tapPendingIntent)
        views.setViewVisibility(R.id.widgetMessage, if (armed) android.view.View.VISIBLE else android.view.View.GONE)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun scheduleReset(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CONFIRMATION_WINDOW_MS,
            resetPendingIntent(context, appWidgetId)
        )
    }

    private fun cancelReset(context: Context, appWidgetId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(resetPendingIntent(context, appWidgetId))
    }

    private fun resetPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val resetIntent = Intent(context, HelpMeWidgetReceiver::class.java).apply {
            action = ACTION_RESET_CONFIRMATION
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId + RESET_REQUEST_OFFSET,
            resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun setArmedState(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(armedKey(appWidgetId), true)
            .apply()
    }

    private fun clearArmedState(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(armedKey(appWidgetId))
            .apply()
    }

    private fun isArmed(context: Context, appWidgetId: Int): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(armedKey(appWidgetId), false)
    }

    private fun armedKey(appWidgetId: Int): String = "widget_armed_$appWidgetId"

    companion object {
private const val ACTION_ARM_SOS = "it.alsicuro.virtualars.WIDGET_ARM_SOS"
private const val ACTION_CONFIRM_SOS = "it.alsicuro.virtualars.WIDGET_CONFIRM_SOS"
private const val ACTION_RESET_CONFIRMATION = "it.alsicuro.virtualars.WIDGET_RESET_CONFIRMATION"
        private const val PREFS_NAME = "helpme_widget_prefs"
        private const val CONFIRMATION_WINDOW_MS = 5_000L
        private const val RESET_REQUEST_OFFSET = 10_000

        private fun immutableFlag(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        }
    }
}
