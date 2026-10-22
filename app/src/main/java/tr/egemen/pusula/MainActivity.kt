package tr.egemen.pusula

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.Locale

/**
 * Core'un ilk sürümü: paneli (aynı asistan, aynı hafıza) uygulama içinde açar.
 *
 * - Normal açılış → Asistan (sohbet) ekranı; panelin geri kalanı alt menüden.
 * - Asistan tuşu (ana ekran/güç tuşuna uzun basma) ya da kulaklık tuşu → `/ses?mod=konusma`:
 *   eller serbest konuşma modu dokunmadan başlar.
 *
 * Giriş bir kez yapılır; panelin oturum çerezi 30 gün geçerli ve WebView çerezleri saklıyor.
 * Banka SMS'leri: panelde Hareket → Telefon verisi → "SMS takibini aç" ile açılır (`SmsForwarder`).
 * Health Connect, konum ve "Hey Core" uyandırma kelimesi sonraki sürümlerde.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val base = BuildConfig.BASE_URL
    private val host = Uri.parse(BuildConfig.BASE_URL).host
    private var awaitingLocationSettings = false
    private var bgRequestedAt = 0L

    /** 21 Ekim: güven yalnızca tam kaynakta (https + host + 443). Eskiden yalnızca host bakılıyordu; aynı host'un
     *  http ya da başka port adresi de panel sayılır, JS köprüsü ve mikrofon ona da açılırdı. */
    private fun isPanel(uri: Uri?): Boolean =
        uri != null && uri.scheme == "https" && uri.host == host && (uri.port == -1 || uri.port == 443)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#FAF9F6"))
        setContentView(web)
        applySystemBarPadding(web)

        CookieManager.getInstance().setAcceptCookie(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Asistan tuşuyla açılınca konuşma modu dokunmadan başlayabilsin (ses + mikrofon).
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString PusulaAndroid/${BuildConfig.VERSION_NAME}"
        }
        web.webViewClient = PanelClient()
        web.webChromeClient = MicClient()
        setupSpeech()
        // Ağ yokken gelen banka SMS'leri kuyrukta bekliyor olabilir: uygulama açılınca gönderilsin
        Thread { SmsForwarder.flush(applicationContext) }.start()
        LocationSender.schedule(applicationContext)   // güncellemeden/yeniden kurulumdan sonra alarm kalsın

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(startUrl(intent))
        }
    }

    /**
     * Cihazın Türkçe sesi: Android WebView'de tarayıcı ses motoru (speechSynthesis) çoğu sürümde yok.
     * Sayfa `PusulaNative.speak(id, metin)` ile cümle cümle okutur; bitince `__pusulaSpoken(id)` çağrılır
     * (konuşma modu okuma bitince yeniden dinlemeye geçsin diye). Ses anında başlar, ücretsizdir.
     */
    private fun setupSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = spoken(utteranceId)
            @Deprecated("eski API")
            override fun onError(utteranceId: String?) = spoken(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = spoken(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = spoken(utteranceId)
        })
        web.addJavascriptInterface(SpeechBridge(), "PusulaNative")
    }

    private fun spoken(id: String?) {
        val safe = (id ?: return).filter { it.isLetterOrDigit() }
        runOnUiThread { web.evaluateJavascript("window.__pusulaSpoken && window.__pusulaSpoken('$safe')", null) }
    }

    /** Sayfaya açılan köprü. Yalnızca Core paneli yüklenir (başka siteler tarayıcıda açılır). */
    private inner class SpeechBridge {
        @JavascriptInterface
        fun speak(id: String, text: String) {
            val engine = tts
            if (engine == null || !ttsReady) {
                spoken(id)   // ses yoksa sayfa beklemesin
                return
            }
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }

        @JavascriptInterface
        fun stop() {
            tts?.stop()
        }

        @JavascriptInterface
        fun available(): Boolean = ttsReady

        /** Banka SMS'i iletimi: kapali | izin_yok | acik */
        @JavascriptInterface
        fun smsStatus(): String = SmsForwarder.status(this@MainActivity)

        /** Konum paylaşımı: kapali | izin_yok | arka_plan_yok | acik */
        @JavascriptInterface
        fun locationStatus(): String = LocationSender.status(this@MainActivity)

        /** Konum paylaşımını açar; izinler sırayla istenir (önce konum, sonra "her zaman"). Sonuç `__pusulaKonum`. */
        @JavascriptInterface
        fun enableLocation(url: String) {
            if (!url.startsWith("$base/sms/")) return   // yalnızca kendi sunucumuz
            LocationSender.enable(this@MainActivity, url)
            runOnUiThread {
                when {
                    !LocationSender.hasForeground(this@MainActivity) -> requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQUEST_LOCATION)
                    !LocationSender.hasBackground(this@MainActivity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        requestBackgroundLocation()
                    else -> {
                        Thread { LocationSender.sendNow(applicationContext) }.start()
                        locationStateToPage()
                    }
                }
            }
        }

        @JavascriptInterface
        fun disableLocation() {
            LocationSender.disable(this@MainActivity)
            runOnUiThread { locationStateToPage() }
        }

        /** Banka uygulaması bildirimleri: kapali | izin_yok | acik (21 Ekim) */
        @JavascriptInterface
        fun notificationStatus(): String = BankNotificationListener.status(this@MainActivity)

        /** Android bu izni diyalogla vermez: Bildirim erişimi ayarı açılır, dönünce panel durumu yeniden okur. */
        @JavascriptInterface
        fun enableNotifications() {
            runOnUiThread {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null))) }
            }
        }

        /** Panel gönderim adresini verir; izin yoksa istenir. Sonuç `__pusulaSms(durum)` ile döner. */
        @JavascriptInterface
        fun enableSms(url: String) {
            if (!url.startsWith("$base/sms/")) return   // yalnızca kendi sunucumuz
            SmsForwarder.setUrl(this@MainActivity, url)
            runOnUiThread {
                if (SmsForwarder.hasPermission(this@MainActivity)) {
                    smsStateToPage()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
                }
            }
        }
    }

    private fun locationStateToPage() {
        val state = LocationSender.status(this)
        web.evaluateJavascript("window.__pusulaKonum && window.__pusulaKonum('$state')", null)
    }

    private fun smsStateToPage() {
        val state = SmsForwarder.status(this)
        web.evaluateJavascript("window.__pusulaSms && window.__pusulaSms('$state')", null)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // singleTask: uygulama açıkken asistan tuşuna basılırsa yeni pencere değil bu çağrı gelir.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceLaunch(intent)) web.loadUrl(startUrl(intent))
    }

    private fun isVoiceLaunch(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND

    // Uygulamanın ana kullanımı asistan: normal açılışta da sohbet ekranı gelir (panelin geri kalanı
    // alttaki menüden). Asistan tuşuyla açılınca konuşma modu da kendiliğinden başlar.
    private fun startUrl(intent: Intent?): String =
        if (isVoiceLaunch(intent)) "$base/ses?mod=konusma" else "$base/ses"

    /** targetSdk 35'te uygulama kenardan kenara çizilir: içerik durum/gezinme çubuğunun altında kalmasın. */
    private fun applySystemBarPadding(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /** Panel uygulamanın içinde kalır; başka sitelere giden bağlantılar telefonun tarayıcısında açılır. */
    private inner class PanelClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (isPanel(url)) return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                Toast.makeText(this@MainActivity, R.string.offline, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Sayfa mikrofon isteyince: Android izni yoksa önce onu sor, sonra sayfaya ver. */
    private inner class MicClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
            val fromPanel = isPanel(request.origin)
            if (!wantsMic || !fromPanel) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingPermission = request
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_BG_LOCATION && !LocationSender.hasBackground(this) &&
            SystemClock.elapsedRealtime() - bgRequestedAt < SILENT_DENIAL_MS) {
            // 21 Ekim: "her zaman izin ver" birkaç kez reddedilince sistem bir daha ekran göstermez, istek anında
            // reddedilir. O zaman uygulamanın izin sayfası açılır; geri dönülünce (onResume) izin yeniden okunur.
            // Ekran gösterildiyse (kullanıcı ayarlardan elle döndü) ikinci kez açılmaz.
            awaitingLocationSettings = true
            Toast.makeText(this, R.string.location_always, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            locationStateToPage()
            return
        }
        if (requestCode == REQUEST_LOCATION || requestCode == REQUEST_BG_LOCATION) {
            // Android 11+: "her zaman izin ver" seçeneği ayrı istenir; sistem bunun için ayarlar sayfasını açar
            if (requestCode == REQUEST_LOCATION && LocationSender.hasForeground(this) && !LocationSender.hasBackground(this) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation()
                return
            }
            LocationSender.schedule(applicationContext)
            Thread { LocationSender.sendNow(applicationContext) }.start()
            locationStateToPage()
            return
        }
        if (requestCode == REQUEST_SMS) {
            // Android 13+: Play Store dışından kurulan uygulamada izin "kısıtlanmış" olabilir, sayfa yol gösterir
            smsStateToPage()
            return
        }
        if (requestCode != REQUEST_MIC) return
        val request = pendingPermission ?: return
        pendingPermission = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Geri tuşu: önce sayfa geçmişinde geri git")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun requestBackgroundLocation() {
        bgRequestedAt = SystemClock.elapsedRealtime()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BG_LOCATION)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingLocationSettings) {
            awaitingLocationSettings = false
            LocationSender.schedule(applicationContext)
            Thread { LocationSender.sendNow(applicationContext) }.start()
            locationStateToPage()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()   // oturum çerezi diske yazılsın: tekrar giriş istenmesin
    }

    companion object {
        private const val REQUEST_MIC = 1
        private const val REQUEST_SMS = 2
        private const val REQUEST_LOCATION = 3
        private const val REQUEST_BG_LOCATION = 4
        private const val SILENT_DENIAL_MS = 700L
    }
}
