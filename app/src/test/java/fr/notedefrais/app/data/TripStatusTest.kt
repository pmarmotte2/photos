package fr.notedefrais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TripStatusTest {
    @Test
    fun newTripStartsInDraftWithoutSubmissionDate() {
        val trip = trip()

        assertEquals(TripStatus.DRAFT, trip.status)
        assertNull(trip.submittedDate)
    }

    @Test
    fun sentTripKeepsItsSubmissionDate() {
        val submittedDate = LocalDate.of(2026, 7, 24)
        val trip = trip().copy(
            status = TripStatus.SENT,
            submittedDate = submittedDate
        )

        assertEquals(TripStatus.SENT, trip.status)
        assertEquals(submittedDate, trip.submittedDate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonDraftStatusRequiresASubmissionDate() {
        trip().copy(status = TripStatus.VALIDATED)
    }

    private fun trip() = Trip(
        name = "Les Clayes",
        startDate = LocalDate.of(2026, 7, 22),
        endDate = LocalDate.of(2026, 7, 23)
    )
}
