package tr.egemen.pusula

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Banka/ödeme uygulamalarının bildirimlerini Core'a iletir (21 Ekim).
 *
 * TEB hesaba gelen parayı (4.800 TL) SMS'le değil CEPTETEB bildirimiyle bildiriyor; sistemde SMS'ten gelmiş tek bir
 * para girişi yoktu. Yalnızca banka/ödeme uygulamalarının tutar içeren bildirimleri çıkar: uygulama adı ya da paket
 * adı bilinen bir bankayla eşleşmeli. Mesajlar uygulamasındaki (RCS dahil) bildirim, gönderen bir bankaysa alınır;
 * kişilerle yazışmalar ve sohbet uygulamaları hiç okunmaz. Şifre/kod içeren bildirim metinsiz bildirilir.
 * Aynı işlem hem SMS'ten hem bildirimden gelirse sunucu tutar + gün ile ikincisini mükerrer sayar.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (sbn.packageName == packageName || SmsForwarder.url(ctx).isNullOrBlank()) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return     // grup özeti: içindekiler ayrıca gelir
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        val sender = senderOf(sbn.packageName, appLabel(sbn.packageName), title) ?: return
        val body = if (title.isNotEmpty() && !text.contains(title)) "$title: $text" else text
        if (!AMOUNT.containsMatchIn(body)) return
        // `when` işlemin zamanıdır ve aynı bildirim güncellenince değişmez: sunucu tekrarını dakika anahtarıyla yakalar
        val time = if (n.`when` > 0) n.`when` else sbn.postTime
        Thread { SmsForwarder.forwardNotification(ctx, sbn.key, sender, body, time) }.start()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    companion object {
        // Kelime olarak aranır ("ing" tek başına "shopping"i yakalamasın diye "ingbanktr", "ingmobil").
        private val BANKS = setOf(
            "teb", "cepteteb", "garanti", "garantibbva", "akbank", "ykb", "yapikredi", "isbank", "iscep", "isbankasi",
            "ziraat", "ziraatbank", "halkbank", "vakifbank", "vakif", "qnb", "finansbank", "enpara", "denizbank",
            "mobildeniz", "ingbanktr", "ingmobil", "papara", "tosla", "iyzico", "paycell", "ininal", "kuveytturk",
            "albaraka", "fibabanka", "odeabank", "hsbc", "sekerbank", "turkiyefinans", "vakifkatilim", "ziraatkatilim",
            "colendi", "param", "hepsipay", "midas",
        )
        private val MESSAGING = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        )
        private val AMOUNT = Regex("""\d[\d.,]*\s*(TL|TRY|₺)|₺\s*\d""", RegexOption.IGNORE_CASE)
        private val TR = Locale("tr", "TR")

        private fun words(s: String): Set<String> =
            s.lowercase(TR).replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
                .replace('ö', 'o').replace('ü', 'u').split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        /** Bildirimin göndereni (banka adı) ya da banka değilse null. */
        fun senderOf(pkg: String, label: String, title: String): String? = when {
            pkg in MESSAGING -> title.takeIf { (words(title) intersect BANKS).isNotEmpty() }
            (words(pkg) intersect BANKS).isNotEmpty() || (words(label) intersect BANKS).isNotEmpty() ->
                label.ifBlank { pkg }
            else -> null
        }

        /** Panelin gösterdiği durum: kapali (SMS bağlantısı yok) | izin_yok | acik */
        fun status(context: Context): String = when {
            SmsForwarder.url(context).isNullOrBlank() -> "kapali"
            !hasAccess(context) -> "izin_yok"
            else -> "acik"
        }

        fun hasAccess(context: Context): Boolean {
            val me = ComponentName(context, BankNotificationListener::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { it == me }
        }
    }
}
