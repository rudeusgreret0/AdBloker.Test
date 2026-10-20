package com.example

import com.example.adblocker.AdBlocker
import com.example.adblocker.FilterList
import com.example.adblocker.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExampleUnitTest {

  @Before
  fun setUp() {
    AdBlocker.setEnabled(true)
    AdBlocker.resetBlockedCount()
  }

  @Test
  fun neverBlocksMainFramePage() {
    assertFalse(
      AdBlocker.shouldBlock(
        url = "https://doubleclick.net/main_page",
        isMainFrame = true,
        pageUrlOrHost = "https://www.forbes.com"
      )
    )
  }

  @Test
  fun blocksKnownAdAndTrackerDomainsAndSubdomains() {
    assertTrue(
      AdBlocker.shouldBlock(
        url = "https://securepubads.g.doubleclick.net/gpt/pubads_impl_2026.js",
        isMainFrame = false,
        pageUrlOrHost = "https://www.theverge.com",
        resourceType = ResourceType.SCRIPT
      )
    )
    assertTrue(
      AdBlocker.shouldBlock(
        url = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        isMainFrame = false,
        pageUrlOrHost = "https://edition.cnn.com",
        resourceType = ResourceType.SCRIPT
      )
    )
    assertTrue(
      AdBlocker.shouldBlock(
        url = "https://sb.scorecardresearch.com/beacon.js",
        isMainFrame = false,
        pageUrlOrHost = "https://www.forbes.com",
        resourceType = ResourceType.SCRIPT
      )
    )
  }

  @Test
  fun preservesFirstPartyContentAndWhitelistedCdns() {
    assertFalse(
      AdBlocker.shouldBlock(
        url = "https://static.theverge.com/assets/app.bundle.js",
        isMainFrame = false,
        pageUrlOrHost = "https://www.theverge.com",
        resourceType = ResourceType.SCRIPT
      )
    )
    assertFalse(
      AdBlocker.shouldBlock(
        url = "https://www.gstatic.com/recaptcha/releases/v1/recaptcha__en.js",
        isMainFrame = false,
        pageUrlOrHost = "https://www.theverge.com",
        resourceType = ResourceType.SCRIPT
      )
    )
  }

  @Test
  fun respectsEnabledFlagAndCosmeticScript() {
    AdBlocker.setEnabled(false)
    assertFalse(
      AdBlocker.shouldBlock(
        url = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        isMainFrame = false,
        pageUrlOrHost = "https://www.forbes.com"
      )
    )

    AdBlocker.setEnabled(true)
    val script = AdBlocker.cosmeticScript("https://www.forbes.com")
    assertTrue(script.contains("adsbygoogle"))
    assertTrue(script.contains("MutationObserver"))
  }

  @Test
  fun parsesCustomEasyListRulesWithExceptionsAndThirdPartyOptions() {
    val customRules = FilterList.compileRules(
      listOf(
        "||ads.customnetwork.org^",
        "||tracker.example.net^\$third-party",
        "@@||safe.ads.customnetwork.org^",
        "##.custom-ad-box",
        "news.example.com##.promoted-story"
      ),
      isFull = false
    )
    assertTrue(customRules.pureBlockedDomains.contains("ads.customnetwork.org"))
    assertTrue(customRules.pureExceptionDomains.contains("safe.ads.customnetwork.org"))
    assertTrue(customRules.conditionalBlockedDomains.containsKey("tracker.example.net"))
    assertTrue(customRules.genericCosmeticSelectors.contains(".custom-ad-box"))
    assertEquals(listOf(".promoted-story"), customRules.domainCosmeticSelectors["news.example.com"])
  }
}
