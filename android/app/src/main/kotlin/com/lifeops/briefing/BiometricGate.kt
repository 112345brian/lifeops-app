package com.lifeops.briefing

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Reusable biometric/app-lock gating primitive.
 *
 * Per the locked-in product decision (see `docs/lifeops_capability_todo.md`'s
 * "On-Device Migration" section and this task's own brief): biometric/app-lock
 * gating applies to user-INITIATED writes and LLM calls that spend API
 * credit -- never to background/unattended automation. The 3 LLM calls moved
 * on-device in this same change (weekly digest, and the not-yet-wired YNAB
 * novel-payee categorization) are all background-automation-triggered, so
 * NONE of them call this object -- see `WeeklyDigest.kt`/`AnthropicClient.kt`'s
 * own comments at each call site.
 *
 * This primitive is built now, purely so Phase 5's native UI (a separate
 * in-flight task, out of scope here) has a ready-made gate to wire up for
 * whatever explicit user-initiated actions it adds later -- e.g. a
 * "regenerate briefing now" button, or a manual YNAB category override.
 * Nothing in this change calls [authenticate] from any automatic path.
 */
object BiometricGate {

    /** Whether this device can even show a biometric/device-credential
     * prompt right now. Check this BEFORE calling [authenticate] so a caller
     * can fall back to an explicit "not available" UI state instead of
     * calling [authenticate] and getting an [Result.Error] back. */
    sealed class Availability {
        /** A biometric or device-credential (PIN/pattern/password) prompt
         * can be shown. */
        object Available : Availability()

        /** No usable authenticator right now -- [reason] is a short,
         * human-readable explanation (no hardware, hardware temporarily
         * unavailable, or nothing enrolled), suitable for direct display or
         * logging. */
        data class Unavailable(val reason: String) : Availability()
    }

    /** Outcome of one [authenticate] call. */
    sealed class Result {
        /** The user authenticated successfully. Proceed with the gated action. */
        object Success : Result()

        /** The user declined or backed out (tapped cancel/the negative
         * button, or the system dismissed the prompt) -- not a hard error,
         * just "no, don't proceed." */
        data class Declined(val message: String) : Result()

        /** A real error prevented showing or completing the prompt (e.g.
         * lockout after too many failed attempts, no biometric/credential
         * enrolled, hardware unavailable). [errorCode] is one of
         * [BiometricPrompt]'s `ERROR_*` constants. */
        data class Error(val errorCode: Int, val message: String) : Result()
    }

    /** [BiometricPrompt.ERROR_*] codes that mean "the user chose not to
     * proceed" rather than "something is actually broken" -- mapped to
     * [Result.Declined] instead of [Result.Error] so a caller can treat them
     * as a plain no rather than surfacing an error UI. */
    private val DECLINED_ERROR_CODES = setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
    )

    /** Checks whether [authenticate] can currently show a prompt --
     * call this first so a caller can render a real "not available" state
     * instead of unconditionally invoking [authenticate]. */
    fun availability(context: Context): Availability {
        val manager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.Unavailable("no biometric hardware")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.Unavailable("biometric hardware temporarily unavailable")
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.Unavailable("no biometric or device credential enrolled")
            else -> Availability.Unavailable("biometric authentication is not available")
        }
    }

    /**
     * Shows a biometric/device-credential prompt and suspends until the user
     * succeeds, declines, or an error occurs. Call ONLY for a user-initiated
     * action the product decision above says should be gated -- never from a
     * background worker (there is no foreground Activity to show a prompt
     * from there anyway, and gating unattended automation is explicitly the
     * thing this decision rules out).
     *
     * @param activity the host activity; must be a [FragmentActivity] (the
     *   type [BiometricPrompt] itself requires). This app's current launcher
     *   activity (`SettingsActivity`) is a plain `ComponentActivity`, not a
     *   `FragmentActivity` -- wiring this up is Phase 5's job, once a real
     *   user-initiated action exists to gate.
     * @param title shown in the system prompt.
     * @param subtitle optional secondary line.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        executor: Executor = ContextCompat.getMainExecutor(activity),
    ): Result = suspendCancellableCoroutine { cont ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(Result.Success)
            }

            override fun onAuthenticationFailed() {
                // A single rejected attempt (e.g. wrong finger) -- the system
                // prompt stays open for another try, so this is NOT terminal;
                // do not resume the coroutine here.
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                val outcome = if (errorCode in DECLINED_ERROR_CODES) {
                    Result.Declined(errString.toString())
                } else {
                    Result.Error(errorCode, errString.toString())
                }
                cont.resume(outcome)
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        if (subtitle != null) infoBuilder.setSubtitle(subtitle)

        cont.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(infoBuilder.build())
    }
}
