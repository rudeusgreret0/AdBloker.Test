package com.example.adblocker

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clean, self-contained, thread-safe Ad & Tracker Blocker module for Android `WebView`.
 *
 * =========================================================================================
 * HOW TO PLUG THIS MODULE INTO ANY ANDROID WEBVIEW IN ANOTHER APP
 * =========================================================================================
 * 1. Copy `AdBlocker.kt` and `FilterList.kt` into your Android project.
 * 2. Initialize once in `Application.onCreate()` or `Activity.onCreate()`:
 *    ```kotlin
 *    AdBlocker.init(context)
 *    ```
 * 3. Inside your `WebViewClient`:
 *    ```kotlin
 *    override fun shouldInterceptRequest(
 *        view: WebView,
 *        request: WebResourceRequest
 *    ): WebResourceResponse? {
 *        // Runs on a WebView background thread — fast & lock-free!
 *        return AdBlocker.interceptRequest(request, currentMainPageUrl)
 *            ?: super.shouldInterceptRequest(view, request)
 *    }
 *
 *    override fun shouldOverrideUrlLoading(
 *        view: WebView,
 *        request: WebResourceRequest
 *    ): Boolean {
 *        if (AdBlocker.shouldBlockNavigation(request, view.url, lastUserTapTimeMs)) {
 *            return true // Block ad redirect / pop-up navigation
 *        }
 *        return false // Let normal links open inside the same WebView
 *    }
 *
 *    override fun onPageFinished(view: WebView, url: String?) {
 *        if (AdBlocker.isEnabled()) {
 *            view.evaluateJavascript(AdBlocker.cosmeticScript(url), null)
 *        }
 *    }
 *    ```
 * 4. Inside your `WebChromeClient` (to block `window.open` ad pop-ups):
 *    ```kotlin
 *    override fun onCreateWindow(
 *        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
 *    ): Boolean {
 *        val targetUrl = view?.hitTestResult?.extra
 *        if (AdBlocker.shouldAllowPopupToLoadInSameWebView(isUserGesture, targetUrl, view?.url) && !targetUrl.isNullOrBlank()) {
 *            view?.loadUrl(targetUrl)
 *        }
 *        return false
 *    }
 *    ```
 * =========================================================================================
 */
object AdBlocker {

  private const val PREFS_NAME = "adblocker_module_prefs"
  private const val KEY_ENABLED = "ad_block_enabled"

  private val enabledFlag = AtomicBoolean(true)
  private val pageBlockedCounter = AtomicInteger(0)

  private val _enabledFlow = MutableStateFlow(true)
  val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

  private val _blockedCount = MutableStateFlow(0)
  /** Live observable count of blocked requests on the current page. */
  val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

  /** Live observable status of the EasyList + EasyPrivacy filter engine. */
  val filterStatus: StateFlow<FilterStatus> = FilterList.status

  // Standard 43-byte 1x1 transparent GIF for blocked image requests so broken-image icons never appear
  private val TRANSPARENT_1X1_GIF: ByteArray =
    byteArrayOf(
      0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
      0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21,
      0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00,
      0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
      0x01, 0x00, 0x3B
    )

  /**
   * Initializes the AdBlocker, restores the saved ON/OFF setting from SharedPreferences,
   * activates bundled offline rules immediately, and starts the 24-hour background refresh
   * for EasyList & EasyPrivacy.
   */
  fun init(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedEnabled = prefs.getBoolean(KEY_ENABLED, true)
    enabledFlag.set(savedEnabled)
    _enabledFlow.value = savedEnabled
    FilterList.initializeAndRefresh(context.applicationContext)
  }

  /**
   * Enables or disables ad blocking. Optionally persists the setting if [context] is passed.
   */
  fun setEnabled(enabled: Boolean, context: Context? = null) {
    enabledFlag.set(enabled)
    _enabledFlow.value = enabled
    if (context != null) {
      context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
    }
  }

  /**
   * Returns whether the ad blocker is currently enabled.
   */
  fun isEnabled(): Boolean = enabledFlag.get()

  /**
   * Returns the current page's blocked request count.
   */
  fun getBlockedCount(): Int = pageBlockedCounter.get()

  /**
   * Resets the blocked request counter (call in `onPageStarted` for top-level navigation).
   */
  fun resetBlockedCount() {
    pageBlockedCounter.set(0)
    _blockedCount.value = 0
  }

  /**
   * Increments the blocked request counter in a thread-safe manner.
   */
  fun recordBlockedRequest() {
    val updated = pageBlockedCounter.incrementAndGet()
    _blockedCount.value = updated
  }

  /**
   * Core network matching method. Safe to call from any background thread.
   *
   * - Never blocks the main page itself (`isMainFrame == true`).
   * - Checks `@@` exception rules first so whitelisted CDNs and first-party assets never break.
   * - Performs O(labels) hash lookups on the hostname and O(tokens) lookups on the URL path.
   */
  fun shouldBlock(
    url: String,
    isMainFrame: Boolean,
    pageUrlOrHost: String? = null,
    resourceType: ResourceType = inferResourceType(url, isMainFrame, null),
  ): Boolean {
    if (!enabledFlag.get()) return false
    // Rule: Never block the main page itself
    if (isMainFrame) return false

    val trimmed = url.trim()
    if (
      trimmed.isEmpty() ||
        trimmed.startsWith("data:", ignoreCase = true) ||
        trimmed.startsWith("blob:", ignoreCase = true) ||
        trimmed.startsWith("about:", ignoreCase = true) ||
        trimmed.startsWith("javascript:", ignoreCase = true)
    ) {
      return false
    }

    val reqHost = FilterList.extractHost(trimmed)
    if (reqHost.isEmpty()) return false

    val pageHost =
      if (pageUrlOrHost != null && pageUrlOrHost.contains("://")) {
        FilterList.extractHost(pageUrlOrHost)
      } else {
        pageUrlOrHost?.lowercase(Locale.ROOT)?.trimEnd('.') ?: ""
      }

    val isThirdParty = FilterList.isThirdPartyRequest(reqHost, pageHost)
    val pathAndQuery = FilterList.extractPathAndQuery(trimmed)
    val urlLower = trimmed.lowercase(Locale.ROOT)
    val rules = FilterList.currentRules()

    // 1. Check if the page domain itself is exempted via $document / $elemhide
    if (pageHost.isNotEmpty() && FilterList.domainMatchesAny(pageHost, rules.elemhideExceptionDomains)) {
      return false
    }

    // 2. Check pure & conditional domain EXCEPTION rules (@@||domain^) by walking host labels
    if (matchesDomainHierarchy(
        host = reqHost,
        pureDomains = rules.pureExceptionDomains,
        conditionalDomains = rules.conditionalExceptionDomains,
        pathAndQuery = pathAndQuery,
        isThirdParty = isThirdParty,
        resourceType = resourceType,
        pageHost = pageHost,
      )
    ) {
      return false
    }

    // 3. Check token-indexed URL pattern EXCEPTION rules
    val urlTokens = FilterList.extractTokens(pathAndQuery)
    for (i in 0 until urlTokens.size) {
      val bucket = rules.tokenExceptionPatterns[urlTokens[i]] ?: continue
      for (j in 0 until bucket.size) {
        if (bucket[j].matches(urlLower, isThirdParty, resourceType, pageHost)) {
          return false
        }
      }
    }

    // 4. Protect first-party essential functional assets (HTML, CSS, Fonts, Media, Main scripts)
    // unless the path explicitly matches a dedicated ad-serving path.
    if (!isThirdParty && pageHost.isNotEmpty()) {
      return matchesExplicitFirstPartyAdPath(pathAndQuery)
    }

    // 5. Fast O(labels) hash lookup for domain-anchored block rules (||domain^)
    if (matchesDomainHierarchy(
        host = reqHost,
        pureDomains = rules.pureBlockedDomains,
        conditionalDomains = rules.conditionalBlockedDomains,
        pathAndQuery = pathAndQuery,
        isThirdParty = isThirdParty,
        resourceType = resourceType,
        pageHost = pageHost,
      )
    ) {
      return true
    }

    // 6. Fast O(tokens) lookup for URL substring/pattern block rules
    for (i in 0 until urlTokens.size) {
      val bucket = rules.tokenBlockedPatterns[urlTokens[i]] ?: continue
      for (j in 0 until bucket.size) {
        if (bucket[j].matches(urlLower, isThirdParty, resourceType, pageHost)) {
          return true
        }
      }
    }

    return false
  }

  /**
   * Ready-to-use helper for `WebViewClient.shouldInterceptRequest`.
   * Returns a safe empty [WebResourceResponse] when blocked, or `null` to allow the request.
   */
  fun interceptRequest(
    request: WebResourceRequest,
    currentPageUrl: String?,
  ): WebResourceResponse? {
    if (!enabledFlag.get()) return null
    val isMainFrame = request.isForMainFrame
    if (isMainFrame) return null

    val url = request.url?.toString() ?: return null
    val headers = request.requestHeaders
    val accept = headers?.get("Accept") ?: headers?.get("accept")
    val resourceType = inferResourceType(url, isMainFrame = false, acceptHeader = accept)

    // Use Referer header as fallback if currentPageUrl isn't updated yet
    val effectivePageUrl =
      if (!currentPageUrl.isNullOrBlank()) {
        currentPageUrl
      } else {
        headers?.get("Referer") ?: headers?.get("referer")
      }

    if (shouldBlock(url, isMainFrame = false, pageUrlOrHost = effectivePageUrl, resourceType = resourceType)) {
      recordBlockedRequest()
      return createEmptyResponse(resourceType)
    }
    return null
  }

  /**
   * Evaluates whether a navigation inside `WebViewClient.shouldOverrideUrlLoading`
   * is an unwanted ad pop-up or forced redirect to an ad page.
   *
   * - Never blocks user-typed URLs or normal first-party/legitimate link navigation.
   * - Blocks navigations targeting known ad/redirect domains (`$popup` or pure ad domains).
   * - Blocks cross-domain background redirects that occur without a user tap on the page.
   */
  fun shouldBlockNavigation(
    request: WebResourceRequest,
    currentPageUrl: String?,
    lastUserTouchTimeMs: Long,
  ): Boolean {
    if (!enabledFlag.get()) return false
    val targetUrl = request.url?.toString() ?: return false
    val targetHost = FilterList.extractHost(targetUrl)
    if (targetHost.isEmpty()) return false

    val currentHost = FilterList.extractHost(currentPageUrl)
    if (currentHost.isEmpty()) return false

    val isThirdParty = FilterList.isThirdPartyRequest(targetHost, currentHost)
    if (!isThirdParty) return false // Always allow same-site navigation

    val rules = FilterList.currentRules()
    val pathAndQuery = FilterList.extractPathAndQuery(targetUrl)

    // 1. Always block navigation if the destination domain itself is a known ad/popup server
    val isAdDomain =
      matchesDomainHierarchy(
        host = targetHost,
        pureDomains = rules.pureBlockedDomains,
        conditionalDomains = rules.conditionalBlockedDomains,
        pathAndQuery = pathAndQuery,
        isThirdParty = true,
        resourceType = ResourceType.POPUP,
        pageHost = currentHost,
      )
    if (isAdDomain) {
      recordBlockedRequest()
      return true
    }

    // 2. Check if this cross-domain navigation happened without a user gesture
    val hasGesture = request.hasGesture()
    val recentUserTap = (System.currentTimeMillis() - lastUserTouchTimeMs) < 1_800L
    val isSearchEnginePage =
      currentHost.contains("google.") ||
        currentHost.contains("bing.com") ||
        currentHost.contains("duckduckgo.com")

    if (!hasGesture && !recentUserTap && !isSearchEnginePage && request.isForMainFrame) {
      // Unsolicited cross-domain main-frame redirect without user interaction:
      // Block if URL contains suspicious ad/redirect query parameters
      val lowerUrl = targetUrl.lowercase(Locale.ROOT)
      if (
        lowerUrl.contains("clickid=") ||
          lowerUrl.contains("aff_id=") ||
          lowerUrl.contains("zoneid=") ||
          lowerUrl.contains("/popunder") ||
          lowerUrl.contains("/redirect?") ||
          lowerUrl.contains("adurl=")
      ) {
        recordBlockedRequest()
        return true
      }
    }

    return false
  }

  /**
   * Call from `WebChromeClient.onCreateWindow` to handle `window.open` and `target="_blank"`.
   * - Blocks automatic script pop-ups (`!isUserGesture`) and pop-ups targeting ad domains.
   * - Allows legitimate user-tapped `target="_blank"` links so the caller can open them in the
   *   same `WebView`.
   */
  fun shouldAllowPopupToLoadInSameWebView(
    isUserGesture: Boolean,
    targetUrl: String?,
    currentPageUrl: String?,
  ): Boolean {
    if (!enabledFlag.get()) {
      return !targetUrl.isNullOrBlank()
    }
    if (!isUserGesture || targetUrl.isNullOrBlank()) {
      recordBlockedRequest()
      return false
    }
    val blocked =
      shouldBlock(
        url = targetUrl,
        isMainFrame = false,
        pageUrlOrHost = currentPageUrl,
        resourceType = ResourceType.POPUP,
      )
    if (blocked) {
      recordBlockedRequest()
      return false
    }
    return true
  }

  /**
   * Returns the JavaScript snippet to inject in `WebViewClient.onPageFinished` for
   * cosmetic filtering. Hides leftover empty ad spaces using EasyList generic CSS
   * selectors plus domain-specific selectors and a lightweight `MutationObserver`.
   */
  fun cosmeticScript(pageUrl: String? = null): String {
    if (!enabledFlag.get()) return "(function(){})();"

    val rules = FilterList.currentRules()
    val pageHost = FilterList.extractHost(pageUrl)

    if (pageHost.isNotEmpty() && FilterList.domainMatchesAny(pageHost, rules.elemhideExceptionDomains)) {
      return "(function(){})();"
    }

    val selectors = ArrayList<String>(rules.genericCosmeticSelectors.size + 32)
    selectors.addAll(rules.genericCosmeticSelectors)

    // Append domain-specific cosmetic selectors for the current host and its parent domains
    if (pageHost.isNotEmpty()) {
      var current = pageHost
      while (current.isNotEmpty()) {
        rules.domainCosmeticSelectors[current]?.let { selectors.addAll(it) }
        val dot = current.indexOf('.')
        if (dot < 0) break
        current = current.substring(dot + 1)
      }
    }

    val cssRuleString =
      selectors
        .joinToString(", ")
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")

    return """
      (function() {
        try {
          var styleId = '__adblocker_cosmetic_css__';
          if (!document.getElementById(styleId)) {
            var style = document.createElement('style');
            style.id = styleId;
            style.type = 'text/css';
            style.appendChild(document.createTextNode('$cssRuleString { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; max-height: 0 !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; pointer-events: none !important; }'));
            (document.head || document.documentElement).appendChild(style);
          }

          function collapseAdSlots() {
            var nodes = document.querySelectorAll('ins.adsbygoogle, [id^="div-gpt-ad"], [id^="google_ads_iframe"], [data-ad-slot], [data-google-query-id], iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"]');
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              el.style.setProperty('display', 'none', 'important');
              var parent = el.parentElement;
              if (parent && parent.children.length === 1 && parent.innerText.trim().length < 20) {
                parent.style.setProperty('display', 'none', 'important');
              }
            }
          }

          collapseAdSlots();
          if (!window.__adblocker_observer_installed__ && typeof MutationObserver !== 'undefined') {
            window.__adblocker_observer_installed__ = true;
            var scheduled = false;
            var observer = new MutationObserver(function() {
              if (!scheduled) {
                scheduled = true;
                setTimeout(function() {
                  scheduled = false;
                  collapseAdSlots();
                }, 250);
              }
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
          }
        } catch (e) {}
      })();
    """.trimIndent()
  }

  /**
   * Normalizes user input from an address/search bar into a loadable URL.
   * - If the user types a URL or domain (e.g. `theverge.com`, `https://cnn.com`), opens it directly.
   * - Otherwise searches with Google (`https://www.google.com/search?q=...`).
   */
  fun resolveInputToUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "https://www.google.com"

    if (
      trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
      return trimmed
    }

    // Check if it looks like a domain / URL (no spaces, has a dot with a valid TLD)
    if (!trimmed.contains(' ') && trimmed.contains('.')) {
      val slashIdx = trimmed.indexOf('/')
      val hostPart = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
      val tld = hostPart.substringAfterLast('.', "")
      if (hostPart.length >= 4 && tld.length >= 2 && tld.all { it.isLetterOrDigit() }) {
        return "https://$trimmed"
      }
    }

    return "https://www.google.com/search?q=${Uri.encode(trimmed)}"
  }

  private fun matchesDomainHierarchy(
    host: String,
    pureDomains: Set<String>,
    conditionalDomains: Map<String, List<DomainRule>>,
    pathAndQuery: String,
    isThirdParty: Boolean,
    resourceType: ResourceType,
    pageHost: String?,
  ): Boolean {
    var current = host
    while (current.isNotEmpty()) {
      if (pureDomains.contains(current)) {
        return true
      }
      val condList = conditionalDomains[current]
      if (condList != null) {
        for (i in 0 until condList.size) {
          if (condList[i].matches(pathAndQuery, isThirdParty, resourceType, pageHost)) {
            return true
          }
        }
      }
      val dotIndex = current.indexOf('.')
      if (dotIndex < 0) break
      current = current.substring(dotIndex + 1)
    }
    return false
  }

  private fun matchesExplicitFirstPartyAdPath(pathAndQuery: String): Boolean {
    return pathAndQuery.startsWith("/pagead/") ||
      pathAndQuery.startsWith("/adserver/") ||
      pathAndQuery.contains("/adsbygoogle.js") ||
      pathAndQuery.contains("/show_ads.js") ||
      pathAndQuery.contains("/pubads_impl")
  }

  private fun inferResourceType(
    url: String,
    isMainFrame: Boolean,
    acceptHeader: String?,
  ): ResourceType {
    if (isMainFrame) return ResourceType.OTHER
    val lowerAccept = acceptHeader?.lowercase(Locale.ROOT) ?: ""
    if (lowerAccept.contains("text/css")) return ResourceType.STYLESHEET
    if (lowerAccept.contains("image/")) return ResourceType.IMAGE
    if (lowerAccept.contains("script") || lowerAccept.contains("javascript")) return ResourceType.SCRIPT
    if (lowerAccept.contains("text/html") || lowerAccept.contains("application/xhtml")) {
      return ResourceType.SUBDOCUMENT
    }

    val path = FilterList.extractPathAndQuery(url).substringBefore('?')
    return when {
      path.endsWith(".js") || path.endsWith(".mjs") -> ResourceType.SCRIPT
      path.endsWith(".css") -> ResourceType.STYLESHEET
      path.endsWith(".png") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".gif") ||
        path.endsWith(".webp") ||
        path.endsWith(".svg") ||
        path.endsWith(".ico") ||
        path.endsWith(".avif") -> ResourceType.IMAGE
      path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> ResourceType.FONT
      path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") -> ResourceType.MEDIA
      path.endsWith(".html") || path.endsWith(".htm") -> ResourceType.SUBDOCUMENT
      else -> ResourceType.XMLHTTPREQUEST
    }
  }

  private fun createEmptyResponse(resourceType: ResourceType): WebResourceResponse {
    val headers =
      mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store",
      )
    return when (resourceType) {
      ResourceType.IMAGE ->
        WebResourceResponse(
          "image/gif",
          null,
          200,
          "OK",
          headers,
          ByteArrayInputStream(TRANSPARENT_1X1_GIF),
        )
      ResourceType.SCRIPT ->
        WebResourceResponse(
          "application/javascript",
          "UTF-8",
          200,
          "OK",
          headers,
          ByteArrayInputStream("/* blocked */".toByteArray(Charsets.UTF_8)),
        )
      ResourceType.STYLESHEET ->
        WebResourceResponse(
          "text/css",
          "UTF-8",
          200,
          "OK",
          headers,
          ByteArrayInputStream("".toByteArray(Charsets.UTF_8)),
        )
      ResourceType.SUBDOCUMENT ->
        WebResourceResponse(
          "text/html",
          "UTF-8",
          200,
          "OK",
          headers,
          ByteArrayInputStream("<!DOCTYPE html><html><head></head><body></body></html>".toByteArray(Charsets.UTF_8)),
        )
      else ->
        WebResourceResponse(
          "text/plain",
          "UTF-8",
          200,
          "OK",
          headers,
          ByteArrayInputStream(ByteArray(0)),
        )
    }
  }
}
