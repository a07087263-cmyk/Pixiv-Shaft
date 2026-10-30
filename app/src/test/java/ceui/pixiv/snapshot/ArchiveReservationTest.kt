package ceui.pixiv.snapshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveReservationTest {
    @Test
    fun oneArtworkHasOnlyOneCreationReservationAcrossCodecs() {
        val id = 987654321L
        assertTrue(ArchiveReservation.tryReserve(id))
        assertFalse(ArchiveReservation.tryReserve(id))
        ArchiveReservation.release(id)
        assertTrue(ArchiveReservation.tryReserve(id))
        ArchiveReservation.release(id)
    }
}
