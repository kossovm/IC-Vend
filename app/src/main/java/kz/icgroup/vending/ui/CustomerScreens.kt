package kz.icgroup.vending.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kz.icgroup.vending.MessageKind
import kz.icgroup.vending.R
import kz.icgroup.vending.Screen
import kz.icgroup.vending.VendingController
import kz.icgroup.vending.data.CellInfo
import kz.icgroup.vending.ui.theme.IcBackground
import kz.icgroup.vending.ui.theme.IcBlue
import kz.icgroup.vending.ui.theme.IcBlueDark
import kz.icgroup.vending.ui.theme.IcGreen
import kz.icgroup.vending.ui.theme.IcGreenDark
import kz.icgroup.vending.ui.theme.IcLine
import kz.icgroup.vending.ui.theme.IcRed
import kz.icgroup.vending.ui.theme.IcSurface
import kz.icgroup.vending.ui.theme.IcText
import kz.icgroup.vending.ui.theme.IcTextSoft

/** Полки шкафа сверху вниз: 01 и 02 — широкие, дальше по две ячейки. Экран повторяет шкаф. */
private val SHELVES = listOf(listOf(1), listOf(2), listOf(3, 4), listOf(5, 6), listOf(7, 8))

private val CardShape = RoundedCornerShape(20.dp)

// ── витрина ──

@Composable
fun CatalogScreen(controller: VendingController, onServiceRequest: () -> Unit) {
    val byNumber = controller.cells.associateBy { it.number }
    val canBuy = controller.online && controller.kaspiReady && !controller.busy

    Box(Modifier.fillMaxSize().background(IcBackground)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_group_logo),
                    contentDescription = "IC Group",
                    modifier = Modifier
                        .height(64.dp)
                        .width(66.dp)
                        // Долгое нажатие на логотип открывает вход в сервисный режим.
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onServiceRequest() }) },
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Выберите товар", color = IcText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Оплата по Kaspi QR", color = IcTextSoft, fontSize = 16.sp)
                }
            }

            val banner = when {
                !controller.online -> "Нет связи с сервером. Покупка временно недоступна"
                !controller.kaspiReady -> "Оплата временно недоступна"
                else -> controller.notice
            }
            if (banner != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = banner,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IcRed)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (shelf in SHELVES) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (number in shelf) {
                            val cell = byNumber[number]
                            CellCard(
                                number = number,
                                cell = cell,
                                enabled = canBuy && cell != null && cell.available,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = { if (cell != null) controller.buy(cell) },
                            )
                        }
                    }
                }
            }
        }

        if (controller.busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99FFFFFF))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = IcGreen)
                    Spacer(Modifier.height(16.dp))
                    Text("Создаём QR для оплаты…", color = IcText, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun CellCard(number: Int, cell: CellInfo?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val soldOut = cell == null || !cell.available
    Row(
        modifier = modifier
            .alpha(if (soldOut) 0.5f else 1f)
            .clip(CardShape)
            .background(IcSurface)
            .border(1.dp, IcLine, CardShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(14.dp))
                .background(IcBackground),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(url = cell?.imageUrl, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = number.toString().padStart(2, '0'),
                    color = IcBlue,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { CellBadge(number, 32.dp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (cell == null || cell.title.isBlank()) "Ячейка $number" else cell.title,
                color = IcText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (soldOut) {
                Text("Нет в наличии", color = IcTextSoft, fontSize = 16.sp)
            } else {
                Text(money(cell!!.price), color = IcGreenDark, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── оплата ──

@Composable
fun PayScreen(pay: Screen.Pay, onCancel: () -> Unit) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val secondsLeft = ((pay.deadline - now) / 1000L).coerceAtLeast(0L)
    val order = pay.order

    Column(
        modifier = Modifier.fillMaxSize().background(IcBackground).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CellBadge(order.cell, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (order.title.isBlank()) "Ячейка ${order.cell}" else order.title,
                color = IcText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(money(order.amount), color = IcGreenDark, fontSize = 44.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(1.dp, IcLine, RoundedCornerShape(24.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val qr = order.qr
            if (qr != null) QrCode(qr, Modifier.fillMaxSize()) else CircularProgressIndicator(color = IcGreen)
        }
        Spacer(Modifier.height(20.dp))

        val hint = when {
            order.scanned -> "Подтвердите оплату в приложении Kaspi"
            secondsLeft == 0L -> "Проверяем оплату…"
            else -> "Отсканируйте QR в приложении Kaspi.kz"
        }
        Text(hint, color = IcText, fontSize = 22.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (secondsLeft > 0L) {
            val text = "Осталось " + (secondsLeft / 60L) + ":" + (secondsLeft % 60L).toString().padStart(2, '0')
            Text(text, color = IcTextSoft, fontSize = 18.sp)
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Отмена", fontSize = 20.sp, color = IcBlueDark)
        }
    }
}

// ── выдача и итог ──

@Composable
fun DispensingScreen(title: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(IcBackground).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = IcGreen, modifier = Modifier.size(96.dp), strokeWidth = 8.dp)
        Spacer(Modifier.height(32.dp))
        Text("Выдаём товар…", color = IcText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (title.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(title, color = IcTextSoft, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun MessageScreen(message: Screen.Message) {
    val color = when (message.kind) {
        MessageKind.SUCCESS -> IcGreen
        MessageKind.INFO -> IcBlue
        MessageKind.PROBLEM -> IcRed
    }
    val symbol = when (message.kind) {
        MessageKind.SUCCESS -> "✓"
        MessageKind.INFO -> "i"
        MessageKind.PROBLEM -> "!"
    }
    Column(
        modifier = Modifier.fillMaxSize().background(IcBackground).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(140.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(symbol, color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(32.dp))
        Text(message.title, color = IcText, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(message.text, color = IcTextSoft, fontSize = 22.sp, textAlign = TextAlign.Center)
    }
}
