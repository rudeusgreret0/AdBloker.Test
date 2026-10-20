package com.example.adblocker

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resource types recognized by EasyList / EasyPrivacy / uBlock filter rules.
 */
enum class ResourceType(val mask: Int) {
  OTHER(1 shl 0),
  SCRIPT(1 shl 1),
  IMAGE(1 shl 2),
  STYLESHEET(1 shl 3),
  XMLHTTPREQUEST(1 shl 4),
  SUBDOCUMENT(1 shl 5),
  MEDIA(1 shl 6),
  FONT(1 shl 7),
  POPUP(1 shl 8);

  companion object {
    const val ALL_MASK: Int =
      (1 shl 0) or
        (1 shl 1) or
        (1 shl 2) or
        (1 shl 3) or
        (1 shl 4) or
        (1 shl 5) or
        (1 shl 6) or
        (1 shl 7) or
        (1 shl 8)
  }
}

/**
 * Party matching requirement for a filter rule.
 */
enum class PartyRequirement {
  ANY,
  THIRD_PARTY_ONLY,
  FIRST_PARTY_ONLY
}

/**
 * Represents a compiled domain-anchored rule (`||domain.com^` or `||domain.com/path`).
 */
data class DomainRule(
  val domain: String,
  val pathPrefix: String?,
  val party: PartyRequirement = PartyRequirement.ANY,
  val typeMask: Int = ResourceType.ALL_MASK,
  val allowedPageDomains: Set<String>? = null,
  val excludedPageDomains: Set<String>? = null,
) {
  fun matches(
    urlPathWithQuery: String,
    isThirdParty: Boolean,
    resourceType: ResourceType,
    pageHost: String?,
  ): Boolean {
    if (party == PartyRequirement.THIRD_PARTY_ONLY && !isThirdParty) return false
    if (party == PartyRequirement.FIRST_PARTY_ONLY && isThirdParty) return false
    if ((typeMask and resourceType.mask) == 0) return false

    if (pathPrefix != null && !urlPathWithQuery.startsWith(pathPrefix)) {
      return false
    }

    if (excludedPageDomains != null && pageHost != null) {
      if (FilterList.domainMatchesAny(pageHost, excludedPageDomains)) return false
    }

    if (allowedPageDomains != null) {
      if (pageHost == null || !FilterList.domainMatchesAny(pageHost, allowedPageDomains)) {
        return false
      }
    }

    return true
  }
}

/**
 * Represents a token-indexed URL substring rule (e.g. `/pagead/js/`, `/adserver.`, `_ad_banner`).
 */
data class PatternRule(
  val pattern: String,
  val party: PartyRequirement = PartyRequirement.ANY,
  val typeMask: Int = ResourceType.ALL_MASK,
  val allowedPageDomains: Set<String>? = null,
  val excludedPageDomains: Set<String>? = null,
) {
  fun matches(
    fullUrlLower: String,
    isThirdParty: Boolean,
    resourceType: ResourceType,
    pageHost: String?,
  ): Boolean {
    if (party == PartyRequirement.THIRD_PARTY_ONLY && !isThirdParty) return false
    if (party == PartyRequirement.FIRST_PARTY_ONLY && isThirdParty) return false
    if ((typeMask and resourceType.mask) == 0) return false
    if (!fullUrlLower.contains(pattern)) return false

    if (excludedPageDomains != null && pageHost != null) {
      if (FilterList.domainMatchesAny(pageHost, excludedPageDomains)) return false
    }

    if (allowedPageDomains != null) {
      if (pageHost == null || !FilterList.domainMatchesAny(pageHost, allowedPageDomains)) {
        return false
      }
    }

    return true
  }
}

/**
 * Immutable snapshot of compiled rules. Swapped atomically via [AtomicReference]
 * so `WebViewClient.shouldInterceptRequest` performs lock-free O(1) hash lookups.
 */
class CompiledFilterSet(
  val pureBlockedDomains: Set<String>,
  val pureExceptionDomains: Set<String>,
  val conditionalBlockedDomains: Map<String, List<DomainRule>>,
  val conditionalExceptionDomains: Map<String, List<DomainRule>>,
  val tokenBlockedPatterns: Map<String, List<PatternRule>>,
  val tokenExceptionPatterns: Map<String, List<PatternRule>>,
  val elemhideExceptionDomains: Set<String>,
  val genericCosmeticSelectors: List<String>,
  val domainCosmeticSelectors: Map<String, List<String>>,
  val totalRuleCount: Int,
  val isFullListLoaded: Boolean,
)

/**
 * Status of the filter list engine.
 */
data class FilterStatus(
  val ruleCount: Int,
  val isSyncedWithEasyList: Boolean,
  val isSyncing: Boolean,
)

/**
 * Self-contained parser, compiler, and 24-hour background cache manager for
 * EasyList, EasyPrivacy, and uBlock-style anti-adblock rules.
 *
 * HOW TO REUSE IN YOUR APP:
 * Copy `FilterList.kt` and `AdBlocker.kt` into your project. `AdBlocker.init(context)`
 * automatically loads the bundled rules immediately and refreshes EasyList + EasyPrivacy
 * in the background every 24 hours.
 */
object FilterList {

  private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
  private const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
  private const val CACHE_DIR_NAME = "adblock_filter_cache"
  private const val EASYLIST_CACHE_FILE = "easylist.txt"
  private const val EASYPRIVACY_CACHE_FILE = "easyprivacy.txt"
  private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L // 24 hours
  private const val MAX_COSMETIC_SELECTORS = 1400

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Common two-level public suffixes so eTLD+1 (first-party vs third-party) is accurate.
  private val TWO_PART_TLDS =
    hashSetOf(
      "co.uk", "org.uk", "ac.uk", "gov.uk",
      "com.au", "net.au", "org.au",
      "co.jp", "ne.jp", "or.jp",
      "com.br", "com.mx", "com.ar", "com.co",
      "co.in", "net.in", "org.in",
      "co.nz", "co.za", "co.kr", "com.sg",
      "com.tw", "com.hk", "com.tr", "com.pl",
      "com.es", "com.fr", "co.il", "co.id",
      "com.vn", "com.my", "com.ph", "com.pk",
      "com.bd", "com.ng", "com.eg", "com.ua"
    )

  private val STOP_TOKENS =
    hashSetOf(
      "http", "https", "html", "json", "text", "java", "script",
      "style", "image", "webp", "woff", "woff2", "true", "false",
      "null", "view", "page", "content", "main", "index", "default"
    )

  /**
   * Safe generic cosmetic selectors that hide real ad iframes and third-party widgets
   * WITHOUT triggering anti-adblock bait checks on `.adsbox` or `.ad-banner`.
   * (Bait classes are handled stealthily in JavaScript by spoofing their offsetHeight/offsetWidth).
   */
  val DEFAULT_COSMETIC_SELECTORS =
    listOf(
      "iframe[src*='doubleclick.net']",
      "iframe[src*='googlesyndication.com']",
      "iframe[src*='adnxs.com']",
      "iframe[src*='rubiconproject.com']",
      "iframe[src*='criteo.com']",
      "iframe[src*='taboola.com']",
      "iframe[src*='outbrain.com']",
      "iframe[src*='exoclick.com']",
      "iframe[src*='exosrv.com']",
      "iframe[src*='magsrv.com']",
      "iframe[src*='realsrv.com']",
      "iframe[src*='pemsrv.com']",
      "iframe[src*='tsyndicate.com']",
      "iframe[src*='juicyads.com']",
      "iframe[src*='jads.co']",
      "iframe[src*='trafficjunky.net']",
      "iframe[src*='adspyglass.com']",
      "iframe[src*='a-ads.com']",
      "iframe[src*='propellerads.com']",
      "iframe[src*='adsterra.com']",
      "iframe[id^='google_ads_iframe']",
      "[id^='div-gpt-ad']",
      "[data-google-query-id]",
      ".taboola-widget",
      ".trc_rbox_container",
      ".OUTBRAIN",
      "#taboola-below-article-thumbnails",
      "#outbrain_widget_0",
      "[id*='taboola-']",
      "[id*='outbrain-']",
      "[id^='mgid_']",
      ".mgbox",
      "ins.eas6a97888e",
      "ins[data-ad-Provider]",
      "ins[data-zoneid]",
      "div[data-zone]",
      "[data-ad-slot]",
      "[data-ad-client]"
    )

  /**
   * Bundled offline fallback filter list combining:
   * 1. Major ad networks (Google, DoubleClick, Rubicon, OpenX, Criteo, Amazon, Taboola, Outbrain)
   * 2. Video, streaming & anime site ad networks (ExoClick, Magsrv, Realsrv, Pemsrv, TSyndicate,
   *    AdSpyglass, JuicyAds, TrafficJunky, TwinRed, Bidgear, Galaksion, RollerAds, A-Ads, Clickadu,
   *    Adsterra, PropellerAds, HilltopAds, Evadav, RichPush, Clickaine, PopAds, PopCash, OnClickAds)
   * 3. Anti-adblock & bait script servers (Admiral, html-load.com, BlockAdBlock, FuckAdBlock, etc.)
   * 4. EasyPrivacy trackers, session recorders & telemetry
   * 5. Essential first-party CDN & video streaming whitelists (`@@`) so videos & sites never break.
   */
  val BUNDLED_FALLBACK_RULES =
    listOf(
      // --- Essential Safe Exceptions (never break CDNs, video streams, captchas, or fonts) ---
      "@@||gstatic.com^",
      "@@||googleapis.com^",
      "@@||cloudflare.com^",
      "@@||cdnjs.cloudflare.com^",
      "@@||jsdelivr.net^",
      "@@||unpkg.com^",
      "@@||recaptcha.net^",
      "@@||hcaptcha.com^",
      "@@||challenges.cloudflare.com^",
      "@@||fontawesome.com^",
      "@@||ytimg.com^",
      "@@||ggpht.com^",
      "@@||akamaized.net^",
      "@@||fastly.net^",
      "@@||jwpsrv.com/library/^",
      "@@||ssl.p.jwpcdn.com^",
      "@@||vjs.zencdn.net^",
      "@@||cdn.plyr.io^",
      "@@||cdn.jsdelivr.net^",
      "@@||cloudfront.net/assets/\$~third-party",

      // --- Video / Streaming / Anime Site Ad Networks & Native Banner Networks ---
      "||exoclick.com^",
      "||exosrv.com^",
      "||main.exoclick.com^",
      "||syndication.exoclick.com^",
      "||main.exosrv.com^",
      "||syndication.exosrv.com^",
      "||magsrv.com^",
      "||a.magsrv.com^",
      "||s.magsrv.com^",
      "||auct.magsrv.com^",
      "||realsrv.com^",
      "||a.realsrv.com^",
      "||s.realsrv.com^",
      "||syndication.realsrv.com^",
      "||pemsrv.com^",
      "||a.pemsrv.com^",
      "||s.pemsrv.com^",
      "||tsyndicate.com^",
      "||cdn.tsyndicate.com^",
      "||pxl.tsyndicate.com^",
      "||tsvideo.com^",
      "||adspyglass.com^",
      "||cdn.adspyglass.com^",
      "||s.adspyglass.com^",
      "||asg.vidstream.pro^",
      "||asg.to^",
      "||juicyads.com^",
      "||jads.co^",
      "||x.jads.co^",
      "||adserver.juicyads.com^",
      "||trafficjunky.net^",
      "||trafficjunky.com^",
      "||media.trafficjunky.net^",
      "||pt.trafficjunky.net^",
      "||twinrdsyn.com^",
      "||twinrdsrv.com^",
      "||twinred.com^",
      "||a-ads.com^",
      "||ad.a-ads.com^",
      "||static.a-ads.com^",
      "||acceptable.a-ads.com^",
      "||bidgear.com^",
      "||platform.bidgear.com^",
      "||galaksion.com^",
      "||rollerads.com^",
      "||evadav.com^",
      "||richpush.co^",
      "||richads.com^",
      "||mondiad.com^",
      "||pushhouse.com^",
      "||adoperator.com^",
      "||octoclick.com^",
      "||clickaine.com^",
      "||adtng.com^",
      "||hw-cdn2.adtng.com^",
      "||clksite.com^",
      "||clkmon.com^",
      "||bebi.com^",
      "||steepto.com^",
      "||adskeeper.co.uk^",
      "||adskeeper.com^",
      "||jsc.adskeeper.co.uk^",
      "||idealmedia.io^",
      "||lentainform.com^",
      "||popads.net^",
      "||serve.popads.net^",
      "||popcash.net^",
      "||propellerads.com^",
      "||adsterra.com^",
      "||adsterratools.com^",
      "||highperformanceformat.com^",
      "||effectivegatecpm.com^",
      "||profitablecpmrate.com^",
      "||topcreativeformat.com^",
      "||alwingulla.com^",
      "||auglupto.com^",
      "||whos.amung.us^",
      "||adxpansion.com^",
      "||clickadu.com^",
      "||hilltopads.net^",
      "||adcash.com^",
      "||aclib.net^",
      "||admaven.com^",
      "||ero-advertising.com^",
      "||plugrush.com^",
      "||trafficfactory.biz^",
      "||trafficfactory.com^",
      "||trafficstars.com^",
      "||cdn.trafficstars.com^",
      "||zeroredirect.com^",
      "||onclickads.net^",
      "||onclkds.com^",
      "||onmarshtompor.com^",
      "||outsligulr.com^",
      "||glugreez.com^",
      "||pushagim.com^",
      "||pushengage.com^\$third-party",
      "||pushcrew.com^\$third-party",
      "||izooto.com^\$third-party",

      // --- Anti-Adblock & Adblock Recovery Providers ---
      "||html-load.com^",
      "||getadmiral.com^",
      "||includemodal.global.ssl.fastly.net^",
      "|| Admiral.com^\$third-party",
      "||blockadblock.com^",
      "||fuckadblock.com^",
      "||pagefair.com^",
      "||pagefair.net^",
      "||tisoomi.com^",
      "||addefend.com^",
      "||instinctiveads.com^",
      "||uponit.com^",
      "||adbull.com^",
      "||adrecover.com^",
      "||delivery.adrecover.com^",
      "||fundingchoices.google.com^",
      "||fundingchoicesmessages.google.com^",

      // --- Google / DoubleClick / Syndication Ad Networks ---
      "||doubleclick.net^",
      "||googlesyndication.com^",
      "||googleadservices.com^",
      "||googletagservices.com^",
      "||pagead2.googlesyndication.com^",
      "||tpc.googlesyndication.com^",
      "||ade.googlesyndication.com^",
      "||adservice.google.com^",
      "||adservice.google.co.uk^",
      "||adservice.google.ca^",
      "||adservice.google.de^",
      "||adservice.google.fr^",
      "||adservice.google.co.jp^",
      "||adservice.google.com.au^",
      "||adservice.google.co.in^",
      "||imasdk.googleapis.com/js/sdkloader/ima3.js",
      "||google-analytics.com^",
      "||ssl.google-analytics.com^",
      "||www.google-analytics.com^",
      "||googletagmanager.com^",
      "||analytics.google.com^",

      // --- Major Programmatic Ad Exchanges & SSPs ---
      "||adnxs.com^",
      "||ib.adnxs.com^",
      "||secure.adnxs.com^",
      "||rubiconproject.com^",
      "||fastlane.rubiconproject.com^",
      "||eus.rubiconproject.com^",
      "||openx.net^",
      "||us-u.openx.net^",
      "||pubmatic.com^",
      "||ads.pubmatic.com^",
      "||hbopenbid.pubmatic.com^",
      "||criteo.com^",
      "||criteo.net^",
      "||static.criteo.net^",
      "||bidder.criteo.com^",
      "||gum.criteo.com^",
      "||casalemedia.com^",
      "||htlb.casalemedia.com^",
      "||indexww.com^",
      "||js-sec.indexww.com^",
      "||amazon-adsystem.com^",
      "||aax.amazon-adsystem.com^",
      "||c.amazon-adsystem.com^",
      "||s.amazon-adsystem.com^",
      "||fls-na.amazon-adsystem.com^",
      "||media.net^",
      "||contextual.media.net^",
      "||static.media.net^",
      "||smartadserver.com^",
      "||ced.sascdn.com^",
      "||teads.tv^",
      "||a.teads.tv^",
      "||t.teads.tv^",
      "||sharethrough.com^",
      "||btlr.sharethrough.com^",
      "||3lift.com^",
      "||tlx.3lift.com^",
      "||eb2.3lift.com^",
      "||lijit.com^",
      "||ap.lijit.com^",
      "||sovrn.com^",
      "||bidswitch.net^",
      "||x.bidswitch.net^",
      "||spotxchange.com^",
      "||spotx.tv^",
      "||stickyadstv.com^",
      "||ads.stickyadstv.com^",
      "||yieldmo.com^",
      "||ads.yieldmo.com^",
      "||kargo.com^",
      "||krk.kargo.com^",
      "||33across.com^",
      "||ssc.33across.com^",
      "||conversantmedia.com^",
      "||fastclick.net^",
      "||media.fastclick.net^",
      "||zedo.com^",
      "||adform.net^",
      "||track.adform.net^",
      "||adition.com^",
      "||serving-sys.com^",
      "||bs.serving-sys.com^",
      "||flashtalking.com^",
      "||servedby.flashtalking.com^",
      "||2mdn.net^",
      "||s0.2mdn.net^",
      "||adroll.com^",
      "||s.adroll.com^",
      "||d.adroll.com^",
      "||revjet.com^",
      "||gumgum.com^",
      "||g2.gumgum.com^",
      "||sonobi.com^",
      "||apex.go.sonobi.com^",
      "||tremorhub.com^",
      "||ads.tremorhub.com^",
      "||undertone.com^",
      "||ads.undertone.com^",
      "||adelement.com^",
      "||adkernel.com^",
      "||admixer.net^",
      "||adblade.com^",
      "||adbuff.com^",
      "||adbutler.com^",
      "||adcolony.com^",
      "||adsrvr.org^",
      "||js.adsrvr.org^",
      "||match.adsrvr.org^",
      "||insight.adsrvr.org^",
      "||adtech.de^",
      "||adtechus.com^",
      "||advertising.com^",
      "||adzerk.net^",
      "||engine.adzerk.net^",
      "||aps.amazon.com^",
      "||betrad.com^",
      "||c.betrad.com^",
      "||emxdgt.com^",
      "||exponential.com^",
      "||tribalfusion.com^",
      "||a.tribalfusion.com^",
      "||fyber.com^",
      "||inmobi.com^",
      "||ironsrc.com^",
      "||loopme.me^",
      "||mintegral.com^",
      "||mobfox.com^",
      "||mopub.com^",
      "||ads.mopub.com^",
      "||nativo.com^",
      "||jadserve.postrelease.com^",
      "||ntv.io^",
      "||s.ntv.io^",
      "||onetag-sys.com^",
      "||playground.xyz^",
      "||richaudience.com^",
      "||rtbhouse.com^",
      "||creativecdn.com^",
      "||seedtag.com^",
      "||s.seedtag.com^",
      "||smaato.net^",
      "||tapjoy.com^",
      "||unityads.unity3d.com^",
      "||vungle.com^",
      "||zemanta.com^",

      // --- Native Content Recommendation & Chumbox Widgets ---
      "||taboola.com^",
      "||cdn.taboola.com^",
      "||trc.taboola.com^",
      "||nr-data.net^",
      "||outbrain.com^",
      "||widgets.outbrain.com^",
      "||odb.outbrain.com^",
      "||outbrainimg.com^",
      "||revcontent.com^",
      "||trends.revcontent.com^",
      "||mgid.com^",
      "||jsc.mgid.com^",
      "||servicer.mgid.com^",
      "||zergnet.com^",
      "||content.ad^",
      "||dianomi.com^",
      "||lockerdome.com^",

      // --- Trackers, Telemetry, Fingerprinting & Session Recording (EasyPrivacy) ---
      "||scorecardresearch.com^",
      "||sb.scorecardresearch.com^",
      "||b.scorecardresearch.com^",
      "||quantserve.com^",
      "||secure.quantserve.com^",
      "||pixel.quantserve.com^",
      "||quantcount.com^",
      "||moatads.com^",
      "||z.moatads.com^",
      "||px.moatads.com^",
      "||doubleverify.com^",
      "||cdn.doubleverify.com^",
      "||tps.doubleverify.com^",
      "||adsafeprotected.com^",
      "||pixel.adsafeprotected.com^",
      "||static.adsafeprotected.com^",
      "||fwmrm.net^",
      "||imrworldwide.com^",
      "||secure-us.imrworldwide.com^",
      "||omtrdc.net^",
      "||2o7.net^",
      "||demdex.net^",
      "||dpm.demdex.net^",
      "||everesttech.net^",
      "||krxd.net^",
      "||cdn.krxd.net^",
      "||beacon.krxd.net^",
      "||bluekai.com^",
      "||tags.bluekai.com^",
      "||bkrtx.com^",
      "||exelator.com^",
      "||loadm.exelator.com^",
      "||rlcdn.com^",
      "||idsync.rlcdn.com^",
      "||pippio.com^",
      "||tapad.com^",
      "||pixel.tapad.com^",
      "||agkn.com^",
      "||aa.agkn.com^",
      "||mathtag.com^",
      "||pixel.mathtag.com^",
      "||turn.com^",
      "||ad.turn.com^",
      "||eyeota.net^",
      "||ps.eyeota.net^",
      "||crwdcntrl.net^",
      "||tags.crwdcntrl.net^",
      "||bcp.crwdcntrl.net^",
      "||lotame.com^",
      "||chartbeat.com^",
      "||static.chartbeat.com^",
      "||ping.chartbeat.net^",
      "||chartbeat.net^",
      "||parsely.com^",
      "||srv.pixel.parsely.com^",
      "||hotjar.com^",
      "||static.hotjar.com^",
      "||vars.hotjar.com^",
      "||script.hotjar.com^",
      "||mouseflow.com^",
      "||fullstory.com^",
      "||rs.fullstory.com^",
      "||edge.fullstory.com^",
      "||clarity.ms^",
      "||www.clarity.ms^",
      "||crazyegg.com^",
      "||script.crazyegg.com^",
      "||inspectlet.com^",
      "||logrocket.io^",
      "||smartlook.com^",
      "||luckyorange.com^",
      "||mixpanel.com^",
      "||api.mixpanel.com^",
      "||cdn.mxpnl.com^",
      "||amplitude.com^",
      "||api.amplitude.com^",
      "||api2.amplitude.com^",
      "||cdn.amplitude.com^",
      "||segment.io^",
      "||api.segment.io^",
      "||cdn.segment.com^",
      "||heapanalytics.com^",
      "||kissmetrics.com^",
      "||newrelic.com^\$third-party",
      "||bam.nr-data.net^",
      "||js-agent.newrelic.com^",
      "||bugsnag.com^\$third-party",
      "||browser.sentry-cdn.com^\$third-party",
      "||branch.io^\$third-party",
      "||api2.branch.io^\$third-party",
      "||app.link^\$third-party",
      "||appsflyer.com^",
      "||adjust.com^",
      "||kochava.com^",
      "||singular.net^",
      "||clevertap.com^",
      "||braze.com^\$third-party",
      "||sdk.iad-01.braze.com^",
      "||onesignal.com^\$third-party",
      "||connect.facebook.net/en_US/fbevents.js",
      "||facebook.com/tr/",
      "||facebook.com/tr?",
      "||bat.bing.com^",
      "||analytics.tiktok.com^",
      "||analytics.twitter.com^",
      "||static.ads-twitter.com^",
      "||ads-twitter.com^",
      "||t.co/i/adsct",
      "||snap.licdn.com^",
      "||px.ads.linkedin.com^",
      "||sc-static.net/scevent.min.js",
      "||tr.snapchat.com^",
      "||ct.pinterest.com^",
      "||widgets.pinterest.com/v3/pidgets/log/",
      "||alb.reddit.com^",
      "||events.redditmedia.com^",
      "||quora.com/_/ad",
      "||mc.yandex.ru^",
      "||an.yandex.ru^",
      "||hm.baidu.com^",
      "||pos.baidu.com^",

      // --- High-Confidence URL Path & Substring Rules (VAST, Preroll, Anti-Adblock, Banners) ---
      "/pagead/js/adsbygoogle.js",
      "/pagead/show_ads.js",
      "/pagead/gadgets/",
      "/pagead/lvz?",
      "/pagead/interaction/",
      "/gpt/pubads_impl_",
      "/tag/js/gpt.js",
      "/adserver/ad_logger",
      "/adserver.js",
      "/ads/ad.js",
      "/ads/banner.",
      "/adframe.js",
      "/admanager.js",
      "/prebid.js\$third-party",
      "/prebid.min.js\$third-party",
      "/show_ads_impl.js",
      "/aclk?sa=l&ai=",
      "/fuckadblock.js",
      "/fuckadblock.min.js",
      "/blockadblock.js",
      "/blockadblock.min.js",
      "/adblock-detector.js",
      "/detect-adblock.js",
      "/adprovider.js",
      "/popunder.js",
      "/pop.js\$third-party",
      "/vast.xml\$third-party",
      "/vmap.xml\$third-party",
      "ad_type=vast"
    )

  private val compiledRulesRef: AtomicReference<CompiledFilterSet> by lazy {
    AtomicReference(compileRules(BUNDLED_FALLBACK_RULES, isFull = false))
  }

  private val _status: MutableStateFlow<FilterStatus> by lazy {
    MutableStateFlow(
      FilterStatus(
        ruleCount = compiledRulesRef.get().totalRuleCount,
        isSyncedWithEasyList = false,
        isSyncing = false,
      )
    )
  }
  val status: StateFlow<FilterStatus>
    get() = _status.asStateFlow()

  /**
   * Returns the current lock-free compiled filter set.
   */
  fun currentRules(): CompiledFilterSet = compiledRulesRef.get()

  /**
   * Loads cached EasyList + EasyPrivacy rules from disk if available, and downloads
   * fresh copies in the background if missing or older than 24 hours.
   */
  fun initializeAndRefresh(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
      val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)
      if (!cacheDir.exists()) {
        cacheDir.mkdirs()
      }

      val easyListFile = File(cacheDir, EASYLIST_CACHE_FILE)
      val easyPrivacyFile = File(cacheDir, EASYPRIVACY_CACHE_FILE)

      if (easyListFile.exists() && easyListFile.length() > 1024L) {
        loadFromCacheFiles(easyListFile, easyPrivacyFile)
      }

      val now = System.currentTimeMillis()
      val easyListFresh =
        easyListFile.exists() &&
          easyListFile.length() > 1024L &&
          (now - easyListFile.lastModified()) < REFRESH_INTERVAL_MS
      val easyPrivacyFresh =
        easyPrivacyFile.exists() &&
          easyPrivacyFile.length() > 1024L &&
          (now - easyPrivacyFile.lastModified()) < REFRESH_INTERVAL_MS

      if (!easyListFresh || !easyPrivacyFresh) {
        _status.value = _status.value.copy(isSyncing = true)
        val ok1 = downloadToFile(EASYLIST_URL, easyListFile)
        val ok2 = downloadToFile(EASYPRIVACY_URL, easyPrivacyFile)
        if (ok1 || ok2 || easyListFile.exists()) {
          loadFromCacheFiles(easyListFile, easyPrivacyFile)
        } else {
          _status.value = _status.value.copy(isSyncing = false)
        }
      }
    }
  }

  private fun loadFromCacheFiles(easyListFile: File, easyPrivacyFile: File) {
    try {
      val combinedLines = ArrayList<String>(95_000)
      combinedLines.addAll(BUNDLED_FALLBACK_RULES)
      if (easyListFile.exists()) {
        easyListFile.useLines { seq -> seq.forEach { combinedLines.add(it) } }
      }
      if (easyPrivacyFile.exists()) {
        easyPrivacyFile.useLines { seq -> seq.forEach { combinedLines.add(it) } }
      }
      val compiled = compileRules(combinedLines, isFull = true)
      compiledRulesRef.set(compiled)
      _status.value =
        FilterStatus(
          ruleCount = compiled.totalRuleCount,
          isSyncedWithEasyList = true,
          isSyncing = false,
        )
    } catch (_: Throwable) {
      _status.value = _status.value.copy(isSyncing = false)
    }
  }

  private fun downloadToFile(urlString: String, targetFile: File): Boolean {
    val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; AdBlockerModule/2.0)")
      }
      if (connection.responseCode == HttpURLConnection.HTTP_OK) {
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
          tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            var line: String? = reader.readLine()
            while (line != null) {
              writer.write(line)
              writer.newLine()
              line = reader.readLine()
            }
          }
        }
        if (tempFile.length() > 1024L) {
          tempFile.renameTo(targetFile)
          true
        } else {
          tempFile.delete()
          false
        }
      } else {
        false
      }
    } catch (_: Throwable) {
      tempFile.delete()
      false
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * Checks whether [host] or any of its parent domains is present in [domainSet]
   * using fast O(labels) hash lookups.
   */
  fun domainMatchesAny(host: String, domainSet: Set<String>): Boolean {
    if (domainSet.isEmpty()) return false
    var current = host.lowercase(Locale.ROOT).trimEnd('.')
    while (current.isNotEmpty()) {
      if (domainSet.contains(current)) return true
      val dotIndex = current.indexOf('.')
      if (dotIndex < 0) break
      current = current.substring(dotIndex + 1)
    }
    return false
  }

  /**
   * Extracts the registrable domain (eTLD+1) of a host to accurately distinguish
   * first-party resources from third-party trackers/ads.
   */
  fun registrableDomain(host: String): String {
    val clean = host.lowercase(Locale.ROOT).trim().trimEnd('.')
    if (clean.isEmpty()) return ""
    val parts = clean.split('.')
    if (parts.size <= 2) return clean
    val lastTwo = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
    return if (TWO_PART_TLDS.contains(lastTwo) && parts.size >= 3) {
      "${parts[parts.size - 3]}.$lastTwo"
    } else {
      lastTwo
    }
  }

  /**
   * Determines whether a request to [requestHost] from a page on [pageHost] is third-party.
   */
  fun isThirdPartyRequest(requestHost: String, pageHost: String?): Boolean {
    if (pageHost.isNullOrBlank()) return false
    val reqBase = registrableDomain(requestHost)
    val pageBase = registrableDomain(pageHost)
    if (reqBase.isEmpty() || pageBase.isEmpty()) return false
    return reqBase != pageBase
  }

  /**
   * Extracts a clean lowercase hostname from a URL string without throwing exceptions.
   */
  fun extractHost(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return try {
      val trimmed = url.trim()
      val schemeEnd = trimmed.indexOf("://")
      val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
      var end = trimmed.indexOfAny(charArrayOf('/', '?', '#', ':'), start)
      if (end < 0) end = trimmed.length
      var host = trimmed.substring(start, end).lowercase(Locale.ROOT)
      val atIdx = host.lastIndexOf('@')
      if (atIdx >= 0) host = host.substring(atIdx + 1)
      host.trimEnd('.')
    } catch (_: Throwable) {
      try {
        URI(url).host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: ""
      } catch (_: Throwable) {
        ""
      }
    }
  }

  /**
   * Extracts the lowercase path and query portion of a URL.
   */
  fun extractPathAndQuery(url: String): String {
    val schemeEnd = url.indexOf("://")
    val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val slashIdx = url.indexOf('/', start)
    if (slashIdx < 0) return "/"
    val hashIdx = url.indexOf('#', slashIdx)
    return if (hashIdx >= 0) {
      url.substring(slashIdx, hashIdx).lowercase(Locale.ROOT)
    } else {
      url.substring(slashIdx).lowercase(Locale.ROOT)
    }
  }

  /**
   * Extracts alphanumeric tokens (4+ chars) from a URL or pattern for fast O(1) hash indexing.
   */
  fun extractTokens(text: String): List<String> {
    val result = ArrayList<String>(8)
    val sb = StringBuilder()
    for (i in 0 until text.length) {
      val c = text[i]
      if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') {
        sb.append(c)
      } else {
        if (sb.length >= 4) {
          val token = sb.toString()
          if (!STOP_TOKENS.contains(token)) {
            result.add(token)
          }
        }
        sb.setLength(0)
      }
    }
    if (sb.length >= 4) {
      val token = sb.toString()
      if (!STOP_TOKENS.contains(token)) {
        result.add(token)
      }
    }
    return result
  }

  // Bait classes commonly used by BlockAdBlock / FuckAdBlock to detect CSS hiding.
  // We exclude these from raw CSS `display:none` injection and instead spoof their dimensions in JS.
  private val BAIT_SELECTORS_TO_SKIP_IN_RAW_CSS =
    hashSetOf(
      ".adsbox",
      ".ad-banner",
      ".ad-placeholder",
      ".text-ad",
      ".banner-ad",
      ".pub_300x250",
      ".pub_300x250m",
      ".pub_728x90",
      "#adsbox",
      "#ad-tester",
      "#ad_tester",
      ".adblock-test"
    )

  /**
   * Compiles EasyList / EasyPrivacy lines into hash sets and token-indexed maps.
   */
  fun compileRules(lines: Iterable<String>, isFull: Boolean): CompiledFilterSet {
    val pureBlockedDomains = HashSet<String>(32_768)
    val pureExceptionDomains = HashSet<String>(4_096)
    val conditionalBlockedDomains = HashMap<String, MutableList<DomainRule>>(8_192)
    val conditionalExceptionDomains = HashMap<String, MutableList<DomainRule>>(1_024)
    val tokenBlockedPatterns = HashMap<String, MutableList<PatternRule>>(4_096)
    val tokenExceptionPatterns = HashMap<String, MutableList<PatternRule>>(512)
    val elemhideExceptionDomains = HashSet<String>(512)
    val genericCosmeticSet = LinkedHashSet<String>(MAX_COSMETIC_SELECTORS)
    val domainCosmeticMap = HashMap<String, MutableList<String>>(2_048)

    genericCosmeticSet.addAll(DEFAULT_COSMETIC_SELECTORS)

    var count = 0

    for (rawLine in lines) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue

      // 1. Handle cosmetic rules (##, #?#, #@#)
      val elemHideIdx = line.indexOf("##")
      if (elemHideIdx >= 0 && !line.contains("#@#") && !line.contains("#?#")) {
        val domainPart = line.substring(0, elemHideIdx).trim()
        val selector = line.substring(elemHideIdx + 2).trim()
        if (
          selector.isNotEmpty() &&
            isValidCssSelector(selector) &&
            !BAIT_SELECTORS_TO_SKIP_IN_RAW_CSS.contains(selector.lowercase(Locale.ROOT))
        ) {
          if (domainPart.isEmpty()) {
            if (genericCosmeticSet.size < MAX_COSMETIC_SELECTORS) {
              genericCosmeticSet.add(selector)
              count++
            }
          } else {
            val domains = domainPart.split(',')
            for (d in domains) {
              val cleanDomain = d.trim().lowercase(Locale.ROOT)
              if (cleanDomain.isNotEmpty() && !cleanDomain.startsWith("~") && !cleanDomain.contains("*")) {
                val list = domainCosmeticMap.getOrPut(cleanDomain) { ArrayList(4) }
                if (list.size < 40) {
                  list.add(selector)
                }
              }
            }
            count++
          }
        }
        continue
      }
      if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) {
        continue
      }

      // 2. Network rules & exceptions (@@)
      var isException = false
      var body = line
      if (body.startsWith("@@")) {
        isException = true
        body = body.substring(2).trim()
      }

      var patternPart = body
      var optionsPart: String? = null
      val dollarIdx = body.lastIndexOf('$')
      if (dollarIdx > 0) {
        patternPart = body.substring(0, dollarIdx)
        optionsPart = body.substring(dollarIdx + 1)
      }

      val parsedOptions = parseOptions(optionsPart) ?: continue
      if (parsedOptions.unsupported) continue

      if (isException && parsedOptions.elemHideOrDocException) {
        val host = extractDomainFromAnchor(patternPart)
        if (host != null) {
          elemhideExceptionDomains.add(host)
          pureExceptionDomains.add(host)
          count++
        }
        continue
      }

      // Case A: Plain hostname or ||domain^ rule
      if (patternPart.startsWith("||")) {
        val withoutAnchor = patternPart.substring(2).lowercase(Locale.ROOT)
        val caretOrSlashIdx = withoutAnchor.indexOfFirst { it == '^' || it == '/' || it == '*' }
        val hostCandidate =
          if (caretOrSlashIdx >= 0) withoutAnchor.substring(0, caretOrSlashIdx) else withoutAnchor
        val remainder =
          if (caretOrSlashIdx >= 0) withoutAnchor.substring(caretOrSlashIdx) else ""

        if (isValidHostname(hostCandidate)) {
          val cleanRemainder = remainder.removePrefix("^")
          if (cleanRemainder.isEmpty() || cleanRemainder == "/") {
            if (parsedOptions.isUnconditional()) {
              if (isException) {
                pureExceptionDomains.add(hostCandidate)
              } else {
                pureBlockedDomains.add(hostCandidate)
              }
            } else {
              val rule =
                DomainRule(
                  domain = hostCandidate,
                  pathPrefix = null,
                  party = parsedOptions.party,
                  typeMask = parsedOptions.typeMask,
                  allowedPageDomains = parsedOptions.allowedDomains,
                  excludedPageDomains = parsedOptions.excludedDomains,
                )
              val map = if (isException) conditionalExceptionDomains else conditionalBlockedDomains
              map.getOrPut(hostCandidate) { ArrayList(2) }.add(rule)
            }
            count++
            continue
          } else if (!cleanRemainder.contains("*") && !cleanRemainder.contains("^")) {
            val pathPrefix = if (cleanRemainder.startsWith("/")) cleanRemainder else "/$cleanRemainder"
            val rule =
              DomainRule(
                domain = hostCandidate,
                pathPrefix = pathPrefix,
                party = parsedOptions.party,
                typeMask = parsedOptions.typeMask,
                allowedPageDomains = parsedOptions.allowedDomains,
                excludedPageDomains = parsedOptions.excludedDomains,
              )
            val map = if (isException) conditionalExceptionDomains else conditionalBlockedDomains
            map.getOrPut(hostCandidate) { ArrayList(2) }.add(rule)
            count++
            continue
          }
        }
      } else if (isValidHostname(patternPart.lowercase(Locale.ROOT))) {
        val hostCandidate = patternPart.lowercase(Locale.ROOT)
        if (parsedOptions.isUnconditional()) {
          if (isException) {
            pureExceptionDomains.add(hostCandidate)
          } else {
            pureBlockedDomains.add(hostCandidate)
          }
        } else {
          val rule =
            DomainRule(
              domain = hostCandidate,
              pathPrefix = null,
              party = parsedOptions.party,
              typeMask = parsedOptions.typeMask,
              allowedPageDomains = parsedOptions.allowedDomains,
              excludedPageDomains = parsedOptions.excludedDomains,
            )
          val map = if (isException) conditionalExceptionDomains else conditionalBlockedDomains
          map.getOrPut(hostCandidate) { ArrayList(2) }.add(rule)
        }
        count++
        continue
      }

      // Case B: Substring/path pattern rule
      val cleanPattern =
        patternPart
          .removePrefix("|")
          .removeSuffix("|")
          .removeSuffix("^")
          .lowercase(Locale.ROOT)

      if (
        cleanPattern.length >= 5 &&
          !cleanPattern.contains("*") &&
          !cleanPattern.contains("^")
      ) {
        val tokens = extractTokens(cleanPattern)
        if (tokens.isNotEmpty()) {
          val bestToken = tokens.maxByOrNull { it.length }!!
          val rule =
            PatternRule(
              pattern = cleanPattern,
              party = parsedOptions.party,
              typeMask = parsedOptions.typeMask,
              allowedPageDomains = parsedOptions.allowedDomains,
              excludedPageDomains = parsedOptions.excludedDomains,
            )
          val map = if (isException) tokenExceptionPatterns else tokenBlockedPatterns
          val bucket = map.getOrPut(bestToken) { ArrayList(2) }
          if (bucket.size < 64) {
            bucket.add(rule)
            count++
          }
        }
      }
    }

    return CompiledFilterSet(
      pureBlockedDomains = pureBlockedDomains,
      pureExceptionDomains = pureExceptionDomains,
      conditionalBlockedDomains = conditionalBlockedDomains,
      conditionalExceptionDomains = conditionalExceptionDomains,
      tokenBlockedPatterns = tokenBlockedPatterns,
      tokenExceptionPatterns = tokenExceptionPatterns,
      elemhideExceptionDomains = elemhideExceptionDomains,
      genericCosmeticSelectors = genericCosmeticSet.toList(),
      domainCosmeticSelectors = domainCosmeticMap,
      totalRuleCount = count,
      isFullListLoaded = isFull,
    )
  }

  private data class ParsedRuleOptions(
    val party: PartyRequirement,
    val typeMask: Int,
    val allowedDomains: Set<String>?,
    val excludedDomains: Set<String>?,
    val elemHideOrDocException: Boolean,
    val unsupported: Boolean,
  ) {
    fun isUnconditional(): Boolean =
      party == PartyRequirement.ANY &&
        typeMask == ResourceType.ALL_MASK &&
        allowedDomains == null &&
        excludedDomains == null &&
        !elemHideOrDocException
  }

  private fun parseOptions(optionsPart: String?): ParsedRuleOptions? {
    if (optionsPart.isNullOrBlank()) {
      return ParsedRuleOptions(
        party = PartyRequirement.ANY,
        typeMask = ResourceType.ALL_MASK,
        allowedDomains = null,
        excludedDomains = null,
        elemHideOrDocException = false,
        unsupported = false,
      )
    }

    var party = PartyRequirement.ANY
    var explicitTypes = 0
    var excludedTypes = 0
    var allowedDomains: MutableSet<String>? = null
    var excludedDomains: MutableSet<String>? = null
    var elemHideOrDoc = false

    val parts = optionsPart.lowercase(Locale.ROOT).split(',')
    for (rawOpt in parts) {
      val opt = rawOpt.trim()
      if (opt.isEmpty()) continue
      when {
        opt == "third-party" || opt == "3p" -> party = PartyRequirement.THIRD_PARTY_ONLY
        opt == "~third-party" || opt == "first-party" || opt == "1p" ->
          party = PartyRequirement.FIRST_PARTY_ONLY
        opt == "script" -> explicitTypes = explicitTypes or ResourceType.SCRIPT.mask
        opt == "~script" -> excludedTypes = excludedTypes or ResourceType.SCRIPT.mask
        opt == "image" -> explicitTypes = explicitTypes or ResourceType.IMAGE.mask
        opt == "~image" -> excludedTypes = excludedTypes or ResourceType.IMAGE.mask
        opt == "stylesheet" || opt == "css" ->
          explicitTypes = explicitTypes or ResourceType.STYLESHEET.mask
        opt == "~stylesheet" || opt == "~css" ->
          excludedTypes = excludedTypes or ResourceType.STYLESHEET.mask
        opt == "xmlhttprequest" || opt == "xhr" ->
          explicitTypes = explicitTypes or ResourceType.XMLHTTPREQUEST.mask
        opt == "~xmlhttprequest" || opt == "~xhr" ->
          excludedTypes = excludedTypes or ResourceType.XMLHTTPREQUEST.mask
        opt == "subdocument" || opt == "frame" ->
          explicitTypes = explicitTypes or ResourceType.SUBDOCUMENT.mask
        opt == "~subdocument" -> excludedTypes = excludedTypes or ResourceType.SUBDOCUMENT.mask
        opt == "media" -> explicitTypes = explicitTypes or ResourceType.MEDIA.mask
        opt == "font" -> explicitTypes = explicitTypes or ResourceType.FONT.mask
        opt == "popup" -> explicitTypes = explicitTypes or ResourceType.POPUP.mask
        opt == "other" -> explicitTypes = explicitTypes or ResourceType.OTHER.mask
        opt == "document" || opt == "elemhide" || opt == "generichide" -> elemHideOrDoc = true
        opt == "important" || opt == "match-case" || opt == "ping" || opt == "websocket" -> {}
        opt.startsWith("domain=") -> {
          val domList = opt.substring("domain=".length).split('|')
          for (d in domList) {
            val cleanD = d.trim()
            if (cleanD.isEmpty()) continue
            if (cleanD.startsWith("~")) {
              val ex = cleanD.substring(1)
              if (ex.isNotEmpty()) {
                if (excludedDomains == null) excludedDomains = HashSet(4)
                excludedDomains.add(ex)
              }
            } else {
              if (allowedDomains == null) allowedDomains = HashSet(4)
              allowedDomains.add(cleanD)
            }
          }
        }
        opt.startsWith("csp=") ||
          opt.startsWith("rewrite=") ||
          opt.startsWith("redirect=") ||
          opt.startsWith("redirect-rule=") ||
          opt == "badfilter" -> {
          return ParsedRuleOptions(
            party = PartyRequirement.ANY,
            typeMask = 0,
            allowedDomains = null,
            excludedDomains = null,
            elemHideOrDocException = false,
            unsupported = true,
          )
        }
      }
    }

    val finalTypeMask =
      when {
        explicitTypes != 0 -> explicitTypes
        excludedTypes != 0 -> ResourceType.ALL_MASK and excludedTypes.inv()
        else -> ResourceType.ALL_MASK
      }

    return ParsedRuleOptions(
      party = party,
      typeMask = finalTypeMask,
      allowedDomains = allowedDomains,
      excludedDomains = excludedDomains,
      elemHideOrDocException = elemHideOrDoc,
      unsupported = false,
    )
  }

  private fun extractDomainFromAnchor(patternPart: String): String? {
    val clean = patternPart.removePrefix("||").removePrefix("|http://").removePrefix("|https://")
    val endIdx = clean.indexOfFirst { it == '^' || it == '/' || it == '*' }
    val host = (if (endIdx >= 0) clean.substring(0, endIdx) else clean).lowercase(Locale.ROOT)
    return if (isValidHostname(host)) host else null
  }

  private fun isValidHostname(candidate: String): Boolean {
    if (candidate.length < 4 || !candidate.contains('.')) return false
    if (candidate.startsWith('.') || candidate.endsWith('.')) return false
    for (i in 0 until candidate.length) {
      val c = candidate[i]
      if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-')) {
        return false
      }
    }
    return true
  }

  private fun isValidCssSelector(selector: String): Boolean {
    if (
      selector.contains(":-abp-") ||
        selector.contains(":has-text") ||
        selector.contains(":xpath") ||
        selector.contains(":upward") ||
        selector.contains(":remove") ||
        selector.contains(":style") ||
        selector.contains("{") ||
        selector.contains("}")
    ) {
      return false
    }
    return true
  }
}
