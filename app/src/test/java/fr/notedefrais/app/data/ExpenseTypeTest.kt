package fr.notedefrais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseTypeTest {
    @Test
    fun everyCategoryHasAtLeastOneType() {
        ExpenseCategory.entries.forEach { category ->
            assertTrue(
                "${category.name} doit proposer au moins un type",
                ExpenseType.forCategory(category).isNotEmpty()
            )
        }
    }

    @Test
    fun typesAreGroupedUnderTheirOwnCategory() {
        ExpenseCategory.entries.forEach { category ->
            ExpenseType.forCategory(category).forEach { type ->
                assertEquals(category, type.category)
            }
        }
    }

    @Test
    fun atosCatalogContainsAllVisibleExpenseTypes() {
        assertEquals(47, ExpenseType.entries.size)
        assertEquals(46, ExpenseType.entries.count { it != ExpenseType.OTHER })
        assertEquals("01 Transports (occasional)", ExpenseType.TRANSPORT_OCCASIONAL.displayLabel)
        assertEquals("11 Move accompanying measures", ExpenseType.MOVE_ACCOMPANYING_MEASURES.displayLabel)
    }
}
