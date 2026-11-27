package kz.icgroup.vending.data

import android.content.Context

/** Настройки автомата и журнал незавершённой выдачи. Хранятся на устройстве. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("icvend", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) {
            var url = value.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            prefs.edit().putString(KEY_URL, url).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var pin: String
        get() = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = prefs.edit().putString(KEY_PIN, value.trim()).apply()

    var serialPath: String
        get() = prefs.getString(KEY_SERIAL, DEFAULT_SERIAL) ?: DEFAULT_SERIAL
        set(value) = prefs.edit().putString(KEY_SERIAL, value.trim()).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()

    /**
     * Журнал выдачи. Записывается ДО команды мотору, чтобы после отключения питания
     * автомат знал, на каком шаге остановился, и не выдал товар дважды.
     *
     * phase: requested   — спросили у сервера разрешение, мотор ещё не трогали
     *        motor_sent  — отправляем команду мотору (после сбоя результат неизвестен)
     *        ok / motor_error / no_reply — результат получен, осталось сообщить серверу
     */
    data class Journal(val orderId: String, val cell: Int, val token: String, val phase: String)

    fun saveJournal(j: Journal?) {
        val e = prefs.edit()
        if (j == null) {
            e.remove(KEY_J_ORDER).remove(KEY_J_CELL).remove(KEY_J_TOKEN).remove(KEY_J_PHASE)
        } else {
            e.putString(KEY_J_ORDER, j.orderId).putInt(KEY_J_CELL, j.cell)
                .putString(KEY_J_TOKEN, j.token).putString(KEY_J_PHASE, j.phase)
        }
        e.commit() // именно commit: запись должна лечь на диск до следующего шага
    }

    fun loadJournal(): Journal? {
        val order = prefs.getString(KEY_J_ORDER, null) ?: return null
        val token = prefs.getString(KEY_J_TOKEN, null) ?: return null
        val phase = prefs.getString(KEY_J_PHASE, null) ?: return null
        return Journal(order, prefs.getInt(KEY_J_CELL, 0), token, phase)
    }

    companion object {
        const val DEFAULT_PIN = "0000"
        const val DEFAULT_SERIAL = "/dev/ttyS1"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_PIN = "pin"
        private const val KEY_SERIAL = "serial_path"
        private const val KEY_J_ORDER = "journal_order"
        private const val KEY_J_CELL = "journal_cell"
        private const val KEY_J_TOKEN = "journal_token"
        private const val KEY_J_PHASE = "journal_phase"
    }
}
