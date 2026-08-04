package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises OnDeviceSystemHealth.kt: the pure folding logic
 * ([computeOnDeviceSystemHealth]'s `SourceSyncStatus` overload) with no
 * Android dependency, plus the real SharedPreferences-backed
 * read/record functions against Robolectric's `RuntimeEnvironment`. See that
 * file's top-level kdoc for the full design and every judgment call these
 * tests are pinning down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnDeviceSystemHealthTest {

    private val neverSynced = SourceSyncStatus(lastSuccessEpochMillis = null)

    // ---- pure folding logic ----

    @Test
    fun computeOnDeviceSystemHealth_returnsNullWhenNothingHasEverSyncedOrFailed() {
        // Fresh install, first tick: FlowSavvy has no marker yet, YNAB isn't
        // configured at all. Must NOT synthesize a SystemHealth with
        // ageMins=null -- that would trip attention.py's "age_mins is None
        // -> risk" branch for a state that is not actually risky (see
        // OnDeviceSystemHealth.kt point 3/4).
        val result = computeOnDeviceSystemHealth(neverSynced, ynab = null, nowEpochMillis = 1_000_000L)

        assertNull(result)
    }

    @Test
    fun computeOnDeviceSystemHealth_freshFlowSavvySyncIsLowAgeAndOk() {
        val now = 1_000_000_000L
        val flowSavvy = SourceSyncStatus(lastSuccessEpochMillis = now - 60_000L) // 1 minute ago

        val health = computeOnDeviceSystemHealth(flowSavvy, ynab = null, nowEpochMillis = now)

        assertEquals(1.0, health!!.ageMins!!, 0.001)
        assertTrue(health.errors.isEmpty())
        // Feed straight into compute() -- a 1-minute-old sync must not raise
        // the system domain at all (below the 30-min watch threshold).
        val attention = compute(AttentionFacts(), system = health)
        assertEquals(Severity.OK, attention.state)
    }

    @Test
    fun computeOnDeviceSystemHealth_staleFlowSavvySyncEscalatesToRisk() {
        val now = 1_000_000_000L
        val flowSavvy = SourceSyncStatus(lastSuccessEpochMillis = now - (150 * 60_000L)) // 150 minutes ago

        val health = computeOnDeviceSystemHealth(flowSavvy, ynab = null, nowEpochMillis = now)
        val attention = compute(AttentionFacts(), system = health)

        assertEquals(150.0, health!!.ageMins!!, 0.001)
        assertEquals(Severity.RISK, attention.state)
        assertTrue(attention.reasons.any { it.domain == Domain.SYSTEM })
    }

    @Test
    fun computeOnDeviceSystemHealth_moderatelyStaleFlowSavvySyncIsWatch() {
        val now = 1_000_000_000L
        val flowSavvy = SourceSyncStatus(lastSuccessEpochMillis = now - (45 * 60_000L)) // 45 minutes ago

        val health = computeOnDeviceSystemHealth(flowSavvy, ynab = null, nowEpochMillis = now)
        val attention = compute(AttentionFacts(), system = health)

        assertEquals(Severity.WATCH, attention.state)
    }

    @Test
    fun computeOnDeviceSystemHealth_neverSyncedFlowSavvyIsExcludedNotMaximallyStale() {
        // FlowSavvy has never synced, but YNAB has (recently) -- FlowSavvy's
        // missing marker must not contribute a "null age" the way a naive
        // Python-style None would; only YNAB's real, fresh age is used.
        val now = 1_000_000_000L
        val ynab = SourceSyncStatus(lastSuccessEpochMillis = now - 60_000L)

        val health = computeOnDeviceSystemHealth(neverSynced, ynab = ynab, nowEpochMillis = now)
        val attention = compute(AttentionFacts(), system = health)

        assertEquals(1.0, health!!.ageMins!!, 0.001)
        assertEquals(Severity.OK, attention.state)
    }

    @Test
    fun computeOnDeviceSystemHealth_worstOfBothSourcesWins() {
        val now = 1_000_000_000L
        val freshFlowSavvy = SourceSyncStatus(lastSuccessEpochMillis = now - 60_000L) // 1 min
        val staleYnab = SourceSyncStatus(lastSuccessEpochMillis = now - (150 * 60_000L)) // 150 min

        val health = computeOnDeviceSystemHealth(freshFlowSavvy, ynab = staleYnab, nowEpochMillis = now)

        assertEquals(150.0, health!!.ageMins!!, 0.001)
    }

    @Test
    fun computeOnDeviceSystemHealth_ynabAttemptFailureProducesErrorsAndFucked() {
        val now = 1_000_000_000L
        val freshFlowSavvy = SourceSyncStatus(lastSuccessEpochMillis = now - 60_000L)
        val failedYnab = SourceSyncStatus(
            lastSuccessEpochMillis = now - (10 * 60_000L),
            lastAttemptFailed = true,
            lastErrorDescription = "YnabConnectionException: timed out",
        )

        val health = computeOnDeviceSystemHealth(freshFlowSavvy, ynab = failedYnab, nowEpochMillis = now)
        val attention = compute(AttentionFacts(), system = health)

        assertEquals(mapOf("ynab" to "YnabConnectionException: timed out"), health!!.errors)
        assertEquals(Severity.FUCKED, attention.state)
        assertTrue(attention.reasons.any { it.domain == Domain.SYSTEM && it.severity == Severity.FUCKED })
    }

    @Test
    fun computeOnDeviceSystemHealth_ynabNeverSyncedButJustFailedStillReportsError() {
        // First-ever YNAB attempt, and it failed: no age signal at all (never
        // succeeded), but the failure itself is real and current -- must
        // still surface as an error, not be silently dropped because there's
        // no age to report alongside it.
        val now = 1_000_000_000L
        val neverSyncedFailedYnab = SourceSyncStatus(
            lastSuccessEpochMillis = null,
            lastAttemptFailed = true,
            lastErrorDescription = null,
        )

        val health = computeOnDeviceSystemHealth(neverSynced, ynab = neverSyncedFailedYnab, nowEpochMillis = now)

        assertNull(health!!.ageMins)
        assertEquals(mapOf("ynab" to "YNAB refresh failed"), health.errors)
    }

    // ---- Context-backed read/record wrappers ----

    @Test
    fun readFlowSavvySyncStatus_neverRecordedReturnsNullTimestamp() {
        val context = RuntimeEnvironment.getApplication()
        // Isolate from any state a previous test in this run may have left
        // in the same SharedPreferences file name.
        context.getSharedPreferences(WidgetKeys.FLOWSAVVY_SYNC_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val status = readFlowSavvySyncStatus(context)

        assertNull(status.lastSuccessEpochMillis)
    }

    @Test
    fun recordFlowSavvySyncSuccess_thenReadReflectsTheTimestamp() {
        val context = RuntimeEnvironment.getApplication()

        recordFlowSavvySyncSuccess(context, 42_000L)
        val status = readFlowSavvySyncStatus(context)

        assertEquals(42_000L, status.lastSuccessEpochMillis)
    }

    @Test
    fun readYnabSyncStatus_returnsNullWhenNoTokenConfigured() {
        val context = RuntimeEnvironment.getApplication()

        val status = readYnabSyncStatus(context)

        assertNull(status)
    }

    // NOTE: the "YNAB IS configured" branch of readYnabSyncStatus (reading
    // WidgetKeys.KEY_LAST_YNAB_REFRESH_AT/_STATUS/_ERROR once
    // WidgetConfigStore.getYnabToken returns non-null) is not exercised here
    // -- WidgetConfigStore's EncryptedSharedPreferences requires a real
    // AndroidKeyStore provider that Robolectric doesn't back by default (no
    // other test in this suite exercises WidgetConfigStore for the same
    // reason). The marker-parsing logic itself (lastSuccessEpochMillis 0 =
    // never / lastAttemptFailed = status=="error" / lastErrorDescription) is
    // simple, direct field reads with no branching of its own worth a
    // separate seam -- the folding logic that actually matters
    // ([computeOnDeviceSystemHealth]'s pure overload above) is fully covered
    // against hand-built [SourceSyncStatus] values instead.
}
