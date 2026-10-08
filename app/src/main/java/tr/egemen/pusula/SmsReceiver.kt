package tr.egemen.pusula

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Gelen SMS'i yakalar ve banka bildirimiyse Para'ya iletir (filtre `SmsForwarder`'da).
 *
 * Uzun SMS'ler parça parça gelir: aynı göndericinin parçaları birleştirilir. Ağ işlemi ana iş parçacığında
 * yapılamaz; `goAsync` ile alıcı, gönderim bitene kadar açık tutulur.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty() || SmsForwarder.url(context).isNullOrBlank()) return

        val bySender = linkedMapOf<String, Pair<StringBuilder, Long>>()
        for (p in parts) {
            val sender = p.displayOriginatingAddress ?: p.originatingAddress ?: continue
            val entry = bySender.getOrPut(sender) { StringBuilder() to p.timestampMillis }
            entry.first.append(p.displayMessageBody ?: p.messageBody ?: "")
        }

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                for ((sender, entry) in bySender) {
                    SmsForwarder.forward(app, sender, entry.first.toString(), entry.second)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
