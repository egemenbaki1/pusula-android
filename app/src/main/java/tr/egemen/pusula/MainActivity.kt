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
 * Pusula'nın ilk sürümü: paneli (aynı asistan, aynı hafıza) uygulama içinde açar.
 *
 * - Normal açılış → Asistan (sohbet) ekranı; panelin geri kalanı alt menüden.
 * - Asistan tuşu (ana ekran/güç tuşuna uzun basma) ya da kulaklık tuşu → `/ses?mod=konusma`:
 *   eller serbest konuşma modu dokunmadan başlar.
 *
 * Giriş bir kez yapılır; panelin oturum çerezi 30 gün geçerli ve WebView çerezleri saklıyor.
 * Banka SMS'leri: panelde Hareket → Telefon verisi → "SMS takibini aç" ile açılır (`SmsForwarder`).
 * Health Connect, konum ve "Hey Pusula" uyandırma kelimesi sonraki sürümlerde.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val base = BuildConfig.BASE_URL
    private val host = Uri.parse(BuildConfig.BASE_URL).host

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

    /** Sayfaya açılan köprü. Yalnızca Pusula paneli yüklenir (başka siteler tarayıcıda açılır). */
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
            if (url.host == host) return false
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
            val fromPanel = request.origin.host == host
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

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()   // oturum çerezi diske yazılsın: tekrar giriş istenmesin
    }

    companion object {
        private const val REQUEST_MIC = 1
        private const val REQUEST_SMS = 2
    }
}
