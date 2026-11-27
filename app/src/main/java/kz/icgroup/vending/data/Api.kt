package kz.icgroup.vending.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** code = 0: нет связи с сервером; иначе HTTP-код ответа. */
class ApiException(val code: Int, message: String) : Exception(message) {
    val isNetwork: Boolean
        get() = code == 0 || code >= 500
}

data class CellInfo(
    val number: Int,
    val title: String,
    val price: Int,
    val stock: Int,
    val imageUrl: String?,
    val available: Boolean,
)

data class OrderInfo(
    val id: String,
    val status: String,
    /** pay, dispense, dispensing, done, closed, refunding, refunded, problem */
    val stage: String,
    val cell: Int,
    val title: String,
    val amount: Int,
    val qr: String?,
    val secondsLeft: Int,
    val scanned: Boolean,
)

data class MachineState(
    val name: String,
    val kaspiReady: Boolean,
    val cells: List<CellInfo>,
    val activeOrder: OrderInfo?,
)

/** Клиент сервера IC Vending (backend/api). */
class Api(private val config: Config) {

    suspend fun state(): MachineState {
        val o = call("GET", "/api/machine/state")
        val cellsJson = o.getJSONArray("cells")
        val cells = ArrayList<CellInfo>()
        for (i in 0 until cellsJson.length()) cells.add(parseCell(cellsJson.getJSONObject(i)))
        val machine = o.getJSONObject("machine")
        return MachineState(
            name = machine.optString("name", ""),
            kaspiReady = o.optBoolean("kaspi_ready", false),
            cells = cells,
            activeOrder = if (o.isNull("active_order")) null else parseOrder(o.getJSONObject("active_order")),
        )
    }

    suspend fun createOrder(cell: Int): OrderInfo =
        parseOrder(call("POST", "/api/machine/orders", JSONObject().put("cell", cell), readTimeoutMs = 35_000))

    suspend fun order(id: String): OrderInfo = parseOrder(call("GET", "/api/machine/orders/$id"))

    suspend fun cancel(id: String): OrderInfo = parseOrder(call("POST", "/api/machine/orders/$id/cancel", JSONObject()))

    /** true — сервер разрешил выдачу (разрешение даётся на заказ один раз). */
    suspend fun dispenseStart(id: String, token: String): Boolean =
        call("POST", "/api/machine/orders/$id/dispense-start", JSONObject().put("token", token))
            .optBoolean("allowed", false)

    /** result: ok, motor_error, no_reply, unknown */
    suspend fun dispenseResult(id: String, token: String, result: String): OrderInfo = parseOrder(
        call(
            "POST", "/api/machine/orders/$id/dispense-result",
            JSONObject().put("token", token).put("result", result),
            readTimeoutMs = 35_000,
        )
    )

    suspend fun setStock(cell: Int, stock: Int): CellInfo =
        parseCell(call("PUT", "/api/machine/cells/$cell", JSONObject().put("stock", stock)))

    private fun parseCell(o: JSONObject) = CellInfo(
        number = o.getInt("number"),
        title = o.optString("title", ""),
        price = o.optInt("price", 0),
        stock = o.optInt("stock", 0),
        imageUrl = if (o.isNull("image_url")) null else o.getString("image_url"),
        available = o.optBoolean("available", false),
    )

    private fun parseOrder(o: JSONObject) = OrderInfo(
        id = o.getString("id"),
        status = o.getString("status"),
        stage = o.getString("stage"),
        cell = o.getInt("cell"),
        title = o.optString("title", ""),
        amount = o.getInt("amount"),
        qr = if (o.isNull("qr")) null else o.getString("qr"),
        secondsLeft = o.optInt("seconds_left", 0),
        scanned = o.optBoolean("scanned", false),
    )

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 10_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(config.serverUrl + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 6_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-Machine-Token", config.token)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("detail", "")
                } catch (e: JSONException) {
                    ""
                }
                throw ApiException(code, if (detail.isNotBlank()) detail else "Ошибка сервера ($code)")
            }
            JSONObject(text)
        } catch (e: IOException) {
            throw ApiException(0, "Нет связи с сервером")
        } catch (e: JSONException) {
            throw ApiException(0, "Сервер ответил непонятно")
        } catch (e: ClassCastException) {
            throw ApiException(0, "Неверный адрес сервера")
        } finally {
            conn?.disconnect()
        }
    }
}
