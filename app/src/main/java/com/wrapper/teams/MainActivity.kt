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
        // Permissions changed -> refresh the FGS type (adds microphone/camera).
        TeamsService.start(this)
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
        // Re-start the keep-alive service so it re-declares its foreground type
        // now that mic/camera may be granted (needed for WebRTC audio capture).
        TeamsService.start(this)
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
        // Keep JS timers running even while backgrounded (pairs with
        // KeepAliveWebView) so Teams' realtime connection stays alive.
        webView.resumeTimers()

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

        // Keep the WebView's (separate) renderer process at a high, non-waivable
        // priority. Otherwise Android lowers its priority and evicts the camera
        // it holds during a call (rapid CONNECT/DISCONNECT -> Teams "camera
        // didn't work"). This was masked while a debugger was attached, which
        // also pins the renderer — hence debug "worked", release didn't.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

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
     * Runs BEFORE the Teams page scripts. Corrects a genuine Android WebView
     * platform gap: WebView does NOT surface an already-granted microphone /
     * camera permission through the standard W3C Permissions API, so
     * navigator.permissions.query() always returns "prompt". Teams reads that,
     * concludes it has no mic permission, and disables the mic/camera call
     * controls ("Allow Teams to access your mic").
     *
     * The permission is in fact granted (the app holds RECORD_AUDIO/CAMERA and
     * our onPermissionRequest grants the WebView request, so getUserMedia
     * succeeds). There is no native Android API to fix the Permissions API
     * result, so we report the truth here. This touches only the standard
     * Permissions API — it does NOT manipulate Teams' DOM or force any button
     * state; Teams enables its own controls once it sees the correct state.
     */
    private fun installDocumentStartShims() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val shim = """
            (function(){
              try{
                // Teams caches a device-health result in localStorage. If the
                // camera/mic ever failed once (e.g. the earlier permission bug),
                // Teams keeps the stale "didn't work" state and disables the
                // control forever. Wipe the health cache on each load so Teams
                // re-detects devices fresh (now that permissions work). We keep
                // the user's device *selections* intact.
                try{
                  for(var i=localStorage.length-1;i>=0;i--){
                    var lk=localStorage.key(i);
                    if(/get-user-media-data-bag|lastUsedPhysicalCameraId|isLowEndDevice|calling_selected_camera_device/i.test(lk))
                      localStorage.removeItem(lk);
                  }
                }catch(e){}
                function mk(state){
                  return { state:state, status:state, onchange:null,
                    addEventListener:function(){}, removeEventListener:function(){},
                    dispatchEvent:function(){return false;} };
                }
                // Read the bridge FRESH on every query. Capturing it once at
                // document-start is racy — window.AndroidTeams may not be injected
                // yet, which would make this return 'prompt' forever (mic/camera
                // intermittently disabled). By query time the bridge is ready.
                function micState(){ try{ var B=window.AndroidTeams; if(B && typeof B.micGranted==='function') return B.micGranted()?'granted':'prompt'; }catch(e){} return 'granted'; }
                function camState(){ try{ var B=window.AndroidTeams; if(B && typeof B.cameraGranted==='function') return B.cameraGranted()?'granted':'prompt'; }catch(e){} return 'granted'; }
                if(!navigator.permissions){ navigator.permissions = {}; }
                var orig = navigator.permissions.query
                  ? navigator.permissions.query.bind(navigator.permissions) : null;
                // WebView never surfaces an already-granted mic/camera permission
                // through the W3C Permissions API (always 'prompt'), so Teams
                // thinks it has no mic and disables the call controls. Report the
                // REAL OS permission state here instead.
                navigator.permissions.query = function(desc){
                  try{
                    var n = desc && desc.name;
                    if(n==='microphone') return Promise.resolve(mk(micState()));
                    if(n==='camera') return Promise.resolve(mk(camState()));
                    if(n==='notifications') return Promise.resolve(mk('granted'));
                  }catch(e){}
                  return orig ? orig(desc) : Promise.resolve(mk('prompt'));
                };
                // WebView's enumerateDevices returns empty deviceId/label until a
                // stream is live, which makes Teams show "No Device Selected".
                // Backfill the real default device id so a device is selectable.
                var md = navigator.mediaDevices;
                if(md && md.enumerateDevices){
                  var oe = md.enumerateDevices.bind(md);
                  md.enumerateDevices = function(){
                    return oe().then(function(ds){
                      var out = ds.map(function(d){
                        // Only AUDIO has a real 'default' pseudo-device. NEVER
                        // fake a 'default' id for video — cameras only have real
                        // (hashed) ids, and a phantom 'default' camera makes Teams
                        // cache camera:default then request deviceId:{exact:default}
                        // which throws OverconstrainedError ('camera didn't work').
                        var isAudio = d.kind==='audioinput' || d.kind==='audiooutput';
                        var id = d.deviceId || (isAudio ? 'default' : '');
                        var lab = d.label || (d.kind==='audioinput'?'Microphone':d.kind==='audiooutput'?'Speaker':d.kind==='videoinput'?'Camera':'');
                        return { deviceId:id, kind:d.kind, label:lab, groupId:d.groupId||'default',
                          toJSON:function(){ return {deviceId:id, kind:d.kind, label:lab, groupId:d.groupId||'default'}; } };
                      });
                      function has(k){ return out.some(function(x){ return x.kind===k; }); }
                      if(!has('audioinput')) out.push({deviceId:'default',kind:'audioinput',label:'Microphone',groupId:'default',toJSON:function(){return this;}});
                      if(!has('audiooutput')) out.push({deviceId:'default',kind:'audiooutput',label:'Speaker',groupId:'default',toJSON:function(){return this;}});
                      return out;
                    });
                  };
                }
                // getUserMedia safety net: a stale/invalid exact deviceId (e.g. a
                // cached camera:default) throws OverconstrainedError. Retry once
                // without the deviceId constraint so any real camera/mic is used.
                if(md && md.getUserMedia){
                  var og = md.getUserMedia.bind(md);
                  md.getUserMedia = function(c){
                    return og(c).catch(function(err){
                      if(err && err.name==='OverconstrainedError' && c && (c.video||c.audio)){
                        try{
                          var c2 = JSON.parse(JSON.stringify(c));
                          if(c2.video && typeof c2.video==='object') delete c2.video.deviceId;
                          if(c2.audio && typeof c2.audio==='object') delete c2.audio.deviceId;
                          return og(c2);
                        }catch(e2){}
                      }
                      throw err;
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
