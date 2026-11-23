package com.robertlangat.todoplanner.ads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.robertlangat.todoplanner.BuildConfig
import com.robertlangat.todoplanner.container

/** Anchored adaptive banner. Renders nothing until consent allows ads and the SDK is ready. */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ready by context.container.ads.ready.collectAsStateWithLifecycle()
    if (!ready) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(320)
        val adSize = remember(widthDp) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp) }
        val adView = remember(widthDp) {
            AdView(context).apply {
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                setAdSize(adSize)
                loadAd(AdRequest.Builder().build())
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { adView.pause() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { adView.resume() }
        DisposableEffect(adView) { onDispose { adView.destroy() } }
        // Reserve the banner's height up front so content doesn't jump when the ad loads.
        AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth().height(adSize.height.dp))
    }
}
