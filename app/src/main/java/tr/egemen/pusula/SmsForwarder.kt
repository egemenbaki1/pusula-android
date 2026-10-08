package tr.egemen.pusula

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Banka SMS'lerini Para'ya iletir (`POST /sms/{anahtar}`).
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

    fun url(context: Context): String? = prefs(context).getString(KEY_URL, null)

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

    /** Arka planda çağrılır (ağ işlemi). Önce bekleyenler, sonra yeni SMS gönderilir. */
    fun forward(context: Context, sender: String, body: String, timeMillis: Long) {
        if (!isFromOrganization(sender) || isSecret(body)) return
        val item = JSONObject().put("gonderen", sender).put("metin", body).put("zaman", timeMillis)
        flush(context)
        if (!send(context, item)) enqueue(context, item)
    }

    /** Kuyruktakileri yeniden dener; gönderilemeyenler kuyrukta kalır. */
    @Synchronized
    fun flush(context: Context) {
        val queue = queue(context)
        if (queue.length() == 0) return
        val left = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.getJSONObject(i)
            if (!send(context, item)) left.put(item)
        }
        saveQueue(context, left)
    }

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

    @Synchronized
    private fun enqueue(context: Context, item: JSONObject) {
        val queue = queue(context)
        queue.put(item)
        val trimmed = JSONArray()
        val start = maxOf(0, queue.length() - MAX_QUEUE)
        for (i in start until queue.length()) trimmed.put(queue.get(i))
        saveQueue(context, trimmed)
    }

    private fun queue(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_QUEUE, "[]")) }.getOrDefault(JSONArray())

    private fun saveQueue(context: Context, queue: JSONArray) {
        prefs(context).edit().putString(KEY_QUEUE, queue.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
