package gpt.ble.manager.android

import gpt.ble.manager.android.scan.hasCompleteName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAdvertisementTest {
    @Test
    fun completeNameCanFollowOtherAdvertisingSections() {
        assertTrue(
            hasCompleteName(
                byteArrayOf(2, 1, 6, 4, 9, 'B'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte())
            )
        )
        assertFalse(hasCompleteName(byteArrayOf(2, 8, 'M'.code.toByte())))
    }

    @Test
    fun absentTruncatedAndTerminatedDataDoesNotBecomeACompleteName() {
        assertFalse(hasCompleteName(null))
        assertFalse(hasCompleteName(byteArrayOf()))
        assertFalse(hasCompleteName(byteArrayOf(4, 9, 65)))
        assertFalse(hasCompleteName(byteArrayOf(2)))
        assertFalse(hasCompleteName(byteArrayOf(0, 2, 9, 65)))
        assertFalse(hasCompleteName(byteArrayOf(-1, 9, 65)))
    }
}
