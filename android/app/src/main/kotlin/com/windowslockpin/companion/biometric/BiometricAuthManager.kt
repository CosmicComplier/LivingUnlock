package com.windowslockpin.companion.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.windowslockpin.companion.core.model.SafeLogger
import java.security.Signature

class BiometricAuthManager(
    private val activity: FragmentActivity
) {
    private var activePrompt: BiometricPrompt? = null

    fun cancelAuthentication() {
        activePrompt?.cancelAuthentication()
        activePrompt = null
    }

    interface Callback {
        fun onAuthenticated(authenticatedSignature: Signature)
        fun onAuthenticationFailed()
        fun onError(errorCode: Int, errString: CharSequence)
    }

    fun canAuthenticateStrong(): Int {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }

    fun authenticateWithCrypto(
        signature: Signature,
        title: String,
        subtitle: String,
        description: String,
        negativeButtonText: String,
        callback: Callback
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    SafeLogger.i(TAG, "Biometric authentication succeeded")
                    val authedSignature = result.cryptoObject?.signature
                    if (authedSignature != null) {
                        callback.onAuthenticated(authedSignature)
                    } else {
                        SafeLogger.e(TAG, "Biometric result did not contain CryptoObject signature")
                        callback.onError(-1, "CryptoObject signature was null")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    SafeLogger.w(TAG, "Biometric authentication error: code=$errorCode, msg=$errString")
                    callback.onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    SafeLogger.w(TAG, "Biometric authentication attempt failed (unrecognized credential)")
                    callback.onAuthenticationFailed()
                }
            }
        )

        val cryptoObject = BiometricPrompt.CryptoObject(signature)
        activePrompt = biometricPrompt
        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }

    companion object {
        private const val TAG = "BiometricAuthManager"
    }
}
