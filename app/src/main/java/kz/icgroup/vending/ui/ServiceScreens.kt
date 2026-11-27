package kz.icgroup.vending.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.icgroup.vending.VendingController
import kz.icgroup.vending.ui.theme.IcBackground
import kz.icgroup.vending.ui.theme.IcBlue
import kz.icgroup.vending.ui.theme.IcGreenDark
import kz.icgroup.vending.ui.theme.IcLine
import kz.icgroup.vending.ui.theme.IcRed
import kz.icgroup.vending.ui.theme.IcSurface
import kz.icgroup.vending.ui.theme.IcText
import kz.icgroup.vending.ui.theme.IcTextSoft

// ── ввод PIN ──

@Composable
fun PinDialog(check: (String) -> Boolean, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .pointerInput(Unit) { detectTapGestures { } }, // нажатия не проходят на витрину под окном
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(IcSurface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Сервисный режим", color = IcText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (wrong) "Неверный PIN" else "Введите PIN",
                color = if (wrong) IcRed else IcTextSoft,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (pin.isEmpty()) " " else "●".repeat(pin.length),
                color = IcText,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(16.dp))

            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("⌫", "0", "OK"),
            )
            for (row in keys) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (key in row) {
                        PinKey(key) {
                            when (key) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (check(pin)) {
                                    onSuccess()
                                } else {
                                    wrong = true
                                    pin = ""
                                }
                                else -> if (pin.length < 8) {
                                    pin += key
                                    wrong = false
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            TextButton(onClick = onDismiss) { Text("Отмена", fontSize = 18.sp, color = IcBlue) }
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(IcBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = IcText, fontSize = 26.sp, fontWeight = FontWeight.Medium)
    }
}

// ── первая настройка ──

@Composable
fun SetupScreen(controller: VendingController, onAndroidSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IcBackground)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
    ) {
        Text("Настройка автомата", color = IcText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Адрес сервера и токен выдаёт администратор (создание автомата на сервере).",
            color = IcTextSoft,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(20.dp))
        SettingsForm(controller)
        Spacer(Modifier.height(24.dp))
        // Моторы можно проверить и без сервера.
        OutlinedButton(onClick = { controller.openService() }, modifier = Modifier.fillMaxWidth()) {
            Text("Тест моторов", fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAndroidSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Настройки Android (Wi-Fi)", fontSize = 16.sp)
        }
    }
}

@Composable
private fun SettingsForm(controller: VendingController) {
    val config = controller.config
    var url by remember { mutableStateOf(config.serverUrl) }
    var token by remember { mutableStateOf(config.token) }
    var pin by remember { mutableStateOf(config.pin) }
    var serial by remember { mutableStateOf(config.serialPath) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Адрес сервера, например http://192.168.1.10:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Токен автомата") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
            label = { Text("PIN сервисного режима (цифры)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            label = { Text("Порт платы моторов") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val notice = controller.notice
        if (notice != null) Text(notice, color = IcRed, fontSize = 16.sp)
        Button(
            onClick = { controller.saveSettings(url, token, pin, serial) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Сохранить и проверить связь", fontSize = 18.sp)
        }
    }
}

// ── сервисный режим ──

@Composable
fun ServiceScreen(controller: VendingController, onAndroidSettings: () -> Unit, onCloseApp: () -> Unit) {
    val byNumber = controller.cells.associateBy { it.number }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IcBackground)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сервисный режим",
                color = IcText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { controller.closeService() }) { Text("К витрине", fontSize = 16.sp) }
        }
        Spacer(Modifier.height(12.dp))
        StatusLine("Сервер", if (controller.online) "на связи" else "нет связи", controller.online)
        StatusLine("Оплата Kaspi", if (controller.kaspiReady) "кассир подключён" else "кассир не подключён", controller.kaspiReady)
        if (controller.serviceLog.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                controller.serviceLog,
                color = IcText,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IcSurface)
                    .border(1.dp, IcLine, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Ячейки", color = IcText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("«Тест» крутит пружину один раз: товар выпадет, остаток не изменится.", color = IcTextSoft, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        for (number in 1..8) {
            val cell = byNumber[number]
            val stock = cell?.stock ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(IcSurface)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CellBadge(number, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (cell == null || cell.title.isBlank()) "Ячейка $number" else cell.title,
                        color = IcText,
                        fontSize = 16.sp,
                        maxLines = 1,
                    )
                    Text("Остаток: $stock", color = if (stock > 0) IcGreenDark else IcRed, fontSize = 14.sp)
                }
                OutlinedButton(onClick = { controller.setStock(number, stock - 1) }, enabled = cell != null && stock > 0) {
                    Text("−", fontSize = 20.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { controller.setStock(number, stock + 1) }, enabled = cell != null) {
                    Text("+", fontSize = 20.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { controller.testMotor(number) }) { Text("Тест", fontSize = 16.sp) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Подключение", color = IcText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        SettingsForm(controller)

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onAndroidSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Настройки Android", fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCloseApp, modifier = Modifier.fillMaxWidth()) {
            Text("Закрыть приложение", fontSize = 16.sp, color = IcRed)
        }
    }
}

@Composable
private fun StatusLine(name: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) IcGreenDark else IcRed))
        Spacer(Modifier.width(8.dp))
        Text("$name: $value", color = IcText, fontSize = 16.sp)
    }
}
