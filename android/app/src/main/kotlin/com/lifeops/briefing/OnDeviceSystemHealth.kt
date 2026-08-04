package com.lifeops.briefing

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The on-device design for [Attention.compute]'s `system` parameter --
 * `Attention.kt`'s own kdoc flags this as deliberately NOT ported from
 * `attention.py`, because it can't be: `system.errors`/`age_mins` meant "is
 * the SERVER-SIDE AUTOMATION PROCESS healthy" (last successful scheduled
 * run, error count from that process) on the Python side, a concept with no
 * on-device equivalent -- there is no separate "automation process" running
 * on the phone the way there was a scheduled runner process on the server;
 * the periodic tick ([LifeOpsComputeWorker]) just runs (or doesn't, per
 * WorkManager's own scheduling) and either fetches data successfully or
 * doesn't. This file designs the genuinely-new on-device concept the kdoc
 * calls for, rather than inventing a fake "process" to monitor.
 *
 * ## The on-device concept: per-data-source sync freshness, not process health
 *
 * The semantic intent `attention.py` protects -- "some domain's data is
 * stale or broken, so escalate independently of whatever the last full
 * computation happened to show" -- maps naturally on-device to: "when did
 * FlowSavvy/YNAB each last successfully sync on THIS device, and how stale
 * is that." Two data sources feed [LifeOpsComputeWorker]'s tick
 * (`FlowSavvyClient`, `refreshYnabCategoriesIfConfigured`/`YnabRefresh.kt`),
 * each independently able to succeed or fail on any given 15-minute tick --
 * that per-source success/failure IS this app's closest on-device analog to
 * "is the automation actually working."
 *
 * ## Design/judgment calls, each a deliberate deviation from a literal port
 *
 * 1. **Persistence shape**: a single scalar "last successful sync" epoch-millis
 *    timestamp per source, gated in its own small SharedPreferences file --
 *    the exact same shape `WeeklyDigest.kt`'s `KEY_LAST_DIGEST_SENT_DATE` and
 *    `YnabWrite.kt`'s `KEY_LAST_YNAB_WRITE_DATE` already use for "a scalar
 *    dedup/staleness marker, not a relational record." No new Room table --
 *    those two files didn't need one for the same shape of data, and this
 *    isn't a history that needs querying, just "how long ago." YNAB doesn't
 *    even need a NEW marker: `YnabRefresh.kt` already persists exactly this
 *    (`KEY_LAST_YNAB_REFRESH_AT`, written only on success) plus a status
 *    string (`KEY_LAST_YNAB_REFRESH_STATUS`) this file reads directly rather
 *    than duplicating. Only FlowSavvy needed a new marker
 *    ([WidgetKeys.KEY_LAST_FLOWSAVVY_SYNC_AT]), since nothing tracked its
 *    sync recency before this file.
 *
 * 2. **"errors" -- populated for YNAB, deliberately NOT for FlowSavvy, and
 *    that asymmetry is real, not an oversight.** `runComputeTick` calls
 *    `fetch.fetch(now)` (the FlowSavvy fetch) essentially FIRST, and
 *    `LifeOpsComputeWorker.doComputeTick` lets a thrown
 *    `FlowSavvyConnectionException`/`FlowSavvyHttpException` propagate all
 *    the way out of `runComputeTick` (caught only in `doWork`, which then
 *    returns `Result.retry()`/`Result.success()` WITHOUT ever calling
 *    `applyComputeTickResult`). That means [Attention.compute] itself is
 *    architecturally UNREACHABLE on any tick where FlowSavvy's fetch just
 *    failed -- there is no live code path where "the most recent FlowSavvy
 *    attempt failed" could ever be read back by a running [Attention.compute]
 *    call, so a `flowSavvyLastAttemptFailed` flag would be dead weight that
 *    always reads `false` in practice. FlowSavvy's health is therefore
 *    surfaced ONLY through staleness (`ageMins`, below) -- a run of FlowSavvy
 *    failures shows up as the last-success timestamp falling further behind
 *    "now" on whichever LATER tick finally succeeds again and actually
 *    reaches [Attention.compute]. YNAB does not have this problem:
 *    `refreshYnabCategoriesIfConfigured` runs earlier in `doComputeTick` and
 *    catches its own failures internally (never throws out of `doComputeTick`),
 *    so a YNAB failure THIS tick is still known by the time this file reads
 *    `KEY_LAST_YNAB_REFRESH_STATUS` a few lines later in the SAME tick --
 *    real, current, worth surfacing as `errors["ynab"]`.
 *
 * 3. **"never synced yet" reads as "not yet applicable," not "maximally
 *    stale."** Mirrors this codebase's own existing convention for a missing
 *    signal -- `AttentionFacts.discretionaryDollars == null` already means
 *    "no YNAB figure yet, skip the money branch entirely" rather than "money
 *    is catastrophically low," and `Attention.compute`'s gym branch skips
 *    entirely when `gymLast7d`/`gymTarget` are null. A data source with no
 *    persisted last-success timestamp (a fresh install, before its very
 *    first successful sync) is EXCLUDED from the staleness computation below
 *    rather than treated as `age_mins = null`, which `attention.py`'s own
 *    branch (`age_mins is None or age_mins >= 120 -> risk`) would otherwise
 *    turn into an immediate false-positive "risk" on literally every fresh
 *    install's first tick. That Python `None -> risk` branch made sense
 *    server-side (a monitored process that SHOULD always report a real age
 *    failing to do so is itself suspicious); it does not hold on-device,
 *    where "never synced yet" is the normal, expected state for the first
 *    few minutes after install.
 *
 * 4. **If NEITHER source has any signal at all** (fresh install, first tick,
 *    YNAB unconfigured or never synced, FlowSavvy never synced, nothing has
 *    failed either), [computeOnDeviceSystemHealth] returns `null` --
 *    identical to passing `system = null` today. This deliberately avoids
 *    ever constructing a [SystemHealth] with `errors` empty AND `ageMins`
 *    null, which (per point 3) would incorrectly trip `attention.py`'s
 *    `age_mins is None -> risk` branch for a case that is not actually risky.
 *
 * 5. **Combining two sources into ONE `ageMins`**: the WORSE (larger, i.e.
 *    more stale) age among whichever sources have a known age, matching
 *    `attention.py`'s own worst-first posture everywhere else in this
 *    module (see `Attention.kt`'s `worstFirst`) -- one badly-stale source
 *    should not be hidden by another, fresher one.
 *
 * 6. **When is `ageMins` actually read, relative to this tick's own fetch?**
 *    [computeOnDeviceSystemHealth] is called in [LifeOpsComputeWorker.doComputeTick]
 *    BEFORE this tick's own FlowSavvy fetch runs (using whatever marker the
 *    PREVIOUS tick(s) left behind), not after. Computing it after would be
 *    circular -- a fetch that just succeeded THIS instant would always look
 *    perfectly fresh (age 0), which would make FlowSavvy staleness
 *    undetectable by definition. Reading the marker from before this tick's
 *    own attempt means a healthy 15-minute cadence reports `ageMins` around
 *    15 (last tick's success), naturally climbing tick over tick if fetches
 *    keep failing, which is the actual staleness signal this exists to
 *    detect. This also matches `attention.py`'s own framing of `system` as
 *    "runner health data" checked somewhat independently of the current
 *    computation, not a report on the current computation's own outcome.
 */

/** One data source's on-device sync status, as of "before this tick's own
 * attempt" (see this file's top-level kdoc, point 6). `lastSuccessEpochMillis`
 * is `null` for "never successfully synced yet" (point 3); `lastAttemptFailed`
 * is the "did the most recent attempt fail" boolean the task brief calls
 * for, deliberately unused for FlowSavvy (point 2). */
internal data class SourceSyncStatus(
    val lastSuccessEpochMillis: Long?,
    val lastAttemptFailed: Boolean = false,
    val lastErrorDescription: String? = null,
)

/**
 * Pure core -- directly unit-testable with no `Context`/SharedPreferences
 * dependency. Folds up to two [SourceSyncStatus] readings into one
 * [SystemHealth] (or `null`, meaning "pass `system = null`," see this file's
 * top-level kdoc point 4).
 *
 * [ynab] is `null` when YNAB isn't configured at all (no token) -- distinct
 * from a configured-but-never-synced [SourceSyncStatus] (`lastSuccessEpochMillis
 * = null`), since an unconfigured source should never contribute a "watch/risk"
 * staleness reason for a feature the user hasn't set up.
 */
internal fun computeOnDeviceSystemHealth(
    flowSavvy: SourceSyncStatus,
    ynab: SourceSyncStatus?,
    nowEpochMillis: Long,
): SystemHealth? {
    val ageMinsFor = { s: SourceSyncStatus ->
        s.lastSuccessEpochMillis?.let { (nowEpochMillis - it) / 60_000.0 }
    }
    val ages = listOfNotNull(ageMinsFor(flowSavvy), ynab?.let(ageMinsFor))
    val errors = buildMap {
        if (ynab?.lastAttemptFailed == true) {
            put("ynab", ynab.lastErrorDescription ?: "YNAB refresh failed")
        }
    }
    if (ages.isEmpty() && errors.isEmpty()) return null
    return SystemHealth(errors = errors, ageMins = ages.maxOrNull())
}

/** Reads FlowSavvy's on-device sync marker -- see [WidgetKeys.FLOWSAVVY_SYNC_PREFS_NAME]/
 * [WidgetKeys.KEY_LAST_FLOWSAVVY_SYNC_AT]'s own kdoc. FlowSavvy has no
 * "last attempt failed" marker -- see this file's top-level kdoc point 2 for
 * why that signal is architecturally unreachable for this specific source. */
internal fun readFlowSavvySyncStatus(context: Context): SourceSyncStatus {
    val prefs = context.getSharedPreferences(WidgetKeys.FLOWSAVVY_SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    val at = prefs.getLong(WidgetKeys.KEY_LAST_FLOWSAVVY_SYNC_AT, 0L)
    return SourceSyncStatus(lastSuccessEpochMillis = at.takeIf { it > 0 })
}

/** Marks a successful FlowSavvy fetch -- called from
 * [LifeOpsComputeWorker.doComputeTick] only after `runComputeTick` returns
 * without throwing (i.e. only on an actual fetch success, mirroring
 * `KEY_LAST_YNAB_REFRESH_AT`/`KEY_LAST_DIGEST_SENT_DATE`'s "written only on
 * success" rule). */
internal fun recordFlowSavvySyncSuccess(context: Context, nowEpochMillis: Long) {
    context.getSharedPreferences(WidgetKeys.FLOWSAVVY_SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(WidgetKeys.KEY_LAST_FLOWSAVVY_SYNC_AT, nowEpochMillis)
        .apply()
}

/** Reads YNAB's on-device sync status by reusing `YnabRefresh.kt`'s own
 * already-persisted marker/status/error keys (see this file's top-level kdoc
 * point 1) -- returns `null` (not just an "empty" status) when no YNAB token
 * is configured at all, so an unconfigured YNAB integration never
 * contributes a staleness/error reason (see [computeOnDeviceSystemHealth]'s
 * kdoc on the `ynab` parameter). */
internal fun readYnabSyncStatus(context: Context): SourceSyncStatus? {
    if (WidgetConfigStore.getYnabToken(context) == null) return null
    val prefs = context.getSharedPreferences(WidgetKeys.YNAB_REFRESH_PREFS_NAME, Context.MODE_PRIVATE)
    val at = prefs.getLong(WidgetKeys.KEY_LAST_YNAB_REFRESH_AT, 0L)
    val status = prefs.getString(WidgetKeys.KEY_LAST_YNAB_REFRESH_STATUS, null)
    val error = prefs.getString(WidgetKeys.KEY_LAST_YNAB_REFRESH_ERROR, null)
    return SourceSyncStatus(
        lastSuccessEpochMillis = at.takeIf { it > 0 },
        lastAttemptFailed = status == "error",
        lastErrorDescription = error,
    )
}

/** Real-dependency entry point called from [LifeOpsComputeWorker.doComputeTick]
 * -- reads both sources' current on-device sync status and folds them into
 * one [SystemHealth] (or `null`) via [computeOnDeviceSystemHealth]. See this
 * file's top-level kdoc point 6 for why this is called BEFORE this tick's
 * own FlowSavvy fetch, not after. */
internal fun computeOnDeviceSystemHealth(context: Context, now: LocalDateTime): SystemHealth? {
    val nowEpochMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return computeOnDeviceSystemHealth(readFlowSavvySyncStatus(context), readYnabSyncStatus(context), nowEpochMillis)
}
