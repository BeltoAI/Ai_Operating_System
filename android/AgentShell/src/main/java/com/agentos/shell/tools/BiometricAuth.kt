package com.agentos.shell.tools

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Thin wrapper over the platform BiometricPrompt (API 28+). Used only as a convenience GATE — on success
 * the caller decrypts a Keystore-cached PIN via [BankVault.biometricPin]. The PIN remains the real root, so
 * this never weakens the vault; it just saves typing. All calls fail safe (return/no-op) if unavailable.
 */
object BiometricAuth {
    /** True if the device has usable biometrics enrolled. */
    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val bm = ctx.getSystemService(BiometricManager::class.java)
            @Suppress("DEPRECATION")
            bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) { false }
    }

    /** Show the system fingerprint/face prompt. Calls [onSuccess] or [onFail] on the main thread. */
    fun prompt(ctx: Context, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT < 28) { onFail("Biometrics not supported."); return }
            val exec = ctx.mainExecutor
            val prompt = BiometricPrompt.Builder(ctx)
                .setTitle(title)
                .setDescription("Unlock your bank vault")
                .setNegativeButton("Use PIN", exec) { _, _ -> onFail("cancelled") }
                .build()
            prompt.authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onSuccess() }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) { onFail(msg?.toString() ?: "error") }
            })
        } catch (e: Exception) { onFail(e.message ?: "error") }
    }
}
