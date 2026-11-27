package kz.icgroup.vending

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kz.icgroup.vending.data.Config
import kz.icgroup.vending.ui.AppRoot
import kz.icgroup.vending.ui.theme.IcVendingTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: VendingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // экран автомата не гаснет
        WindowCompat.setDecorFitsSystemWindows(window, false)

        controller = VendingController(Config(applicationContext), lifecycleScope)
        setContent {
            IcVendingTheme {
                AppRoot(
                    controller = controller,
                    onAndroidSettings = { startActivity(Intent(Settings.ACTION_SETTINGS)) },
                    onCloseApp = { finishAffinity() },
                )
            }
        }
        controller.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Прячет строку состояния и кнопки навигации: покупатель видит только витрину. */
    private fun hideSystemBars() {
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }
}
