package kz.icgroup.vending

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.icgroup.vending.data.Api
import kz.icgroup.vending.data.ApiException
import kz.icgroup.vending.data.CellInfo
import kz.icgroup.vending.data.Config
import kz.icgroup.vending.data.OrderInfo
import kz.icgroup.vending.hw.SpringBoard
import java.util.UUID

/** Что сейчас на экране. */
sealed interface Screen {
    object Setup : Screen
    object Catalog : Screen
    /** deadline — момент по SystemClock.elapsedRealtime(), когда истекает QR. */
    data class Pay(val order: OrderInfo, val deadline: Long) : Screen
    data class Dispensing(val title: String) : Screen
    data class Message(val kind: MessageKind, val title: String, val text: String) : Screen
    object Service : Screen
}

enum class MessageKind { SUCCESS, INFO, PROBLEM }

/**
 * Вся логика автомата: витрина → оплата → выдача → результат.
 * Экраны только показывают состояние отсюда и вызывают методы.
 */
class VendingController(val config: Config, private val scope: CoroutineScope) {

    private val api = Api(config)
    private var board = SpringBoard(config.serialPath)

    var screen by mutableStateOf<Screen>(Screen.Catalog)
        private set
    var cells by mutableStateOf<List<CellInfo>>(emptyList())
        private set
    var online by mutableStateOf(false)
        private set
    var kaspiReady by mutableStateOf(false)
        private set
    var machineName by mutableStateOf("")
        private set
    /** Идёт создание заказа: витрина показывает «Создаём QR…». */
    var busy by mutableStateOf(false)
        private set
    /** Короткое сообщение об ошибке на витрине или в настройках. */
    var notice by mutableStateOf<String?>(null)
    /** Строка результата на сервисном экране. */
    var serviceLog by mutableStateOf("")
        private set

    private var pollJob: Job? = null
    private var flowJob: Job? = null

    private val flowActive: Boolean
        get() = flowJob?.isActive == true

    // ── запуск ──

    fun start() {
        pollJob?.cancel()
        flowJob?.cancel()
        if (!config.isConfigured) {
            screen = Screen.Setup
            return
        }
        screen = Screen.Catalog
        pollJob = scope.launch {
            recoverJournal()
            while (isActive) {
                if (screen is Screen.Catalog || screen is Screen.Service) refreshState()
                // Результат выдачи не дошёл до сервера (не было связи): досылаем, когда связь вернулась.
                if (online && !flowActive && screen is Screen.Catalog && config.loadJournal() != null) recoverJournal()
                delay(5_000)
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        flowJob?.cancel()
        board.disconnect()
    }

    private suspend fun refreshState() {
        try {
            val s = api.state()
            cells = s.cells
            kaspiReady = s.kaspiReady
            machineName = s.name
            online = true
            val active = s.activeOrder
            if (active != null && !flowActive && screen is Screen.Catalog && config.loadJournal() == null) {
                when (active.stage) {
                    // Остался неоплаченный заказ от прошлого покупателя: закрываем.
                    "pay" -> try {
                        api.cancel(active.id)
                    } catch (e: ApiException) {
                        // не страшно, сервер закроет его сам по сроку
                    }
                    // Оплачен, но не выдан (например, автомат перезагрузился): выдаём.
                    "dispense" -> flowJob = scope.launch { dispense(active.id, active.cell, active.title, null) }
                }
            }
        } catch (e: ApiException) {
            online = false
        }
    }

    // ── покупка ──

    fun buy(cell: CellInfo) {
        if (flowActive || busy || !online || !kaspiReady || !cell.available) return
        notice = null
        flowJob = scope.launch {
            busy = true
            val order = try {
                api.createOrder(cell.number)
            } catch (e: ApiException) {
                notice = e.message
                null
            } finally {
                busy = false
            }
            if (order != null) payLoop(order)
        }
    }

    private fun payScreen(order: OrderInfo) =
        Screen.Pay(order, SystemClock.elapsedRealtime() + order.secondsLeft * 1000L)

    private suspend fun payLoop(first: OrderInfo) {
        var order = first
        screen = payScreen(order)
        // Сервер сам закрывает заказ через ~70 с после срока QR. Это запас на случай, если он молчит.
        val giveUpAt = SystemClock.elapsedRealtime() + (order.secondsLeft + 150) * 1000L
        while (true) {
            delay(1_500)
            try {
                order = api.order(order.id)
            } catch (e: ApiException) {
                if (SystemClock.elapsedRealtime() > giveUpAt) {
                    showMessage(
                        MessageKind.PROBLEM, "Нет связи с сервером",
                        "Если вы оплатили, деньги вернутся или товар будет выдан. Заказ ${shortId(order.id)}", 15,
                    )
                    return
                }
                continue // связь мигнула: QR остаётся на экране, пробуем дальше
            }
            when (order.stage) {
                "pay" -> screen = payScreen(order)
                "dispense" -> {
                    dispense(order.id, order.cell, order.title, null)
                    return
                }
                "closed" -> {
                    val text = if (order.status == "expired") "Время оплаты вышло" else "Оплата не прошла"
                    showMessage(MessageKind.INFO, text, "Выберите товар ещё раз", 5)
                    return
                }
                else -> {
                    showOutcome(order)
                    return
                }
            }
        }
    }

    /** Кнопка «Отмена» на экране оплаты. */
    fun cancelPayment() {
        val pay = screen as? Screen.Pay ?: return
        flowJob?.cancel()
        screen = Screen.Catalog
        scope.launch {
            try {
                api.cancel(pay.order.id)
            } catch (e: ApiException) {
                // сервер закроет заказ сам; если оплата всё же пройдёт, он вернёт деньги
            }
            refreshState()
        }
    }

    // ── выдача ──

    /**
     * existingToken != null — продолжаем выдачу после перезапуска (разрешение уже запрашивали,
     * но мотор не трогали). Сервер на тот же token снова ответит «можно».
     */
    private suspend fun dispense(orderId: String, cell: Int, title: String, existingToken: String?) {
        screen = Screen.Dispensing(title)
        val token = existingToken ?: UUID.randomUUID().toString()
        val journal = Config.Journal(orderId, cell, token, "requested")
        config.saveJournal(journal)

        val allowed: Boolean? = try {
            retrying { api.dispenseStart(orderId, token) }
        } catch (e: ApiException) {
            false
        }
        if (allowed == null) {
            // Сервер недоступен. Журнал оставляем: при следующем запуске продолжим.
            showMessage(MessageKind.PROBLEM, "Нет связи с сервером", "Товар будет выдан, когда связь появится. Заказ ${shortId(orderId)}", 15)
            return
        }
        if (!allowed) {
            config.saveJournal(null)
            showMessage(MessageKind.PROBLEM, "Не удалось выдать товар", "Обратитесь к администратору. Заказ ${shortId(orderId)}", 15)
            return
        }

        config.saveJournal(journal.copy(phase = "motor_sent"))
        val code = when (board.spin(cell)) {
            SpringBoard.SpinResult.OK -> "ok"
            SpringBoard.SpinResult.MOTOR_ERROR -> "motor_error"
            SpringBoard.SpinResult.NOT_SENT -> "motor_error" // команда не ушла: мотор не крутился, можно возвращать деньги
            SpringBoard.SpinResult.NO_REPLY -> "no_reply"
        }
        config.saveJournal(journal.copy(phase = code))
        report(journal.copy(phase = code))
    }

    /** Сообщает серверу результат выдачи и показывает покупателю итог. */
    private suspend fun report(journal: Config.Journal) {
        val order: OrderInfo? = try {
            retrying { api.dispenseResult(journal.orderId, journal.token, journal.phase) }
        } catch (e: ApiException) {
            config.saveJournal(null) // сервер отказал по существу: повторять бессмысленно
            null
        }
        if (order != null) config.saveJournal(null)

        when {
            journal.phase == "ok" ->
                showMessage(MessageKind.SUCCESS, "Заберите товар", "Спасибо за покупку!", 6)
            order != null -> showOutcome(order)
            else -> showMessage(
                MessageKind.PROBLEM, "Товар не выдан",
                "Обратитесь к администратору. Заказ ${shortId(journal.orderId)}", 15,
            )
        }
    }

    private suspend fun showOutcome(order: OrderInfo) {
        when (order.stage) {
            "done" -> showMessage(MessageKind.SUCCESS, "Заберите товар", "Спасибо за покупку!", 6)
            "refunded", "refunding" -> showMessage(
                MessageKind.INFO, "Товар не выдан", "Деньги возвращены на ваш счёт Kaspi", 10,
            )
            else -> showMessage(
                MessageKind.PROBLEM, "Нужна помощь администратора",
                "Назовите номер заказа ${shortId(order.id)}", 15,
            )
        }
    }

    private suspend fun showMessage(kind: MessageKind, title: String, text: String, seconds: Int) {
        screen = Screen.Message(kind, title, text)
        delay(seconds * 1000L)
        screen = Screen.Catalog
        refreshState()
    }

    /** Продолжение после перезапуска приложения или отключения питания. */
    private suspend fun recoverJournal() {
        val j = config.loadJournal() ?: return
        when (j.phase) {
            "requested" -> dispense(j.orderId, j.cell, "", j.token)
            // Питание пропало посреди команды мотору: выдан ли товар, неизвестно. Решит человек.
            "motor_sent" -> report(j.copy(phase = "unknown"))
            else -> report(j)
        }
    }

    /** Повторяет запрос при сбоях связи. null — так и не дозвонились. Ошибки вида 4xx пробрасывает. */
    private suspend fun <T> retrying(times: Int = 10, block: suspend () -> T): T? {
        repeat(times) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.isNetwork) throw e
                delay(3_000)
            }
        }
        return null
    }

    private fun shortId(orderId: String): String = orderId.take(8).uppercase()

    // ── сервисный режим ──

    fun checkPin(pin: String): Boolean = pin == config.pin

    fun openService() {
        if (flowActive) return
        serviceLog = ""
        screen = Screen.Service
        scope.launch { refreshState() }
    }

    fun closeService() {
        screen = if (config.isConfigured) Screen.Catalog else Screen.Setup
    }

    fun testMotor(cell: Int) {
        if (flowActive) return
        flowJob = scope.launch {
            serviceLog = "Ячейка $cell: крутим…"
            val result = board.spin(cell)
            serviceLog = when (result) {
                SpringBoard.SpinResult.OK -> "Ячейка $cell: мотор отработал"
                SpringBoard.SpinResult.MOTOR_ERROR -> "Ячейка $cell: плата сообщила об ошибке мотора"
                SpringBoard.SpinResult.NO_REPLY -> "Ячейка $cell: плата не ответила"
                SpringBoard.SpinResult.NOT_SENT -> "Порт ${config.serialPath} не открылся: ${board.lastError ?: "неизвестная ошибка"}"
            }
        }
    }

    fun setStock(cell: Int, stock: Int) {
        if (stock < 0) return
        scope.launch {
            try {
                api.setStock(cell, stock)
                refreshState()
            } catch (e: ApiException) {
                serviceLog = "Остаток не сохранён: ${e.message}"
            }
        }
    }

    /** Сохраняет настройки и проверяет связь с сервером. */
    fun saveSettings(serverUrl: String, token: String, pin: String, serialPath: String) {
        if (flowActive) return
        notice = null
        if (pin.length < 4 || !pin.all { it.isDigit() }) {
            notice = "PIN: минимум 4 цифры"
            return
        }
        config.serverUrl = serverUrl
        config.token = token
        config.pin = pin
        if (serialPath.trim() != config.serialPath) {
            config.serialPath = serialPath.ifBlank { Config.DEFAULT_SERIAL }
            board.disconnect()
            board = SpringBoard(config.serialPath)
        }
        if (!config.isConfigured) {
            notice = "Укажите адрес сервера и токен автомата"
            return
        }
        flowJob = scope.launch {
            try {
                api.state()
                start()
            } catch (e: ApiException) {
                notice = if (e.code == 401) "Сервер не узнал токен автомата" else e.message
            }
        }
    }
}
