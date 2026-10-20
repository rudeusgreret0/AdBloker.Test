package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.adblocker.AdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context and verify AdBlocker persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AdBlock Test", appName)

    AdBlocker.setEnabled(false, context)
    assertFalse(AdBlocker.isEnabled())

    AdBlocker.setEnabled(true, context)
    assertTrue(AdBlocker.isEnabled())

    assertEquals("https://www.forbes.com", AdBlocker.resolveInputToUrl("www.forbes.com"))
    assertEquals(
      "https://www.google.com/search?q=android%20webview%20adblock",
      AdBlocker.resolveInputToUrl("android webview adblock")
    )
  }
}
