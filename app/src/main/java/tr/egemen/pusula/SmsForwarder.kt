package tr.egemen.pusula

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Banka SMS'lerini Core'a iletir (`POST /sms/{anahtar}`).
 *
 * Telefondan yalnızca şu çıkar: harfli bir göndericiden (TEB, GARANTI…) gelen ve içinde şifre/doğrulama
 * kodu geçmeyen SMS. Kişilerden gelen (numaralı) SMS'ler ve tek kullanımlık kodlar hiç gönderilmez.
 * Sunucu ulaşılamazsa ya da yapay zekâ o an yoksa (503) SMS kuyrukta bekler, sonraki SMS'te ya da
 * uygulama açılınca yeniden denenir: harcama kaybolmaz.
 */
object SmsForwarder {
    private const val PREFS = "pusula_sms"
    private const val KEY_URL = "url"
    private const val KEY_QUEUE = "kuyruk"
    private const val MAX_QUEUE = 200

    // Sunucudaki sms_ingest.SECRET_WORDS ile aynı: bunlar telefondan çıkmaz.
    private val SECRET_WORDS = listOf(
        "şifre", "sifre", "parola", "doğrulama", "dogrulama", "onay kodu", "güvenlik kodu", "guvenlik kodu",
        "tek kullanımlık", "tek kullanimlik", "otp", "3d secure", "3d güvenli", "kimseyle paylaşma",
        "kimseyle paylasma", "code", " kod ", "kodunuz", "kodu:",
    )
    private val TR = Locale("tr", "TR")

    fun url(context: Context): String? = moved(prefs(context).getString(KEY_URL, null))

    // 19 Ekim 2026: egemen.tr'nin süresi doldu, sunucu core.brand35.com'a taşındı. Eski adresle kaydedilmiş
    // SMS/konum uçları yeni sunucuya döner (anahtar aynı); kullanıcının yeniden eşleştirmesi gerekmez.
    private const val OLD_BASE = "https://para.egemen.tr"

    fun moved(url: String?): String? =
        if (url != null && url.startsWith(OLD_BASE)) BuildConfig.BASE_URL + url.removePrefix(OLD_BASE) else url

    fun setUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, url).apply()
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /** Panelin gösterdiği durum: kapali | izin_yok | acik */
    fun status(context: Context): String = when {
        url(context).isNullOrBlank() -> "kapali"
        !hasPermission(context) -> "izin_yok"
        else -> "acik"
    }

    /** Kişilerden gelen SMS'ler numaradır (+90532…); bankalar harfli gönderici adı kullanır. */
    fun isFromOrganization(sender: String): Boolean = sender.any { it.isLetter() }

    fun isSecret(body: String): Boolean {
        val text = " ${body.lowercase(TR)} "
        return SECRET_WORDS.any { text.contains(it) }
    }

    /** Arka planda çağrılır (ağ işlemi). SMS önce kuyruğa yazılır, sonra kuyruk gönderilir.
     *
     * 16 Ekim: 23:48'deki 732 TL'lik TEB SMS'i sunucuya hiç ulaşmadı. Eskiden önce gönderiliyor, ancak
     * başarısız olursa kuyruğa yazılıyordu; Android alıcıya ~10 sn tanıyor, bağlantı süreleri 8+25 sn — ağ
     * yavaşken süreç gönderim bitmeden öldürülürse SMS ne gönderilmiş ne kuyruğa yazılmış olur. Artık önce
     * diske yazılıyor; sunucu aynı SMS'i ikinci kez kaydetmez (gönderen + metin + dakika anahtarı). */
    fun forward(context: Context, sender: String, body: String, timeMillis: Long) {
        if (!isFromOrganization(sender)) return
        // 21 Ekim: iyzico ile aynı anda gelen TEB SMS'i sunucuya hiç ulaşmadı ve hiçbir yerde iz yoktu. Şifre/kod
        // içeren SMS'in metni yine telefondan çıkmaz; yalnızca gönderen ve uzunluk gider, sunucu "telefonda elendi" yazar.
        val item = if (isSecret(body))
            JSONObject().put("gonderen", sender).put("metin", "").put("elendi", "gizli").put("uzunluk", body.length)
        else JSONObject().put("gonderen", sender).put("metin", body)
        enqueue(context, item.put("zaman", timeMillis))
        // SMS alıcısının süresi kısa: kuyruk zaten diskte, bu sürede gönderilemeyen 15 dk'lık alarmda gider
        flush(context, System.currentTimeMillis() + RECEIVER_BUDGET_MS)
    }

    // 21 Ekim: flush ile enqueue aynı kilidi tutuyordu. 15 dk'lık alarm yavaş ağda kuyruğu gönderirken (istek başı
    // 8+25 sn) gelen yeni SMS diske yazılmak için bekliyordu; Android süreci o arada öldürürse SMS kaybolurdu.
    // Artık kilit yalnızca diske okuma/yazma süresince tutulur, ağ işi kilidin dışında; tek gönderim aynı anda çalışır.
    private val diskLock = Any()
    private val sending = AtomicBoolean(false)
    private const val RECEIVER_BUDGET_MS = 8_000L

    /** Kuyruktakileri yeniden dener; gönderilemeyenler kuyrukta kalır. `deadline` sonrası yeni istek başlatılmaz. */
    fun flush(context: Context, deadline: Long = Long.MAX_VALUE) {
        if (!sending.compareAndSet(false, true)) return   // başka gönderim sürüyor: yeni SMS'i o da görür (döngü)
        try {
            val tried = HashSet<String>()
            while (System.currentTimeMillis() < deadline) {
                val pending = synchronized(diskLock) { queue(context) }
                val sent = HashSet<String>()
                var any = false
                for (i in 0 until pending.length()) {
                    val item = pending.getJSONObject(i)
                    val k = key(item)
                    if (!tried.add(k)) continue
                    any = true
                    if (System.currentTimeMillis() >= deadline) break
                    if (send(context, item)) sent.add(k)
                }
                if (sent.isNotEmpty()) synchronized(diskLock) {
                    val current = queue(context)
                    val left = JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.getJSONObject(i)
                        if (key(item) !in sent) left.put(item)
                    }
                    saveQueue(context, left)
                }
                if (!any) break          // gönderim sırasında yeni SMS gelmediyse bitti
            }
        } finally {
            sending.set(false)
        }
    }

    /** Banka uygulaması bildirimi (`BankNotificationListener`): SMS gibi kuyruğa, sunucuda "bildirim" kanalı.
     *  Uygulamalar aynı bildirimi güncelleyip yeniden yayınlar: aynı bildirim (`key`) aynı metinle 24 saat içinde
     *  bir kez gider. Yeni bir işlem yeni bildirimdir (farklı key ya da metin). */
    fun forwardNotification(context: Context, notificationKey: String, sender: String, body: String, timeMillis: Long) {
        if (!firstTime(context, "$notificationKey|$body")) return
        val item = if (isSecret(body))
            JSONObject().put("gonderen", sender).put("metin", "").put("elendi", "gizli").put("uzunluk", body.length)
        else JSONObject().put("gonderen", sender).put("metin", body)
        enqueue(context, item.put("kanal", "bildirim").put("zaman", timeMillis))
        flush(context, System.currentTimeMillis() + RECEIVER_BUDGET_MS)
    }

    private const val KEY_SEEN = "gorulen_bildirimler"
    private const val SEEN_MS = 24 * 60 * 60 * 1000L

    private fun firstTime(context: Context, identity: String): Boolean = synchronized(diskLock) {
        val now = System.currentTimeMillis()
        val hash = identity.hashCode().toString()
        val seen = runCatching { JSONObject(prefs(context).getString(KEY_SEEN, "{}")) }.getOrDefault(JSONObject())
        val kept = JSONObject()
        for (k in seen.keys()) if (now - seen.optLong(k) < SEEN_MS) kept.put(k, seen.optLong(k))
        val fresh = !kept.has(hash)
        if (fresh) kept.put(hash, now)
        prefs(context).edit().putString(KEY_SEEN, kept.toString()).commit()
        fresh
    }

    private fun key(item: JSONObject): String =
        "${item.optString("kanal")}|${item.optString("gonderen")}|${item.optLong("zaman")}|${item.optString("metin")}"

    /** true: sunucu aldı ya da kalıcı olarak reddetti (yeniden denemenin anlamı yok). */
    private fun send(context: Context, item: JSONObject): Boolean {
        val target = url(context) ?: return true
        return try {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(item.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            // 2xx tamam; 400/404 kalıcı (bozuk istek / anahtar iptal) — kuyrukta bekletmek boşuna
            code in 200..299 || code == 400 || code == 404
        } catch (e: Exception) {
            false
        }
    }

    private fun enqueue(context: Context, item: JSONObject) = synchronized(diskLock) {
        val queue = queue(context)
        queue.put(item)
        val trimmed = JSONArray()
        val start = maxOf(0, queue.length() - MAX_QUEUE)
        for (i in start until queue.length()) trimmed.put(queue.get(i))
        saveQueue(context, trimmed)
    }

    private fun queue(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_QUEUE, "[]")) }.getOrDefault(JSONArray())

    // commit: diske yazılmadan dönmez. apply arka planda yazar; süreç hemen öldürülürse yeni SMS diske hiç inmeyebilir.
    private fun saveQueue(context: Context, queue: JSONArray) {
        prefs(context).edit().putString(KEY_QUEUE, queue.toString()).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
