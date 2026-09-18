package fr.notedefrais.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAnnouncementTest {
    @Test
    fun plainTextMessageIsTrimmedAndAccepted() {
        assertEquals(
            "Bienvenue dans Fo Notes",
            sanitizeAnnouncement("  Bienvenue dans Fo Notes\r\n", "text/plain; charset=utf-8")
        )
    }

    @Test
    fun emptyMessageDisablesThePopup() {
        assertNull(sanitizeAnnouncement(" \n ", "text/plain"))
    }

    @Test
    fun htmlFallbackIsRejected() {
        assertNull(
            sanitizeAnnouncement("<html>Site web</html>", "text/html; charset=utf-8")
        )
    }

    @Test
    fun oversizedMessageIsRejected() {
        assertNull(sanitizeAnnouncement("a".repeat(4_001), "text/plain"))
    }
}
