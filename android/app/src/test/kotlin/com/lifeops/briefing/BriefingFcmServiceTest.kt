package com.lifeops.briefing

import com.lifeops.briefing.data.BriefingState
import com.lifeops.briefing.data.YnabCategoryBalance
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingFcmServiceTest {
    @Test
    fun briefingPersistPreservesLocalYnabRefreshWhenIncomingPayloadLacksBalances() {
        val existing = BriefingState.empty().copy(
            discretionaryCurrentDollars = 180,
            ynabCategoryBalances = listOf(YnabCategoryBalance("Fun", 180)),
        )
        val incoming = BriefingState.empty().copy(
            discretionaryDollars = -125,
            discretionaryCurrentDollars = null,
            ynabCategoryBalances = emptyList(),
        )

        val merged = incoming.withLocalYnabFallback(existing)

        assertEquals(-125, merged.discretionaryDollars)
        assertEquals(180, merged.discretionaryCurrentDollars)
        assertEquals(listOf(YnabCategoryBalance("Fun", 180)), merged.ynabCategoryBalances)
    }

    @Test
    fun briefingPersistUsesIncomingYnabFieldsWhenPayloadHasThem() {
        val existing = BriefingState.empty().copy(
            discretionaryCurrentDollars = 180,
            ynabCategoryBalances = listOf(YnabCategoryBalance("Fun", 180)),
        )
        val incoming = BriefingState.empty().copy(
            discretionaryCurrentDollars = 42,
            ynabCategoryBalances = listOf(YnabCategoryBalance("Fun", 42)),
        )

        val merged = incoming.withLocalYnabFallback(existing)

        assertEquals(42, merged.discretionaryCurrentDollars)
        assertEquals(listOf(YnabCategoryBalance("Fun", 42)), merged.ynabCategoryBalances)
    }
}
