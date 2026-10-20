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
 *
 * Features:
 * 1. Network-level O(1) hash blocking with Stealth Surrogates (Google IMA, GPT, AdSense, Prebid,
 *    ExoClick, BlockAdBlock/FuckAdBlock, and Empty VAST 3.0 XML for pre-roll/mid-roll video ads).
 * 2. Complete White-Box & Empty-Slot Elimination: Blocked iframes return a zero-height transparent
 *    self-collapsing HTML payload, and cosmetic JS collapses empty ad wrappers and blank banner
 *    containers so no white boxes or empty gaps ever remain on screen.
 * 3. Universal HTML5 Video Ad Neutralizer: Strips VAST/ad configs from FluidPlayer, JWPlayer, and
 *    Video.js, and auto-fast-forwards/skips any in-stream video ads.
 * 4. Anti-Adblock Bait Spoofing: Spoofs `offsetHeight`, `offsetWidth`, `clientHeight`, `clientWidth`,
 *    `getBoundingClientRect`, `getComputedStyle`, and `fetch` bait probes so websites never detect
 *    that their ad slots were collapsed.
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

  // Standard 43-byte 1x1 transparent GIF for blocked image requests
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

  // Transparent, zero-height, self-collapsing HTML for blocked iframes so white boxes NEVER appear!
  private val TRANSPARENT_COLLAPSING_IFRAME_HTML_BYTES: ByteArray =
    """<!DOCTYPE html><html style="background:transparent!important;background-color:transparent!important;display:none!important;height:0!important;width:0!important;overflow:hidden!important;"><head><meta name="color-scheme" content="dark light"><style>html,body{background:transparent!important;background-color:transparent!important;margin:0!important;padding:0!important;height:0!important;width:0!important;display:none!important;overflow:hidden!important;border:0!important;}</style><script>try{if(window.frameElement){var f=window.frameElement;f.style.setProperty('display','none','important');f.style.setProperty('height','0px','important');f.style.setProperty('min-height','0px','important');var p=f.parentElement;if(p&&!p.querySelector('video')&&(p.innerText||'').trim().length<25){p.style.setProperty('display','none','important');p.style.setProperty('height','0px','important');p.style.setProperty('min-height','0px','important');p.style.setProperty('margin','0px','important');p.style.setProperty('padding','0px','important');}}}catch(e){}</script></head><body></body></html>""".toByteArray(Charsets.UTF_8)

  // Universal Ad-SDK & Anti-Adblock Surrogate JS returned for intercepted ad scripts.
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

        // BlockAdBlock / FuckAdBlock / ExoClick / AdSpyglass / Prebid Surrogates
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
        window.pbjs = window.pbjs || { que: [], cmd: [], addAdUnits: noop, requestBids: function(o){ if(o && o.bidsBackHandler) try{ o.bidsBackHandler(); }catch(e){} }, setConfig: noop };
      } catch (e) {}
    })();
    """.trimIndent().toByteArray(Charsets.UTF_8)

  fun init(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedEnabled = prefs.getBoolean(KEY_ENABLED, true)
    enabledFlag.set(savedEnabled)
    _enabledFlow.value = savedEnabled
    FilterList.initializeAndRefresh(context.applicationContext)
  }

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

  fun isEnabled(): Boolean = enabledFlag.get()

  fun getBlockedCount(): Int = pageBlockedCounter.get()

  fun resetBlockedCount() {
    pageBlockedCounter.set(0)
    _blockedCount.value = 0
  }

  fun recordBlockedRequest() {
    val updated = pageBlockedCounter.incrementAndGet()
    _blockedCount.value = updated
  }

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

    if (pageHost.isNotEmpty() && FilterList.domainMatchesAny(pageHost, rules.elemhideExceptionDomains)) {
      return false
    }

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

    val urlTokens = FilterList.extractTokens(pathAndQuery)
    for (i in 0 until urlTokens.size) {
      val bucket = rules.tokenExceptionPatterns[urlTokens[i]] ?: continue
      for (j in 0 until bucket.size) {
        if (bucket[j].matches(urlLower, isThirdParty, resourceType, pageHost)) {
          return false
        }
      }
    }

    if (!isThirdParty && pageHost.isNotEmpty()) {
      return matchesExplicitFirstPartyAdPath(pathAndQuery)
    }

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
    val secFetchDest = headers?.get("Sec-Fetch-Dest") ?: headers?.get("sec-fetch-dest")
    val resourceType = inferResourceType(url, isMainFrame = false, acceptHeader = accept, secFetchDest = secFetchDest)

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
    if (!isThirdParty) return false

    val rules = FilterList.currentRules()
    val pathAndQuery = FilterList.extractPathAndQuery(targetUrl)

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
   * Returns the Ultimate Stealth Anti-Adblock + White-Box Eliminator + Video Ad Neutralizer script.
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
          // --- PART 1: STEALTH ANTI-ADBLOCK GLOBALS, VIDEO PLAYER HOOKS & BAIT SPOOFING ---
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

            // Strip VAST / Ad options from FluidPlayer & JWPlayer before initialization
            var origFluidPlayer = window.fluidPlayer;
            Object.defineProperty(window, 'fluidPlayer', {
              configurable: true,
              get: function() {
                if (!origFluidPlayer) return undefined;
                return function(id, options) {
                  if (options && options.vastOptions) {
                    delete options.vastOptions;
                  }
                  return origFluidPlayer.apply(this, arguments);
                };
              },
              set: function(fn) {
                origFluidPlayer = fn;
              }
            });

            function isBaitOrAdElement(el) {
              if (!el || !el.tagName) return false;
              var cls = (typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '');
              if (!cls) return false;
              var lower = cls.toLowerCase();
              return (
                lower.indexOf('adsbox') !== -1 ||
                lower.indexOf('ad-banner') !== -1 ||
                lower.indexOf('ad_banner') !== -1 ||
                lower.indexOf('ad-placeholder') !== -1 ||
                lower.indexOf('ad-slot') !== -1 ||
                lower.indexOf('ad-unit') !== -1 ||
                lower.indexOf('ad-container') !== -1 ||
                lower.indexOf('ad-wrapper') !== -1 ||
                lower.indexOf('ad-tester') !== -1 ||
                lower.indexOf('ad_tester') !== -1 ||
                lower.indexOf('adblock') !== -1 ||
                lower.indexOf('pub_300x250') !== -1 ||
                lower.indexOf('text-ad') !== -1 ||
                lower.indexOf('banner-ad') !== -1 ||
                lower.indexOf('adsbygoogle') !== -1
              );
            }

            // Spoof offsetHeight / offsetWidth / clientHeight / clientWidth so CSS hiding is undetectable!
            var origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
            var origOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
            var origClientHeight = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
            var origClientWidth = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');

            if (origOffsetHeight && origOffsetHeight.get) {
              Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
                configurable: true,
                get: function() {
                  var h = origOffsetHeight.get.call(this);
                  if (h === 0 && isBaitOrAdElement(this)) return 250;
                  return h;
                }
              });
            }
            if (origOffsetWidth && origOffsetWidth.get) {
              Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
                configurable: true,
                get: function() {
                  var w = origOffsetWidth.get.call(this);
                  if (w === 0 && isBaitOrAdElement(this)) return 300;
                  return w;
                }
              });
            }
            if (origClientHeight && origClientHeight.get) {
              Object.defineProperty(Element.prototype, 'clientHeight', {
                configurable: true,
                get: function() {
                  var h = origClientHeight.get.call(this);
                  if (h === 0 && isBaitOrAdElement(this)) return 250;
                  return h;
                }
              });
            }
            if (origClientWidth && origClientWidth.get) {
              Object.defineProperty(Element.prototype, 'clientWidth', {
                configurable: true,
                get: function() {
                  var w = origClientWidth.get.call(this);
                  if (w === 0 && isBaitOrAdElement(this)) return 300;
                  return w;
                }
              });
            }

            var origGetBCR = Element.prototype.getBoundingClientRect;
            if (origGetBCR) {
              Element.prototype.getBoundingClientRect = function() {
                var rect = origGetBCR.call(this);
                if (isBaitOrAdElement(this) && (rect.height === 0 || rect.width === 0)) {
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
                if (el && isBaitOrAdElement(el)) {
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
                  var isXml = urlStr.toLowerCase().indexOf('vast') !== -1 || urlStr.toLowerCase().indexOf('.xml') !== -1;
                  return Promise.resolve(
                    new Response(
                      isXml ? '<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>' : 'window.canRunAds=true;',
                      {
                        status: 200,
                        statusText: 'OK',
                        headers: { 'Content-Type': isXml ? 'application/xml' : 'application/javascript' }
                      }
                    )
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

          // --- PART 2: COSMETIC CSS INJECTION (Hides all ad slots with zero height & transparent bg) ---
          var styleAttr = 'data-v-shield-style';
          var rootNode = document.head || document.documentElement;
          if (rootNode && !rootNode.querySelector('style[' + styleAttr + ']')) {
            var style = document.createElement('style');
            style.setAttribute(styleAttr, '1');
            style.type = 'text/css';
            style.appendChild(document.createTextNode('$cssRuleString { display: none !important; visibility: hidden !important; opacity: 0 !important; height: 0px !important; min-height: 0px !important; max-height: 0px !important; margin: 0px !important; padding: 0px !important; border: none !important; background: transparent !important; overflow: hidden !important; pointer-events: none !important; } iframe[src="about:blank"], iframe[src=""] { background: transparent !important; }'));
            rootNode.appendChild(style);
          }

          // --- PART 3: WHITE-BOX COLLAPSER, VIDEO AD SKIPPER & ANTI-ADBLOCK OVERLAY REMOVER ---
          function isLegitVideoIframe(iframe) {
            if (!iframe) return false;
            var src = (iframe.getAttribute('src') || iframe.getAttribute('data-src') || '').toLowerCase();
            if (!src || src === 'about:blank') return false;
            var allowFs = iframe.hasAttribute('allowfullscreen') || iframe.hasAttribute('webkitallowfullscreen') || (iframe.getAttribute('allow') || '').indexOf('fullscreen') !== -1;
            var isAdSrc =
              src.indexOf('doubleclick') !== -1 ||
              src.indexOf('googlesyndication') !== -1 ||
              src.indexOf('exoclick') !== -1 ||
              src.indexOf('exosrv') !== -1 ||
              src.indexOf('magsrv') !== -1 ||
              src.indexOf('realsrv') !== -1 ||
              src.indexOf('pemsrv') !== -1 ||
              src.indexOf('tsyndicate') !== -1 ||
              src.indexOf('juicyads') !== -1 ||
              src.indexOf('jads.co') !== -1 ||
              src.indexOf('trafficjunky') !== -1 ||
              src.indexOf('adspyglass') !== -1 ||
              src.indexOf('a-ads.com') !== -1 ||
              src.indexOf('bidgear') !== -1 ||
              src.indexOf('adsterra') !== -1 ||
              src.indexOf('propellerads') !== -1 ||
              src.indexOf('/ads/') !== -1 ||
              src.indexOf('banner') !== -1;
            if (isAdSrc) return false;
            var rect = iframe.getBoundingClientRect();
            if (allowFs && rect.height >= 180) return true;
            if (
              src.indexOf('embed') !== -1 ||
              src.indexOf('player') !== -1 ||
              src.indexOf('video') !== -1 ||
              src.indexOf('stream') !== -1 ||
              src.indexOf('youtube.com') !== -1 ||
              src.indexOf('vimeo.com') !== -1 ||
              src.indexOf('dailymotion.com') !== -1
            ) {
              return true;
            }
            return false;
          }

          function collapseElementAndEmptyWrapper(el) {
            if (!el || el === document.body || el === document.documentElement) return;
            if (el.querySelector && (el.querySelector('video') || isLegitVideoIframe(el))) return;
            el.style.setProperty('display', 'none', 'important');
            el.style.setProperty('height', '0px', 'important');
            el.style.setProperty('min-height', '0px', 'important');
            el.style.setProperty('margin', '0px', 'important');
            el.style.setProperty('padding', '0px', 'important');
            el.style.setProperty('background', 'transparent', 'important');

            // Walk up 1-3 parent wrapper levels if they only existed to hold this ad box
            var parent = el.parentElement;
            var depth = 0;
            while (
              parent &&
              depth < 3 &&
              parent !== document.body &&
              parent !== document.documentElement &&
              parent.tagName !== 'MAIN' &&
              parent.tagName !== 'ARTICLE' &&
              !parent.querySelector('video')
            ) {
              var hasLegitVideo = false;
              var childIframes = parent.querySelectorAll('iframe');
              for (var c = 0; c < childIframes.length; c++) {
                if (isLegitVideoIframe(childIframes[c])) {
                  hasLegitVideo = true;
                  break;
                }
              }
              if (hasLegitVideo) break;

              var textLen = (parent.innerText || '').trim().length;
              if (textLen < 25 && parent.children.length <= 3) {
                parent.style.setProperty('display', 'none', 'important');
                parent.style.setProperty('height', '0px', 'important');
                parent.style.setProperty('min-height', '0px', 'important');
                parent.style.setProperty('margin', '0px', 'important');
                parent.style.setProperty('padding', '0px', 'important');
                parent.style.setProperty('background', 'transparent', 'important');
                parent = parent.parentElement;
                depth++;
              } else {
                break;
              }
            }
          }

          function cleanPageAdsAndWhiteBoxes() {
            // 1. Collapse known ad slots & their parent wrapper boxes (eliminating white boxes!)
            var adNodes = document.querySelectorAll(
              'ins.adsbygoogle, .adsbygoogle, ins[class*="eas"], ins[data-zoneid], div[data-zone], [id^="div-gpt-ad"], [id^="google_ads_iframe"], [data-ad-slot], [data-ad-client], [data-google-query-id], .ad-banner, .ad-container, .ad-wrapper, .ad-slot, .ad-unit, .ad-placeholder, .adbox, .adslot, .adsbox, .advert, .advertisement'
            );
            for (var a = 0; a < adNodes.length; a++) {
              collapseElementAndEmptyWrapper(adNodes[a]);
            }

            // 2. Collapse all ad iframes & blank/white placeholder iframes
            var iframes = document.querySelectorAll('iframe');
            for (var f = 0; f < iframes.length; f++) {
              var ifr = iframes[f];
              if (isLegitVideoIframe(ifr)) continue;
              var src = (ifr.getAttribute('src') || '').trim().toLowerCase();
              var rect = ifr.getBoundingClientRect();
              var isBannerHeight = rect.height > 0 && rect.height <= 320;
              if (
                src === '' ||
                src === 'about:blank' ||
                src.indexOf('javascript:') === 0 ||
                isBannerHeight
              ) {
                collapseElementAndEmptyWrapper(ifr);
              }
            }

            // 3. Detect & collapse leftover empty white rounded boxes (banner wrappers whose ad failed/blocked)
            var boxes = document.querySelectorAll('div, section, aside, ins');
            for (var b = 0; b < boxes.length; b++) {
              var box = boxes[b];
              if (box.children.length > 4) continue;
              if (box.querySelector('video, canvas, svg, input, button, nav, h1, h2, h3')) continue;
              var txt = (box.innerText || '').trim();
              if (txt.length > 20) continue;

              var r = box.getBoundingClientRect();
              if (r.width >= 200 && r.height >= 45 && r.height <= 320) {
                var cs = window.getComputedStyle(box);
                var bg = (cs.backgroundColor || '').replace(/\s+/g, '');
                var clsAndId = ((typeof box.className === 'string' ? box.className : '') + ' ' + (box.id || '')).toLowerCase();
                var isWhiteBg =
                  bg === 'rgb(255,255,255)' ||
                  bg === 'rgba(255,255,255,1)' ||
                  bg === 'rgb(248,250,252)' ||
                  bg === 'rgb(241,245,249)';
                var hasOnlyEmptyOrBlockedChildren =
                  box.children.length === 0 ||
                  box.querySelector('iframe, ins, script');

                if (
                  (isWhiteBg && hasOnlyEmptyOrBlockedChildren && txt.length === 0) ||
                  clsAndId.indexOf('banner') !== -1 ||
                  clsAndId.indexOf('ad-') !== -1 ||
                  clsAndId.indexOf('ads-') !== -1 ||
                  clsAndId.indexOf('sponsor') !== -1
                ) {
                  collapseElementAndEmptyWrapper(box);
                }
              }
            }

            // 4. Detect and remove "Please disable your ad blocker" overlays
            for (var i = 0; i < boxes.length; i++) {
              var el = boxes[i];
              if (el.children.length > 12) continue;
              var t = (el.innerText || '').trim().toLowerCase();
              if (!t || t.length > 350) continue;

              if (
                t.indexOf('please disable your ad blocker') !== -1 ||
                t.indexOf('disable your ad blocker for this site') !== -1 ||
                t.indexOf('advertising supports free playback') !== -1 ||
                t.indexOf('adblock detected') !== -1 ||
                t.indexOf('ad blocker detected') !== -1 ||
                t.indexOf('turn off your ad blocker') !== -1
              ) {
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

                var mediaNodes = document.querySelectorAll('video, iframe');
                for (var v = 0; v < mediaNodes.length; v++) {
                  var m = mediaNodes[v];
                  if (m.tagName === 'VIDEO' || isLegitVideoIframe(m)) {
                    m.style.removeProperty('display');
                    m.style.removeProperty('visibility');
                    m.style.setProperty('filter', 'none', 'important');
                    m.style.setProperty('opacity', '1', 'important');
                  }
                }
              }
            }

            // 5. Remove in-page affiliate promo banners (e.g. "AI Image Generator ... Generate ↗")
            var links = document.querySelectorAll('a[target="_blank"], div[onclick]');
            for (var j = 0; j < links.length; j++) {
              var node = links[j];
              var href = (node.getAttribute && (node.getAttribute('href') || node.getAttribute('data-url') || '')) || '';
              var lowerHref = href.toLowerCase();
              var nodeText = (node.innerText || '').trim().toLowerCase();

              if (
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
                (nodeText.indexOf('ai image generator') !== -1 && nodeText.indexOf('generate') !== -1)
              ) {
                collapseElementAndEmptyWrapper(node);
              }
            }

            // 6. Universal Video Pre-roll / Mid-roll Ad Skipper (YouTube, JWPlayer, VideoJS, FluidPlayer)
            var skipBtns = document.querySelectorAll(
              '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .jw-skip, .vjs-skip-button, .videoAdUiSkipButton, .fluid_ad_skip, [class*="skip-ad"], [class*="skip_ad"], [id*="skip-ad"]'
            );
            for (var s = 0; s < skipBtns.length; s++) {
              try { skipBtns[s].click(); } catch (e) {}
            }

            var adPlayerContainers = document.querySelectorAll(
              '.ad-showing, .ad-interrupting, .jw-flag-ads, .vjs-ad-playing, .fluid_ad_playing, .ima-ad-container'
            );
            for (var p = 0; p < adPlayerContainers.length; p++) {
              var adVid = adPlayerContainers[p].querySelector('video');
              if (adVid) {
                try {
                  adVid.muted = true;
                  adVid.playbackRate = 16.0;
                  if (isFinite(adVid.duration) && adVid.duration > 0 && adVid.duration < 180) {
                    adVid.currentTime = adVid.duration;
                  }
                } catch (e) {}
              }
            }
          }

          cleanPageAdsAndWhiteBoxes();

          if (!window.__adblocker_observer_installed__ && typeof MutationObserver !== 'undefined') {
            window.__adblocker_observer_installed__ = true;
            var scheduled = false;
            var observer = new MutationObserver(function() {
              if (!scheduled) {
                scheduled = true;
                setTimeout(function() {
                  scheduled = false;
                  cleanPageAdsAndWhiteBoxes();
                }, 150);
              }
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
          }
        } catch (e) {}
      })();
    """.trimIndent()
  }

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
    secFetchDest: String? = null,
  ): ResourceType {
    if (isMainFrame) return ResourceType.OTHER
    val lowerDest = secFetchDest?.lowercase(Locale.ROOT) ?: ""
    if (lowerDest == "iframe" || lowerDest == "frame" || lowerDest == "subdocument") {
      return ResourceType.SUBDOCUMENT
    }
    if (lowerDest == "script") return ResourceType.SCRIPT
    if (lowerDest == "style") return ResourceType.STYLESHEET
    if (lowerDest == "image") return ResourceType.IMAGE

    val lowerAccept = acceptHeader?.lowercase(Locale.ROOT) ?: ""
    if (lowerAccept.contains("text/html") || lowerAccept.contains("application/xhtml")) {
      return ResourceType.SUBDOCUMENT
    }
    if (lowerAccept.contains("text/css")) return ResourceType.STYLESHEET
    if (lowerAccept.contains("image/")) return ResourceType.IMAGE
    if (lowerAccept.contains("script") || lowerAccept.contains("javascript")) return ResourceType.SCRIPT

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
          ByteArrayInputStream(TRANSPARENT_COLLAPSING_IFRAME_HTML_BYTES),
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
