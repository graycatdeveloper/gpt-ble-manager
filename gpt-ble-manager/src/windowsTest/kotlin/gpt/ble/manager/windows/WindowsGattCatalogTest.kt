package gpt.ble.manager.windows

import gpt.ble.manager.BleUuid
import gpt.ble.manager.windows.gatt.decodeWindowsGattCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsGattCatalogTest {
    @Test
    fun repeatedUuidsRemainSeparateAndDescriptorsFollowHandles() {
        val records =
            arrayOf(
                "D|25|28|2902",
                "C|20|25|ffb1|18",
                "S|1|ffb0",
                "C|1|2|ffb1|2",
                "D|2|4|2901",
                "S|20|ffb0",
            )

        val services = decodeWindowsGattCatalog("session", records)

        assertEquals(listOf(1, 20), services.map { it.id })
        assertTrue(services.all { it.uuid == BleUuid.parse("ffb0") })
        val first = services[0].characteristics.single()
        val second = services[1].characteristics.single()
        assertEquals(2, first.id)
        assertEquals(25, second.id)
        assertEquals("session", second.connectionId)
        assertEquals(4, first.descriptors.single().id)
        assertEquals(28, second.descriptors.single().id)
        assertEquals(25, second.descriptors.single().characteristicId)
        assertTrue(second.canNotify)
    }

    @Test
    fun emptyCatalogIsAllowedButMalformedRecordsAreNotSilentlyDropped() {
        assertTrue(decodeWindowsGattCatalog("session", emptyArray()).isEmpty())
        assertFailsWith<NumberFormatException> {
            decodeWindowsGattCatalog("session", arrayOf("S|invalid|1800"))
        }
        assertFailsWith<IndexOutOfBoundsException> {
            decodeWindowsGattCatalog("session", arrayOf("C|1"))
        }
    }
}
