package kz.icgroup.vending.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.icgroup.vending.ui.theme.IcRed

/** 1500 → «1 500 ₸» */
fun money(amount: Int): String =
    amount.toString().reversed().chunked(3).joinToString(" ").reversed() + " ₸"

/** Красный кружок с номером, как наклейка на полке шкафа. */
@Composable
fun CellBadge(number: Int, size: Dp = 36.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(IcRed),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}
