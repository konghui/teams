package com.wrapper.teams

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wrapper.teams.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), CallController.WebDriver {

    private lateinit var binding: ActivityMainBinding
    private val webView get() = binding.webView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingChatId: String? = null
    private var pendingChatTitle: String? = null

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingWebPermission?.let { req ->
            // Grant the web request regardless; the OS layer is the real gate and
            // the user just answered it.
            runCatching { req.grant(req.resources) }
            pendingWebPermission = null
        }
    }

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK && data?.data != null)
            arrayOf(data.data!!) else null
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    // Startup permissions (mic/camera/notifications). Battery-optimization
    // whitelist is requested ONLY after this dialog is answered, otherwise the
    // battery Activity pops over and dismisses the permission dialog before the
    // user can tap it.
    private val startupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestIgnoreBatteryOptimization()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        requestStartupPermissions()
        TeamsService.start(this)
        configureWebView()

        if (savedInstanceState == null) {
            webView.loadUrl(Constants.TEAMS_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        // Cold start from a message notification: navigate after page is ready.
        pendingChatId = intent?.getStringExtra(Constants.EXTRA_CHAT_ID)
        pendingChatTitle = intent?.getStringExtra(Constants.EXTRA_CHAT_TITLE)

        CallController.bind(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = Constants.DESKTOP_UA
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(applicationContext), Constants.JS_BRIDGE)

        installDocumentStartShims()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase()
                // Keep ALL normal web navigation (incl. auth redirects and
                // links) inside the WebView so nothing kicks out to Chrome.
                if (scheme == "http" || scheme == "https" || scheme == null) {
                    return false
                }
                // Never kick out to the native Teams app: swallow its deep-link
                // schemes and stay inside our WebView. The user explicitly wants
                // to remain on the Teams web page.
                if (scheme == "msteams" || scheme == "msauth" ||
                    url.startsWith("intent:") || url.contains("teams.microsoft.com/l/") &&
                    scheme != "https"
                ) {
                    return true
                }
                // Only hand off real non-web schemes (tel:, mailto:, sip:,
                // callto:, etc.) to the system.
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }.getOrDefault(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectScript()
                // If we were launched from a message notification, jump to that
                // chat once the page (and inject.js) are ready.
                if (pendingChatId != null || pendingChatTitle != null) {
                    val id = pendingChatId ?: ""
                    val title = pendingChatTitle ?: ""
                    pendingChatId = null
                    pendingChatTitle = null
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "window.__teamsWrapperOpenChat && " +
                                "window.__teamsWrapperOpenChat(${jsStr(id)}, ${jsStr(title)})", null
                        )
                    }, 800)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }

            // Teams opens some actions via window.open()/target=_blank. Load
            // them in the same WebView instead of spawning an external browser.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val href = view.hitTestResult.extra
                if (!href.isNullOrBlank()) {
                    view.loadUrl(href)
                }
                return false
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return runCatching {
                    fileChooser.launch(params.createIntent())
                    true
                }.getOrElse {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, mime, _ ->
            runCatching {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    allowScanningByMediaScanner()
                }
                getSystemService(DownloadManager::class.java).enqueue(req)
                Toast.makeText(this, "开始下载…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val resources = request.resources
        val needsMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val needsCam = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val micOk = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // Visible diagnostic so we can see on-device whether this fires and what
        // the OS permission state is.
        Toast.makeText(
            this,
            "媒体请求 mic=$needsMic(系统:$micOk) cam=$needsCam(系统:$camOk)",
            Toast.LENGTH_LONG
        ).show()

        // If the OS-level permission is missing, request it now (the battery
        // intent no longer interrupts), then grant the web request in the result.
        val missing = mutableListOf<String>()
        if (needsMic && !micOk) missing += android.Manifest.permission.RECORD_AUDIO
        if (needsCam && !camOk) missing += android.Manifest.permission.CAMERA
        if (missing.isNotEmpty()) {
            pendingWebPermission = request
            webPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        // Auto-allow the browser-layer request; OS gate already satisfied.
        request.grant(resources)
    }

    /**
     * Runs BEFORE the Teams page scripts. Polyfills browser APIs that Android
     * WebView omits but Teams relies on to enable mic/camera/call features:
     *  - navigator.permissions.query (undefined in WebView) -> report granted
     *  - navigator.mediaDevices.selectAudioOutput stub
     */
    private fun installDocumentStartShims() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val shim = """
            (function(){
              try{
                if(!navigator.permissions){ navigator.permissions = {}; }
                if(!navigator.permissions.query){
                  navigator.permissions.query = function(desc){
                    return Promise.resolve({
                      state:'granted', status:'granted', onchange:null,
                      addEventListener:function(){}, removeEventListener:function(){},
                      dispatchEvent:function(){return false;}
                    });
                  };
                }
              }catch(e){}
            })();
        """.trimIndent()
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("*"))
        }
    }

    private fun injectScript() {
        val js = runCatching {
            assets.open("inject.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        webView.evaluateJavascript(js, null)
    }

    private fun requestStartupPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += android.Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Battery-opt request is chained in the result callback.
            startupPermissions.launch(missing.toTypedArray())
        } else {
            requestIgnoreBatteryOptimization()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App already running: navigate straight away.
        val chatId = intent.getStringExtra(Constants.EXTRA_CHAT_ID)
        val title = intent.getStringExtra(Constants.EXTRA_CHAT_TITLE)
        if (!chatId.isNullOrBlank() || !title.isNullOrBlank()) {
            runJs(
                "window.__teamsWrapperOpenChat && window.__teamsWrapperOpenChat(" +
                    "${jsStr(chatId ?: "")}, ${jsStr(title ?: "")})"
            )
        }
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // --- CallController.WebDriver ---
    override fun runJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun bringToFront() {
        val i = Intent(this, MainActivity::class.java).apply {
            action = Constants.ACTION_SHOW_MAIN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(i)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        // First let Teams handle back: if we're inside a conversation, click its
        // "Return to list" control so the Android back gesture/button goes back
        // to the main list. Teams keeps no URL history, so goBack() alone won't.
        webView.evaluateJavascript(
            "(window.__teamsWrapperBack && window.__teamsWrapperBack()) ? 'true' : 'false'"
        ) { result ->
            if (result != "\"true\"" && result != "true") {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        }
    }

    override fun onDestroy() {
        CallController.unbind(this)
        super.onDestroy()
    }
}
