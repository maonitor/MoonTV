package cn.iepose.yhroppo.moontv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.net.URI

/** 承载自有 MoonTV 服务，负责网页导航、视频全屏与 Android 生命周期衔接。 */
class MainActivity : Activity() {
    /** 页面和视频全屏视图的共同根容器；由窗口边距监听器处理安全区域。 */
    private lateinit var rootContainer: FrameLayout

    /** 普通浏览区域，全屏视频显示时暂时隐藏。 */
    private lateinit var webContent: FrameLayout

    /** 使用独立应用数据目录保存 Cookie 和站点本地存储的浏览器组件。 */
    private lateinit var webView: WebView

    /** 当前主页面的加载进度，子资源失败不会切换整个页面的错误状态。 */
    private lateinit var loadingProgress: ProgressBar

    /** 原生网络错误页，仅在主文档无法正常加载时显示。 */
    private lateinit var errorPanel: View

    /** 网络错误原因，不显示包含登录参数的失败 URL。 */
    private lateinit var errorMessage: TextView

    /** 接收 WebView 提供的视频全屏视图。 */
    private lateinit var fullscreenContainer: FrameLayout

    /** UI 线程上的网页全屏状态检查调度器，不向网页暴露原生接口。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 构建时指定的 HTTPS 站点地址，也是重试和外部浏览器的回退入口。 */
    private val serverUrl: String = BuildConfig.SERVER_URL

    /** 可信站点的解析结果；只允许同协议、同主机、同端口的页面留在壳内。 */
    private val serverOrigin: URI = URI(BuildConfig.SERVER_URL)

    /** 最近一次可信主文档地址，主文档失败后仍可重试原地址。 */
    private var lastMainFrameUrl: String = BuildConfig.SERVER_URL

    /** 页面导航代次，丢弃旧页面异步 JavaScript 返回的过期结果。 */
    private var navigationGeneration = 0

    /** 主文档是否加载失败，避免 onPageFinished 覆盖原生错误页。 */
    private var mainFrameFailed = false

    /** Activity 是否在前台；后台时停止页面检查并暂停媒体。 */
    private var resumed = false

    /** WebView 是否已经销毁，防止生命周期结束后的异步访问。 */
    private var webViewDestroyed = false

    /** 同一导航代次最多保留一次待返回的全屏状态检查。 */
    private var fullscreenCheckPending = false

    /** 网页通过 CSS 或 DOM 全屏 API 报告的全屏状态。 */
    private var pageFullscreen = false

    /** WebView 当前交出的原生视频全屏视图；空值表示未进入此模式。 */
    private var customView: View? = null

    /** 网页退出原生全屏的回调，请求退出时至多调用一次。 */
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 当前是否已应用原生沉浸模式，供两种全屏路径共同使用。 */
    private var immersive = false

    /** 进入全屏之前的方向策略，退出时恢复，避免永久锁屏。 */
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** Android 13 及以上的系统返回回调，销毁时解除注册。 */
    private var backCallback: OnBackInvokedCallback? = null

    /** 前台每 500 毫秒检查可信页面的全屏状态，后台和销毁时移除。 */
    private val fullscreenPoll = object : Runnable {
        /** 读取当前页面状态并调度下一次检查。 */
        override fun run() {
            if (!resumed || webViewDestroyed) return
            checkPageFullscreen()
            mainHandler.postDelayed(this, FULLSCREEN_POLL_INTERVAL_MS)
        }
    }

    /**
     * 初始化壳界面并恢复可信页面历史；不接收外部 Intent 指定的网页地址。
     * @param savedInstanceState 系统保存的浏览历史，首次启动时为空。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootContainer = findViewById(R.id.root_container)
        webContent = findViewById(R.id.web_content)
        webView = findViewById(R.id.web_view)
        loadingProgress = findViewById(R.id.loading_progress)
        errorPanel = findViewById(R.id.error_panel)
        errorMessage = findViewById(R.id.error_message)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        configureWindow()
        configureWebView()
        findViewById<View>(R.id.retry_button).setOnClickListener {
            webView.loadUrl(lastMainFrameUrl)
        }
        findViewById<View>(R.id.browser_button).setOnClickListener {
            openInBrowser(lastMainFrameUrl)
        }
        registerSystemBack()

        val history = savedInstanceState?.getBundle(WEB_VIEW_STATE)?.let(webView::restoreState)
        if (history == null || !isTrustedPage(webView.url)) {
            webView.loadUrl(serverUrl)
        }
    }

    /** 让普通页面避开系统栏和刘海，视频全屏时则交由沉浸模式管理。 */
    @Suppress("DEPRECATION")
    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = BASE_SYSTEM_UI
        }
        rootContainer.setOnApplyWindowInsetsListener { _, insets ->
            if (immersive) {
                rootContainer.setPadding(0, 0, 0, 0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                rootContainer.setPadding(
                    safeArea.left, safeArea.top, safeArea.right,
                    maxOf(safeArea.bottom, keyboard.bottom)
                )
            } else {
                rootContainer.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        updateSystemBars()
    }

    /** 配置站内登录、网页播放器和浏览边界，禁止文件访问及 HTTP 混合内容。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = object : WebViewClient() {
            /**
             * 站内导航由 WebView 继续；有用户手势的外部网页交给浏览器。
             * @param view 发起导航的 WebView。
             * @param request 导航目标及其主框架、用户手势信息。
             * @return true 表示拦截，false 表示继续加载。
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (!request.isForMainFrame) {
                    return request.url.scheme !in listOf("https", "http")
                }
                if (isTrustedPage(target)) return false
                if (request.hasGesture()) openInBrowser(target)
                return true
            }

            /**
             * 为新主文档重置状态，并使旧页面的全屏回调失效。
             * @param view 正在加载页面的 WebView。
             * @param url 主文档地址。
             * @param favicon 站点图标，可为空；壳不使用该图标。
             */
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                navigationGeneration++
                fullscreenCheckPending = false
                pageFullscreen = false
                dismissCustomView(requestPageExit = true)
                updateImmersiveMode()
                if (!isTrustedPage(url)) {
                    view.stopLoading()
                    showPageError(getString(R.string.loading_error))
                    return
                }
                lastMainFrameUrl = url
                mainFrameFailed = false
                errorPanel.visibility = View.GONE
                loadingProgress.progress = 0
                loadingProgress.visibility = View.VISIBLE
            }

            /**
             * 主页面加载结束后隐藏进度；失败页面保留原生重试入口。
             * @param view 已结束加载的 WebView。
             * @param url 完成加载的页面地址。
             */
            override fun onPageFinished(view: WebView, url: String) {
                loadingProgress.visibility = View.GONE
                if (!mainFrameFailed && isTrustedPage(url)) checkPageFullscreen()
            }

            /**
             * 只将主文档网络失败展示为原生错误页。
             * @param view 请求所属的 WebView。
             * @param request 失败请求，可能为子资源。
             * @param error WebView 提供的网络错误信息。
             */
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) showPageError(getString(R.string.network_error))
            }

            /**
             * 只拦截主文档 HTTP 错误，不让图片或视频源错误覆盖整个网站。
             * @param view 请求所属的 WebView。
             * @param request 失败请求及主框架标识。
             * @param errorResponse HTTP 状态及响应头，不读取可能包含隐私的响应体。
             */
            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) showPageError(getString(R.string.loading_error))
            }

            /**
             * 始终取消无效证书请求，绝不绕过 HTTPS 校验。
             * @param view 请求所属的 WebView。
             * @param handler 证书错误的处理器，只调用 cancel。
             * @param error 含失败资源地址的证书错误，可能来自子资源。
             */
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == lastMainFrameUrl) showPageError(getString(R.string.ssl_error))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            /**
             * 更新页面进度条，不覆盖错误面板。
             * @param view 报告进度的 WebView。
             * @param newProgress 0 到 100 的加载百分比。
             */
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress.progress = newProgress
                loadingProgress.visibility =
                    if (newProgress == 100 || mainFrameFailed) View.GONE else View.VISIBLE
            }

            /**
             * 接收视频或 DOM 全屏视图；仅允许可信顶层页面请求原生全屏。
             * @param view WebView 提供、需要添加到窗口中的全屏视图。
             * @param callback 请求网页退出全屏时使用的回调。
             */
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null || !isTrustedPage(webView.url)) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                fullscreenContainer.visibility = View.VISIBLE
                webContent.visibility = View.INVISIBLE
                updateImmersiveMode()
            }

            /** 网页自行退出原生全屏后，幂等移除视频视图并恢复窗口。 */
            override fun onHideCustomView() {
                dismissCustomView(requestPageExit = false)
            }
        }
    }

    /**
     * 检查网址是否属于构建时固定的 HTTPS 站点，不使用字符串前缀判断。
     * @param url 待检查的完整页面地址，空值视为不可信。
     * @return 协议、主机、有效端口匹配且无用户名密码时为 true。
     */
    private fun isTrustedPage(url: String?): Boolean = try {
        val candidate = URI(url ?: "")
        candidate.scheme.equals("https", ignoreCase = true) &&
            candidate.host?.equals(serverOrigin.host, ignoreCase = true) == true &&
            candidate.rawUserInfo == null &&
            (if (candidate.port == -1) 443 else candidate.port) ==
            (if (serverOrigin.port == -1) 443 else serverOrigin.port)
    } catch (_: Exception) {
        false
    }

    /**
     * 在外部浏览器打开普通 HTTP(S) 链接，不将自定义协议转成原生 Intent。
     * @param url 用户点击的网页地址；无效地址或其他协议直接忽略。
     */
    private fun openInBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.loading_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示主文档加载错误，保留已保存的 Cookie 和观看数据供重试使用。
     * @param message 面向用户的错误说明，不含凭据或响应内容。
     */
    private fun showPageError(message: String) {
        mainFrameFailed = true
        pageFullscreen = false
        dismissCustomView(requestPageExit = true)
        updateImmersiveMode()
        loadingProgress.visibility = View.GONE
        errorMessage.text = message
        errorPanel.visibility = View.VISIBLE
    }

    /** 仅读取可信顶层网页的有限布尔状态，兼容 ArtPlayer CSS 网页全屏。 */
    private fun checkPageFullscreen() {
        if (!resumed || webViewDestroyed || mainFrameFailed || fullscreenCheckPending ||
            !isTrustedPage(webView.url)) return
        val generation = navigationGeneration
        fullscreenCheckPending = true
        webView.evaluateJavascript(READ_FULLSCREEN_SCRIPT) { result ->
            if (generation != navigationGeneration || webViewDestroyed) return@evaluateJavascript
            fullscreenCheckPending = false
            if (!resumed || mainFrameFailed || !isTrustedPage(webView.url)) return@evaluateJavascript
            pageFullscreen = result == "true"
            updateImmersiveMode()
        }
    }

    /** 合并原生视频与网页 CSS 全屏状态，统一管理方向、系统栏和亮屏。 */
    private fun updateImmersiveMode() {
        val shouldBeImmersive = customView != null || pageFullscreen
        if (immersive == shouldBeImmersive) return
        immersive = shouldBeImmersive
        if (immersive) {
            previousOrientation = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requestedOrientation = previousOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updateSystemBars()
        rootContainer.requestApplyInsets()
    }

    /** 按系统版本隐藏或恢复系统栏，支持全屏时滑动临时呼出导航。 */
    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) {
                    hide(WindowInsets.Type.systemBars())
                } else {
                    show(WindowInsets.Type.systemBars())
                }
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                setSystemBarsAppearance(if (immersive) 0 else lightBars, lightBars)
            }
        } else {
            window.decorView.systemUiVisibility = if (immersive) {
                BASE_SYSTEM_UI or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                BASE_SYSTEM_UI or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    /**
     * 清理原生全屏；先清回调引用以避免 WebView 同步回调造成重入。
     * @param requestPageExit true 表示同时请求网页退出，false 用于网页已退出的通知。
     */
    private fun dismissCustomView(requestPageExit: Boolean) {
        val callback = customViewCallback
        customViewCallback = null
        customView = null
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        webContent.visibility = View.VISIBLE
        if (requestPageExit) callback?.onCustomViewHidden()
        updateImmersiveMode()
    }

    /** Android 13 及以上接入系统返回分发，旧系统使用 onBackPressed。 */
    private fun registerSystemBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!
            )
        }
    }

    /** 依次退出原生视频全屏、网页全屏、历史页面，最后关闭壳界面。 */
    private fun handleBack() {
        if (customView != null) {
            pageFullscreen = false
            dismissCustomView(requestPageExit = true)
        } else if (pageFullscreen && isTrustedPage(webView.url)) {
            requestPageFullscreenExit()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    /** 请求网页自身退出全屏，并在退出未生效时给出可见提示。 */
    private fun requestPageFullscreenExit() {
        val generation = navigationGeneration
        webView.evaluateJavascript(EXIT_FULLSCREEN_SCRIPT) { accepted ->
            if (!resumed || webViewDestroyed || mainFrameFailed ||
                generation != navigationGeneration || !isTrustedPage(webView.url)) {
                return@evaluateJavascript
            }
            if (accepted != "true") {
                Toast.makeText(this, R.string.fullscreen_exit_error, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            mainHandler.postDelayed({
                if (resumed && !webViewDestroyed && !mainFrameFailed &&
                    generation == navigationGeneration && isTrustedPage(webView.url)) {
                    webView.evaluateJavascript(READ_FULLSCREEN_SCRIPT) { stillFullscreen ->
                        if (resumed && !webViewDestroyed && !mainFrameFailed &&
                            generation == navigationGeneration && isTrustedPage(webView.url) &&
                            stillFullscreen == "true") {
                            Toast.makeText(this, R.string.fullscreen_exit_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, 1_000L)
        }
    }

    /** Android 12 及更早版本的系统返回入口。 */
    @Deprecated("旧系统兼容入口；Android 13 以上由 OnBackInvokedCallback 处理。")
    override fun onBackPressed() {
        handleBack()
    }

    /** 回到前台时恢复 WebView 和全屏状态检查。 */
    override fun onResume() {
        super.onResume()
        resumed = true
        webView.onResume()
        mainHandler.removeCallbacks(fullscreenPoll)
        mainHandler.post(fullscreenPoll)
    }

    /** 进入后台时暂停媒体和检查；Cookie 有效期继续由服务器控制。 */
    override fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(fullscreenPoll)
        if (isTrustedPage(webView.url)) {
            webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(media){media.pause();});",
                null
            )
        }
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    /**
     * 保存可信网页历史，不把账号密码复制到原生配置。
     * @param outState 由系统保存、下次 Activity 重建时提供的状态容器。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isTrustedPage(webView.url)) {
            outState.putBundle(WEB_VIEW_STATE, Bundle().also(webView::saveState))
        }
    }

    /** 销毁时解除返回回调、全屏和网页资源，不清除用户的站点存储。 */
    override fun onDestroy() {
        resumed = false
        mainHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        pageFullscreen = false
        dismissCustomView(requestPageExit = true)
        webViewDestroyed = true
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** 壳的状态键和仅供可信页面执行的固定脚本。 */
    private companion object {
        /** Activity 重建时保存 WebView 导航历史的 Bundle 键。 */
        const val WEB_VIEW_STATE = "moontv.webview.state"

        /** 前台全屏状态检查间隔，单位为毫秒。 */
        const val FULLSCREEN_POLL_INTERVAL_MS = 500L

        /** 旧系统绘制到系统栏下方，再由根容器补齐安全区域。 */
        @Suppress("DEPRECATION")
        const val BASE_SYSTEM_UI = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        /** 只返回布尔值；兼容浏览器原生全屏及 ArtPlayer 的两种全屏类名。 */
        const val READ_FULLSCREEN_SCRIPT = """
            (function(){return !!(document.fullscreenElement || document.webkitFullscreenElement ||
              document.querySelector('.art-fullscreen-web, .art-fullscreen'));})()
        """

        /** 优先点击 ArtPlayer 自身按钮退出 CSS 全屏，保留播放器内部状态。 */
        const val EXIT_FULLSCREEN_SCRIPT = """
            (function(){
              if(document.fullscreenElement && document.exitFullscreen){
                document.exitFullscreen().catch(function(){
                  console.warn('MoonTV: fullscreen exit failed');
                });return true;
              }
              if(document.webkitFullscreenElement && document.webkitExitFullscreen){
                document.webkitExitFullscreen();return true;
              }
              var player=document.querySelector('.art-fullscreen-web, .art-fullscreen');
              if(!player)return true;
              var selector=player.classList.contains('art-fullscreen-web')
                ? '.art-control-fullscreen-web' : '.art-control-fullscreen';
              var button=player.querySelector(selector);
              if(!button)return false;button.click();return true;
            })()
        """
    }
}
