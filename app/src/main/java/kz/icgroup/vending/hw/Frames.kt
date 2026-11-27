package kz.icgroup.vending.hw

/**
 * Протокол платы привода пружин (эталон и описание: docs/vend_protocol.py).
 *
 * Кадр 8 байт в обе стороны: 58 50 cmd a' b' rnd chk 4C
 *   rnd — случайный байт; если (rnd & 0x20) == 0, то a' = ~a, b' = ~b, иначе a' = a ^ rnd, b' = b ^ rnd
 *   chk — XOR первых шести байт
 */
object Frames {
    const val SIZE = 8
    const val CMD_HANDSHAKE = 0x00
    const val CMD_SPIN = 0x11        // команда «крутить» и ответ «мотор отработал»
    const val CMD_SPIN_ERROR = 0x10  // ответ «ошибка мотора»

    private const val HEAD0 = 0x58
    private const val HEAD1 = 0x50
    private const val TAIL = 0x4C

    data class Message(val cmd: Int, val a: Int, val b: Int)

    fun encode(cmd: Int, a: Int, b: Int, rnd: Int): ByteArray {
        val r = rnd and 0xFF
        val a2: Int
        val b2: Int
        if ((r and 0x20) == 0) {
            a2 = a.inv() and 0xFF
            b2 = b.inv() and 0xFF
        } else {
            a2 = (a xor r) and 0xFF
            b2 = (b xor r) and 0xFF
        }
        val body = intArrayOf(HEAD0, HEAD1, cmd and 0xFF, a2, b2, r)
        var chk = 0
        for (x in body) chk = chk xor x
        val out = ByteArray(SIZE)
        for (i in body.indices) out[i] = body[i].toByte()
        out[6] = chk.toByte()
        out[7] = TAIL.toByte()
        return out
    }

    /** Разбирает один кадр. null, если это не кадр или не сошлась контрольная сумма. */
    fun decode(frame: ByteArray, offset: Int = 0): Message? {
        if (frame.size - offset < SIZE) return null
        fun at(i: Int): Int = frame[offset + i].toInt() and 0xFF
        if (at(0) != HEAD0 || at(1) != HEAD1 || at(7) != TAIL) return null
        var chk = 0
        for (i in 0 until 6) chk = chk xor at(i)
        if (chk != at(6)) return null
        val r = at(5)
        val a: Int
        val b: Int
        if ((r and 0x20) == 0) {
            a = at(3).inv() and 0xFF
            b = at(4).inv() and 0xFF
        } else {
            a = at(3) xor r
            b = at(4) xor r
        }
        return Message(at(2), a, b)
    }

    /**
     * Ячейка (номер наклейки 1..8) → адрес мотора (ряд, колонка).
     * Проверено на шкафу: 01 и 02 — широкие полки, дальше по две ячейки на полке.
     */
    fun cellAddress(cell: Int): Pair<Int, Int>? = when (cell) {
        1 -> 1 to 1
        2 -> 2 to 1
        3 -> 3 to 1
        4 -> 3 to 2
        5 -> 4 to 1
        6 -> 4 to 2
        7 -> 5 to 1
        8 -> 5 to 2
        else -> null
    }

    fun handshake(rnd: Int): ByteArray = encode(CMD_HANDSHAKE, 0xE0, 0xE0, rnd)

    fun spin(row: Int, col: Int, rnd: Int): ByteArray = encode(CMD_SPIN, row, col, rnd)
}

/** Собирает кадры из потока байт: порт может отдать кадр кусками или с мусором впереди. */
class FrameAssembler {
    private val buf = ByteArray(256)
    private var len = 0

    fun feed(data: ByteArray, count: Int): List<Frames.Message> {
        val out = ArrayList<Frames.Message>()
        for (i in 0 until count) {
            if (len == buf.size) drop(1)
            buf[len++] = data[i]
            while (len >= Frames.SIZE) {
                val msg = Frames.decode(buf, 0)
                if (msg != null) {
                    out.add(msg)
                    drop(Frames.SIZE)
                } else {
                    drop(1)
                }
            }
        }
        return out
    }

    private fun drop(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }
}
