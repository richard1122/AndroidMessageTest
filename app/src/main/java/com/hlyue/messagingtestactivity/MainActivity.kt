package com.hlyue.messagingtestactivity

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebMessagePort.WebMessageCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableField
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewCompat.WebMessageListener
import com.hlyue.messagingtestactivity.databinding.LayoutWebviewBinding
import java.time.LocalTime
import java.time.format.DateTimeFormatter


class MainActivity : ComponentActivity() {
    private lateinit var binding: LayoutWebviewBinding
    private val vm: WebviewActivityViewModel by lazy { WebviewActivityViewModel() }
    private val TAG = "MainActivity"

    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", AssetsPathHandler(this))
        .build()

    private val webViewClient = object :WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return loader.shouldInterceptRequest(request.url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.layout_webview)
        binding.lifecycleOwner = this
        binding.vm = vm
        binding.loggingText.movementMethod = ScrollingMovementMethod.getInstance()
        binding.webview.webViewClient = webViewClient
        binding.webview.settings.javaScriptEnabled = true
        WebView.setWebContentsDebuggingEnabled(true)
        binding.webview.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
        vm.loggingString.set(getWebViewVersion(this))
        WebViewCompat.addWebMessageListener(binding.webview, "host", setOf("*"), object : WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy
            ) {
                Log.e(TAG, "onPostMessage: ${message.data}")
                vm.loggingString.set(vm.loggingString.get() +
                        "${DateTimeFormatter.ISO_LOCAL_TIME.format(LocalTime.now())}: ${message.data}\n")
                when (message.data) {
                    "background-prepare" -> vm.setupBackgroundPort(binding.webview)
                    "background-port" -> {}
                    else -> {
                        if (message.data!!.startsWith("port-message-count")) {
                            vm.hostPostMessageDone(message.data!!)
                        }
                    }
                }
            }
        })
    }

    class WebviewActivityViewModel {
        private val TAG = "WebviewActivityViewModel"
        private val backgroundThread = HandlerThread("WebViewBackground")
        private val backgroundHandler by lazy { Handler(backgroundThread.looper) }
        private lateinit var backgroundPort: WebMessagePort
        private var messageHandler: WebMessageCallback? = null
        private val expectedMessageCount = 100000
        init {
            backgroundThread.start()
        }

        var loggingString = ObservableField<String>("")

        fun setupBackgroundPort(webView: WebView) {
            assert(!this::backgroundPort.isInitialized) { "Background port should not be initialized again" }
            val ports = webView.createWebMessageChannel()
            webView.postWebMessage(WebMessage("port", arrayOf(ports[0])), Uri.EMPTY)
            backgroundPort = ports[1]
            backgroundHandler.post {
                backgroundPort.setWebMessageCallback(object : WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        messageHandler?.onMessage(port, message)
                    }
                }, backgroundHandler)
            }
        }

        fun hostPostMessage() {
            assert(this::backgroundPort.isInitialized) { "Background port should be set" }
            Log.e(TAG, "hostPostMessage")
            Debug.startMethodTracingSampling("new", 8 * 1024 * 1024, 100)
            backgroundHandler.post {
                (1..expectedMessageCount).forEach { backgroundPort.postMessage(WebMessage("Count: $it")) }
            }
        }

        fun hostPostMessageDone(message: String) {
            if (message.split(' ').last().toInt() % expectedMessageCount == 0) {
                Debug.stopMethodTracing()
                Log.e(TAG, "Stop tracing")
            }
        }
    }

    fun getWebViewVersion(activity: Activity): String {
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(activity)
        return if (webViewPackage != null) webViewPackage.versionName else "Unknown"
    }
}
