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
 * Clean, self-contained, thread-safe Stealth Ad & Tracker Blocker module for Android `WebView`.
 * Includes Brave/uBlock-style Surrogate Scripts, VAST Video Ad Neutralization, and Anti-Adblock
 * Defuser Scriptlets so video players and websites never detect the ad blocker.
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
 *        // Runs on a WebView background thread — fast, lock-free, returns stealth surrogates!
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
 *    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
 *        if (AdBlocker.isEnabled()) {
 *            view.evaluateJavascript(AdBlocker.cosmeticScript(url), null)
 *        }
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

  // Valid Empty VAST 3.0 XML response so video players think 0 ads are scheduled and play immediately
  private val EMPTY_VAST_XML_BYTES: ByteArray =
    """<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>""".toByteArray(Charsets.UTF_8)

  // Universal Ad-SDK & Anti-Adblock Surrogate JS returned for intercepted ad scripts.
  // Prevents "Please disable your ad blocker" errors when sites check if ad scripts loaded globals.
  private val SURROGATE_AD_SDK_BYTES: ByteArray =
    """
    (function() {
      try {
        window.canRunAds = true;
        window.canShowAds = true;
        window.isAdBlockActive = false;
        window.adblock = false;
        window.adBlockEnabled = false;
        window.hasAdblock = false;
        window.__adblock = false;
        window.adBlocker = false;
        window.google_ad_modifications = true;
        window.google_ad_status = 1;

        var noop = function() {};
        var noopThis = function() { return this; };

        if (!window.adsbygoogle || !window.adsbygoogle.loaded) {
          window.adsbygoogle = window.adsbygoogle || [];
          window.adsbygoogle.loaded = true;
          window.adsbygoogle.push = function() { return 1; };
        }

        if (!window.googletag) {
          var slotStub = {
            addService: noopThis,
            clearCategoryExclusions: noopThis,
            clearTargeting: noopThis,
            defineSizeMapping: noopThis,
            get: function() { return null; },
            getAdUnitPath: function() { return ""; },
            getAttributeKeys: function() { return []; },
            getCategoryExclusions: function() { return []; },
            getDomId: function() { return ""; },
            getSlotElementId: function() { return ""; },
            getTargeting: function() { return []; },
            getTargetingKeys: function() { return []; },
            set: noopThis,
            setCategoryExclusion: noopThis,
            setClickUrl: noopThis,
            setCollapseEmptyDiv: noopThis,
            setSafeFrameConfig: noopThis,
            setTargeting: noopThis
          };
          var pubadsStub = {
            addEventListener: noopThis,
            clear: noop,
            clearCategoryExclusions: noopThis,
            clearTagForChildDirectedTreatment: noopThis,
            clearTargeting: noopThis,
            collapseEmptyDivs: noop,
            defineOutOfPagePassback: function() { return slotStub; },
            definePassback: function() { return slotStub; },
            disableInitialLoad: noop,
            display: noop,
            enableAsyncRendering: noop,
            enableSingleRequest: noop,
            enableSyncRendering: noop,
            enableVideoAds: noop,
            get: function() { return null; },
            getAttributeKeys: function() { return []; },
            getSlots: function() { return []; },
            getTargeting: function() { return []; },
            getTargetingKeys: function() { return []; },
            isInitialLoadDisabled: function() { return true; },
            refresh: noop,
            removeEventListener: noopThis,
            set: noopThis,
            setCategoryExclusion: noopThis,
            setCentering: noop,
            setCookieOptions: noopThis,
            setForceSafeFrame: noopThis,
            setLocation: noopThis,
            setPublisherProvidedId: noopThis,
            setRequestNonPersonalizedAds: noopThis,
            setSafeFrameConfig: noopThis,
            setTagForChildDirectedTreatment: noopThis,
            setTargeting: noopThis,
            setVideoContent: noopThis,
            updateCorrelator: noop
          };
          window.googletag = {
            apiReady: true,
            pubadsReady: true,
            cmd: {
              push: function(fn) {
                if (typeof fn === 'function') {
                  try { fn(); } catch (e) {}
                }
                return 1;
              }
            },
            companionAds: function() { return pubadsStub; },
            content: function() { return pubadsStub; },
            defineOutOfPageSlot: function() { return slotStub; },
            defineSlot: function() { return slotStub; },
            destroySlots: function() { return true; },
            disablePublisherConsole: noop,
            display: noop,
            enableServices: noop,
            getVersion: function() { return ""; },
            pubads: function() { return pubadsStub; },
            setAdIframeTitle: noop,
            sizeMapping: function() {
              return { addSize: noopThis, build: function() { return []; } };
            }
          };
        }

        // Google IMA HTML5 Video SDK Surrogate (auto-completes pre-roll so video plays immediately)
        window.google = window.google || {};
        if (!window.google.ima) {
          var AdsManagerStub = function() {
            this._listeners = {};
            this.addEventListener = function(type, fn) { this._listeners[type] = fn; };
            this.removeEventListener = noop;
            this.init = noop;
            this.start = function() {
              var self = this;
              setTimeout(function() {
                if (self._listeners['contentResumeRequested']) {
                  try { self._listeners['contentResumeRequested']({ getAd: function(){ return null; } }); } catch(e){}
                }
                if (self._listeners['allAdsCompleted']) {
                  try { self._listeners['allAdsCompleted']({ getAd: function(){ return null; } }); } catch(e){}
                }
              }, 10);
            };
            this.resize = noop;
            this.destroy = noop;
            this.pause = noop;
            this.resume = noop;
            this.skip = noop;
            this.setVolume = noop;
            this.getVolume = function() { return 0; };
            this.getRemainingTime = function() { return 0; };
          };
          window.google.ima = {
            AdDisplayContainer: function() { this.initialize = noop; this.destroy = noop; },
            AdsLoader: function() {
              this._listeners = {};
              this.addEventListener = function(type, fn) { this._listeners[type] = fn; };
              this.removeEventListener = noop;
              this.contentComplete = noop;
              this.destroy = noop;
              this.getSettings = function() {
                return { setVpaidMode: noop, setLocale: noop, setDisableCustomPlaybackForIOS10Plus: noop, setAutoPlayAdBreaks: noop, setNumRedirects: noop };
              };
              this.requestAds = function() {
                var self = this;
                setTimeout(function() {
                  var fn = self._listeners['adsManagerLoaded'];
                  if (typeof fn === 'function') {
                    try {
                      fn({
                        getAdsManager: function() { return new AdsManagerStub(); }
                      });
                    } catch (e) {}
                  }
                }, 10);
              };
            },
            AdsManagerLoadedEvent: { Type: { ADS_MANAGER_LOADED: 'adsManagerLoaded' } },
            AdErrorEvent: { Type: { AD_ERROR: 'adError' } },
            AdEvent: {
              Type: {
                CONTENT_PAUSE_REQUESTED: 'contentPauseRequested',
                CONTENT_RESUME_REQUESTED: 'contentResumeRequested',
                ALL_ADS_COMPLETED: 'allAdsCompleted',
                COMPLETE: 'complete',
                LOADED: 'loaded',
                STARTED: 'started',
                SKIPPED: 'skipped'
              }
            },
            AdsRenderingSettings: function() {},
            AdsRequest: function() {},
            ViewMode: { NORMAL: 'normal', FULLSCREEN: 'fullscreen' },
            ImaSdkSettings: { VpaidMode: { ENABLED: 1, INSECURE: 2, DISABLED: 0 } },
            settings: { setVpaidMode: noop, setLocale: noop, setDisableCustomPlaybackForIOS10Plus: noop }
          };
        }

        // BlockAdBlock / FuckAdBlock / ExoClick / AdSpyglass Surrogates
        var FakeDetector = function() {
          this.check = function() { return false; };
          this.emitEvent = function() { return this; };
          this.clearEvent = noop;
          this.on = function(detected, fn) {
            if (!detected && typeof fn === 'function') { try { fn(); } catch(e){} }
            return this;
          };
          this.onDetected = function() { return this; };
          this.onNotDetected = function(fn) {
            if (typeof fn === 'function') { try { fn(); } catch(e){} }
            return this;
          };
          this.setOption = function() { return this; };
        };
        window.BlockAdBlock = window.FuckAdBlock = FakeDetector;
        window.blockAdBlock = window.fuckAdBlock = new FakeDetector();
        window.ExoLoader = { addZone: noop, serve: noop, push: noop };
        window.adsbyexoclick = window.adsbyexoclick || [];
        window.adsbyjuicy = window.adsbyjuicy || [];
        window.AdSpyglass = window.AdSpyglass || {};
      } catch (e) {}
    })();
    """.trimIndent().toByteArray(Charsets.UTF_8)

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
    // unless the path explicitly matches a dedicated ad-serving or anti-adblock path.
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
   * Returns a stealth surrogate [WebResourceResponse] when blocked, or `null` to allow the request.
   */
  fun interceptRequest(
    request: WebResourceRequest,
    currentPageUrl: String?,
  ): WebResourceResponse? {
    if (!enabledFlag.get()) return null
    if (request.isForMainFrame) return null

    val url = request.url?.toString() ?: return null
    val headers = request.requestHeaders
    val accept = headers?.get("Accept") ?: headers?.get("accept")
    val origin = headers?.get("Origin") ?: headers?.get("origin")
    val resourceType = inferResourceType(url, isMainFrame = false, acceptHeader = accept)

    val effectivePageUrl =
      if (!currentPageUrl.isNullOrBlank()) {
        currentPageUrl
      } else {
        headers?.get("Referer") ?: headers?.get("referer")
      }

    if (shouldBlock(url, isMainFrame = false, pageUrlOrHost = effectivePageUrl, resourceType = resourceType)) {
      recordBlockedRequest()
      return createSurrogateResponse(url, resourceType, accept, origin)
    }
    return null
  }

  /**
   * Evaluates whether a navigation inside `WebViewClient.shouldOverrideUrlLoading`
   * is an unwanted ad pop-up or forced redirect to an ad page.
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

    // 2. Block suspicious affiliate / pop-under / AI generator promo redirects even on click
    val lowerUrl = targetUrl.lowercase(Locale.ROOT)
    if (
      lowerUrl.contains("clickid=") ||
        lowerUrl.contains("aff_id=") ||
        lowerUrl.contains("zoneid=") ||
        lowerUrl.contains("campaign_id=") ||
        lowerUrl.contains("banner_id=") ||
        lowerUrl.contains("/popunder") ||
        lowerUrl.contains("adurl=") ||
        lowerUrl.contains("exoclick") ||
        lowerUrl.contains("tsyndicate") ||
        lowerUrl.contains("juicyads") ||
        lowerUrl.contains("magsrv") ||
        lowerUrl.contains("realsrv")
    ) {
      recordBlockedRequest()
      return true
    }

    // 3. Block unsolicited cross-domain main-frame redirects without user interaction
    val hasGesture = request.hasGesture()
    val recentUserTap = (System.currentTimeMillis() - lastUserTouchTimeMs) < 1_800L
    val isSearchEnginePage =
      currentHost.contains("google.") ||
        currentHost.contains("bing.com") ||
        currentHost.contains("duckduckgo.com")

    if (!hasGesture && !recentUserTap && !isSearchEnginePage && request.isForMainFrame) {
      recordBlockedRequest()
      return true
    }

    return false
  }

  /**
   * Call from `WebChromeClient.onCreateWindow` to handle `window.open` and `target="_blank"`.
   * - Blocks automatic script pop-ups (`!isUserGesture`) and pop-ups targeting ad/affiliate domains.
   * - Allows legitimate user-tapped same-domain links so the caller can open them in the same `WebView`.
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
    val targetHost = FilterList.extractHost(targetUrl)
    val currentHost = FilterList.extractHost(currentPageUrl)
    val isThirdParty = FilterList.isThirdPartyRequest(targetHost, currentHost)
    val isSearchEngine =
      currentHost.contains("google.") ||
        currentHost.contains("bing.com") ||
        currentHost.contains("duckduckgo.com")

    // On non-search sites (like video streaming sites), pop-ups opening a third-party domain via
    // window.open / target="_blank" when tapping the video player are almost always pop-under ads!
    if (isThirdParty && !isSearchEngine) {
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
   * Returns the Brave/uBlock-style Stealth Anti-Adblock Defuser + Cosmetic Filtering script.
   *
   * 1. Spoofs `offsetHeight`, `offsetWidth`, `clientHeight`, `clientWidth`, `getBoundingClientRect`,
   *    and `getComputedStyle` on bait elements (`.adsbox`, `.ad-banner`, `ins.adsbygoogle`, etc.)
   *    so anti-adblock scripts think ads rendered with `300x250` dimensions!
   * 2. Wraps `window.fetch` and `XMLHttpRequest` so network bait checks to ad servers resolve
   *    cleanly with HTTP 200 OK instead of triggering `.catch()` anti-adblock modals.
   * 3. Automatically dismisses/removes any "Please disable your ad blocker" overlay and restores
   *    hidden/paused `<video>` or player `<iframe>` elements.
   * 4. Collapses leftover ad slots, third-party affiliate promo banners (like "Generate ↗" ad cards),
   *    and invisible click-hijack overlays.
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
          // --- PART 1: STEALTH ANTI-ADBLOCK GLOBALS & BAIT SPOOFING (Runs once per document) ---
          if (!window.__stealth_adblock_shield__) {
            window.__stealth_adblock_shield__ = true;

            window.canRunAds = true;
            window.canShowAds = true;
            window.isAdBlockActive = false;
            window.adblock = false;
            window.adBlockEnabled = false;
            window.hasAdblock = false;
            window.__adblock = false;
            window.adBlocker = false;
            window.google_ad_modifications = true;

            if (!window.adsbygoogle || !window.adsbygoogle.loaded) {
              window.adsbygoogle = window.adsbygoogle || [];
              window.adsbygoogle.loaded = true;
              window.adsbygoogle.push = function() { return 1; };
            }

            // Defuse window.open pop-unders while returning a truthy dummy window so scripts don't error
            window.open = function() {
              return {
                closed: false,
                focus: function() {},
                blur: function() {},
                close: function() {},
                postMessage: function() {},
                location: { href: 'about:blank', assign: function(){}, replace: function(){} }
              };
            };

            // Identify whether an element is an anti-adblock bait element
            function isBaitElement(el) {
              if (!el || !el.tagName) return false;
              var cls = (typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '');
              if (!cls) return false;
              var lower = cls.toLowerCase();
              return (
                lower.indexOf('adsbox') !== -1 ||
                lower.indexOf('ad-banner') !== -1 ||
                lower.indexOf('ad_banner') !== -1 ||
                lower.indexOf('ad-placeholder') !== -1 ||
                lower.indexOf('ad-tester') !== -1 ||
                lower.indexOf('ad_tester') !== -1 ||
                lower.indexOf('adblock-test') !== -1 ||
                lower.indexOf('pub_300x250') !== -1 ||
                lower.indexOf('text-ad') !== -1 ||
                lower.indexOf('banner-ad') !== -1 ||
                lower.indexOf('adsbygoogle') !== -1
              );
            }

            // Spoof offsetHeight / offsetWidth / clientHeight / clientWidth for bait elements
            var origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
            var origOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
            var origClientHeight = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
            var origClientWidth = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');

            if (origOffsetHeight && origOffsetHeight.get) {
              Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
                configurable: true,
                get: function() {
                  if (isBaitElement(this)) return 250;
                  return origOffsetHeight.get.call(this);
                }
              });
            }
            if (origOffsetWidth && origOffsetWidth.get) {
              Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
                configurable: true,
                get: function() {
                  if (isBaitElement(this)) return 300;
                  return origOffsetWidth.get.call(this);
                }
              });
            }
            if (origClientHeight && origClientHeight.get) {
              Object.defineProperty(Element.prototype, 'clientHeight', {
                configurable: true,
                get: function() {
                  if (isBaitElement(this)) return 250;
                  return origClientHeight.get.call(this);
                }
              });
            }
            if (origClientWidth && origClientWidth.get) {
              Object.defineProperty(Element.prototype, 'clientWidth', {
                configurable: true,
                get: function() {
                  if (isBaitElement(this)) return 300;
                  return origClientWidth.get.call(this);
                }
              });
            }

            var origGetBCR = Element.prototype.getBoundingClientRect;
            if (origGetBCR) {
              Element.prototype.getBoundingClientRect = function() {
                var rect = origGetBCR.call(this);
                if (isBaitElement(this) && (rect.height === 0 || rect.width === 0)) {
                  return {
                    top: rect.top || 10,
                    left: rect.left || 10,
                    bottom: (rect.top || 10) + 250,
                    right: (rect.left || 10) + 300,
                    width: 300,
                    height: 250,
                    x: rect.x || 10,
                    y: rect.y || 10,
                    toJSON: function() { return this; }
                  };
                }
                return rect;
              };
            }

            var origGetComputedStyle = window.getComputedStyle;
            if (origGetComputedStyle) {
              window.getComputedStyle = function(el, pseudo) {
                var style = origGetComputedStyle.call(window, el, pseudo);
                if (el && isBaitElement(el)) {
                  return new Proxy(style, {
                    get: function(target, prop) {
                      if (prop === 'display') return 'block';
                      if (prop === 'visibility') return 'visible';
                      if (prop === 'opacity') return '1';
                      if (prop === 'height') return '250px';
                      if (prop === 'width') return '300px';
                      if (prop === 'getPropertyValue') {
                        return function(name) {
                          if (name === 'display') return 'block';
                          if (name === 'visibility') return 'visible';
                          if (name === 'opacity') return '1';
                          if (name === 'height') return '250px';
                          if (name === 'width') return '300px';
                          return target.getPropertyValue(name);
                        };
                      }
                      var val = target[prop];
                      return typeof val === 'function' ? val.bind(target) : val;
                    }
                  });
                }
                return style;
              };
            }

            // Shield fetch() from rejecting when a page probes an ad script or bait URL
            function isAdBaitUrl(u) {
              if (!u || typeof u !== 'string') return false;
              var l = u.toLowerCase();
              return (
                l.indexOf('adsbygoogle') !== -1 ||
                l.indexOf('doubleclick.net') !== -1 ||
                l.indexOf('googlesyndication') !== -1 ||
                l.indexOf('pagead2') !== -1 ||
                l.indexOf('exoclick') !== -1 ||
                l.indexOf('exosrv') !== -1 ||
                l.indexOf('magsrv') !== -1 ||
                l.indexOf('realsrv') !== -1 ||
                l.indexOf('pemsrv') !== -1 ||
                l.indexOf('tsyndicate') !== -1 ||
                l.indexOf('adspyglass') !== -1 ||
                l.indexOf('juicyads') !== -1 ||
                l.indexOf('jads.co') !== -1 ||
                l.indexOf('a-ads.com') !== -1 ||
                l.indexOf('bidgear') !== -1 ||
                l.indexOf('fuckadblock') !== -1 ||
                l.indexOf('blockadblock') !== -1 ||
                l.indexOf('/ads.js') !== -1 ||
                l.indexOf('/adframe.js') !== -1 ||
                l.indexOf('html-load.com') !== -1 ||
                l.indexOf('getadmiral.com') !== -1
              );
            }

            if (window.fetch) {
              var origFetch = window.fetch;
              window.fetch = function(input, init) {
                var urlStr = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                if (isAdBaitUrl(urlStr)) {
                  return Promise.resolve(
                    new Response('window.canRunAds=true;', {
                      status: 200,
                      statusText: 'OK',
                      headers: { 'Content-Type': 'application/javascript' }
                    })
                  );
                }
                return origFetch.apply(this, arguments).catch(function(err) {
                  if (isAdBaitUrl(urlStr)) {
                    return new Response('{}', { status: 200, statusText: 'OK' });
                  }
                  throw err;
                });
              };
            }
          }

          // --- PART 2: SAFE COSMETIC CSS INJECTION (Uses random style ID so scripts can't detect it) ---
          var styleAttr = 'data-v-shield-style';
          if (document.head && !document.head.querySelector('style[' + styleAttr + ']')) {
            var style = document.createElement('style');
            style.setAttribute(styleAttr, '1');
            style.type = 'text/css';
            style.appendChild(document.createTextNode('$cssRuleString { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; max-height: 0 !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; pointer-events: none !important; }'));
            document.head.appendChild(style);
          }

          // --- PART 3: ACTIVE ANTI-ADBLOCK OVERLAY KILLER & NATIVE PROMO CLEANER ---
          function cleanAntiAdblockAndPromos() {
            // 1. Detect and neutralize "Please disable your ad blocker" warning overlays
            var candidates = document.querySelectorAll('div, section, aside, dialog');
            for (var i = 0; i < candidates.length; i++) {
              var el = candidates[i];
              if (el.children.length > 12) continue;
              var txt = (el.innerText || '').trim().toLowerCase();
              if (!txt || txt.length > 350) continue;

              if (
                txt.indexOf('please disable your ad blocker') !== -1 ||
                txt.indexOf('disable your ad blocker for this site') !== -1 ||
                txt.indexOf('advertising supports free playback') !== -1 ||
                txt.indexOf('adblock detected') !== -1 ||
                txt.indexOf('ad blocker detected') !== -1 ||
                txt.indexOf('turn off your ad blocker') !== -1
              ) {
                // Walk up to the overlay container (but never remove the main player wrapper or body)
                var target = el;
                while (
                  target.parentElement &&
                  target.parentElement !== document.body &&
                  target.parentElement.tagName !== 'MAIN' &&
                  !target.parentElement.querySelector('video') &&
                  (target.parentElement.innerText || '').trim().length < 400
                ) {
                  target = target.parentElement;
                }
                target.style.setProperty('display', 'none', 'important');
                target.style.setProperty('visibility', 'hidden', 'important');
                target.style.setProperty('pointer-events', 'none', 'important');
                target.style.setProperty('z-index', '-9999', 'important');

                // Restore any blurred or hidden video / iframe inside the player container
                var videos = document.querySelectorAll('video, iframe');
                for (var v = 0; v < videos.length; v++) {
                  var media = videos[v];
                  var mSrc = (media.src || '').toLowerCase();
                  if (mSrc.indexOf('doubleclick') === -1 && mSrc.indexOf('exoclick') === -1 && mSrc.indexOf('magsrv') === -1) {
                    media.style.removeProperty('display');
                    media.style.removeProperty('visibility');
                    media.style.setProperty('filter', 'none', 'important');
                    media.style.setProperty('opacity', '1', 'important');
                  }
                }
                if (document.body) {
                  document.body.style.removeProperty('overflow');
                  document.body.style.removeProperty('position');
                }
              }
            }

            // 2. Remove in-page affiliate promo banners (e.g., "AI Image Generator ... Generate ↗" cards & third-party ad cards)
            var links = document.querySelectorAll('a[target="_blank"], div[onclick], section, aside, ins');
            for (var j = 0; j < links.length; j++) {
              var node = links[j];
              var href = (node.getAttribute && (node.getAttribute('href') || node.getAttribute('data-url') || '')) || '';
              var lowerHref = href.toLowerCase();
              var nodeText = (node.innerText || '').trim().toLowerCase();

              var isAffiliateBanner =
                lowerHref.indexOf('exoclick') !== -1 ||
                lowerHref.indexOf('tsyndicate') !== -1 ||
                lowerHref.indexOf('magsrv') !== -1 ||
                lowerHref.indexOf('realsrv') !== -1 ||
                lowerHref.indexOf('juicyads') !== -1 ||
                lowerHref.indexOf('jads.co') !== -1 ||
                lowerHref.indexOf('trafficjunky') !== -1 ||
                lowerHref.indexOf('adspyglass') !== -1 ||
                lowerHref.indexOf('clickid=') !== -1 ||
                lowerHref.indexOf('aff_id=') !== -1 ||
                lowerHref.indexOf('zoneid=') !== -1 ||
                lowerHref.indexOf('utm_medium=banner') !== -1 ||
                (nodeText.indexOf('ai image generator') !== -1 && nodeText.indexOf('generate') !== -1) ||
                (nodeText.indexOf('sponsored') !== -1 && nodeText.length < 180);

              if (isAffiliateBanner) {
                var card = node;
                if (
                  card.parentElement &&
                  card.parentElement !== document.body &&
                  (card.parentElement.innerText || '').trim().length < 220 &&
                  !card.parentElement.querySelector('video')
                ) {
                  card = card.parentElement;
                }
                card.style.setProperty('display', 'none', 'important');
              }
            }

            // 3. Remove invisible high-z-index click-hijack overlays placed over video players
            var divs = document.querySelectorAll('div, a');
            for (var k = 0; k < divs.length; k++) {
              var d = divs[k];
              var st = d.style;
              if (!st) continue;
              var z = parseInt(st.zIndex || '0', 10);
              if (z > 900 && d.children.length === 0 && (d.innerText || '').trim() === '') {
                var rect = d.getBoundingClientRect();
                if (rect.width > 200 && rect.height > 150) {
                  d.style.setProperty('display', 'none', 'important');
                  d.style.setProperty('pointer-events', 'none', 'important');
                }
              }
            }

            // 4. Auto-click video ad skip buttons if any player renders an in-stream skip control
            var skipBtns = document.querySelectorAll('.jw-skip, .vjs-skip-button, .videoAdUiSkipButton, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, [class*="skip-ad"], [id*="skip-ad"]');
            for (var s = 0; s < skipBtns.length; s++) {
              try { skipBtns[s].click(); } catch (e) {}
            }
          }

          cleanAntiAdblockAndPromos();

          if (!window.__adblocker_observer_installed__ && typeof MutationObserver !== 'undefined') {
            window.__adblocker_observer_installed__ = true;
            var scheduled = false;
            var observer = new MutationObserver(function() {
              if (!scheduled) {
                scheduled = true;
                setTimeout(function() {
                  scheduled = false;
                  cleanAntiAdblockAndPromos();
                }, 180);
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
      pathAndQuery.contains("/pubads_impl") ||
      pathAndQuery.contains("/fuckadblock") ||
      pathAndQuery.contains("/blockadblock") ||
      pathAndQuery.contains("/adblock-detector")
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

  /**
   * Returns a stealth surrogate response tailored to the request type so sites and video players
   * never detect that the ad request was blocked.
   */
  private fun createSurrogateResponse(
    url: String,
    resourceType: ResourceType,
    acceptHeader: String?,
    originHeader: String?,
  ): WebResourceResponse {
    val corsOrigin = if (!originHeader.isNullOrBlank()) originHeader else "*"
    val headers =
      mapOf(
        "Access-Control-Allow-Origin" to corsOrigin,
        "Access-Control-Allow-Credentials" to "true",
        "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, OPTIONS, HEAD",
        "Access-Control-Allow-Headers" to "*",
        "Cache-Control" to "no-store",
      )

    val lowerUrl = url.lowercase(Locale.ROOT)
    val lowerAccept = acceptHeader?.lowercase(Locale.ROOT) ?: ""

    // 1. VAST / VMAP Video Ad XML Request -> Return valid empty VAST 3.0 XML so video plays immediately
    if (
      lowerUrl.contains("vast") ||
        lowerUrl.contains("vmap") ||
        lowerUrl.contains("preroll") ||
        lowerUrl.contains("midroll") ||
        lowerUrl.endsWith(".xml") ||
        lowerAccept.contains("application/xml") ||
        lowerAccept.contains("text/xml")
    ) {
      return WebResourceResponse(
        "application/xml",
        "UTF-8",
        200,
        "OK",
        headers,
        ByteArrayInputStream(EMPTY_VAST_XML_BYTES),
      )
    }

    // 2. JSON Ad Config / Bid Request -> Return valid empty JSON object `{}` so response.json() succeeds
    if (
      lowerAccept.contains("application/json") ||
        lowerUrl.endsWith(".json") ||
        lowerUrl.contains("format=json")
    ) {
      return WebResourceResponse(
        "application/json",
        "UTF-8",
        200,
        "OK",
        headers,
        ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)),
      )
    }

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
          ByteArrayInputStream(SURROGATE_AD_SDK_BYTES),
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
          "application/json",
          "UTF-8",
          200,
          "OK",
          headers,
          ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)),
        )
    }
  }
}
