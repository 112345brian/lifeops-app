package com.lifeops.briefing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Exercises `YnabWrite.kt` -- the on-device read-decide-write pipeline
 * wiring `YnabEngine.kt`'s decision logic and `AnthropicClient.kt`'s
 * `categorizeUnknownPayee` to real YNAB fetch/PATCH calls. No real network
 * call anywhere: [YnabFetch]/[YnabWriteClient]/[YnabBudgetWriteClient] are
 * injected fakes (mirrors `LifeOpsComputeWorkerTest.kt`'s fake
 * [FlowSavvyFetch] pattern), and the LLM call is a plain injected function
 * (mirrors `AnthropicClientTest.kt`'s own no-network parsing tests) rather
 * than a real [AnthropicClient]. Robolectric is required because
 * `org.json.JSONObject`/`Context.getSharedPreferences` are real Android
 * stubs otherwise -- same reasoning `AnthropicClientTest.kt`/
 * `LifeOpsComputeWorkerTest.kt` already document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YnabWriteTest {

    private val now = LocalDateTime.of(2026, 8, 2, 9, 0, 0)

    // ---- JSON parsing ----

    @Test
    fun parseYnabCategories_filtersHiddenAndDeleted() {
        val data = JSONObject(
            """
            {"category_groups": [
                {"categories": [
                    {"id": "c1", "name": "Eating Out"},
                    {"id": "c2", "name": "Hidden", "hidden": true},
                    {"id": "c3", "name": "Deleted", "deleted": true}
                ]}
            ]}
            """.trimIndent(),
        )

        val cats = parseYnabCategories(data)

        assertEquals(listOf(YnabCategory("c1", "Eating Out")), cats)
    }

    @Test
    fun parseYnabHistoryTxns_readsApprovedCategoryPayee() {
        val data = JSONObject(
            """{"transactions": [{"approved": true, "category_id": "c1", "payee_name": "Chipotle"}]}""",
        )

        val hist = parseYnabHistoryTxns(data)

        assertEquals(listOf(YnabHistoryTxn(true, "c1", "Chipotle")), hist)
    }

    @Test
    fun parseYnabUnapprovedTxns_readsAllFieldsIncludingExplicitNulls() {
        val data = JSONObject(
            """{"transactions": [{"id": "t1", "amount": -12000, "payee_name": null, "category_id": null, "transfer_account_id": null}]}""",
        )

        val txns = parseYnabUnapprovedTxns(data)

        assertEquals(listOf(YnabUnapprovedTxn("t1", -12000, null, null, null)), txns)
    }

    @Test
    fun parseYnabMonth_filtersHiddenAndDeletedAndReadsBalanceBudgeted() {
        val data = JSONObject(
            """
            {"month": {"categories": [
                {"id": "c1", "name": "Shopping", "balance": -5000, "budgeted": 10000},
                {"id": "c2", "name": "Hidden", "balance": 0, "budgeted": 0, "hidden": true}
            ]}}
            """.trimIndent(),
        )

        val month = parseYnabMonth(data)

        assertEquals(listOf(YnabMonthCategory("c1", "Shopping", -5000, 10000)), month.categories)
    }

    // ---- resolveNovelPayees ----

    @Test
    fun resolveNovelPayees_appliesLlmCategoryAndAutoApprovesSmallAmount() {
        val cats = listOf(YnabCategory("c1", "Groceries"), YnabCategory("c2", "Savings"))
        val novel = listOf(YnabNovelTxn("t1", "Trader Joe's", 42.50))

        val result = resolveNovelPayees(novel, cats, YNAB_NO_ASSIGN_DEFAULT) { payee, amount, names ->
            assertEquals("Trader Joe's", payee)
            assertEquals(42.50, amount, 0.001)
            assertFalse("Savings" in names) // no_assign categories must never be offered to the LLM
            "Groceries"
        }

        assertEquals(listOf(YnabCategorizeAction("t1", "c1")), result.categorize)
        assertEquals(listOf("t1"), result.approve)
    }

    @Test
    fun resolveNovelPayees_largeAmountCategorizedButNotAutoApproved() {
        val cats = listOf(YnabCategory("c1", "Rent"))
        val novel = listOf(YnabNovelTxn("t1", "Landlord LLC", 2000.0)) // >= $150 REVIEW threshold

        val result = resolveNovelPayees(novel, cats, emptyList()) { _, _, _ -> "Rent" }

        assertEquals(listOf(YnabCategorizeAction("t1", "c1")), result.categorize)
        assertTrue(result.approve.isEmpty())
    }

    @Test
    fun resolveNovelPayees_llmReturningNullOrUnknownCategoryLeavesTransactionUnresolved() {
        val cats = listOf(YnabCategory("c1", "Groceries"))
        val novel = listOf(YnabNovelTxn("t1", "Mystery Corp", 10.0), YnabNovelTxn("t2", "Other Corp", 5.0))

        val result = resolveNovelPayees(novel, cats, emptyList()) { payee, _, _ ->
            if (payee == "Mystery Corp") null else "Not A Real Category"
        }

        assertTrue(result.categorize.isEmpty())
        assertTrue(result.approve.isEmpty())
    }

    @Test
    fun resolveNovelPayees_emptyNovelListNeverCallsLlm() {
        var called = false
        val result = resolveNovelPayees(emptyList(), emptyList(), emptyList()) { _, _, _ -> called = true; null }

        assertFalse(called)
        assertTrue(result.categorize.isEmpty())
    }

    // ---- buildYnabWriteDecision ----

    @Test
    fun buildYnabWriteDecision_mergesCategorizeAndApproveIntoOneUpdatePerTransaction() {
        val plan = YnabPlan(
            categorize = listOf(YnabCategorizeAction("t1", "c1")),
            approve = listOf("t1", "t2"),
            novel = listOf(YnabNovelTxn("t3", "X", 1.0)),
            holds = listOf(YnabHold("t4", "large/unusual")),
            cover = listOf(YnabCoverAction("c9", 5000)),
        )
        val novelResolution = NovelResolution(
            categorize = listOf(YnabCategorizeAction("t3", "c2")),
            approve = emptyList(),
        )

        val decision = buildYnabWriteDecision(plan, novelResolution)

        val byId = decision.updates.associateBy { it.id }
        assertEquals("c1", byId.getValue("t1").categoryId)
        assertEquals(true, byId.getValue("t1").approved)
        assertNull(byId.getValue("t2").categoryId)
        assertEquals(true, byId.getValue("t2").approved)
        assertEquals("c2", byId.getValue("t3").categoryId)
        assertNull(byId.getValue("t3").approved) // categorized via LLM but not auto-approved
        assertEquals(3, decision.updates.size)
        assertEquals(2, decision.categorizedCount)
        assertEquals(2, decision.approvedCount)
        assertEquals(1, decision.novelCount)
        assertEquals(1, decision.heldCount)
        assertEquals(listOf(YnabCoverAction("c9", 5000)), decision.cover)
    }

    // ---- isYnabWriteDue ----

    @Test
    fun isYnabWriteDue_falseWhenAlreadyRunToday() {
        assertFalse(isYnabWriteDue(now, "2026-08-02"))
    }

    @Test
    fun isYnabWriteDue_trueWhenNeverRunOrDifferentDay() {
        assertTrue(isYnabWriteDue(now, null))
        assertTrue(isYnabWriteDue(now, "2026-08-01"))
    }

    // ---- runYnabWriteTickIfDue: full fetch-decide-write flow ----

    private val cats = listOf(
        YnabCategory("c-eat", "Eating Out"),
        YnabCategory("c-sav", "Savings"),
    )

    private fun fakeFetch(
        history: List<YnabHistoryTxn> = emptyList(),
        unapproved: List<YnabUnapprovedTxn> = emptyList(),
        month: YnabMonth = YnabMonth(emptyList()),
    ) = YnabFetch { YnabFetchResult(cats, history, unapproved, month) }

    @Test
    fun runYnabWriteTickIfDue_knownPayeeIsCategorizedAndApprovedViaWrite() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val history = List(4) { YnabHistoryTxn(true, "c-eat", "Chipotle") }
        val unapproved = listOf(YnabUnapprovedTxn("t1", -12_000, "Chipotle", null, null))
        var writtenUpdates: List<YnabTxnUpdate>? = null

        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = fakeFetch(history = history, unapproved = unapproved),
            write = YnabWriteClient { writtenUpdates = it },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = null,
        )

        assertEquals(listOf(YnabTxnUpdate("t1", "c-eat", true)), writtenUpdates)
        val gatePrefs = context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertEquals("2026-08-02", gatePrefs.getString(KEY_LAST_YNAB_WRITE_DATE, null))
    }

    @Test
    fun runYnabWriteTickIfDue_novelPayeeGoesThroughCategorizeUnknownPayeeCallback() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val unapproved = listOf(YnabUnapprovedTxn("t1", -8_000, "Some New Shop", null, null))
        var writtenUpdates: List<YnabTxnUpdate>? = null
        var llmCalled = false

        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = fakeFetch(unapproved = unapproved),
            write = YnabWriteClient { writtenUpdates = it },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = { payee, amount, _ ->
                llmCalled = true
                assertEquals("Some New Shop", payee)
                // Signed milliunits/1000, same as Python's `amt / 1000` --
                // outflow amounts stay negative, matching runner.py passing
                // nv["amount"] straight through unabs'd to categorize_unknown.
                assertEquals(-8.0, amount, 0.001)
                "Eating Out"
            },
        )

        assertTrue(llmCalled)
        assertEquals(listOf(YnabTxnUpdate("t1", "c-eat", true)), writtenUpdates)
    }

    @Test
    fun runYnabWriteTickIfDue_approveHoldAndCoverWriteTheCorrectCallShapes() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Large txn -> held, never approved. Cover: an overspent category
        // drained from a discretionary source.
        val month = YnabMonth(
            listOf(
                YnabMonthCategory("c-over", "Fun", balance = -2000, budgeted = 0),
                YnabMonthCategory("c-eat", "Eating Out", balance = 5000, budgeted = 5000),
            ),
        )
        val unapproved = listOf(YnabUnapprovedTxn("t1", -200_000, "Big Purchase", "c-eat", null))
        var writtenUpdates: List<YnabTxnUpdate>? = null
        val budgetWrites = mutableListOf<Pair<String, Int>>()

        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = fakeFetch(unapproved = unapproved, month = month),
            write = YnabWriteClient { writtenUpdates = it },
            budgetWrite = YnabBudgetWriteClient { categoryId, budgeted -> budgetWrites.add(categoryId to budgeted) },
            categorizeUnknownPayee = null,
            coverOrder = listOf("Eating Out"),
        )

        // Held (large/unusual): no approve, no categorize write for t1 at all.
        assertTrue(writtenUpdates.isNullOrEmpty())
        assertEquals(listOf("c-over" to 2000, "c-eat" to 3000), budgetWrites)
    }

    @Test
    fun runYnabWriteTickIfDue_skipsWhenAlreadyRunToday() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_YNAB_WRITE_DATE, "2026-08-02").apply()

        var fetchCalled = false
        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = YnabFetch { fetchCalled = true; YnabFetchResult(emptyList(), emptyList(), emptyList(), YnabMonth(emptyList())) },
            write = YnabWriteClient { },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = null,
        )

        assertFalse(fetchCalled)
    }

    @Test
    fun runYnabWriteTickIfDue_fetchFailureLeavesGateUnmarkedForRetryNextTick() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = YnabFetch { throw java.io.IOException("network down") },
            write = YnabWriteClient { org.junit.Assert.fail("write must not be attempted after a fetch failure") },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = null,
        )

        val gatePrefs = context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertNull(gatePrefs.getString(KEY_LAST_YNAB_WRITE_DATE, null))
    }

    @Test
    fun runYnabWriteTickIfDue_writeFailureLeavesGateUnmarkedForRetryNextTick() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val unapproved = listOf(YnabUnapprovedTxn("t1", -1_000, "Known Corp", "c-eat", null))
        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = fakeFetch(unapproved = unapproved),
            write = YnabWriteClient { throw java.io.IOException("HTTP 500") },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = null,
        )

        val gatePrefs = context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertNull(gatePrefs.getString(KEY_LAST_YNAB_WRITE_DATE, null))
    }

    @Test
    fun runYnabWriteTickIfDue_noApiKeyLeavesNovelPayeesUnresolvedButStillWritesKnownOnes() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val history = List(4) { YnabHistoryTxn(true, "c-eat", "Chipotle") }
        val unapproved = listOf(
            YnabUnapprovedTxn("t1", -12_000, "Chipotle", null, null), // confident rule -> categorized
            YnabUnapprovedTxn("t2", -5_000, "Totally New Payee", null, null), // novel, unresolved without an LLM
        )
        var writtenUpdates: List<YnabTxnUpdate>? = null

        runYnabWriteTickIfDue(
            context = context,
            now = now,
            fetch = fakeFetch(history = history, unapproved = unapproved),
            write = YnabWriteClient { writtenUpdates = it },
            budgetWrite = YnabBudgetWriteClient { _, _ -> },
            categorizeUnknownPayee = null, // no Anthropic API key configured
        )

        assertEquals(listOf(YnabTxnUpdate("t1", "c-eat", true)), writtenUpdates)
    }
}
