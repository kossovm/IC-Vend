package kz.icgroup.vending.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kz.icgroup.vending.Screen
import kz.icgroup.vending.VendingController
import kz.icgroup.vending.ui.theme.IcBackground

/** Корень интерфейса: показывает экран, который выбрал контроллер. */
@Composable
fun AppRoot(controller: VendingController, onAndroidSettings: () -> Unit, onCloseApp: () -> Unit) {
    var pinOpen by remember { mutableStateOf(false) }

    // Автомат работает как киоск: кнопка «Назад» ничего не делает.
    BackHandler(enabled = true) { }

    Box(Modifier.fillMaxSize().background(IcBackground)) {
        when (val screen = controller.screen) {
            Screen.Setup -> SetupScreen(controller, onAndroidSettings)
            Screen.Catalog -> CatalogScreen(controller, onServiceRequest = { pinOpen = true })
            is Screen.Pay -> PayScreen(screen, onCancel = { controller.cancelPayment() })
            is Screen.Dispensing -> DispensingScreen(screen.title)
            is Screen.Message -> MessageScreen(screen)
            Screen.Service -> ServiceScreen(controller, onAndroidSettings, onCloseApp)
        }

        if (pinOpen && controller.screen == Screen.Catalog) {
            PinDialog(
                check = { controller.checkPin(it) },
                onSuccess = {
                    pinOpen = false
                    controller.openService()
                },
                onDismiss = { pinOpen = false },
            )
        }
    }
}
