package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin port of `tests/test_ynab_engine.py` -- see `YnabEngine.kt`'s kdoc for
 * why this exists. One test per case from the Python suite, each annotated
 * with which Python test it corresponds to. No Robolectric needed: like
 * `Routine.kt`, `YnabEngine.kt` has zero Android-framework dependency, so
 * plain JUnit is enough.
 */
class YnabEngineTest {

    private val cats = listOf(
        YnabCategory(id = "c-eat", name = "Eating Out"),
        YnabCategory(id = "c-shop", name = "Shopping"),
        YnabCategory(id = "c-sav", name = "Savings"),
    )

    private fun txn(
        id: String = "t1",
        amount: Int = -12_000,
        payee: String? = "Chipotle",
        categoryId: String? = null,
        transfer: String? = null,
    ) = YnabUnapprovedTxn(
        id = id,
        amount = amount,
        payeeName = payee,
        categoryId = categoryId,
        transferAccountId = transfer,
    )

    private fun hist(payee: String, cid: String, n: Int): List<YnabHistoryTxn> =
        List(n) { YnabHistoryTxn(approved = true, categoryId = cid, payeeName = payee) }

    private fun plan(
        history: List<YnabHistoryTxn> = emptyList(),
        unapproved: List<YnabUnapprovedTxn> = emptyList(),
        month: YnabMonth = YnabMonth(emptyList()),
        coverOrder: List<String> = emptyList(),
        noAssign: List<String> = listOf("Savings"),
    ): YnabPlan = com.lifeops.briefing.plan(cats, history, unapproved, month, coverOrder, noAssign)

    // ── categorize / approve ────────────────────────────────────────────────

    // Corresponds to test_confident_payee_categorized_and_approved
    @Test
    fun confidentPayee_categorizedAndApproved() {
        val out = plan(history = hist("Chipotle", "c-eat", 4), unapproved = listOf(txn()))

        assertEquals(listOf(YnabCategorizeAction("t1", "c-eat")), out.categorize)
        assertEquals(listOf("t1"), out.approve)
        assertTrue(out.novel.isEmpty())
    }

    // Corresponds to test_single_occurrence_goes_to_llm_not_auto
    @Test
    fun singleOccurrence_goesToLlmNotAuto() {
        // one historical sighting must NOT become an auto-rule
        val out = plan(history = hist("Chipotle", "c-eat", 1), unapproved = listOf(txn()))

        assertTrue(out.categorize.isEmpty())
        assertEquals(1, out.novel.size)
    }

    // Corresponds to test_two_unanimous_occurrences_auto_categorize
    @Test
    fun twoUnanimousOccurrences_autoCategorize() {
        val out = plan(history = hist("Chipotle", "c-eat", 2), unapproved = listOf(txn()))

        assertEquals(listOf(YnabCategorizeAction("t1", "c-eat")), out.categorize)
    }

    // Corresponds to test_split_small_sample_not_auto
    @Test
    fun splitSmallSample_notAuto() {
        // 1x Eating Out + 1x Shopping: not unanimous -> LLM decides
        val h = hist("Chipotle", "c-eat", 1) + hist("Chipotle", "c-shop", 1)
        val out = plan(history = h, unapproved = listOf(txn()))

        assertTrue(out.categorize.isEmpty())
        assertEquals(1, out.novel.size)
    }

    // Corresponds to test_majority_below_70pct_not_auto
    @Test
    fun majorityBelow70Pct_notAuto() {
        val h = hist("Chipotle", "c-eat", 3) + hist("Chipotle", "c-shop", 2)
        val out = plan(history = h, unapproved = listOf(txn())) // 3/5 = 60%

        assertTrue(out.categorize.isEmpty())
    }

    // Corresponds to test_never_auto_assigns_pure_fund
    @Test
    fun neverAutoAssignsPureFund() {
        val out = plan(
            history = hist("Weird Payee", "c-sav", 5),
            unapproved = listOf(txn(payee = "Weird Payee")),
        )

        assertTrue(out.categorize.isEmpty())
        assertEquals(1, out.novel.size) // falls through to LLM instead
    }

    // Corresponds to test_precategorized_fund_txn_held_not_approved
    @Test
    fun precategorizedFundTxn_heldNotApproved() {
        // pre-set straight into Savings (import rule / fat-finger) -> hold, never approve
        val out = plan(unapproved = listOf(txn(categoryId = "c-sav")))

        assertTrue(out.approve.isEmpty())
        assertEquals(listOf(YnabHold("t1", "assigned to protected fund")), out.holds)
    }

    // Corresponds to test_precategorized_normal_txn_approved
    @Test
    fun precategorizedNormalTxn_approved() {
        val out = plan(unapproved = listOf(txn(categoryId = "c-eat")))

        assertEquals(listOf("t1"), out.approve)
        assertTrue(out.holds.isEmpty())
    }

    // Corresponds to test_large_txn_held_for_review
    @Test
    fun largeTxn_heldForReview() {
        val out = plan(
            history = hist("Chipotle", "c-eat", 4),
            unapproved = listOf(txn(amount = -200_000)), // $200 >= $150 review line
        )

        assertTrue(out.approve.isEmpty())
        assertEquals(listOf(YnabHold("t1", "large/unusual")), out.holds)
        assertEquals(listOf(YnabCategorizeAction("t1", "c-eat")), out.categorize) // still categorized
    }

    // Corresponds to test_income_and_transfers_skipped
    @Test
    fun incomeAndTransfers_skipped() {
        val out = plan(
            unapproved = listOf(
                txn(amount = 50_000),
                txn(id = "t2", transfer = "acct"),
            ),
        )

        assertTrue(out.approve.isEmpty())
        assertTrue(out.novel.isEmpty())
        assertTrue(out.holds.isEmpty())
    }

    // ── cover (overspend) ────────────────────────────────────────────────────

    /** balances: name -> (balance, budgeted). Mirrors the Python `_month` helper. */
    private fun month(vararg balances: Pair<String, Pair<Int, Int>>): YnabMonth =
        YnabMonth(
            balances.map { (n, bg) ->
                YnabMonthCategory(id = "c-${n.take(4).lowercase()}", name = n, balance = bg.first, budgeted = bg.second)
            },
        )

    // Corresponds to test_cover_moves_from_want_to_deficit
    @Test
    fun cover_movesFromWantToDeficit() {
        val m = month("Eating Out" to (-10_000 to 50_000), "Shopping" to (30_000 to 40_000))
        val out = cover(m, listOf("Shopping"))
        val moves = out.associate { it.categoryId to it.budgeted }

        assertEquals(60_000, moves["c-eati"]) // +10k
        assertEquals(30_000, moves["c-shop"]) // -10k
    }

    // Corresponds to test_cover_never_drives_source_below_zero
    @Test
    fun cover_neverDrivesSourceBelowZero() {
        val m = month("Eating Out" to (-50_000 to 50_000), "Shopping" to (30_000 to 40_000))
        val out = cover(m, listOf("Shopping"))
        val moves = out.associate { it.categoryId to it.budgeted }

        assertEquals(10_000, moves["c-shop"]) // gave only its 30k balance
        assertEquals(80_000, moves["c-eati"]) // covered partially; rest stays red
    }

    // Corresponds to test_cover_skips_self_funding
    @Test
    fun cover_skipsSelfFunding() {
        val m = month("Shopping" to (-10_000 to 40_000))

        assertTrue(cover(m, listOf("Shopping")).isEmpty())
    }

    // Corresponds to test_cover_drains_sources_in_priority_order
    @Test
    fun cover_drainsSourcesInPriorityOrder() {
        val m = month(
            "Eating Out" to (-40_000 to 0),
            "Shopping" to (30_000 to 30_000),
            "Splurge" to (30_000 to 30_000),
        )
        val out = cover(m, listOf("Shopping", "Splurge"))
        val moves = out.associate { it.categoryId to it.budgeted }

        assertEquals(0, moves["c-shop"]) // fully drained first
        assertEquals(20_000, moves["c-splu"]) // then 10k from second
    }

    // Corresponds to test_cover_negative_source_gives_nothing
    @Test
    fun cover_negativeSourceGivesNothing() {
        val m = month("Eating Out" to (-10_000 to 0), "Shopping" to (-5_000 to 20_000))

        assertTrue(cover(m, listOf("Shopping")).isEmpty()) // an overdrawn want can't cover anything
    }

    // Corresponds to test_cover_empty_order_returns_empty
    @Test
    fun cover_emptyOrderReturnsEmpty() {
        val m = month("Eating Out" to (-10_000 to 0))

        assertTrue(cover(m, emptyList()).isEmpty())
    }
}
