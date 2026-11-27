package kz.icgroup.vending.hw

import android.serialport.SerialPort
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.random.Random

/** Плата привода пружин на последовательном порту (115200 бод). */
class SpringBoard(private val path: String) {

    enum class SpinResult {
        OK,           // плата ответила «мотор отработал»
        MOTOR_ERROR,  // плата ответила «ошибка мотора»
        NOT_SENT,     // команду не удалось отправить: мотор точно не крутился
        NO_REPLY,     // команда ушла, ответа нет: крутился ли мотор, неизвестно
    }

    private class Pending(val row: Int, val col: Int, val result: CompletableDeferred<SpinResult>)

    private val mutex = Mutex()
    @Volatile
    private var port: SerialPort? = null

    @Volatile
    private var pending: Pending? = null

    @Volatile
    var lastError: String? = null
        private set

    val isOpen: Boolean
        get() = port != null

    /** Открывает порт и шлёт рукопожатие. Возвращает true при успехе, текст ошибки лежит в lastError. */
    @Synchronized
    fun connect(): Boolean {
        if (port != null) return true
        return try {
            val p = SerialPort(File(path), 115200)
            port = p
            startReader(p)
            p.outputStream.write(Frames.handshake(Random.nextInt(256)))
            p.outputStream.flush()
            lastError = null
            true
        } catch (e: Throwable) {
            // Throwable, а не Exception: если в APK нет нативной библиотеки под процессор, прилетит Error.
            Log.e(TAG, "open $path failed", e)
            lastError = e.message ?: e.javaClass.simpleName
            closeQuietly()
            false
        }
    }

    @Synchronized
    fun disconnect() {
        closeQuietly()
    }

    private fun closeQuietly() {
        val p = port
        port = null
        try {
            p?.tryClose()
        } catch (e: Throwable) {
            Log.w(TAG, "close failed", e)
        }
    }

    private fun startReader(p: SerialPort) {
        val thread = Thread {
            val assembler = FrameAssembler()
            val chunk = ByteArray(64)
            try {
                val input = p.inputStream
                while (port === p) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) {
                        Thread.sleep(20)
                        continue
                    }
                    for (msg in assembler.feed(chunk, n)) onMessage(msg)
                }
            } catch (e: Throwable) {
                if (port === p) Log.w(TAG, "reader stopped", e)
            }
        }
        thread.name = "spring-board-reader"
        thread.isDaemon = true
        thread.start()
    }

    private fun onMessage(msg: Frames.Message) {
        Log.d(TAG, "rx cmd=${msg.cmd} a=${msg.a} b=${msg.b}")
        val p = pending ?: return
        if (msg.a != p.row || msg.b != p.col) return
        when (msg.cmd) {
            Frames.CMD_SPIN -> p.result.complete(SpinResult.OK)
            Frames.CMD_SPIN_ERROR -> p.result.complete(SpinResult.MOTOR_ERROR)
        }
    }

    /** Один оборот пружины ячейки = один товар. Команды идут строго по одной. */
    suspend fun spin(cell: Int, timeoutMs: Long = 25_000L): SpinResult = mutex.withLock {
        val address = Frames.cellAddress(cell)
        if (address == null) {
            lastError = "нет ячейки $cell"
            return@withLock SpinResult.NOT_SENT
        }
        val opened = withContext(Dispatchers.IO) { connect() }
        if (!opened) return@withLock SpinResult.NOT_SENT

        val deferred = CompletableDeferred<SpinResult>()
        pending = Pending(address.first, address.second, deferred)
        try {
            val sent = withContext(Dispatchers.IO) {
                try {
                    val out = port?.outputStream ?: return@withContext false
                    out.write(Frames.spin(address.first, address.second, Random.nextInt(256)))
                    out.flush()
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "write failed", e)
                    lastError = e.message ?: e.javaClass.simpleName
                    closeQuietly()
                    false
                }
            }
            if (!sent) return@withLock SpinResult.NOT_SENT
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: SpinResult.NO_REPLY
        } finally {
            pending = null
        }
    }

    private companion object {
        const val TAG = "SpringBoard"
    }
}
