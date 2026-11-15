package com.sbro.emucorex.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.sbro.emucorex.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modular AdMob manager providing policy-compliant advertising for Grand Theft Rickshaw.
 *
 * Rules strictly followed:
 * 1. Never cover gameplay controls or active gameplay.
 * 2. Never unexpectedly interrupt active emulation.
 * 3. Ads load asynchronously without blocking app startup.
 * 4. Fails gracefully if ad fill fails or offline.
 * 5. Uses official Google test ad units during development.
 */
object AdMobManager {
    private const val TAG = "AdMobManager"
    private const val MIN_INTERSTITIAL_INTERVAL_MS = 60_000L // 60s cooldown

    private val isInitialized = AtomicBoolean(false)
    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialLoading = false
    private var lastInterstitialShowTime = 0L

    private var rewardedAd: RewardedAd? = null
    private var isRewardedLoading = false

    fun initialize(activity: Activity) {
        if (isInitialized.getAndSet(true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Request UMP consent first
                val params = ConsentRequestParameters.Builder()
                    .setTagForUnderAgeOfConsent(false)
                    .build()

                val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
                consentInfo.requestConsentInfoUpdate(
                    activity,
                    params,
                    {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                            if (formError != null) {
                                Log.w(TAG, "Consent form error: ${formError.message}")
                            }
                            if (consentInfo.canRequestAds()) {
                                initMobileAds(activity)
                            }
                        }
                    },
                    { error ->
                        Log.w(TAG, "Consent info update failed: ${error.message}")
                        initMobileAds(activity)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "AdMob initialization error", e)
                initMobileAds(activity)
            }
        }
    }

    private fun initMobileAds(context: Context) {
        MobileAds.initialize(context) { status ->
            Log.i(TAG, "Google Mobile Ads SDK initialized: $status")
            preloadInterstitial(context)
            preloadRewarded(context)
        }
    }

    fun preloadInterstitial(context: Context) {
        if (interstitialAd != null || isInterstitialLoading) return
        isInterstitialLoading = true

        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isInterstitialLoading = false
                    Log.d(TAG, "Interstitial ad preloaded successfully")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    isInterstitialLoading = false
                    Log.d(TAG, "Interstitial ad failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    fun showInterstitialIfReady(activity: Activity, onDismissed: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val ad = interstitialAd
        if (ad != null && (now - lastInterstitialShowTime >= MIN_INTERSTITIAL_INTERVAL_MS)) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    lastInterstitialShowTime = SystemClock.elapsedRealtime()
                    preloadInterstitial(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    preloadInterstitial(activity)
                    onDismissed()
                }
            }
            ad.show(activity)
        } else {
            // Not ready or within cooldown; proceed immediately
            onDismissed()
        }
    }

    fun preloadRewarded(context: Context) {
        if (rewardedAd != null || isRewardedLoading) return
        isRewardedLoading = true

        val request = AdRequest.Builder().build()
        RewardedAd.load(
            context,
            BuildConfig.ADMOB_REWARDED_ID,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isRewardedLoading = false
                    Log.d(TAG, "Rewarded ad preloaded successfully")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    rewardedAd = null
                    isRewardedLoading = false
                    Log.d(TAG, "Rewarded ad failed to load: ${loadAdError.message}")
                }
            }
        )
    }

    fun showRewarded(activity: Activity, onUserEarnedReward: () -> Unit, onDismissed: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    preloadRewarded(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    rewardedAd = null
                    preloadRewarded(activity)
                    onDismissed()
                }
            }
            ad.show(activity) { reward ->
                Log.d(TAG, "User earned reward: ${reward.amount} ${reward.type}")
                onUserEarnedReward()
            }
        } else {
            preloadRewarded(activity)
            onDismissed()
        }
    }
}

/**
 * Standard Banner Ad Composable for menus and launcher screens.
 */
@Composable
fun AdMobBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = BuildConfig.ADMOB_BANNER_ID
) {
    val adView = remember { mutableMapOf<String, AdView>() }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
        update = { /* Updates if needed */ }
    )

    DisposableEffect(Unit) {
        onDispose {
            adView.values.forEach { it.destroy() }
        }
    }
}
