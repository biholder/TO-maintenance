package app.flightlog.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.flightlog.reader.screens.SettingsScreen
import app.flightlog.reader.update.Updater
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import app.flightlog.reader.ui.LocalLandscape
import app.flightlog.reader.ui.Splash
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
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
import app.flightlog.reader.screens.ReportChartsScreen
import app.flightlog.reader.screens.ReportParamsScreen
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
            AppTheme(dark) { Root(vm, ::share, ::requestNotifications) }
        }
        // Один раз спросить разрешение на уведомления, если включена автопроверка обновлений.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (vm.updateAuto && !prefs.getBoolean("notifications_asked", false)) {
            prefs.edit().putBoolean("notifications_asked", true).apply()
            requestNotifications()
        }
        // Файл, переданный через «Поделиться» / «Открыть с помощью» (не повторяем при повороте экрана).
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Разрешение на уведомления (Android 13+) — для сообщений о новых версиях. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIncoming(intent: Intent?) {
        // Нажатие на уведомление о новой версии.
        if (intent?.getBooleanExtra(Updater.EXTRA_OPEN_UPDATE, false) == true) {
            intent.removeExtra(Updater.EXTRA_OPEN_UPDATE)
            vm.splashShown = true
            vm.openUpdates()
            return
        }
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
private fun Root(vm: AppViewModel, share: (File, String) -> Unit, requestNotifications: () -> Unit) {
    BackHandler(enabled = vm.stack.size > 1 || vm.sheet || vm.compareMode || vm.fullChart != null) { vm.back() }
    Box(Modifier.fillMaxSize().background(tk.bg)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            CompositionLocalProvider(LocalLandscape provides (maxWidth > maxHeight)) {
                when (vm.screen) {
                    Screen.LIBRARY -> LibraryScreen(vm)
                    Screen.DECODE -> DecodeScreen(vm, share)
                    Screen.FLIGHT -> FlightScreen(vm)
                    Screen.COMPARE -> CompareScreen(vm)
                    Screen.EXPORT -> ExportScreen(vm, share)
                    Screen.REPORT_CHARTS -> ReportChartsScreen(vm)
                    Screen.REPORT_PARAMS -> ReportParamsScreen(vm)
                    Screen.SETTINGS -> SettingsScreen(vm, requestNotifications)
                }
            }
        }
        if (vm.sheet) ImportSheet(vm)
        if (!vm.splashShown) {
            // Поверх всего экрана, касания до конца заставки не доходят до интерфейса.
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
                Splash { vm.splashShown = true }
            }
        }
        vm.toast?.let { msg ->
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.background(tk.tx, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    T(msg, Type.body(14.sp, androidx.compose.ui.text.font.FontWeight.Medium), tk.bg)
                }
            }
        }
    }
}
