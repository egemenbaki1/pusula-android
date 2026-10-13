package tr.egemen.pusula

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Konum paylaşımı (13 Ekim): "markete gelince süt hatırlat" gibi konuma bağlı hatırlatıcılar için.
 *
 * Yaklaşık 15 dakikada bir telefonun güncel konumu Core'a (`POST /veri/{anahtar}`) gider; sunucu kayıtlı
 * yerlerle eşleştirir ve o yere bağlı hatırlatıcıyı gönderir. Konum yalnızca kendi sunucuna gider; ham
 * noktalar sunucuda kısa süre saklanıp silinir. Ek kütüphane yok: Android'in kendi LocationManager'ı.
 */
object LocationSender {
    private const val PREFS = "pusula_konum"
    private const val KEY_URL = "url"
    private const val KEY_ON = "acik"
    const val INTERVAL_MS = 15 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasForeground(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Panelin gösterdiği durum: kapali | izin_yok | arka_plan_yok | acik */
    fun status(context: Context): String = when {
        !prefs(context).getBoolean(KEY_ON, false) || prefs(context).getString(KEY_URL, null).isNullOrBlank() -> "kapali"
        !hasForeground(context) -> "izin_yok"
        !hasBackground(context) -> "arka_plan_yok"
        else -> "acik"
    }

    /** Panelin verdiği SMS adresinden veri adresi türetilir (aynı anahtar). */
    fun enable(context: Context, smsUrl: String) {
        prefs(context).edit().putString(KEY_URL, smsUrl.replace("/sms/", "/veri/")).putBoolean(KEY_ON, true).apply()
        schedule(context)
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, false).apply()
        alarm(context).cancel(pendingIntent(context))
    }

    private fun alarm(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, LocationAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Pil dostu, kesin olmayan tekrar (sistem toplu uyandırır). Açılışta ve izin verilince yeniden kurulur. */
    fun schedule(context: Context) {
        if (!prefs(context).getBoolean(KEY_ON, false)) return
        alarm(context).setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000L, INTERVAL_MS, pendingIntent(context))
    }

    /** Arka planda çağrılır. Konum alınamazsa ya da izin yoksa sessizce biter; `done` her durumda çağrılır. */
    fun sendNow(context: Context, done: () -> Unit = {}) {
        val url = prefs(context).getString(KEY_URL, null)
        if (url.isNullOrBlank() || !prefs(context).getBoolean(KEY_ON, false) || !hasForeground(context)) {
            done(); return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> { done(); return }
        }
        val finish = { loc: Location? ->
            executor.execute {
                try {
                    if (loc != null) post(url, loc)
                } finally {
                    done()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, CancellationSignal(), executor) { loc ->
                    finish(loc ?: lastKnown(lm, provider))
                }
            } else {
                finish(lastKnown(lm, provider))
            }
        } catch (e: SecurityException) {
            done()
        }
    }

    /** Geri çağrı başka iş parçacığında çalışır: izin o arada geri alındıysa çökme yerine boş dön. */
    private fun lastKnown(lm: LocationManager, provider: String): Location? =
        try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }

    private fun post(url: String, loc: Location) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val point = JSONObject().put("enlem", loc.latitude).put("boylam", loc.longitude)
            .put("dogruluk", loc.accuracy.toInt()).put("zaman", iso.format(Date(loc.time)))
        val body = JSONObject().put("konum", JSONArray().put(point)).put("kaynak", "telefon").toString()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode   // yanıtı beklemek yeterli; başarısızsa bir sonraki turda yeni konum gider
            conn.disconnect()
        } catch (e: Exception) {
            // ağ yok: sonraki turda yeni konum gönderilir
        }
    }
}
