package kz.icgroup.vending

import kz.icgroup.vending.hw.FrameAssembler
import kz.icgroup.vending.hw.Frames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Эталонные кадры сняты с настоящего шкафа 25.11.2026. */
class FramesTest {

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesCommandsThatMovedRealMotors() {
        assertArrayEquals(bytes("58 50 00 1f 1f 00 08 4c"), Frames.handshake(0))
        assertArrayEquals(bytes("58 50 11 fe fe 00 19 4c"), Frames.spin(1, 1, 0))
        assertArrayEquals(bytes("58 50 11 fe f7 00 10 4c"), Frames.spin(1, 8, 0))
    }

    @Test
    fun decodesRepliesCapturedFromBoard() {
        assertEquals(Frames.Message(0x11, 1, 1), Frames.decode(bytes("58 50 11 fe fe 1d 04 4c")))
        assertEquals(Frames.Message(0x11, 1, 1), Frames.decode(bytes("58 50 11 aa aa ab b2 4c")))
        assertEquals(Frames.Message(0x10, 1, 3), Frames.decode(bytes("58 50 10 a7 a5 a6 bc 4c")))
        assertEquals(Frames.Message(0x11, 3, 2), Frames.decode(bytes("58 50 11 a8 a9 ab b3 4c")))
        assertEquals(Frames.Message(0x11, 5, 2), Frames.decode(bytes("58 50 11 fa fd 4f 51 4c")))
    }

    @Test
    fun rejectsBrokenFrames() {
        assertNull(Frames.decode(bytes("58 50 11 fe fe 1d 05 4c"))) // не та контрольная сумма
        assertNull(Frames.decode(bytes("00 00 00 00 00 00 00 00")))
        assertNull(Frames.decode(bytes("58 50 11")))
    }

    @Test
    fun roundTripForEveryRandomByte() {
        for (rnd in 0..255) {
            for (cell in 1..8) {
                val (row, col) = Frames.cellAddress(cell)!!
                assertEquals(Frames.Message(0x11, row, col), Frames.decode(Frames.spin(row, col, rnd)))
            }
        }
    }

    @Test
    fun cellMapMatchesCabinet() {
        assertEquals(1 to 1, Frames.cellAddress(1))
        assertEquals(2 to 1, Frames.cellAddress(2))
        assertEquals(3 to 2, Frames.cellAddress(4))
        assertEquals(5 to 2, Frames.cellAddress(8))
        assertNull(Frames.cellAddress(9))
    }

    @Test
    fun assemblerSurvivesSplitFramesAndNoise() {
        val a = FrameAssembler()
        val reply = bytes("58 50 11 fe fe 1d 04 4c")
        val noisy = bytes("00 00 00 58 50") // мусор и оборванное начало кадра
        assertEquals(0, a.feed(noisy, noisy.size).size)
        assertEquals(0, a.feed(reply, 3).size)
        val rest = reply.copyOfRange(3, 8)
        assertEquals(listOf(Frames.Message(0x11, 1, 1)), a.feed(rest, rest.size))
        val two = reply + bytes("58 50 10 a7 a5 a6 bc 4c")
        assertEquals(2, a.feed(two, two.size).size)
    }
}
