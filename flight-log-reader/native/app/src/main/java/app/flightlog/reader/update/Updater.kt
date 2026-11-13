package app.flightlog.reader.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.flightlog.reader.MainActivity
import app.flightlog.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Релиз PLOV на GitHub. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
    val publishedAt: String,
)

/**
 * Обновление приложения из релизов GitHub: проверка последней версии, скачивание APK,
 * проверка подписи и запуск системной установки. Релизы собирает CI репозитория
 * ([REPO]) и подписывает постоянным ключом, поэтому новая версия ставится поверх.
 */
object Updater {
    const val REPO = "biholder/TO-maintenance"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "PLOV updater (https://github.com/$REPO)"

    const val CHANNEL = "updates"
    const val EXTRA_OPEN_UPDATE = "open_update"
    private const val WORK = "plov-update-check"
    private const val NOTIFICATION_ID = 1001

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Сравнение версий вида 0.5.1: положительное — [a] новее. */
    fun compare(a: String, b: String): Int {
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/vnd.github+json")
        instanceFollowRedirects = true
    }

    /** Последний релиз с APK. */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val c = open(API)
        try {
            when (c.responseCode) {
                200 -> Unit
                403, 429 -> throw IOException("GitHub временно ограничил запросы, попробуйте позже")
                404 -> throw IOException("В репозитории нет опубликованных релизов")
                else -> throw IOException("GitHub ответил ${c.responseCode}")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
            apk ?: throw IOException("В последнем релизе нет файла APK")
            Release(
                version = j.getString("tag_name").removePrefix("v"),
                notes = j.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                pageUrl = j.optString("html_url", RELEASES_PAGE),
                publishedAt = j.optString("published_at").take(10),
            )
        } finally { c.disconnect() }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Скачивает APK; [progress] — доля 0..1. Старые файлы обновлений удаляются. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        d.listFiles()?.forEach { it.delete() }
        val out = File(d, "PLOV-${r.version}.apk")
        val tmp = File(d, "PLOV-${r.version}.apk.part")
        val c = open(r.apkUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode != 200) throw IOException("Не удалось скачать: код ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (r.apkSize > 0 && done != r.apkSize) throw IOException("Файл скачан не полностью")
        } finally { c.disconnect() }
        if (!tmp.renameTo(out)) throw IOException("Не удалось сохранить файл")
        verify(ctx, out, r)
        out
    }

    /** APK — то же приложение, новее установленного и подписан тем же ключом. */
    private fun verify(ctx: Context, apk: File, r: Release) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        if (archive == null) { apk.delete(); throw IOException("Скачанный файл не является приложением Android") }
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        if (archive.packageName != ctx.packageName) { apk.delete(); throw IOException("Файл релиза — другое приложение") }
        if (versionCode(archive) <= versionCode(installed)) { apk.delete(); throw IOException("Версия ${r.version} не новее установленной") }
        if (certs(archive) != certs(installed)) {
            apk.delete()
            throw IOException("Подпись новой версии не совпадает с установленной — установка отменена")
        }
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else @Suppress("DEPRECATION") p.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Разрешено ли приложению ставить APK (Android 8+: «Установка неизвестных приложений»). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Открывает системную настройку «Разрешить установку из этого источника». */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Системный установщик для скачанного APK. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Фоновая проверка и уведомление ──

    /** Включает или выключает ежедневную проверку в фоне. */
    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Уведомить о версии, если о ней ещё не уведомляли. */
    fun notifyOnce(ctx: Context, r: Release) {
        val p = prefs(ctx)
        if (p.getString("update_notified", null) == r.version) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления PLOV", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Доступна версия PLOV ${r.version}")
            .setContentText("Нажмите, чтобы скачать и установить")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
            p.edit().putString("update_notified", r.version).apply()
        } catch (_: SecurityException) {
            // Нет разрешения на уведомления — останется плашка в приложении.
        }
    }

    class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val r = try { latest() } catch (_: Exception) { return Result.retry() }
            if (compare(r.version, currentVersion(applicationContext)) > 0) notifyOnce(applicationContext, r)
            prefs(applicationContext).edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            return Result.success()
        }
    }
}
