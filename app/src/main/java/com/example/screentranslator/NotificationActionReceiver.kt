package com.example.screentranslator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receives notification actions (Stop / Pause / Resume / Translate Now) and
 * forwards them to the running [FloatingService] through a plain [Intent].
 *
 * The receiver itself is intentionally tiny: it resolves the service class and
 * sends an [Intent] with the requested action.  This keeps the broadcast
 * lifecycle independent of the service lifecycle while still letting the user
 * stop the translator from the notification shade without reopening the app.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "com.example.screentranslator.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.screentranslator.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.screentranslator.ACTION_RESUME"
        const val ACTION_TRANSLATE = "com.example.screentranslator.ACTION_TRANSLATE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val service = Intent(context, FloatingService::class.java).apply {
            this.action = action
        }
        try {
            context.startService(service)
        } catch (e: Exception) {
            Toast.makeText(context, "تعذّر تنفيذ الإجراء: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
