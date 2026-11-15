package com.robertlangat.todoplanner.core

import com.robertlangat.todoplanner.core.planner.AiPlanValidator
import com.robertlangat.todoplanner.core.planner.PlanSource
import com.robertlangat.todoplanner.core.planner.TimeWindow
import com.robertlangat.todoplanner.core.wear.WearPaths
import com.robertlangat.todoplanner.core.wear.WearPayload
import com.robertlangat.todoplanner.core.wear.WearTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Cross-component contracts: backend response -> app validator, phone -> watch payload. */
class ContractTest {
    /** Captured verbatim from backend/server.mjs mockPlan() for the README example prompt. */
    private val backendMockResponse =
        """{"model":"mock","summary":"Mock plan with 2 tasks","tasks":[{"title":"Revise for an exam","durationMinutes":30,"priority":"high","start":"2026-11-16T17:30","subtasks":[]},{"title":"Tomorrow's errands.","durationMinutes":30,"priority":"medium","start":"2026-11-16T18:05","subtasks":[]}]}"""

    @Test fun backendResponseValidatesInApp() {
        val date = LocalDate.parse("2026-11-16")
        val draft = AiPlanValidator.validate(
            backendMockResponse, date, emptyList(), listOf(TimeWindow(date.atTime(17, 30), date.atTime(19, 30))),
        )
        assertEquals(PlanSource.AI, draft.source)
        assertEquals("mock", draft.modelName)
        assertEquals(2, draft.items.size)
        assertEquals(LocalDateTime.of(2026, 11, 16, 17, 30), draft.items[0].slot!!.start)
        assertTrue(draft.items.all { it.flags.isEmpty() })
    }

    @Test fun wearPayloadRoundTrip() {
        val p = WearPayload(
            1_800_000_000_000L,
            listOf(WearTask("t1", "2026-11-16", "Revise \"ch. 3\"", "18:00", false, 3, false)),
            listOf(WearTask("t2", null, "Pay rent", null, false, 0, true)),
        )
        assertEquals(p, WearPayload.decode(p.encode()))
        assertNull(WearPayload.decode("garbage"))
        assertEquals("/complete/t1/2026-11-16", WearPaths.completePath("t1", "2026-11-16"))
        assertEquals("/complete/t2/none", WearPaths.completePath("t2", null))
    }
}
