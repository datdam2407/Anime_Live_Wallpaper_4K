package com.example.ads


import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Manages Google User Messaging Platform (UMP) GDPR/CCPA consent flow and gates
 * MobileAds SDK initialization + ad preloading until consent allows ad requests.
 */
class GoogleMobileAdsConsentManager(
    context: Context,
) {
    companion object {
        private const val TAG = "AdsConsentManager"
    }


    private val appContext = context.applicationContext
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isMobileAdsInitializeStarted = AtomicBoolean(false)


    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()


    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED


    /**
     * Requests fresh consent info from UMP and displays the GDPR/CCPA consent form when required
     * (e.g., for users in EEA/UK). Calls [onAdsReady] once [ConsentInformation.canRequestAds] is
     * true and [MobileAds.initialize] has completed.
     */
    fun gatherConsentAndInitializeAds(
        activity: Activity,
        onAdsReady: () -> Unit = {},
        onConsentGatheringComplete: (FormError?) -> Unit = {},
    ) {
        val params = ConsentRequestParameters.Builder().build()


        // If consent was already satisfied in a prior session, we can start initializing right away.
        if (consentInformation.canRequestAds()) {
            initializeMobileAdsIfAllowed(onAdsReady)
        }


        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        logW("UMP form error (${formError.errorCode}): ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAdsIfAllowed(onAdsReady)
                    } else {
                        _canRequestAds.value = false
                    }
                    onConsentGatheringComplete(formError)
                }
            },
            { requestConsentError ->
                logW("UMP info update error (${requestConsentError.errorCode}): ${requestConsentError.message}")
                if (consentInformation.canRequestAds()) {
                    initializeMobileAdsIfAllowed(onAdsReady)
                } else {
                    _canRequestAds.value = false
                }
                onConsentGatheringComplete(requestConsentError)
            },
        )
    }


    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (FormError?) -> Unit,
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            _canRequestAds.value = consentInformation.canRequestAds()
            onDismissed(formError)
        }
    }


    private fun initializeMobileAdsIfAllowed(onAdsReady: () -> Unit) {
        if (!consentInformation.canRequestAds()) return


        if (isMobileAdsInitializeStarted.getAndSet(true)) {
            if (_canRequestAds.value) {
                runOnMain(onAdsReady)
            }
            return
        }


        runCatching {
            MobileAds.initialize(appContext) {
                _canRequestAds.value = true
                runOnMain(onAdsReady)
            }
        }.onFailure { error ->
            isMobileAdsInitializeStarted.set(false)
            logW("MobileAds initialization failed: ${error.message}")
        }
    }


    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }


    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }
}