package com.robertlangat.todoplanner.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.robertlangat.todoplanner.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob integration.
 *
 * - Consent first: Google's User Messaging Platform shows the GDPR/US-states consent form where it's
 *   required. Ads are only requested once `canRequestAds()` is true.
 * - Banner: an anchored adaptive banner above the bottom navigation on Tasks, Calendar and Mine.
 * - Interstitial: only at natural breaks (after saving a task in the editor or saving a plan),
 *   at most once every [MIN_INTERVAL_MS], after every [EVERY_NTH_EVENT]th break, and never in the
 *   first minute of a session. Never on launch, never when ticking a checkbox, never in the widget
 *   or on the watch.
 */
class AdsManager(private val context: Context) {
    companion object {
        const val MIN_INTERVAL_MS = 3 * 60_000L
        const val EVERY_NTH_EVENT = 3
        const val SESSION_GRACE_MS = 60_000L
    }

    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initStarted = AtomicBoolean(false)

    private val _ready = MutableStateFlow(false)
    /** True once consent allows ads and the SDK is initialised. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    /** True when the user must be able to change their consent (show "Privacy options" in Settings). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private var lastShownAt = 0L
    private var breaks = 0
    private val sessionStart = SystemClock.elapsedRealtime()

    /** Call from the launcher Activity's onCreate. */
    fun gatherConsent(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> onConsentKnown() }
            },
            { _ -> onConsentKnown() }, // e.g. offline: fall back to the consent stored last time
        )
        // Consent obtained in a previous session lets ads start without waiting for the network.
        onConsentKnown()
    }

    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            onConsentKnown()
            onDone()
        }
    }

    private fun onConsentKnown() {
        _privacyOptionsRequired.value =
            consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (!consent.canRequestAds() || initStarted.getAndSet(true)) return
        scope.launch {
            withContext(Dispatchers.IO) { MobileAds.initialize(context) {} }
            _ready.value = true
            loadInterstitial()
        }
    }

    private fun loadInterstitial() {
        if (!_ready.value || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(
            context, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loadingInterstitial = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                    loadingInterstitial = false
                }
            },
        )
    }

    /** Call at a natural break. Shows an interstitial only if the frequency caps allow it. */
    fun onNaturalBreak(activity: Activity?) {
        breaks++
        val now = SystemClock.elapsedRealtime()
        val ad = interstitial
        val allowed = activity != null && ad != null &&
            breaks % EVERY_NTH_EVENT == 0 &&
            now - sessionStart >= SESSION_GRACE_MS &&
            (lastShownAt == 0L || now - lastShownAt >= MIN_INTERVAL_MS)
        if (!allowed) { loadInterstitial(); return }
        ad!!.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; loadInterstitial() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { interstitial = null; loadInterstitial() }
        }
        lastShownAt = now
        ad.show(activity!!)
    }
}
