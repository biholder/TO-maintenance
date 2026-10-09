package app.flightlog.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import app.flightlog.reader.screens.CompareScreen
import app.flightlog.reader.screens.DecodeScreen
import app.flightlog.reader.screens.ExportScreen
import app.flightlog.reader.screens.FlightScreen
import app.flightlog.reader.screens.ImportSheet
import app.flightlog.reader.screens.LibraryScreen
import app.flightlog.reader.ui.AppTheme
import app.flightlog.reader.ui.T
import app.flightlog.reader.ui.Type
import app.flightlog.reader.ui.tk
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = vm.themeOverride ?: isSystemInDarkTheme()
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            AppTheme(dark) { Root(vm, ::share) }
        }
        // Файл, переданный через «Поделиться» / «Открыть с помощью» (не повторяем при повороте экрана).
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.data,
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importShared(uris)
        // Чтобы повторный показ Activity не импортировал файл ещё раз.
        intent?.action = Intent.ACTION_MAIN
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, file.name))
    }
}

@Composable
private fun Root(vm: AppViewModel, share: (File, String) -> Unit) {
    BackHandler(enabled = vm.stack.size > 1 || vm.sheet || vm.compareMode) { vm.back() }
    Box(Modifier.fillMaxSize().background(tk.bg)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (vm.screen) {
                Screen.LIBRARY -> LibraryScreen(vm)
                Screen.DECODE -> DecodeScreen(vm)
                Screen.FLIGHT -> FlightScreen(vm)
                Screen.COMPARE -> CompareScreen(vm)
                Screen.EXPORT -> ExportScreen(vm, share)
            }
        }
        if (vm.sheet) ImportSheet(vm)
        vm.toast?.let { msg ->
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.background(tk.tx).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    T(msg, Type.body(14.sp, androidx.compose.ui.text.font.FontWeight.Medium), tk.bg)
                }
            }
        }
    }
}
