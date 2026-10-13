package tr.egemen.pusula

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 15 dakikalık konum alarmı ve telefon açılışı (13 Ekim, konuma bağlı hatırlatıcı).
 * Açılışta alarm yeniden kurulur (Android yeniden başlayınca alarmlar silinir); alarmda konum gönderilir.
 */
class LocationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LocationSender.schedule(context)
            return
        }
        val pending = goAsync()
        LocationSender.sendNow(context.applicationContext) { pending.finish() }
    }
}
