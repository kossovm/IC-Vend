package kz.icgroup.vending

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Запускает витрину после включения автомата. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val start = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(start)
        }
    }
}
