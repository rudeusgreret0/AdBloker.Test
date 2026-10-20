package com.example

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.adblocker.AdBlocker
import com.example.ui.theme.MyApplicationTheme
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AdBlocker.init(applicationContext)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        AdBlockTestScreen()
      }
    }
  }
}

private data class QuickTestPreset(val label: String, val url: String)

private val QUICK_TEST_PRESETS = listOf(
  QuickTestPreset("Forbes", "https://www.forbes.com"),
  QuickTestPreset("Speedtest", "https://www.speedtest.net"),
  QuickTestPreset("CNN", "https://edition.cnn.com"),
  QuickTestPreset("Wikia / Fandom", "https://minecraft.fandom.com"),
  QuickTestPreset("The Verge", "https://www.theverge.com"),
)

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun AdBlockTestScreen() {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current

  val isAdBlockEnabled by AdBlocker.enabledFlow.collectAsStateWithLifecycle()
  val blockedCount by AdBlocker.blockedCount.collectAsStateWithLifecycle()
  val filterStatus by AdBlocker.filterStatus.collectAsStateWithLifecycle()

  var addressInput by rememberSaveable { mutableStateOf("https://www.google.com") }
  var currentUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
  var canGoBack by remember { mutableStateOf(false) }
  var loadProgress by remember { mutableIntStateOf(100) }
  var lastUserTouchTimeMs by remember { mutableLongStateOf(0L) }

  // Fullscreen custom view state for HTML5 video players
  var customFullscreenView by remember { mutableStateOf<View?>(null) }
  var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

  val currentMainUrlRef = remember { AtomicReference("https://www.google.com") }
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }

  fun loadInputOrSearch(rawQuery: String) {
    focusManager.clearFocus()
    val targetUrl = AdBlocker.resolveInputToUrl(rawQuery)
    addressInput = targetUrl
    currentUrl = targetUrl
    currentMainUrlRef.set(targetUrl)
    AdBlocker.resetBlockedCount()
    webViewInstance?.loadUrl(targetUrl)
  }

  // Hardware/gesture Back button exits fullscreen video first, or navigates back in WebView history
  BackHandler(enabled = customFullscreenView != null || canGoBack) {
    if (customFullscreenView != null) {
      customViewCallback?.onCustomViewHidden()
      customFullscreenView = null
      customViewCallback = null
    } else {
      webViewInstance?.let { wv ->
        if (wv.canGoBack()) {
          wv.goBack()
          canGoBack = wv.canGoBack()
        }
      }
    }
  }

  val shieldCardBg by animateColorAsState(
    targetValue = if (isAdBlockEnabled) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    },
    animationSpec = tween(durationMillis = 260),
    label = "shieldCardBg"
  )

  val shieldBorderColor by animateColorAsState(
    targetValue = if (isAdBlockEnabled) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
      MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    },
    animationSpec = tween(durationMillis = 260),
    label = "shieldBorderColor"
  )

  val shieldIconScale by animateFloatAsState(
    targetValue = if (isAdBlockEnabled) 1.0f else 0.92f,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "shieldIconScale"
  )

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      // Compact, refined Top Control Surface
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Row 1: Back button + Address/Search bar + Reload + Search button
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            IconButton(
              onClick = {
                webViewInstance?.let { wv ->
                  if (wv.canGoBack()) {
                    wv.goBack()
                    canGoBack = wv.canGoBack()
                  }
                }
              },
              enabled = canGoBack,
              modifier = Modifier
                .size(44.dp)
                .testTag("back_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.navigate_back),
                tint = if (canGoBack) {
                  MaterialTheme.colorScheme.onSurface
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                }
              )
            }

            // Sleek capsule address / search bar
            Row(
              modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .border(
                  width = 1.dp,
                  color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                  shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = if (addressInput.startsWith("https://")) {
                  Icons.Outlined.Lock
                } else {
                  Icons.Outlined.Search
                },
                contentDescription = null,
                tint = if (addressInput.startsWith("https://")) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
              ) {
                if (addressInput.isEmpty()) {
                  Text(
                    text = stringResource(R.string.address_bar_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                BasicTextField(
                  value = addressInput,
                  onValueChange = { addressInput = it },
                  singleLine = true,
                  textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                  ),
                  cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                  keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search
                  ),
                  keyboardActions = KeyboardActions(
                    onSearch = { loadInputOrSearch(addressInput) },
                    onGo = { loadInputOrSearch(addressInput) }
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("address_input")
                )
              }

              if (addressInput.isNotEmpty()) {
                IconButton(
                  onClick = { addressInput = "" },
                  modifier = Modifier
                    .size(32.dp)
                    .testTag("clear_address_button")
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.clear_input),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                  )
                }
              }
            }

            Button(
              onClick = { loadInputOrSearch(addressInput) },
              shape = RoundedCornerShape(22.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              ),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
              modifier = Modifier
                .height(44.dp)
                .testTag("search_button")
            ) {
              Text(
                text = stringResource(R.string.search_button),
                style = MaterialTheme.typography.labelLarge
              )
            }
          }

          // Row 2: Stealth Ad Blocker Shield Bar + Live Animated Counter + Auto-Reload Switch
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(shieldCardBg)
              .border(
                width = 1.dp,
                color = shieldBorderColor,
                shape = RoundedCornerShape(16.dp)
              )
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier = Modifier
                  .size(34.dp)
                  .scale(shieldIconScale)
                  .clip(CircleShape)
                  .background(
                    if (isAdBlockEnabled) {
                      MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                      MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    }
                  ),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (isAdBlockEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                  contentDescription = null,
                  tint = if (isAdBlockEnabled) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
                  modifier = Modifier.size(18.dp)
                )
              }

              Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Text(
                    text = stringResource(R.string.ad_block_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isAdBlockEnabled) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.surface
                    }
                  ) {
                    Text(
                      text = if (isAdBlockEnabled) {
                        stringResource(R.string.ad_block_on)
                      } else {
                        stringResource(R.string.ad_block_off)
                      },
                      style = MaterialTheme.typography.labelMedium,
                      color = if (isAdBlockEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                  }
                }

                // Smooth animated live counter
                AnimatedContent(
                  targetState = blockedCount,
                  transitionSpec = {
                    (slideInVertically { height -> height / 2 } + fadeIn(tween(150)))
                      .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(150)))
                  },
                  label = "blockedCounterAnim"
                ) { count ->
                  val statusSuffix = if (filterStatus.isSyncing) " · Syncing lists…" else " · Stealth active"
                  Text(
                    text = stringResource(R.string.blocked_requests_count, count) +
                      if (isAdBlockEnabled) statusSuffix else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("blocked_counter_text")
                  )
                }
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              IconButton(
                onClick = {
                  AdBlocker.resetBlockedCount()
                  webViewInstance?.reload()
                },
                modifier = Modifier
                  .size(42.dp)
                  .testTag("reload_button")
              ) {
                Icon(
                  imageVector = Icons.Outlined.Refresh,
                  contentDescription = stringResource(R.string.reload_page),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(19.dp)
                )
              }

              Switch(
                checked = isAdBlockEnabled,
                onCheckedChange = { enabled ->
                  AdBlocker.setEnabled(enabled, context)
                  AdBlocker.resetBlockedCount()
                  webViewInstance?.clearCache(false)
                  webViewInstance?.reload()
                },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                  checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.testTag("ad_block_switch")
              )
            }
          }

          // Row 3: Quick Test Presets
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = stringResource(R.string.quick_test_sites),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QUICK_TEST_PRESETS.forEach { preset ->
              val isSelected = currentUrl.contains(preset.url.removePrefix("https://www."))
              Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                  .clip(RoundedCornerShape(999.dp))
                  .clickable { loadInputOrSearch(preset.url) }
                  .testTag("preset_${preset.label.lowercase().replace(" ", "_")}")
              ) {
                Text(
                  text = preset.label,
                  style = MaterialTheme.typography.labelLarge,
                  color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                  modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      }

      AnimatedVisibility(
        visible = loadProgress in 1..99,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        LinearProgressIndicator(
          progress = { loadProgress / 100f },
          modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
      }

      HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
      )

      // Main WebView + Fullscreen Video Container
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        AndroidView(
          modifier = Modifier
            .fillMaxSize()
            .testTag("main_webview"),
          factory = { ctx ->
            WebView(ctx).apply {
              layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
              )

              setOnTouchListener { _, event ->
                if (
                  event.action == MotionEvent.ACTION_DOWN ||
                    event.action == MotionEvent.ACTION_UP
                ) {
                  lastUserTouchTimeMs = System.currentTimeMillis()
                }
                false
              }

              settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false // Allows video players to auto-play smoothly after pre-roll skip

                // Strip "; wv" and "Version/4.0" from User-Agent so websites treat WebView like mobile Brave/Chrome
                userAgentString = userAgentString
                  .replace("; wv)", ")")
                  .replace("Version/4.0 ", "")
              }

              CookieManager.getInstance().setAcceptCookie(true)
              CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

              webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(
                  view: WebView,
                  request: WebResourceRequest,
                ): WebResourceResponse? {
                  return AdBlocker.interceptRequest(request, currentMainUrlRef.get())
                    ?: super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                  view: WebView,
                  request: WebResourceRequest,
                ): Boolean {
                  val url = request.url?.toString() ?: return false

                  if (!url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                  ) {
                    return true
                  }

                  if (AdBlocker.shouldBlockNavigation(request, currentMainUrlRef.get(), lastUserTouchTimeMs)) {
                    return true
                  }

                  return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                  super.onPageStarted(view, url, favicon)
                  if (!url.isNullOrBlank()) {
                    val previousUrl = currentMainUrlRef.get()
                    currentMainUrlRef.set(url)
                    currentUrl = url
                    addressInput = url
                    if (previousUrl != url) {
                      AdBlocker.resetBlockedCount()
                    }
                  }
                  canGoBack = view.canGoBack()
                  // Inject stealth anti-adblock shield as early as possible
                  if (AdBlocker.isEnabled()) {
                    view.evaluateJavascript(AdBlocker.cosmeticScript(url), null)
                  }
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                  super.onPageCommitVisible(view, url)
                  canGoBack = view.canGoBack()
                  if (AdBlocker.isEnabled()) {
                    view.evaluateJavascript(AdBlocker.cosmeticScript(url), null)
                  }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                  super.onPageFinished(view, url)
                  canGoBack = view.canGoBack()
                  if (AdBlocker.isEnabled()) {
                    view.evaluateJavascript(AdBlocker.cosmeticScript(url), null)
                  }
                }
              }

              webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                  loadProgress = newProgress
                  if (view != null && AdBlocker.isEnabled() && (newProgress >= 15)) {
                    view.evaluateJavascript(AdBlocker.cosmeticScript(view.url), null)
                  }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                  if (customFullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                  }
                  customFullscreenView = view
                  customViewCallback = callback
                }

                override fun onHideCustomView() {
                  customFullscreenView = null
                  customViewCallback?.onCustomViewHidden()
                  customViewCallback = null
                }

                override fun onCreateWindow(
                  view: WebView?,
                  isDialog: Boolean,
                  isUserGesture: Boolean,
                  resultMsg: Message?,
                ): Boolean {
                  val extraUrl = view?.hitTestResult?.extra
                  if (
                    AdBlocker.shouldAllowPopupToLoadInSameWebView(
                      isUserGesture = isUserGesture,
                      targetUrl = extraUrl,
                      currentPageUrl = view?.url,
                    ) && !extraUrl.isNullOrBlank()
                  ) {
                    view?.loadUrl(extraUrl)
                  }
                  return false
                }
              }

              webViewInstance = this
              loadUrl(currentUrl)
            }
          },
          update = { wv ->
            canGoBack = wv.canGoBack()
          }
        )

        // Fullscreen HTML5 video container when user taps fullscreen inside a video player
        if (customFullscreenView != null) {
          AndroidView(
            modifier = Modifier
              .fillMaxSize()
              .background(androidx.compose.ui.graphics.Color.Black),
            factory = { ctx ->
              FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT
                )
              }
            },
            update = { container ->
              container.removeAllViews()
              customFullscreenView?.let { v ->
                (v.parent as? ViewGroup)?.removeView(v)
                container.addView(
                  v,
                  FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                  )
                )
              }
            }
          )
        }
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewInstance?.stopLoading()
    }
  }
}
