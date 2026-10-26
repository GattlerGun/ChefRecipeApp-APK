package com.example.recipeapp

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.random.Random

private const val ACTION_INSTALL_COMMIT = "com.example.recipeapp.ACTION_INSTALL_COMMIT"
private const val DEFAULT_GITHUB_REPO = "GattlerGun/ChefRecipeApp-APK"

private val LightCulinaryColorScheme = lightColorScheme(
    primary = Color(0xFFD84315), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFCCBC), onPrimaryContainer = Color(0xFF3E2723),
    secondary = Color(0xFFEF6C00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2), onSecondaryContainer = Color(0xFF4E342E),
    tertiary = Color(0xFF2E7D32), onTertiary = Color.White,
    background = Color(0xFFFFFBF8), onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5EBE6), onSurfaceVariant = Color(0xFF49454F)
)

private val DarkCulinaryColorScheme = darkColorScheme(
    primary = Color(0xFFFF8A65), onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFF5D4037), onPrimaryContainer = Color(0xFFFFCCBC),
    secondary = Color(0xFFFFB74D), onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF4E342E), onSecondaryContainer = Color(0xFFFFE0B2),
    tertiary = Color(0xFF81C784), onTertiary = Color(0xFF1B5E20),
    background = Color(0xFF121212), onBackground = Color(0xFFEDE0DC),
    surface = Color(0xFF1E1E1E), onSurface = Color(0xFFEDE0DC),
    surfaceVariant = Color(0xFF2C2624), onSurfaceVariant = Color(0xFFD7CCC8)
)

@Immutable
data class Recipe(
    val id: String,
    val title: String,
    val description: String,
    val cuisine: String,
    val category: String,
    val cookingTime: String,
    val difficulty: String,
    val calories: Int,
    val imageUrls: List<String>,
    val ingredients: List<String>,
    val instructions: List<String>,
    val isCustom: Boolean = false
) {
    val cookingMinutes: Int = CulinaryUtils.parseCookingMinutesOnce(cookingTime)
    val searchIndexText: String = buildString {
        append(title.lowercase()).append(' ')
        append(description.lowercase()).append(' ')
        append(cuisine.lowercase()).append(' ')
        append(category.lowercase()).append(' ')
        ingredients.forEach { append(it.lowercase()).append(' ') }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("description", description)
        put("cuisine", cuisine); put("category", category); put("cookingTime", cookingTime)
        put("difficulty", difficulty); put("calories", calories); put("isCustom", isCustom)
        put("imageUrls", JSONArray(imageUrls)); put("ingredients", JSONArray(ingredients))
        put("instructions", JSONArray(instructions))
    }

    companion object {
        fun fromJson(obj: JSONObject): Recipe {
            val imgs = obj.optJSONArray("imageUrls") ?: JSONArray()
            val ings = obj.optJSONArray("ingredients") ?: JSONArray()
            val insts = obj.optJSONArray("instructions") ?: JSONArray()
            val title = obj.optString("title", "Домашнее блюдо")
            val cuisine = obj.optString("cuisine", "Домашняя")
            val category = obj.optString("category", "Вторые блюда")
            val ingList = List(ings.length()) { ings.getString(it) }
            val rawDesc = obj.optString("description", "")
            val cleanImages = List(imgs.length()) { imgs.getString(it) }
                .filter { !it.contains("/images/ingredients/", ignoreCase = true) }
                .ifEmpty { CulinaryUtils.buildDishPhotoUrls(title, 1) }

            return Recipe(
                id = obj.optString("id", "rec_${System.currentTimeMillis()}"),
                title = title,
                description = if (rawDesc.isNotBlank()) rawDesc else CulinaryUtils.buildDishDescription(title, cuisine, category, ingList),
                cuisine = cuisine, category = category,
                cookingTime = obj.optString("cookingTime", "30 мин"),
                difficulty = obj.optString("difficulty", "Легко"),
                calories = obj.optInt("calories", 250),
                imageUrls = cleanImages, ingredients = ingList,
                instructions = List(insts.length()) { insts.getString(it) },
                isCustom = obj.optBoolean("isCustom", false)
            )
        }
    }
}

@Immutable
data class ParsedTimer(val durationSeconds: Int, val label: String)

@Immutable
data class GitHubReleaseInfo(
    val repoName: String,
    val releaseTitle: String,
    val publishedAtMillis: Long,
    val publishedFormatted: String,
    val downloadUrl: String,
    val sizeMb: String,
    val isNewerThanInstalled: Boolean
)

enum class DataSourceMode(val shortLabel: String, val fullLabel: String) {
    LOCAL("📚 Локальная", "📚 Локальная база"),
    MEAL_DB_API("🌍 Мировая", "🌍 Мировая база (RU)"),
    FREE_AI("✨ ИИ Шеф", "✨ ИИ Шеф (RU)")
}

enum class SortOrder(val label: String) {
    FRESH_BATCH("🎲 Новизна"),
    FRIDGE_MATCH("🧊 По холодильнику"),
    FASTEST("⚡ Быстрые"),
    LOW_CALORIE("🥗 Легкие")
}

enum class AppScreen { RECIPES, SHOPPING_LIST, ADD_RECIPE }

// =========================================================================
// МЕНЕДЖЕР АВТООБНОВЛЕНИЯ С GITHUB RELEASES
// =========================================================================
object AppUpdateManager {
    var signatureConflictState by mutableStateOf(false)

    fun getInstalledUpdateTimestamp(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) { 0L }

    fun formatTimestamp(millis: Long): String {
        if (millis <= 0L) return "Неизвестно"
        return SimpleDateFormat("dd.MM.yyyy в HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoTimestamp(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    suspend fun checkGitHubUpdate(context: Context, repoPath: String = DEFAULT_GITHUB_REPO): Result<GitHubReleaseInfo> =
        withContext(Dispatchers.IO) {
            val cleanRepo = repoPath.removePrefix("https://github.com/").removePrefix("http://github.com/")
                .removePrefix("github.com/").trim('/').ifEmpty { DEFAULT_GITHUB_REPO }
            val candidateRepos = listOf(cleanRepo, "GattlerGun/RecipeApp").distinct()
            val installedTime = getInstalledUpdateTimestamp(context)

            for (repo in candidateRepos) {
                val urls = listOf(
                    "https://api.github.com/repos/$repo/releases/tags/latest-apk",
                    "https://api.github.com/repos/$repo/releases/latest",
                    "https://api.github.com/repos/$repo/releases"
                )
                for (apiUrl in urls) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("User-Agent", "ChefRecipeApp-Updater")
                            setRequestProperty("Cache-Control", "no-cache")
                            connectTimeout = 6000; readTimeout = 6000
                        }
                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val releaseObj = if (body.trimStart().startsWith("[")) {
                                val arr = JSONArray(body)
                                if (arr.length() > 0) arr.getJSONObject(0) else continue
                            } else JSONObject(body)

                            val assets = releaseObj.optJSONArray("assets") ?: JSONArray()
                            var apkUrl = ""
                            var assetUpdatedIso = releaseObj.optString("published_at", "")
                            var sizeBytes = 0L

                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                                    apkUrl = asset.optString("browser_download_url", "")
                                    assetUpdatedIso = asset.optString("updated_at", assetUpdatedIso)
                                    sizeBytes = asset.optLong("size", 0L)
                                    break
                                }
                            }
                            if (apkUrl.isEmpty()) apkUrl = "https://github.com/$repo/releases/download/latest-apk/ChefRecipeApp.apk"
                            val remoteMillis = parseIsoTimestamp(assetUpdatedIso)
                            val sizeMb = if (sizeBytes > 0) String.format(Locale.US, "%.1f МБ", sizeBytes / 1048576.0) else "APK сборка"
                            val isNewer = remoteMillis > (installedTime + 60_000L)

                            return@withContext Result.success(
                                GitHubReleaseInfo(
                                    repoName = repo,
                                    releaseTitle = releaseObj.optString("name", "Свежая сборка APK"),
                                    publishedAtMillis = remoteMillis,
                                    publishedFormatted = formatTimestamp(remoteMillis),
                                    downloadUrl = apkUrl,
                                    sizeMb = sizeMb,
                                    isNewerThanInstalled = isNewer
                                )
                            )
                        }
                    } catch (_: Exception) {
                    } finally { conn?.disconnect() }
                }
            }
            val directUrl = "https://github.com/$cleanRepo/releases/download/latest-apk/ChefRecipeApp.apk"
            Result.success(
                GitHubReleaseInfo(
                    repoName = cleanRepo, releaseTitle = "Прямая сборка (latest-apk)",
                    publishedAtMillis = System.currentTimeMillis(), publishedFormatted = "Последний билд в main",
                    downloadUrl = directUrl, sizeMb = "ChefRecipeApp.apk", isNewerThanInstalled = true
                )
            )
        }

    private fun copyApkToPublicDownloads(context: Context, sourceFile: File) {
        try {
            val fileName = "ChefRecipeApp_latest.apk"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) resolver.openOutputStream(uri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                sourceFile.copyTo(File(dir, fileName), overwrite = true)
            }
        } catch (_: Exception) {}
    }

    suspend fun downloadAndInstallApk(context: Context, releaseInfo: GitHubReleaseInfo, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                var currentUrl = releaseInfo.downloadUrl
                var redirects = 0
                var conn: HttpURLConnection
                while (true) {
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true; requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; ChefRecipeApp)")
                        setRequestProperty("Cache-Control", "no-cache")
                        connectTimeout = 15000; readTimeout = 30000
                    }
                    if (conn.responseCode in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank() || redirects >= 6) break
                        currentUrl = loc; redirects++
                    } else break
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext Result.failure(Exception("APK еще не выгружен в Releases (код ${conn.responseCode})"))
                }
                val totalBytes = conn.contentLengthLong
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "ChefRecipeApp_update.apk")
                if (apkFile.exists()) apkFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                copyApkToPublicDownloads(context, apkFile)
                withContext(Dispatchers.Main) { triggerPackageInstaller(context, apkFile) }
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }

    fun triggerPackageInstaller(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, "Файл обновления не найден", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Разрешите установку обновлений для «Шеф-Повар» и нажмите «Обновить» еще раз", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        var session: PackageInstaller.Session? = null
        try {
            val installer = context.packageManager.packageInstaller
            try { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } } catch (_: Exception) {}
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            apkFile.inputStream().use { input ->
                session.openWrite("ChefRecipeApp_update.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output, 32 * 1024)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(context, MainActivity::class.java).apply { action = ACTION_INSTALL_COMMIT }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getActivity(context, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка установщика: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }
}

// =========================================================================
// КУЛИНАРНЫЕ УТИЛИТЫ И СЛОВАРЬ
// =========================================================================
object CulinaryUtils {
    val CUISINES = listOf("Все", "Русская", "Корейская", "Французская", "Итальянская", "Японская", "Грузинская", "Мексиканская", "Китайская", "Тайская", "Индийская")
    val CATEGORIES = listOf("Все", "Супы", "Каши и завтраки", "Вторые блюда", "Выпечка", "Что-то к чаю", "Салаты", "Паста и пицца")
    val QUICK_FRIDGE = listOf("Курица", "Яйца", "Картофель", "Сыр", "Томаты", "Молоко", "Рис", "Фарш", "Творог", "Капуста", "Грибы")

    private val HOURS_REGEX = Regex("(\\d+)\\s*ч")
    private val MINS_REGEX = Regex("(\\d+)\\s*мин")
    private val ANY_DIGIT_REGEX = Regex("(\\d+)")
    private val TIMER_REGEX = Regex("(\\d+)\\s*(мин(?:ут[а-хей]?)?|час(?:[а-ов]?)?|сек(?:унд[а-хей]?)?)", RegexOption.IGNORE_CASE)
    private val NUMBER_REGEX = Regex("(\\d+(?:[.,]\\d+)?)")
    private val STEP_SPLIT_REGEX = Regex("(?=\\b\\d+\\.)|\\r?\\n")
    private val STEP_PREFIX_REGEX = Regex("^\\d+[.)]\\s*")
    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
    private val LATIN_REGEX = Regex("[a-zA-Z]{2,}")
    private val NON_WORD_REGEX = Regex("[^а-яёa-z\\s-]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun parseCookingMinutesOnce(cookingTime: String): Int {
        val lower = cookingTime.lowercase()
        val hours = HOURS_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = MINS_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()
            ?: ANY_DIGIT_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 30
        return hours * 60 + mins
    }

    fun buildDishPhotoUrls(dishNameForPrompt: String, seedBase: Int, primaryThumbUrl: String? = null): List<String> {
        val cleanName = dishNameForPrompt.replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s]"), " ").trim()
        val encoded = URLEncoder.encode(cleanName.ifEmpty { "cooked dish" }, "UTF-8").replace("+", "%20")
        val angle1 = "https://image.pollinations.ai/prompt/$encoded%20cooked%20dish%20plated%20restaurant%20food%20photography?width=600&height=400&nologo=true&seed=${seedBase + 11}"
        val angle2 = "https://image.pollinations.ai/prompt/$encoded%20delicious%20prepared%20meal%20overhead%20closeup%20serving?width=600&height=400&nologo=true&seed=${seedBase + 55}"
        val angle3 = "https://image.pollinations.ai/prompt/$encoded%20traditional%20cooked%20food%20on%20table%20gourmet?width=600&height=400&nologo=true&seed=${seedBase + 99}"
        return if (!primaryThumbUrl.isNullOrBlank()) listOf(primaryThumbUrl, angle1, angle2) else listOf(angle1, angle2, angle3)
    }

    private val AREA_TO_RUSSIAN_CUISINE = mapOf(
        "russian" to "Русская", "korean" to "Корейская", "french" to "Французская", "italian" to "Итальянская",
        "japanese" to "Японская", "georgian" to "Грузинская", "mexican" to "Мексиканская", "chinese" to "Китайская",
        "thai" to "Тайская", "indian" to "Индийская", "greek" to "Греческая", "spanish" to "Испанская",
        "turkish" to "Турецкая", "vietnamese" to "Вьетнамская", "moroccan" to "Марокканская", "american" to "Американская",
        "british" to "Британская", "polish" to "Польская", "ukrainian" to "Украинская", "croatian" to "Хорватская",
        "irish" to "Ирландская", "jamaican" to "Ямайская", "egyptian" to "Египетская", "dutch" to "Голландская",
        "portuguese" to "Португальская", "canadian" to "Канадская", "malaysian" to "Малайзийская", "tunisian" to "Тунисская",
        "filipino" to "Филиппинская", "kenyan" to "Кенийская", "norwegian" to "Норвежская", "australian" to "Австралийская"
    )

    private val CUISINE_FLAGS = mapOf(
        "Русская" to "🇷🇺", "Корейская" to "🇰🇷", "Французская" to "🇫🇷", "Итальянская" to "🇮🇹",
        "Японская" to "🇯🇵", "Грузинская" to "🇬🇪", "Мексиканская" to "🇲🇽", "Китайская" to "🇨🇳",
        "Тайская" to "🇹🇭", "Индийская" to "🇮🇳", "Греческая" to "🇬🇷", "Испанская" to "🇪🇸",
        "Турецкая" to "🇹🇷", "Вьетнамская" to "🇻🇳", "Марокканская" to "🇲🇦", "Американская" to "🇺🇸",
        "Британская" to "🇬🇧", "Польская" to "🇵🇱", "Украинская" to "🇺🇦", "Хорватская" to "🇭🇷",
        "Ирландская" to "🇮🇪", "Ямайская" to "🇯🇲", "Египетская" to "🇪🇬", "Голландская" to "🇳🇱",
        "Португальская" to "🇵🇹", "Канадская" to "🇨🇦", "Малайзийская" to "🇲🇾", "Тунисская" to "🇹🇳",
        "Филиппинская" to "🇵🇭", "Домашняя" to "🏠"
    )

    fun buildDishDescription(title: String, cuisine: String, category: String, ingredients: List<String>): String {
        val coreIngs = ingredients.take(4)
            .map { normalizeAndTranslateCulinary(it.substringBefore("—").substringBefore("-")).trim().lowercase() }
            .filter { it.isNotBlank() }.joinToString(", ")
        val categoryFlavor = when (category) {
            "Супы", "Супы и закуски" -> "Ароматное первое блюдо с насыщенным бульоном и согревающим вкусом"
            "Каши и завтраки" -> "Сытное и нежное утреннее блюдо для питательного завтрака"
            "Выпечка" -> "Румяная домашняя выпечка с мягкой текстурой и аппетитной корочкой"
            "Что-то к чаю" -> "Нежный десерт к чаю или кофе с приятной сладостью и ароматом"
            "Салаты", "Салаты и гарниры" -> "Свежее сбалансированное блюдо с ярким сочетанием текстур"
            "Паста и пицца" -> "Классическое блюдо с насыщенным соусом и аппетитной подачей"
            else -> "Сытное и ароматное основное блюдо для обеда или ужина"
        }
        val cuisineNote = if (cuisine != "Мировая" && cuisine != "Все") "традиции кухни «$cuisine»" else "мировой кулинарной коллекции"
        return if (coreIngs.isNotEmpty()) "$categoryFlavor из $cuisineNote. В основе рецепта — $coreIngs." else "$categoryFlavor из $cuisineNote."
    }

    fun resolveRealCuisine(rawArea: String?, dishTitle: String, selectedFilterCuisine: String): String {
        val cleanArea = rawArea?.trim()?.lowercase() ?: ""
        if (cleanArea.isNotEmpty() && cleanArea != "unknown") {
            AREA_TO_RUSSIAN_CUISINE[cleanArea]?.let { return it }
            if (!hasLatin(cleanArea)) {
                val cap = cleanArea.replaceFirstChar { it.uppercase() }
                if (cap != "Все" && cap != "Любая" && cap != "Разные") return cap
            }
        }
        val lowerTitle = dishTitle.lowercase()
        when {
            lowerTitle.contains("кимчи") || lowerTitle.contains("корейск") || lowerTitle.contains("пибимпап") || lowerTitle.contains("токпокки") || lowerTitle.contains("яннём") || lowerTitle.contains("пулькоги") -> return "Корейская"
            lowerTitle.contains("борщ") || lowerTitle.contains("сырник") || lowerTitle.contains("строганов") || lowerTitle.contains("пельмен") || lowerTitle.contains("оливье") -> return "Русская"
            lowerTitle.contains("рамен") || lowerTitle.contains("терияки") || lowerTitle.contains("суши") || lowerTitle.contains("удон") || lowerTitle.contains("японск") -> return "Японская"
            lowerTitle.contains("паста") || lowerTitle.contains("карбонара") || lowerTitle.contains("пицца") || lowerTitle.contains("лазанья") || lowerTitle.contains("тирамису") -> return "Итальянская"
            lowerTitle.contains("круассан") || lowerTitle.contains("гратен") || lowerTitle.contains("жюльен") || lowerTitle.contains("жульен") || lowerTitle.contains("французск") -> return "Французская"
            lowerTitle.contains("хачапури") || lowerTitle.contains("чкмерули") || lowerTitle.contains("хинкали") || lowerTitle.contains("харчо") || lowerTitle.contains("грузинск") -> return "Грузинская"
            lowerTitle.contains("кесадилья") || lowerTitle.contains("тако") || lowerTitle.contains("буррито") || lowerTitle.contains("гуакамоле") -> return "Мексиканская"
            lowerTitle.contains("том ям") || lowerTitle.contains("пад тай") || lowerTitle.contains("тайск") -> return "Тайская"
            lowerTitle.contains("карри") || lowerTitle.contains("масала") || lowerTitle.contains("индийск") -> return "Индийская"
            lowerTitle.contains("вок") || lowerTitle.contains("кисло-сладк") || lowerTitle.contains("китайск") -> return "Китайская"
        }
        return if (selectedFilterCuisine != "Все") selectedFilterCuisine else "Мировая"
    }

    fun resolveRealCategory(rawCategory: String?, dishTitle: String, selectedFilterCategory: String): String {
        if (selectedFilterCategory != "Все") return selectedFilterCategory
        val lowerTitle = dishTitle.lowercase()
        if (lowerTitle.contains("суп") || lowerTitle.contains("борщ") || lowerTitle.contains("рамен") || lowerTitle.contains("бульон") || lowerTitle.contains("том ям")) return "Супы"
        if (lowerTitle.contains("салат") || lowerTitle.contains("цезарь") || lowerTitle.contains("оливье")) return "Салаты"
        val cleanCat = rawCategory?.trim()?.lowercase() ?: ""
        return when (cleanCat) {
            "starter" -> "Супы"
            "breakfast" -> "Каши и завтраки"
            "dessert" -> "Что-то к чаю"
            "pasta" -> "Паста и пицца"
            "side" -> "Салаты"
            "beef", "chicken", "pork", "lamb", "seafood", "goat", "miscellaneous", "vegan", "vegetarian" -> "Вторые блюда"
            else -> if (!hasLatin(cleanCat) && cleanCat.isNotEmpty()) cleanCat.replaceFirstChar { it.uppercase() } else "Вторые блюда"
        }
    }

    fun formatCuisineWithFlag(cuisine: String): String = "${CUISINE_FLAGS[cuisine] ?: "🌍"} $cuisine"

    private val SPEECH_REPLACEMENTS = listOf(
        Regex("(\\d+)\\s*-\\s*(\\d+)") to "$1 или $2",
        Regex("(?i)\\bст\\.?\\s*л\\.?") to "столовых ложек",
        Regex("(?i)\\bч\\.?\\s*л\\.?") to "чайных ложек",
        Regex("(?i)(\\d+)\\s*г\\b\\.?") to "$1 граммов",
        Regex("(?i)(\\d+)\\s*гр\\b\\.?") to "$1 граммов",
        Regex("(?i)(\\d+)\\s*кг\\b\\.?") to "$1 килограммов",
        Regex("(?i)(\\d+)\\s*мл\\b\\.?") to "$1 миллилитров",
        Regex("(?i)(\\d+)\\s*л\\b\\.?") to "$1 литров",
        Regex("(?i)(\\d+)\\s*шт\\.?") to "$1 штуки",
        Regex("(?i)(\\d+)\\s*мин\\.?") to "$1 минут",
        Regex("(?i)(\\d+)\\s*ч\\b\\.?") to "$1 час"
    )

    private val UNIT_PRE_REPLACEMENTS = listOf(
        Regex("(?i)\\b(\\d+)\\s+1/2\\b") to "$1.5", Regex("(?i)\\b(\\d+)\\s+1/4\\b") to "$1.25",
        Regex("(?i)\\b(\\d+)\\s+3/4\\b") to "$1.75", Regex("(?i)\\b(\\d+)\\s+1/3\\b") to "$1.3",
        Regex("(?i)\\b(\\d+)\\s+2/3\\b") to "$1.7", Regex("(?i)\\b1/2\\b") to "0.5",
        Regex("(?i)\\b1/4\\b") to "0.25", Regex("(?i)\\b3/4\\b") to "0.75",
        Regex("(?i)\\b1/3\\b") to "0.3", Regex("(?i)\\b2/3\\b") to "0.7",
        Regex("(?i)(\\d+)\\s*g\\b") to "$1 г", Regex("(?i)(\\d+)\\s*kg\\b") to "$1 кг",
        Regex("(?i)(\\d+)\\s*ml\\b") to "$1 мл", Regex("(?i)(\\d+)\\s*oz\\b") to "$1 унц.",
        Regex("(?i)(\\d+)\\s*lb(?:s)?\\b") to "$1 фунт.", Regex("(?i)\\btbsp(?:s)?\\b") to "ст.л.",
        Regex("(?i)\\btblsp(?:s)?\\b") to "ст.л.", Regex("(?i)\\btablespoon(?:s)?\\b") to "ст.л.",
        Regex("(?i)\\btsp(?:s)?\\b") to "ч.л.", Regex("(?i)\\bteaspoon(?:s)?\\b") to "ч.л."
    )

    private val CULINARY_DICTIONARY: List<Pair<Regex, String>> = listOf(
        "graham cracker crumbs" to "крошка песочного печенья", "graham crackers" to "песочное печенье", "graham cracker" to "песочное печенье",
        "digestive biscuits" to "песочное печенье", "evaporated milk" to "концентрированное молоко", "condensed milk" to "сгущенное молоко",
        "extra virgin olive oil" to "оливковое масло", "sunflower oil" to "подсолнечное масло", "vegetable oil" to "растительное масло",
        "sesame seed oil" to "кунжутное масло", "sesame oil" to "кунжутное масло", "coconut oil" to "кокосовое масло", "olive oil" to "оливковое масло",
        "unsalted butter" to "сливочное масло", "salted butter" to "сливочное масло", "melted butter" to "растопленное масло",
        "chicken breast" to "куриная грудка", "chicken breasts" to "куриные грудки", "chicken thighs" to "куриные бедра", "chicken wings" to "куриные крылышки",
        "chicken stock" to "куриный бульон", "chicken broth" to "куриный бульон", "beef stock" to "говяжий бульон", "vegetable stock" to "овощной бульон",
        "minced beef" to "говяжий фарш", "ground beef" to "говяжий фарш", "minced pork" to "свиной фарш", "ground pork" to "свиной фарш",
        "heavy cream" to "жирные сливки", "double cream" to "сливки", "single cream" to "сливки", "sour cream" to "сметана", "cream cheese" to "сливочный сыр",
        "cheddar cheese" to "сыр Чеддер", "parmesan cheese" to "сыр Пармезан", "mozzarella cheese" to "сыр Моцарелла", "feta cheese" to "сыр Фета",
        "spring onions" to "зеленый лук", "spring onion" to "зеленый лук", "green onions" to "зеленый лук", "red onion" to "красный лук",
        "garlic cloves" to "зубчики чеснока", "garlic clove" to "зубчик чеснока", "cloves garlic" to "зубчика чеснока", "garlic powder" to "сушеный чеснок",
        "cherry tomatoes" to "томаты черри", "chopped tomatoes" to "рубленые томаты", "tomato puree" to "томатное пюре", "tomato paste" to "томатная паста",
        "tomato sauce" to "томатный соус", "soy sauce" to "соевый соус", "fish sauce" to "рыбный соус", "oyster sauce" to "устричный соус",
        "worcestershire sauce" to "вустерский соус", "hot sauce" to "острый соус", "white wine vinegar" to "белый винный уксус", "apple cider vinegar" to "яблочный уксус",
        "balsamic vinegar" to "бальзамический уксус", "rice vinegar" to "рисовый уксус", "lemon juice" to "лимонный сок", "lime juice" to "сок лайма",
        "all-purpose flour" to "пшеничная мука", "plain flour" to "пшеничная мука", "self-raising flour" to "мука с разрыхлителем", "cornstarch" to "кукурузный крахмал",
        "corn flour" to "кукурузный крахмал", "baking powder" to "разрыхлитель", "baking soda" to "сода", "bicarbonate of soda" to "сода",
        "caster sugar" to "мелкий сахар", "brown sugar" to "коричневый сахар", "icing sugar" to "сахарная пудра", "powdered sugar" to "сахарная пудра",
        "vanilla extract" to "ванильный экстракт", "black pepper" to "черный перец", "cayenne pepper" to "кайенский перец", "chili powder" to "порошок чили",
        "bell pepper" to "болгарский перец", "sweet potatoes" to "батат", "bay leaf" to "лавровый лист", "bay leaves" to "лавровый лист",
        "egg yolks" to "яичные желтки", "egg yolk" to "желток", "egg whites" to "яичные белки", "coconut milk" to "кокосовое молоко",
        "bread crumbs" to "панировочные сухари", "breadcrumbs" to "панировочные сухари", "sesame seeds" to "кунжут", "peanut butter" to "арахисовая паста",
        "to taste" to "по вкусу", "for garnish" to "для украшения", "to serve" to "для подачи", "finely chopped" to "мелко нарезанный", "thinly sliced" to "тонко нарезанный",
        "preheat the oven" to "разогрейте духовку", "medium heat" to "средний огонь", "golden brown" to "золотистая корочка", "frying pan" to "сковорода",
        "chicken" to "курица", "beef" to "говядина", "pork" to "свинина", "lamb" to "баранина", "turkey" to "индейка", "duck" to "утка", "bacon" to "бекон",
        "sausage" to "колбаски", "ham" to "ветчина", "meat" to "мясо", "mince" to "фарш", "salmon" to "лосось", "tuna" to "тунец", "cod" to "треска", "fish" to "рыба",
        "shrimp" to "креветки", "prawns" to "креветки", "prawn" to "креветка", "seafood" to "морепродукты", "potatoes" to "картофель", "potato" to "картофель",
        "carrots" to "морковь", "carrot" to "морковь", "onions" to "лук", "onion" to "лук", "shallots" to "лук-шалот", "leek" to "лук-порей",
        "garlic" to "чеснок", "ginger" to "имбирь", "tomatoes" to "томаты", "tomato" to "томат", "cucumber" to "огурец", "cabbage" to "капуста",
        "broccoli" to "брокколи", "cauliflower" to "цветная капуста", "spinach" to "шпинат", "lettuce" to "салат", "celery" to "сельдерей",
        "zucchini" to "кабачок", "courgette" to "кабачок", "eggplant" to "баклажан", "aubergine" to "баклажан", "pumpkin" to "тыква",
        "mushrooms" to "грибы", "mushroom" to "грибы", "peas" to "горошек", "beans" to "фасоль", "lentils" to "чечевица", "chickpeas" to "нут", "corn" to "кукуруза",
        "avocado" to "авокадо", "olives" to "оливки", "beetroot" to "свекла", "peppers" to "перец", "parsley" to "петрушка", "cilantro" to "кинза",
        "coriander" to "кориандр", "dill" to "укроп", "basil" to "базилик", "mint" to "мята", "rosemary" to "розмарин", "thyme" to "тимьян", "oregano" to "орегано",
        "paprika" to "паприка", "cumin" to "зира", "turmeric" to "куркума", "cinnamon" to "корица", "nutmeg" to "мускатный орех", "cloves" to "зубчика",
        "clove" to "зубчик", "cardamom" to "кардамон", "vanilla" to "ваниль", "curry" to "карри", "chili" to "чили", "chilli" to "чили", "mustard" to "горчица",
        "mayonnaise" to "майонез", "ketchup" to "кетчуп", "vinegar" to "уксус", "sauce" to "соус", "paste" to "паста", "stock" to "бульон", "broth" to "бульон",
        "water" to "вода", "milk" to "молоко", "cream" to "сливки", "butter" to "сливочное масло", "yogurt" to "йогурт", "cheese" to "сыр", "parmesan" to "Пармезан",
        "mozzarella" to "Моцарелла", "cheddar" to "Чеддер", "ricotta" to "Рикотта", "mascarpone" to "Маскарпоне", "tofu" to "сыр тофу", "eggs" to "яйца", "egg" to "яйцо",
        "yolk" to "желток", "yolks" to "желтки", "flour" to "мука", "sugar" to "сахар", "salt" to "соль", "pepper" to "перец", "honey" to "мёд", "syrup" to "сироп",
        "chocolate" to "шоколад", "cocoa" to "какао", "yeast" to "дрожжи", "starch" to "крахмал", "rice" to "рис", "pasta" to "паста", "spaghetti" to "спагетти",
        "noodles" to "лапша", "noodle" to "лапша", "lasagne" to "лазанья", "oats" to "овсянка", "bread" to "хлеб", "baguette" to "багет", "tortillas" to "тортильи",
        "dough" to "тесто", "pastry" to "тесто", "crumbs" to "крошка", "apple" to "яблоко", "apples" to "яблоки", "banana" to "банан", "lemon" to "лимон",
        "lime" to "лайм", "orange" to "апельсин", "strawberries" to "клубника", "berries" to "ягоды", "raisins" to "изюм", "walnuts" to "грецкие орехи",
        "almonds" to "миндаль", "peanuts" to "арахис", "cashews" to "кешью", "nuts" to "орехи", "coconut" to "кокос", "oil" to "масло",
        "cups" to "стакана", "cup" to "стакан", "tbsp" to "ст.л.", "tbs" to "ст.л.", "tsp" to "ч.л.", "oz" to "унц.", "lb" to "фунт.", "grams" to "г", "gram" to "г",
        "pinch" to "щепотка", "dash" to "щепотка", "handful" to "горсть", "bunch" to "пучок", "slice" to "ломтик", "slices" to "ломтика", "piece" to "кусочек",
        "pieces" to "кусочка", "can" to "банка", "tin" to "банка", "packet" to "уп.", "pkg" to "уп.", "fillet" to "филе", "breast" to "грудка", "thighs" to "бедра",
        "large" to "крупный", "medium" to "средний", "small" to "мелкий", "fresh" to "свежий", "dried" to "сушеный", "frozen" to "замороженный", "smoked" to "копченый",
        "sweet" to "сладкий", "hot" to "острый", "spicy" to "пряный", "warm" to "теплый", "cold" to "холодный", "boiling" to "кипящий", "melted" to "растопленный",
        "chopped" to "нарезанный", "diced" to "кубиками", "sliced" to "ломтиками", "minced" to "измельченный", "grated" to "тертый", "crushed" to "раздавленный",
        "peeled" to "очищенный", "beaten" to "взбитый", "mashed" to "размятый", "roasted" to "запеченный", "fried" to "обжаренный", "boiled" to "отварной",
        "cooked" to "готовый", "whole" to "целый", "halved" to "пополам", "finely" to "мелко", "optional" to "по желанию",
        "preheat" to "разогрейте", "heat" to "нагрейте", "boil" to "варите", "simmer" to "томите", "fry" to "обжарьте", "saute" to "обжарьте", "bake" to "выпекайте",
        "roast" to "запекайте", "mix" to "смешайте", "stir" to "перемешайте", "whisk" to "взбейте", "beat" to "взбейте", "chop" to "нарежьте", "peel" to "очистите",
        "grate" to "натрите", "pour" to "влейте", "add" to "добавьте", "place" to "положите", "put" to "положите", "remove" to "выньте", "drain" to "слейте",
        "cover" to "накройте", "season" to "приправьте", "sprinkle" to "посыпьте", "garnish" to "украсьте", "serve" to "подавайте", "cook" to "готовьте",
        "leave" to "оставьте", "cool" to "остудите", "oven" to "духовка", "pan" to "сковорода", "pot" to "кастрюля", "bowl" to "миска", "plate" to "тарелка",
        "minutes" to "минут", "minute" to "минуту", "mins" to "минут", "min" to "мин", "hours" to "часа", "hour" to "час", "seconds" to "секунд", "until" to "до",
        "tender" to "мягкости", "golden" to "золотистости", "with" to "с", "and" to "и", "or" to "или", "in" to "в", "on" to "на", "for" to "в течение", "then" to "затем"
    ).sortedByDescending { it.first.length }
        .map { (en, ru) -> Regex("(?i)(?<![a-zA-Z])${Regex.escape(en)}(?![a-zA-Z])") to ru }

    fun hasLatin(text: String): Boolean = LATIN_REGEX.containsMatchIn(text)

    fun normalizeAndTranslateCulinary(text: String): String {
        var res = text.trim()
        if (res.isEmpty()) return res
        for ((regex, replacement) in UNIT_PRE_REPLACEMENTS) res = regex.replace(res, replacement)
        if (!hasLatin(res)) return res
        for ((regex, ru) in CULINARY_DICTIONARY) {
            res = regex.replace(res, ru)
            if (!hasLatin(res)) break
        }
        return res.replace(Regex("(?i)\\b(the|a|an|of|to)\\b"), " ").replace(Regex("\\s{2,}"), " ").trim()
    }

    fun computeFridgeStems(fridgeItems: List<String>): Set<String> {
        if (fridgeItems.isEmpty()) return emptySet()
        return fridgeItems.mapNotNull { item ->
            val clean = item.trim().lowercase()
            if (clean.isEmpty()) null else when {
                clean.length >= 5 -> clean.dropLast(2)
                clean.length == 4 -> clean.dropLast(1)
                else -> clean
            }
        }.toSet()
    }

    fun isIngredientMatchingStems(recipeIngredient: String, fridgeStems: Set<String>): Boolean {
        if (fridgeStems.isEmpty()) return false
        val lowerIng = recipeIngredient.lowercase()
        return fridgeStems.any { lowerIng.contains(it) }
    }

    fun scaleIngredientAmount(ingredient: String, portions: Int, basePortions: Int = 2): String {
        if (portions == basePortions) return ingredient
        val ratio = portions.toDouble() / basePortions.toDouble()
        return NUMBER_REGEX.replace(ingredient) { matchResult ->
            val num = matchResult.value.replace(",", ".").toDoubleOrNull() ?: return@replace matchResult.value
            val scaled = num * ratio
            if (scaled >= 10 || scaled % 1.0 == 0.0) scaled.roundToInt().toString()
            else String.format(Locale.US, "%.1f", scaled).replace(".0", "")
        }
    }

    fun extractTimerInfo(stepText: String): ParsedTimer? {
        val match = TIMER_REGEX.find(stepText) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("час") -> value * 3600
            unit.startsWith("мин") -> value * 60
            unit.startsWith("сек") -> value
            else -> value * 60
        }
        return ParsedTimer(seconds, match.value)
    }

    fun humanizeCookingText(text: String): String {
        var result = text
        for ((regex, replacement) in SPEECH_REPLACEMENTS) result = regex.replace(result, replacement)
        return result.replace("°C", " градусов Цельсия").replace("°", " градусов").replace(" - ", " — ")
    }

    fun splitSentences(text: String): List<String> = text.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }

    fun ensureValidInstructions(rawSteps: List<String>, title: String, ingredients: List<String>): List<String> {
        val cleaned = rawSteps.flatMap { it.split(STEP_SPLIT_REGEX) }
            .map { normalizeAndTranslateCulinary(it.replace(STEP_PREFIX_REGEX, "").trim()) }
            .filter { it.length > 6 && !it.lowercase().startsWith("приятного аппетита") }
        if (cleaned.size >= 2) return cleaned
        val mainIngs = ingredients.take(3).joinToString(", ") { it.substringBefore("-").substringBefore("—").trim() }
        return listOf(
            "Подготовьте все ингредиенты для блюда «$title»: промойте и нарежьте основные продукты ($mainIngs).",
            "Прогрейте сковороду или кастрюлю, соедините подготовленные компоненты и готовьте на среднем огне в течение 12 минут, периодически помешивая.",
            "Добавьте специи, соль и соус по вкусу, убавьте огонь и томите под крышкой еще 15 минут до полной готовности.",
            "Дайте готовому блюду настояться 5 минут перед подачей к столу."
        )
    }

    fun parseVoiceCommand(spokenText: String, currentCuisine: String, currentCategory: String): Triple<String, List<String String,>> {
        val lower = spokenText.lowercase()
        var newCuisine = currentCuisine
        var newCategory = currentCategory
        val cuisineRoots = mapOf(
            "русск" to "Русская", "корейск" to "Корейская", "французск" to "Французская", "итальянск" to "Итальянская",
            "японск" to "Японская", "грузинск" to "Грузинская", "мексиканск" to "Мексиканская", "китайск" to "Китайская", "тайск" to "Тайская", "индийск" to "Индийская"
        )
        cuisineRoots.forEach { (root, name) -> if (lower.contains(root)) newCuisine = name }
        val categoryRoots = mapOf(
            "суп" to "Супы", "бульон" to "Супы", "каш" to "Каши и завтраки", "завтрак" to "Каши и завтраки",
            "выпечк" to "Выпечка", "пирог" to "Выпечка", "к чаю" to "Что-то к чаю", "десерт" to "Что-то к чаю",
            "сладк" to "Что-то к чаю", "салат" to "Салаты", "паст" to "Паста и пицца", "пицц" to "Паста и пицца",
            "второе" to "Вторые блюда", "ужин" to "Вторые блюда"
        )
        categoryRoots.forEach { (root, name) -> if (lower.contains(root)) newCategory = name }
        val stopWords = setOf("приготовь", "приготовить", "сделай", "подбери", "найди", "покажи", "хочу", "блюдо", "рецепт", "из", "у", "меня", "есть", "в", "холодильнике", "дома", "или", "мне", "пожалуйста", "на", "обед", "ужин", "завтрак", "суп", "салат")
        val normalizeMap = mapOf(
            "курицы" to "Курица", "курицу" to "Курица", "курочки" to "Курица", "картошки" to "Картофель", "картошка" to "Картофель",
            "сыра" to "Сыр", "яиц" to "Яйца", "яйцо" to "Яйца", "молока" to "Молоко", "риса" to "Рис", "грибов" to "Грибы",
            "помидоров" to "Томаты", "помидоры" to "Томаты", "фарша" to "Фарш", "творога" to "Творог", "капусты" to "Капуста", "говядины" to "Говядина", "свинины" to "Свинина"
        )
        val extracted = lower.replace(NON_WORD_REGEX, " ").split(WHITESPACE_REGEX).map { it.trim() }
            .filter { it.length > 2 && !stopWords.contains(it) }
            .filter { w -> cuisineRoots.keys.none { w.startsWith(it) } }
            .map { w -> normalizeMap[w] ?: w.replaceFirstChar { it.uppercase() } }.distinct()
        return Triple(newCuisine, newCategory, extracted)
    }

    fun playAlarmSound() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
            Handler(Looper.getMainLooper()).postDelayed({ try { toneGen.release() } catch (_: Exception) {} }, 1600L)
        } catch (_: Exception) {}
    }
}

// =========================================================================
// ХРАНИЛИЩЕ И ГОЛОСОВОЙ ДВИЖОК
// =========================================================================
class RecipeStorage(context: Context) {
    private val prefs = context.getSharedPreferences("chef_app_v10_storage", Context.MODE_PRIVATE)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val cachedTransJson = prefs.getString("translation_cache_json", null)
        if (!cachedTransJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(cachedTransJson)
                obj.keys().forEach { k -> RecipeRepository.putCachedTranslation(k, obj.getString(k)) }
            } catch (_: Exception) {}
        }
    }

    fun isDarkTheme(systemDefault: Boolean): Boolean = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", systemDefault) else systemDefault
    fun saveDarkTheme(enabled: Boolean) = prefs.edit().putBoolean("dark_mode", enabled).apply()
    fun loadGitHubRepo(): String = prefs.getString("github_repo_path", DEFAULT_GITHUB_REPO) ?: DEFAULT_GITHUB_REPO
    fun saveGitHubRepo(repo: String) = prefs.edit().putString("github_repo_path", repo.trim()).apply()
    fun loadStringSet(key: String): List<String> = prefs.getStringSet(key, emptySet())?.toList() ?: emptyList()

    fun loadRecipeList(key: String): List<Recipe> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { Recipe.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveAllAsync(fridge: List<String>, cart: List<String>, favorites: List<Recipe>, customRecipes: List<Recipe>) {
        val fridgeCopy = fridge.toSet(); val cartCopy = cart.toSet()
        val favCopy = favorites.toList(); val customCopy = customRecipes.toList()
        ioScope.launch {
            val favArr = JSONArray().apply { favCopy.forEach { put(it.toJson()) } }
            val customArr = JSONArray().apply { customCopy.forEach { put(it.toJson()) } }
            val transObj = JSONObject(RecipeRepository.exportTranslationCacheSnapshot())
            prefs.edit()
                .putStringSet("fridge", fridgeCopy).putStringSet("cart", cartCopy)
                .putString("fav_recipes_json", favArr.toString())
                .putString("custom_recipes_json", customArr.toString())
                .putString("translation_cache_json", transObj.toString()).apply()
        }
    }
}

class SpeechManager(private val context: Context, private val onSpeakingStateChanged: (String?) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ttsEngine: TextToSpeech? = null
    private var availableRuVoices: List<Voice> = emptyList()
    private var selectedVoiceIndex = 0
    private var isSystemTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentRootId: String? = null

    init {
        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = ttsEngine?.setLanguage(Locale("ru", "RU"))
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isSystemTtsReady = true
                    try {
                        val voices = ttsEngine?.voices?.filter { it.locale.language.equals("ru", ignoreCase = true) }
                            ?.sortedWith(
                                compareByDescending<Voice> { it.name.contains("network", ignoreCase = true) }
                                    .thenByDescending { it.name.contains("ruf", ignoreCase = true) || it.name.contains("rud", ignoreCase = true) }
                                    .thenByDescending { it.quality }
                            ) ?: emptyList()
                        if (voices.isNotEmpty()) { availableRuVoices = voices; ttsEngine?.voice = voices[0] }
                    } catch (_: Exception) {}
                    ttsEngine?.setSpeechRate(0.98f); ttsEngine?.setPitch(0.98f)
                    ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { mainHandler.post { updateState(utteranceId?.substringBefore("__part_")) } }
                        override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith("_last") == true) mainHandler.post { updateState(null) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { mainHandler.post { updateState(null) } }
                    })
                }
            }
        }
        ttsEngine = try { TextToSpeech(context, initListener, "com.google.android.tts") } catch (_: Exception) { TextToSpeech(context, initListener) }
    }

    private fun updateState(newRootId: String?) { currentRootId = newRootId; onSpeakingStateChanged(newRootId) }

    fun stop() {
        updateState(null)
        try { mediaPlayer?.stop(); mediaPlayer?.reset() } catch (_: Exception) {}
        try { ttsEngine?.stop() } catch (_: Exception) {}
    }

    fun switchVoiceProfile() {
        stop()
        val engine = ttsEngine
        if (availableRuVoices.size > 1 && engine != null) {
            selectedVoiceIndex = (selectedVoiceIndex + 1) % availableRuVoices.size
            val nextVoice = availableRuVoices[selectedVoiceIndex]
            engine.voice = nextVoice
            val voiceType = if (nextVoice.name.contains("network", true)) "Нейро-HD" else "Локальный"
            Toast.makeText(context, "🎙 Голос #${selectedVoiceIndex + 1} ($voiceType)", Toast.LENGTH_SHORT).show()
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sample_last") }
            engine.speak("Привет! Я ваш шеф-повар.", TextToSpeech.QUEUE_FLUSH, params, "sample_last")
        } else Toast.makeText(context, "Доступен 1 системный голосовой профиль", Toast.LENGTH_SHORT).show()
    }

    fun speak(rootUtteranceId: String, rawText: String) {
        if (currentRootId == rootUtteranceId) { stop(); return }
        stop()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Toast.makeText(context, "🔊 Прибавьте громкость мультимедиа!", Toast.LENGTH_LONG).show()
        }
        val preparedText = CulinaryUtils.humanizeCookingText(rawText)
        val sentences = CulinaryUtils.splitSentences(preparedText).ifEmpty { listOf(preparedText) }
        updateState(rootUtteranceId)
        val engine = ttsEngine
        if (isSystemTtsReady && engine != null) {
            var spokeOk = false
            sentences.forEachIndexed { idx, sentence ->
                val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val suffix = if (idx == sentences.lastIndex) "_last" else "_$idx"
                val partId = "${rootUtteranceId}__part$suffix"
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, partId) }
                if (engine.speak(sentence, queueMode, params, partId) == TextToSpeech.SUCCESS) spokeOk = true
            }
            if (spokeOk) return
        }
        playCloudChunksSequentially(rootUtteranceId, sentences, 0)
    }

    private fun playCloudChunksSequentially(rootId: String, chunks: List<String>, index: Int) {
        if (currentRootId != rootId || index >= chunks.size) { if (currentRootId == rootId) updateState(null); return }
        try {
            val encoded = URLEncoder.encode(chunks[index].take(180), "UTF-8")
            val streamUrl = "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ru&q=$encoded"
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(streamUrl)
                setOnPreparedListener { start() }
                setOnCompletionListener { playCloudChunksSequentially(rootId, chunks, index + 1) }
                setOnErrorListener { _, _, _ -> updateState(null); true }
                prepareAsync()
            }
        } catch (_: Exception) { updateState(null) }
    }

    fun release() {
        stop()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { ttsEngine?.shutdown() } catch (_: Exception) {}
    }
}

// =========================================================================
// ACTIVITY И UI ЭКРАНЫ
// =========================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleInstallCommitIntent(intent)
        setContent {
            val context = LocalContext.current
            val storage = remember { RecipeStorage(context) }
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(storage.isDarkTheme(systemDark)) }

            MaterialTheme(colorScheme = if (isDarkTheme) DarkCulinaryColorScheme else LightCulinaryColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RecipeAppScreen(
                        storage = storage,
                        isDarkTheme = isDarkTheme,
                        onToggleDarkTheme = { isDarkTheme = !isDarkTheme; storage.saveDarkTheme(isDarkTheme) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallCommitIntent(intent)
    }

    private fun handleInstallCommitIntent(intent: Intent?) {
        if (intent?.action == ACTION_INSTALL_COMMIT) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this, "✅ Приложение успешно обновлено!", Toast.LENGTH_LONG).show()
                PackageInstaller.STATUS_FAILURE_CONFLICT -> AppUpdateManager.signatureConflictState = true
                else -> {
                    if (msg.contains("INCOMPATIBLE", true) || msg.contains("signature", true)) AppUpdateManager.signatureConflictState = true
                    else if (status != -1) Toast.makeText(this, "Установка не завершена: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAppScreen(storage: RecipeStorage, isDarkTheme: Boolean, onToggleDarkTheme: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentlySpeakingId by remember { mutableStateOf<String?>(null) }
    val speechManager = remember(context) { SpeechManager(context) { newId -> currentlySpeakingId = newId } }
    DisposableEffect(speechManager) { onDispose { speechManager.release() } }

    var currentScreen by remember { mutableStateOf(AppScreen.RECIPES) }
    var selectedMode by remember { mutableStateOf(DataSourceMode.LOCAL) }
    var selectedCuisine by remember { mutableStateOf("Все") }
    var selectedCategory by remember { mutableStateOf("Все") }
    var selectedSort by remember { mutableStateOf(SortOrder.FRESH_BATCH) }
    var sliderPosition by remember { mutableFloatStateOf(10f) }
    var committedLimitCount by remember { mutableIntStateOf(10) }
    var refreshSeed by remember { mutableIntStateOf(0) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var onlineSearchResults by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }

    var showOnlyFavorites by remember { mutableStateOf(false) }
    var isFiltersExpanded by remember { mutableStateOf(false) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var githubRepoInput by remember { mutableStateOf(storage.loadGitHubRepo()) }
    var releaseInfo by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    var ingredientInput by remember { mutableStateOf("") }
    var lastVoiceCommand by remember { mutableStateOf<String?>(null) }

    val fridgeItems = remember { mutableStateListOf<String>().apply { addAll(storage.loadStringSet("fridge")) } }
    val shoppingListItems = remember { mutableStateListOf<String>().apply { addAll(storage.loadStringSet("cart")) } }
    val checkedShoppingItems = remember { mutableStateListOf<String>() }
    val favoriteRecipes = remember { mutableStateListOf<Recipe>().apply { addAll(storage.loadRecipeList("fav_recipes_json")) } }
    val customUserRecipes = remember { mutableStateListOf<Recipe>().apply { addAll(storage.loadRecipeList("custom_recipes_json")) } }

    val fridgeStemsSet by remember { derivedStateOf { CulinaryUtils.computeFridgeStems(fridgeItems) } }
    val shoppingItemsSet by remember { derivedStateOf { shoppingListItems.toSet() } }
    val favoriteIdsSet by remember { derivedStateOf { favoriteRecipes.map { it.id }.toSet() } }

    fun persistAll() = storage.saveAllAsync(fridgeItems, shoppingListItems, favoriteRecipes, customUserRecipes)

    fun checkUpdatesNow(showToast: Boolean = false) {
        coroutineScope.launch {
            isCheckingUpdate = true
            storage.saveGitHubRepo(githubRepoInput)
            val res = AppUpdateManager.checkGitHubUpdate(context, githubRepoInput)
            isCheckingUpdate = false
            res.onSuccess { info ->
                releaseInfo = info
                if (info.isNewerThanInstalled) Toast.makeText(context, "🚀 На GitHub доступна новая сборка (${info.publishedFormatted})!", Toast.LENGTH_LONG).show()
                else if (showToast) Toast.makeText(context, "✅ У вас установлена самая свежая сборка", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                if (showToast) Toast.makeText(context, "Ошибка проверки GitHub: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdatesNow(showToast = false) }

    var recipes by remember { mutableStateOf(RecipeRepository.buildLocalDatabaseRecipes("Все", "Все", emptyList(), customUserRecipes, 10, 0)) }
    var isLoading by remember { mutableStateOf(false) }
    var activeLoadJob by remember { mutableStateOf<Job?>(null) }

    fun loadRecipes(
        modeToUse: DataSourceMode = selectedMode,
        cuisineToUse: String = selectedCuisine,
        categoryToUse: String = selectedCategory,
        targetCount: Int = committedLimitCount,
        voiceText: String? = lastVoiceCommand,
        collapseHeader: Boolean = false,
        forceNewBatch: Boolean = false
    ) {
        speechManager.stop()
        if (collapseHeader) isFiltersExpanded = false
        if (forceNewBatch) { refreshSeed += 1; selectedSort = SortOrder.FRESH_BATCH }
        val currentSeed = refreshSeed
        val currentFridge = fridgeItems.toList()
        val currentCustom = customUserRecipes.toList()

        activeLoadJob?.cancel()
        activeLoadJob = coroutineScope.launch {
            if (modeToUse == DataSourceMode.LOCAL) {
                recipes = RecipeRepository.buildLocalDatabaseRecipes(cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed)
                isLoading = false
                return@launch
            }
            isLoading = true
            try {
                recipes = when (modeToUse) {
                    DataSourceMode.LOCAL -> RecipeRepository.buildLocalDatabaseRecipes(cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed)
                    DataSourceMode.MEAL_DB_API -> RecipeRepository.fetchTranslatedMealDbRecipes(cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed)
                    DataSourceMode.FREE_AI -> RecipeRepository.fetchAiRussianRecipes(cuisineToUse, categoryToUse, currentFridge, currentCustom, voiceText, targetCount, currentSeed)
                }
                persistAll()
            } catch (_: Exception) {
                recipes = RecipeRepository.buildLocalDatabaseRecipes(cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed)
            } finally { isLoading = false }
        }
    }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) { onlineSearchResults = emptyList(); isSearchingOnline = false; return@LaunchedEffect }
        delay(400L)
        isSearchingOnline = true
        try { onlineSearchResults = RecipeRepository.searchGlobalOnlineRecipes(q) } catch (_: Exception) { onlineSearchResults = emptyList() }
        finally { isSearchingOnline = false }
    }

    LaunchedEffect(selectedCuisine, selectedCategory, committedLimitCount, fridgeItems.size, customUserRecipes.size, selectedMode) {
        if (selectedMode == DataSourceMode.LOCAL) {
            recipes = RecipeRepository.buildLocalDatabaseRecipes(selectedCuisine, selectedCategory, fridgeItems.toList(), customUserRecipes.toList(), committedLimitCount, refreshSeed)
        }
    }

    fun addIngredientsFromInput() {
        val parts = ingredientInput.split(",", ";").map { it.trim().replaceFirstChar { ch -> ch.uppercase() } }
            .filter { it.isNotEmpty() && !fridgeItems.contains(it) }
        if (parts.isNotEmpty()) { fridgeItems.addAll(parts); persistAll() }
        ingredientInput = ""
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                lastVoiceCommand = spokenText
                val (newCuisine, newCategory, extractedIngs) = CulinaryUtils.parseVoiceCommand(spokenText, selectedCuisine, selectedCategory)
                selectedCuisine = newCuisine; selectedCategory = newCategory
                if (extractedIngs.isNotEmpty()) { fridgeItems.clear(); fridgeItems.addAll(extractedIngs); persistAll() }
                loadRecipes(selectedMode, newCuisine, newCategory, committedLimitCount, spokenText, collapseHeader = true, forceNewBatch = true)
            }
        }
    }

    fun startVoiceRecognition() {
        speechManager.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите, какие продукты есть дома или какое блюдо найти...")
        }
        try { voiceLauncher.launch(intent) } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Голосовой ввод Google не установлен", Toast.LENGTH_LONG).show()
        }
    }

    val displayedRecipes by remember(recipes, showOnlyFavorites, favoriteRecipes.size, customUserRecipes.size, searchQuery, onlineSearchResults, selectedSort, fridgeStemsSet) {
        derivedStateOf {
            val q = searchQuery.trim()
            val searched = if (q.isEmpty()) {
                if (showOnlyFavorites) favoriteRecipes.toList() else recipes
            } else {
                val globalPool = (customUserRecipes + favoriteRecipes + recipes + RecipeRepository.getAllCuratedRecipes() + RecipeRepository.getCachedOnlineRecipes() + onlineSearchResults)
                    .distinctBy { it.title.lowercase() }
                val qStem = if (q.length >= 5) q.dropLast(1).lowercase() else q.lowercase()
                globalPool.filter { r -> r.searchIndexText.contains(qStem) }
            }
            when (selectedSort) {
                SortOrder.FRESH_BATCH -> searched
                SortOrder.FRIDGE_MATCH -> searched.sortedWith(compareByDescending<Recipe> { it.isCustom }.thenByDescending { r -> r.ingredients.count { CulinaryUtils.isIngredientMatchingStems(it, fridgeStemsSet) } })
                SortOrder.FASTEST -> searched.sortedBy { it.cookingMinutes }
                SortOrder.LOW_CALORIE -> searched.sortedBy { it.calories }
            }
        }
    }

    if (AppUpdateManager.signatureConflictState) {
        AlertDialog(
            onDismissRequest = { AppUpdateManager.signatureConflictState = false },
            title = { Text("⚠️ Конфликт подписи старой сборки", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Текущая установленная версия была собрана со старым временным ключом GitHub.", fontSize = 13.sp)
                    Text("✅ Новая сборка уже сохранена в папку «Загрузки» (Downloads / ChefRecipeApp_latest.apk).", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("Нажмите «Удалить старую версию», затем установите ChefRecipeApp_latest.apk из Загрузок.", fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    AppUpdateManager.signatureConflictState = false
                    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Удалить старую версию") }
            },
            dismissButton = { TextButton(onClick = { AppUpdateManager.signatureConflictState = false }) { Text("Позже") } }
        )
    }

    if (showUpdateDialog) {
        val installedTimeMillis = remember(showUpdateDialog) { AppUpdateManager.getInstalledUpdateTimestamp(context) }
        val installedTimeStr = remember(installedTimeMillis) { AppUpdateManager.formatTimestamp(installedTimeMillis) }

        AlertDialog(
            onDismissRequest = { if (!isDownloadingUpdate) showUpdateDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Обновление с GitHub", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = githubRepoInput, onValueChange = { githubRepoInput = it }, label = { Text("Ваш репозиторий GitHub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("📱 Установленная версия: $installedTimeStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (isCheckingUpdate) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Проверяем свежий релиз на GitHub...", fontSize = 13.sp)
                        }
                    } else if (releaseInfo != null) {
                        val info = releaseInfo!!
                        val isNewAvailable = info.publishedAtMillis > (installedTimeMillis + 60_000L)
                        Surface(
                            color = if (isNewAvailable) Color(0xFF1B3B22) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (isNewAvailable) "🚀 Доступна новая версия на GitHub!" else "✅ Установлена последняя версия",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    color = if (isNewAvailable) Color(0xFFA5D6A7) else MaterialTheme.colorScheme.onSurface
                                )
                                Text("📦 Релиз: ${info.releaseTitle}", fontSize = 12.sp)
                                Text("🕒 Сборка на GitHub: ${info.publishedFormatted}", fontSize = 12.sp)
                                Text("💾 Файл: ${info.sizeMb}", fontSize = 12.sp)
                            }
                        }
                    }

                    if (isDownloadingUpdate) {
                        Column {
                            Text("Скачивание APK: ${(downloadProgress * 100).roundToInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetInfo = releaseInfo ?: GitHubReleaseInfo(
                            repoName = githubRepoInput.trim(), releaseTitle = "latest-apk",
                            publishedAtMillis = System.currentTimeMillis(), publishedFormatted = "Сейчас",
                            downloadUrl = "https://github.com/${githubRepoInput.trim()}/releases/download/latest-apk/ChefRecipeApp.apk",
                            sizeMb = "APK", isNewerThanInstalled = true
                        )
                        coroutineScope.launch {
                            isDownloadingUpdate = true; downloadProgress = 0.05f
                            val result = AppUpdateManager.downloadAndInstallApk(context, targetInfo) { prog -> downloadProgress = prog }
                            isDownloadingUpdate = false
                            result.onFailure { err -> Toast.makeText(context, "Ошибка: ${err.localizedMessage}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    enabled = !isDownloadingUpdate && !isCheckingUpdate
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    val hasUpdate = (releaseInfo?.publishedAtMillis ?: 0L) > (installedTimeMillis + 60_000L)
                    Text(if (hasUpdate) "Обновить сейчас" else "Переустановить APK")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { checkUpdatesNow(showToast = true) }, enabled = !isDownloadingUpdate && !isCheckingUpdate) { Text("Проверить") }
                    TextButton(onClick = { showUpdateDialog = false }, enabled = !isDownloadingUpdate) { Text("Закрыть") }
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        // Компактная шапка (42dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🍲 Шеф-Повар",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { currentScreen = AppScreen.RECIPES; showOnlyFavorites = false; searchQuery = "" }
                )
                IconButton(onClick = onToggleDarkTheme, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Тема", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showUpdateDialog = true; checkUpdatesNow(showToast = false) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate, contentDescription = "Обновление с GitHub",
                            tint = if (releaseInfo?.isNewerThanInstalled == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (releaseInfo?.isNewerThanInstalled == true) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)).align(Alignment.TopEnd))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (currentlySpeakingId != null) {
                    IconButton(onClick = { speechManager.stop() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.VolumeOff, contentDescription = "Стоп", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(onClick = { speechManager.switchVoiceProfile() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Голос", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { currentScreen = AppScreen.RECIPES; isSearchVisible = !isSearchVisible; if (!isSearchVisible) searchQuery = "" }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск", tint = if (isSearchVisible || searchQuery.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { currentScreen = AppScreen.RECIPES; showOnlyFavorites = false; isFiltersExpanded = !isFiltersExpanded }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Фильтры", tint = if (isFiltersExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { currentScreen = if (currentScreen == AppScreen.ADD_RECIPE) AppScreen.RECIPES else AppScreen.ADD_RECIPE }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "Свой рецепт", tint = if (currentScreen == AppScreen.ADD_RECIPE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
                }
                TextButton(onClick = { currentScreen = if (currentScreen == AppScreen.SHOPPING_LIST) AppScreen.RECIPES else AppScreen.SHOPPING_LIST }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                    Text("🛒${shoppingListItems.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { currentScreen = AppScreen.RECIPES; showOnlyFavorites = !showOnlyFavorites }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                    Text("❤️${favoriteRecipes.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (showOnlyFavorites) Color(0xFFE53935) else MaterialTheme.colorScheme.primary)
                }
            }
        }

        AnimatedVisibility(visible = isSearchVisible && currentScreen == AppScreen.RECIPES) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Глобальный поиск (борщ, кимчи, рамен, курица)...", fontSize = 13.sp) },
                leadingIcon = {
                    if (isSearchingOnline) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    IconButton(onClick = { searchQuery = ""; isSearchVisible = false }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", modifier = Modifier.size(16.dp))
                    }
                },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        when (currentScreen) {
            AppScreen.SHOPPING_LIST -> {
                Spacer(modifier = Modifier.height(4.dp))
                ShoppingListView(shoppingItems = shoppingListItems, checkedItems = checkedShoppingItems, onUpdatePrefs = { persistAll() }, onBackToRecipes = { currentScreen = AppScreen.RECIPES })
            }
            AppScreen.ADD_RECIPE -> {
                Spacer(modifier = Modifier.height(4.dp))
                AddCustomRecipeView(
                    cuisines = CulinaryUtils.CUISINES.filter { it != "Все" } + "Домашняя",
                    categories = CulinaryUtils.CATEGORIES.filter { it != "Все" },
                    onSaveRecipe = { newRecipe ->
                        customUserRecipes.add(0, newRecipe); favoriteRecipes.add(0, newRecipe); persistAll()
                        selectedMode = DataSourceMode.LOCAL; currentScreen = AppScreen.RECIPES; loadRecipes(DataSourceMode.LOCAL)
                        Toast.makeText(context, "✅ Рецепт «${newRecipe.title}» сохранен!", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { currentScreen = AppScreen.RECIPES }
                )
            }
            AppScreen.RECIPES -> {
                AnimatedVisibility(visible = isFiltersExpanded && !showOnlyFavorites) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                        items(DataSourceMode.entries) { mode ->
                                            FilterChip(selected = selectedMode == mode, onClick = { selectedMode = mode; loadRecipes(mode, selectedCuisine, selectedCategory, forceNewBatch = true) }, label = { Text(mode.fullLabel, fontSize = 11.sp) })
                                        }
                                    }
                                    IconButton(onClick = { isFiltersExpanded = false }, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Свернуть") }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("🧊 Холодильник:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    if (fridgeItems.isNotEmpty() || lastVoiceCommand != null) {
                                        Text(
                                            text = "Очистить (${fridgeItems.size})", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { fridgeItems.clear(); lastVoiceCommand = null; persistAll(); loadRecipes(forceNewBatch = true) }
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = ingredientInput, onValueChange = { ingredientInput = it },
                                        placeholder = { Text("Впиши продукт или нажми 🎤", fontSize = 12.sp) },
                                        singleLine = true, modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { addIngredientsFromInput() })
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledIconButton(onClick = { addIngredientsFromInput() }, enabled = ingredientInput.isNotBlank(), modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, contentDescription = "Добавить", modifier = Modifier.size(18.dp)) }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledIconButton(onClick = { startVoiceRecognition() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary), modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Mic, contentDescription = "Голосом", modifier = Modifier.size(18.dp)) }
                                }

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(fridgeItems) { item ->
                                        InputChip(selected = true, onClick = { fridgeItems.remove(item); persistAll() }, label = { Text("✓ $item", fontSize = 11.sp) }, trailingIcon = { Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(13.dp)) })
                                    }
                                    items(CulinaryUtils.QUICK_FRIDGE.filter { !fridgeItems.contains(it) }) { suggestion ->
                                        FilterChip(selected = false, onClick = { fridgeItems.add(suggestion); persistAll() }, label = { Text("+ $suggestion", fontSize = 11.sp) })
                                    }
                                }

                                Text("Кухня мира:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(CulinaryUtils.CUISINES) { cuisine ->
                                        FilterChip(
                                            selected = selectedCuisine == cuisine,
                                            onClick = { selectedCuisine = cuisine; loadRecipes(selectedMode, cuisine, selectedCategory, forceNewBatch = true) },
                                            label = { Text(if (cuisine == "Все") "🌍 Все" else CulinaryUtils.formatCuisineWithFlag(cuisine), fontSize = 11.sp) }
                                        )
                                    }
                                }

                                Text("Категория блюда:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(CulinaryUtils.CATEGORIES) { category ->
                                        FilterChip(
                                            selected = selectedCategory == category,
                                            onClick = { selectedCategory = category; loadRecipes(selectedMode, selectedCuisine, category, forceNewBatch = true) },
                                            label = { Text(category, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Количество блюд: ${sliderPosition.roundToInt()}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Slider(
                                        value = sliderPosition, onValueChange = { sliderPosition = it },
                                        onValueChangeFinished = { committedLimitCount = sliderPosition.roundToInt() },
                                        valueRange = 5f..20f, steps = 14, modifier = Modifier.height(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = { committedLimitCount = sliderPosition.roundToInt(); loadRecipes(targetCount = committedLimitCount, collapseHeader = true, forceNewBatch = true) },
                                    enabled = !isLoading
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Подобрать")
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                when (selectedMode) {
                                    DataSourceMode.FREE_AI -> "✨ ИИ придумывает блюда кухни «$selectedCuisine»..."
                                    DataSourceMode.MEAL_DB_API -> "🌍 Загружаем блюда кухни «$selectedCuisine»..."
                                    DataSourceMode.LOCAL -> "Обновляем подборку рецептов..."
                                }
                            )
                        }
                    }
                } else if (displayedRecipes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (showOnlyFavorites) "Вы еще не добавили ни одного рецепта в Избранное ❤️" else "По запросу «$searchQuery» ничего не найдено")
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showOnlyFavorites = false; searchQuery = ""; isSearchVisible = false }) { Text("Показать все рецепты") }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item(key = "scrollable_compact_bar") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { isFiltersExpanded = !isFiltersExpanded }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = when {
                                                searchQuery.isNotBlank() -> "🔍 Поиск «$searchQuery»: найдено ${displayedRecipes.size}"
                                                showOnlyFavorites -> "❤️ Избранное (${displayedRecipes.size})"
                                                else -> "${selectedMode.shortLabel} • $selectedCuisine • $selectedCategory (${displayedRecipes.size}) ▾"
                                            },
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                        )
                                        if (!showOnlyFavorites && searchQuery.isBlank()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.clickable { loadRecipes(targetCount = committedLimitCount, collapseHeader = true, forceNewBatch = true) }
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                    Icon(Icons.Default.Refresh, contentDescription = "Новые", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("Новые", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                                }
                                            }
                                        }
                                    }
                                }

                                if (!isFiltersExpanded) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        items(DataSourceMode.entries) { mode ->
                                            FilterChip(
                                                selected = selectedMode == mode && !showOnlyFavorites,
                                                onClick = { showOnlyFavorites = false; selectedMode = mode; loadRecipes(mode, selectedCuisine, selectedCategory, forceNewBatch = true) },
                                                label = { Text(mode.shortLabel, fontSize = 11.sp) }, modifier = Modifier.height(28.dp)
                                            )
                                        }
                                        items(SortOrder.entries) { sortOption ->
                                            FilterChip(selected = selectedSort == sortOption, onClick = { selectedSort = sortOption }, label = { Text(sortOption.label, fontSize = 11.sp) }, modifier = Modifier.height(28.dp))
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(items = displayedRecipes, key = { _, item -> item.id }) { index, recipe ->
                            val isFav = favoriteIdsSet.contains(recipe.id)
                            RecipeCard(
                                index = index + 1, recipe = recipe, isDarkTheme = isDarkTheme,
                                fridgeStems = fridgeStemsSet, shoppingSet = shoppingItemsSet, isFavorite = isFav,
                                currentlySpeakingId = currentlySpeakingId,
                                onSpeakText = { utteranceId, text -> speechManager.speak(utteranceId, text) },
                                onAddMissingToCart = { missingItems ->
                                    var addedCount = 0
                                    missingItems.forEach { item -> if (!shoppingListItems.contains(item)) { shoppingListItems.add(item); addedCount++ } }
                                    persistAll()
                                    Toast.makeText(context, if (addedCount > 0) "Добавлено в список покупок: $addedCount шт. 🛒" else "Все эти продукты уже в списке покупок!", Toast.LENGTH_SHORT).show()
                                },
                                onToggleFavorite = {
                                    val existingIdx = favoriteRecipes.indexOfFirst { it.id == recipe.id || it.title == recipe.title }
                                    if (existingIdx != -1) favoriteRecipes.removeAt(existingIdx) else favoriteRecipes.add(0, recipe)
                                    persistAll()
                                },
                                onDeleteCustom = if (recipe.isCustom) {
                                    {
                                        customUserRecipes.removeAll { it.id == recipe.id }
                                        favoriteRecipes.removeAll { it.id == recipe.id }
                                        persistAll(); loadRecipes()
                                        Toast.makeText(context, "Рецепт удален", Toast.LENGTH_SHORT).show()
                                    }
                                } else null
                            )
                        }

                        if (!showOnlyFavorites && searchQuery.isBlank()) {
                            item {
                                OutlinedButton(
                                    onClick = { loadRecipes(targetCount = committedLimitCount, collapseHeader = true, forceNewBatch = true) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Ещё")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🔄 Подобрать другие блюда по этим фильтрам")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomRecipeView(cuisines: List<String>, categories: List<String>, onSaveRecipe: (Recipe) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedCuisine by remember { mutableStateOf(cuisines.firstOrNull() ?: "Русская") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Вторые блюда") }
    var cookingTime by remember { mutableStateOf("35 мин") }
    var calories by remember { mutableStateOf("250") }
    var customImageUrl by remember { mutableStateOf("") }
    var ingredientsText by remember { mutableStateOf("") }
    var instructionsText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("👨‍🍳 Добавить свой рецепт", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Закрыть"); Spacer(modifier = Modifier.width(4.dp)); Text("Отмена") }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название блюда *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Краткое описание блюда") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        Text("Кухня:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cuisines) { c -> FilterChip(selected = selectedCuisine == c, onClick = { selectedCuisine = c }, label = { Text(CulinaryUtils.formatCuisineWithFlag(c), fontSize = 12.sp) }) }
        }
        Text("Категория:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categories) { cat -> FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat, fontSize = 12.sp) }) }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = cookingTime, onValueChange = { cookingTime = it }, label = { Text("Время готовки") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = calories, onValueChange = { calories = it }, label = { Text("Ккал на 100 г") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = ingredientsText, onValueChange = { ingredientsText = it }, label = { Text("Ингредиенты (с новой строки) *") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = instructionsText, onValueChange = { instructionsText = it }, label = { Text("Шаги приготовления (с новой строки) *") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(value = customImageUrl, onValueChange = { customImageUrl = it }, label = { Text("Ссылка на фото (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = {
                if (title.isBlank() || ingredientsText.isBlank()) {
                    Toast.makeText(context, "Заполните название и ингредиенты!", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val parsedIngs = ingredientsText.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
                val rawSteps = instructionsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val finalSteps = CulinaryUtils.ensureValidInstructions(rawSteps, title.trim(), parsedIngs)
                val finalDesc = if (description.isNotBlank()) description.trim() else CulinaryUtils.buildDishDescription(title.trim(), selectedCuisine, selectedCategory, parsedIngs)
                val imgs = CulinaryUtils.buildDishPhotoUrls(title.trim(), 10, customImageUrl.trim().takeIf { it.isNotEmpty() })

                onSaveRecipe(
                    Recipe(
                        id = "custom_${System.currentTimeMillis()}", title = title.trim(), description = finalDesc,
                        cuisine = selectedCuisine, category = selectedCategory, cookingTime = cookingTime.ifBlank { "30 мин" },
                        difficulty = "Домашний", calories = calories.toIntOrNull() ?: 250,
                        imageUrls = imgs, ingredients = parsedIngs, instructions = finalSteps, isCustom = true
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = "Сохранить")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сохранить в Локальную базу и Избранное")
        }
    }
}

@Composable
fun ShoppingListView(shoppingItems: MutableList<String>, checkedItems: MutableList<String>, onUpdatePrefs: () -> Unit, onBackToRecipes: () -> Unit) {
    val context = LocalContext.current
    var newItemText by remember { mutableStateOf("") }

    fun addCustomItem() {
        val trimmed = newItemText.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isNotEmpty() && !shoppingItems.contains(trimmed)) { shoppingItems.add(trimmed); onUpdatePrefs(); newItemText = "" }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToRecipes) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад"); Spacer(modifier = Modifier.width(4.dp)); Text("К рецептам") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shoppingItems.isNotEmpty()) {
                    FilledTonalButton(onClick = {
                        val shareText = buildString {
                            append("🛒 Мой список покупок (Шеф-Повар):\n\n")
                            shoppingItems.forEachIndexed { index, item -> append("${index + 1}. ${if (checkedItems.contains(item)) "✅" else "▫️"} $item\n") }
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, shareText); type = "text/plain" }, "Отправить список покупок"))
                    }) { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Поделиться", fontSize = 12.sp) }
                    OutlinedButton(onClick = { shoppingItems.clear(); checkedItems.clear(); onUpdatePrefs() }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Очистить", fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItemText, onValueChange = { newItemText = it },
                placeholder = { Text("Добавить свой продукт в список...", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addCustomItem() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(onClick = { addCustomItem() }, enabled = newItemText.isNotBlank()) { Icon(Icons.Default.Add, contentDescription = "Добавить") }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (shoppingItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒 Список покупок пуст", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Нажмите кнопку «Добавить недостающие» в карточке любого рецепта!", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text("Куплено: ${checkedItems.size} из ${shoppingItems.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shoppingItems, key = { it }) { item ->
                    val isChecked = checkedItems.contains(item)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isChecked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().clickable { if (isChecked) checkedItems.remove(item) else checkedItems.add(item) }.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(checked = isChecked, onCheckedChange = { if (it) checkedItems.add(item) else checkedItems.remove(item) })
                                Text(item, fontSize = 15.sp, textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None, color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = { shoppingItems.remove(item); checkedItems.remove(item); onUpdatePrefs() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipeCard(
    index: Int,
    recipe: Recipe,
    isDarkTheme: Boolean,
    fridgeStems: Set<String>,
    shoppingSet: Set<String>,
    isFavorite: Boolean,
    currentlySpeakingId: String?,
    onSpeakText: (String, String) -> Unit,
    onAddMissingToCart: (List<String>) -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteCustom: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var portions by remember(recipe.id) { mutableIntStateOf(2) }
    val scaledIngredients = remember(recipe.ingredients, portions) { recipe.ingredients.map { CulinaryUtils.scaleIngredientAmount(it, portions) } }
    val safeInstructions = remember(recipe.instructions, recipe.title) { CulinaryUtils.ensureValidInstructions(recipe.instructions, recipe.title, recipe.ingredients) }

    val checkedIngredients = remember(recipe.id, fridgeStems, portions) {
        mutableStateListOf<String>().apply { scaledIngredients.forEach { ing -> if (CulinaryUtils.isIngredientMatchingStems(ing, fridgeStems)) add(ing) } }
    }
    val completedSteps = remember(recipe.id) { mutableStateListOf<Int>() }

    val missingIngredients = scaledIngredients.filter { !checkedIngredients.contains(it) }
    val matchedCount = scaledIngredients.count { CulinaryUtils.isIngredientMatchingStems(it, fridgeStems) }
    val needToBuyCount = (scaledIngredients.size - matchedCount).coerceAtLeast(0)
    val fullRecipeUtteranceId = "full_${recipe.id}"
    val isSpeakingFullRecipe = currentlySpeakingId == fullRecipeUtteranceId

    val badgeBgColor = when {
        needToBuyCount == 0 && isDarkTheme -> Color(0xFF1B3B22)
        needToBuyCount == 0 && !isDarkTheme -> Color(0xFFE8F5E9)
        isDarkTheme -> Color(0xFF3E2D14)
        else -> Color(0xFFFFF8E1)
    }
    val badgeTextColor = when {
        needToBuyCount == 0 && isDarkTheme -> Color(0xFFA5D6A7)
        needToBuyCount == 0 && !isDarkTheme -> Color(0xFF2E7D32)
        isDarkTheme -> Color(0xFFFFCC80)
        else -> Color(0xFFF57F17)
    }
    val inFridgeColor = if (isDarkTheme) Color(0xFF81C784) else Color(0xFF2E7D32)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (recipe.isCustom) Text("👨‍🍳 Мой домашний рецепт", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("#$index. ${recipe.title}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val shareText = buildString {
                                append("🍲 ${recipe.title} (${recipe.cuisine})\n📖 ${recipe.description}\n⏱ ${recipe.cookingTime} • 🔥 ${recipe.calories} ккал\n\n🛒 Ингредиенты (на $portions порц.):\n")
                                scaledIngredients.forEach { append("• $it\n") }
                                append("\n👨‍🍳 Приготовление:\n")
                                safeInstructions.forEachIndexed { idx, s -> append("${idx + 1}. $s\n") }
                            }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, shareText); type = "text/plain" }, "Поделиться рецептом"))
                        },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                    if (onDeleteCustom != null) {
                        IconButton(onClick = onDeleteCustom, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
                    }
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(34.dp)) {
                        Icon(imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Избранное", tint = if (isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(CulinaryUtils.formatCuisineWithFlag(recipe.cuisine), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text("🍽 ${recipe.category}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text("⏱ ${recipe.cookingTime}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text("🔥 ${recipe.calories} ккал", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }

            if (recipe.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("📖 ${recipe.description}", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            FilledTonalButton(
                onClick = {
                    val fullSpeech = buildString {
                        append("Рецепт: ${recipe.title}. ${recipe.description} Кухня: ${recipe.cuisine}. Время готовки: ${recipe.cookingTime}. ")
                        append("Ингредиенты на $portions порции: ${scaledIngredients.joinToString(". ")}. Способ приготовления: ")
                        safeInstructions.forEachIndexed { i, step -> append("Шаг ${i + 1}. $step. ") }
                        append("Приятного аппетита!")
                    }
                    onSpeakText(fullRecipeUtteranceId, fullSpeech)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Icon(imageVector = if (isSpeakingFullRecipe) Icons.Default.Stop else Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isSpeakingFullRecipe) "⏹ Остановить озвучку" else "🔊 Озвучить описание и рецепт", fontSize = 13.sp)
            }

            if (fridgeStems.isNotEmpty()) {
                Surface(color = badgeBgColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = if (needToBuyCount == 0) "🎉 Все продукты есть дома! ($matchedCount из ${scaledIngredients.size})"
                        else "🧊 Дома есть: $matchedCount из ${scaledIngredients.size} • 🛒 Докупить: $needToBuyCount",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val safeImages = recipe.imageUrls.ifEmpty { listOf("https://www.themealdb.com/images/media/meals/llcbn01574260722.jpg") }
            val pagerState = rememberPagerState(pageCount = { safeImages.size })

            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val imgUrl = safeImages[page]
                    val imgReq = remember(imgUrl) {
                        ImageRequest.Builder(context).data(imgUrl).size(600, 400).crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build()
                    }
                    AsyncImage(
                        model = imgReq, contentDescription = recipe.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Text("📷 ${pagerState.currentPage + 1}/${safeImages.size}", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                if (safeImages.size > 1) {
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(safeImages.size) { iteration ->
                            val color = if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.5f)
                            Box(modifier = Modifier.clip(CircleShape).background(color).size(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ингредиенты:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Порций: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedIconButton(onClick = { if (portions > 1) portions-- }, modifier = Modifier.size(28.dp)) { Text("-", fontWeight = FontWeight.Bold) }
                    Text(" $portions ", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                    OutlinedIconButton(onClick = { if (portions < 12) portions++ }, modifier = Modifier.size(28.dp)) { Text("+", fontWeight = FontWeight.Bold) }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            scaledIngredients.forEach { ingredient ->
                val isChecked = checkedIngredients.contains(ingredient)
                val inFridge = CulinaryUtils.isIngredientMatchingStems(ingredient, fridgeStems)
                val inCart = shoppingSet.contains(ingredient)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().clickable { if (isChecked) checkedIngredients.remove(ingredient) else checkedIngredients.add(ingredient) }.padding(vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = isChecked, onCheckedChange = { if (it) checkedIngredients.add(ingredient) else checkedIngredients.remove(ingredient) })
                        Text(ingredient, fontSize = 14.sp, textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None)
                    }
                    when {
                        inFridge -> Text("✅ Дома", fontSize = 11.sp, color = inFridgeColor, fontWeight = FontWeight.Medium)
                        inCart -> Text("🛒 В списке", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (missingIngredients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(onClick = { onAddMissingToCart(missingIngredients) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = "В корзину", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Добавить недостающие в список покупок (${missingIngredients.size})")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Пошаговое приготовление:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (completedSteps.isNotEmpty()) {
                    Text("Готово: ${completedSteps.size}/${safeInstructions.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            safeInstructions.forEachIndexed { stepIndex, step ->
                val stepUtteranceId = "step_${recipe.id}_$stepIndex"
                val isStepSpeaking = currentlySpeakingId == stepUtteranceId
                val isStepDone = completedSteps.contains(stepIndex)
                val timerInfo = remember(step) { CulinaryUtils.extractTimerInfo(step) }

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Surface(
                        color = when {
                            isStepSpeaking -> MaterialTheme.colorScheme.primaryContainer
                            isStepDone -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else -> Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { if (isStepDone) completedSteps.remove(stepIndex) else completedSteps.add(stepIndex) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(
                                text = if (isStepDone) "✓ ${stepIndex + 1}. $step" else "${stepIndex + 1}. $step",
                                fontSize = 14.sp,
                                fontWeight = if (isStepSpeaking) FontWeight.SemiBold else FontWeight.Normal,
                                textDecoration = if (isStepDone) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (isStepDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onSpeakText(stepUtteranceId, "Шаг ${stepIndex + 1}. $step") }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    imageVector = if (isStepSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = "Озвучить шаг",
                                    tint = if (isStepSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (timerInfo != null) {
                        StepTimerWidget(timerKey = stepUtteranceId, timerInfo = timerInfo, isDarkTheme = isDarkTheme)
                    }
                }
            }
        }
    }
}

@Composable
fun StepTimerWidget(timerKey: String, timerInfo: ParsedTimer, isDarkTheme: Boolean) {
    var timeLeft by remember(timerKey) { mutableIntStateOf(timerInfo.durationSeconds) }
    var isRunning by remember(timerKey) { mutableStateOf(false) }
    var isFinished by remember(timerKey) { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && timeLeft > 0) {
            delay(1000L)
            timeLeft -= 1
            if (timeLeft == 0) { isRunning = false; isFinished = true; CulinaryUtils.playAlarmSound() }
        }
    }

    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", timeLeft / 60, timeLeft % 60)
    val containerBg = when {
        isFinished && isDarkTheme -> Color(0xFF1B3B22)
        isFinished && !isDarkTheme -> Color(0xFFE8F5E9)
        isRunning && isDarkTheme -> Color(0xFF3E2D14)
        isRunning && !isDarkTheme -> Color(0xFFFFF8E1)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val accentDoneColor = if (isDarkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)

    Surface(color = containerBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, contentDescription = "Таймер", tint = if (isFinished) accentDoneColor else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isFinished -> "✅ Готово! (${timerInfo.label})"
                        isRunning -> "⏳ Осталось: $timeFormatted"
                        else -> "⏱ Таймер на ${timerInfo.label}"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isFinished) accentDoneColor else MaterialTheme.colorScheme.onSurface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isFinished) {
                    FilledTonalButton(onClick = { isRunning = !isRunning }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) {
                        Icon(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Старт", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRunning) "Пауза" else "Старт", fontSize = 11.sp)
                    }
                }
                OutlinedButton(onClick = { isRunning = false; isFinished = false; timeLeft = timerInfo.durationSeconds }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) {
                    Text("Сброс", fontSize = 11.sp)
                }
            }
        }
    }
}

// =========================================================================
// РЕПОЗИТОРИЙ ДАННЫХ И ЛОКАЛЬНАЯ БАЗА БЛЮД
// =========================================================================
object RecipeRepository {
    private val translationSemaphore = Semaphore(4)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val mealDbDetailCache = ConcurrentHashMap<String, Recipe>()
    private val recentlyShownMealDbIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val recentlyShownAiTitles = Collections.synchronizedSet(LinkedHashSet<String>())
    private val searchLetters = listOf('b', 'c', 's', 'p', 'm', 't', 'f', 'g', 'r', 'l', 'k', 'a', 'd', 'h', 'n', 'w')

    fun getAllCuratedRecipes(): List<Recipe> = CURATED_LOCAL_RECIPES
    fun getCachedOnlineRecipes(): List<Recipe> = mealDbDetailCache.values.toList()
    fun putCachedTranslation(key: String, value: String) { translationCache[key] = value }
    fun exportTranslationCacheSnapshot(): Map<String, String> = translationCache.entries.take(350).associate { it.key to it.value }

    private fun httpGet(urlStr: String, timeoutMs: Int = 5500): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = timeoutMs; readTimeout = timeoutMs
        }
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    fun translateRuToEn(text: String): String {
        val clean = text.trim().lowercase()
        val quickDict = mapOf(
            "борщ" to "borscht", "суп" to "soup", "курица" to "chicken", "говядина" to "beef", "свинина" to "pork",
            "рыба" to "fish", "лосось" to "salmon", "креветки" to "prawn", "паста" to "pasta", "пицца" to "pizza",
            "салат" to "salad", "пирог" to "pie", "торт" to "cake", "печенье" to "cookies", "рамен" to "ramen",
            "карри" to "curry", "тако" to "tacos", "бургер" to "burger", "стейк" to "steak", "рис" to "rice",
            "картофель" to "potato", "грибы" to "mushroom", "сыр" to "cheese", "блины" to "pancakes", "шоколад" to "chocolate"
        )
        quickDict[clean]?.let { return it }
        return try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val response = httpGet("https://translate.googleapis.com/translate_a/single?client=gtx&sl=ru&tl=en&dt=t&q=$encoded", 4000)
            JSONArray(response).getJSONArray(0).getJSONArray(0).getString(0).trim()
        } catch (_: Exception) { clean }
    }

    fun translateEnToRu(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        translationCache[trimmed]?.let { return it }
        val preCleaned = CulinaryUtils.normalizeAndTranslateCulinary(trimmed)
        if (!CulinaryUtils.hasLatin(preCleaned)) { translationCache[trimmed] = preCleaned; return preCleaned }

        try {
            val encoded = URLEncoder.encode(preCleaned.take(1800), "UTF-8")
            val conn = (URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 4500; readTimeout = 4500
            }
            val response = try {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write("q=$encoded") }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
            val outerArray = JSONArray(response).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until outerArray.length()) {
                val part = outerArray.optJSONArray(i)?.optString(0)
                if (!part.isNullOrEmpty()) sb.append(part)
            }
            val translated = sb.toString().trim()
            if (translated.isNotEmpty()) {
                val finalClean = CulinaryUtils.normalizeAndTranslateCulinary(translated)
                translationCache[trimmed] = finalClean
                return finalClean
            }
        } catch (_: Exception) {}

        translationCache[trimmed] = preCleaned
        return preCleaned
    }

    suspend fun translateBatchLinesEnToRu(lines: List<String>): List<String> = translationSemaphore.withPermit {
        if (lines.isEmpty()) return@withPermit emptyList()
        val sanitizedLines = lines.map { CulinaryUtils.normalizeAndTranslateCulinary(it.replace("\r", " ").replace("\n", " ").trim()) }
        if (sanitizedLines.none { CulinaryUtils.hasLatin(it) }) return@withPermit sanitizedLines
        val combined = sanitizedLines.joinToString("\n")
        val splitBack = translateEnToRu(combined).split("\n").map { CulinaryUtils.normalizeAndTranslateCulinary(it.trim()) }
        if (splitBack.size == lines.size) return@withPermit splitBack
        return@withPermit sanitizedLines.map { translateEnToRu(it) }
    }

    suspend fun searchGlobalOnlineRecipes(queryRu: String): List<Recipe> = withContext(Dispatchers.IO) {
        try {
            val enQuery = URLEncoder.encode(translateRuToEn(queryRu), "UTF-8")
            val response = httpGet("https://www.themealdb.com/api/json/v1/1/search.php?s=$enQuery", 5000)
            val mealsArray = JSONObject(response).optJSONArray("meals") ?: return@withContext emptyList()
            val limit = minOf(mealsArray.length(), 4)
            coroutineScope {
                (0 until limit).map { i -> async { parseAndTranslateMealDbObject(mealsArray.getJSONObject(i), "Все", "Все", i) } }.awaitAll().filterNotNull()
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun parseAndTranslateMealDbObject(detailObj: JSONObject, requestedCuisine: String, requestedCategory: String, indexHint: Int): Recipe? {
        return try {
            val mealId = detailObj.getString("idMeal")
            mealDbDetailCache[mealId]?.let { return it }
            val enTitle = detailObj.optString("strMeal", "Dish")
            val enArea = detailObj.optString("strArea", "")
            val enCategory = detailObj.optString("strCategory", "")
            val enInstructions = detailObj.optString("strInstructions", "")

            val rawIngredients = mutableListOf<String>()
            for (idx in 1..15) {
                val ing = detailObj.optString("strIngredient$idx", "").trim()
                val measure = detailObj.optString("strMeasure$idx", "").trim()
                if (ing.isNotEmpty() && ing != "null") {
                    val cleanMeasure = if (measure.isNotEmpty() && measure != "null") " — $measure" else ""
                    rawIngredients.add(CulinaryUtils.normalizeAndTranslateCulinary("$ing$cleanMeasure"))
                }
            }

            val enSteps = enInstructions.replace("\r\n", "\n").split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.length > 8 }.take(6)
            val allLinesToTranslate = mutableListOf<String>().apply { add(enTitle); addAll(rawIngredients); addAll(enSteps) }
            val translatedLines = translateBatchLinesEnToRu(allLinesToTranslate)

            val ruTitle = translatedLines.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Блюдо мировой кухни"
            val ingCount = rawIngredients.size
            val ruIngredients = translatedLines.drop(1).take(ingCount).map { it.replaceFirstChar { ch -> ch.uppercase() } }.ifEmpty { listOf("Основные ингредиенты — 300 г") }
            val finalSteps = CulinaryUtils.ensureValidInstructions(translatedLines.drop(1 + ingCount), ruTitle, ruIngredients)

            val realRussianCuisine = CulinaryUtils.resolveRealCuisine(enArea, ruTitle, requestedCuisine)
            val realRussianCategory = CulinaryUtils.resolveRealCategory(enCategory, ruTitle, requestedCategory)
            val dishDesc = CulinaryUtils.buildDishDescription(ruTitle, realRussianCuisine, realRussianCategory, ruIngredients)
            val dishPhotos = CulinaryUtils.buildDishPhotoUrls(enTitle, mealId.hashCode() and 0x7FFF, detailObj.optString("strMealThumb", ""))

            val built = Recipe(
                id = "mealdb_$mealId", title = ruTitle, description = dishDesc,
                cuisine = realRussianCuisine, category = realRussianCategory,
                cookingTime = "${25 + (indexHint * 5) % 35} мин", difficulty = "Средне",
                calories = 220 + (indexHint * 25) % 180, imageUrls = dishPhotos,
                ingredients = ruIngredients, instructions = finalSteps
            )
            mealDbDetailCache[mealId] = built
            built
        } catch (_: Exception) { null }
    }

    private fun extractStepsFromJsonObject(obj: JSONObject, title: String, ingredients: List<String>): List<String> {
        val rawList = mutableListOf<String>()
        val arrayField = obj.optJSONArray("instructions") ?: obj.optJSONArray("steps") ?: obj.optJSONArray("directions")
        if (arrayField != null) {
            for (i in 0 until arrayField.length()) rawList.add(arrayField.optString(i, ""))
        } else {
            val strField = obj.optString("instructions", obj.optString("steps", ""))
            if (strField.isNotBlank()) rawList.addAll(strField.split("\n"))
        }
        return CulinaryUtils.ensureValidInstructions(rawList, title, ingredients)
    }

    suspend fun fetchAiRussianRecipes(
        cuisine: String, category: String, fridgeItems: List<String>, customRecipes: List<Recipe>,
        voicePrompt: String?, count: Int, refreshSeed: Int
    ): List<Recipe> = withContext(Dispatchers.IO) {
        try {
            val fridgeText = if (fridgeItems.isNotEmpty()) "Используй продукты: ${fridgeItems.joinToString(", ")}." else ""
            val voiceText = if (!voicePrompt.isNullOrBlank()) "Запрос: $voicePrompt." else ""
            val recentAiList = synchronized(recentlyShownAiTitles) { recentlyShownAiTitles.toList().takeLast(12) }
            val excludeText = if (recentAiList.isNotEmpty()) "Не повторяй: ${recentAiList.joinToString(", ")}." else ""
            val askCount = minOf(count, 6)
            val strictCuisineInstruction = if (cuisine == "Все") "разные страны мира (укажи реальную кухню каждого блюда)" else "СТРОГО ТОЛЬКО «$cuisine» кухня!"

            val userPrompt = """
                Партия №${refreshSeed + 1}. Создай $askCount новых рецептов на русском языке.
                Кухня: $strictCuisineInstruction. Категория: ${if (category == "Все") "разные" else "СТРОГО «$category»"}.
                $fridgeText $voiceText $excludeText
                Верни ТОЛЬКО JSON массив:
                [{"title":"Название","description":"Краткое описание","englishName":"dish name","cuisine":"${if (cuisine == "Все") "Точная кухня" else cuisine}","category":"${if (category == "Все") "Точная категория" else category}","cookingTime":"35 мин","difficulty":"Легко","calories":250,"ingredients":["Продукт - 200 г"],"instructions":["Шаг 1. Подготовьте продукты.","Шаг 2. Обжарьте 10 минут.","Шаг 3. Варите 15 минут."]}]
            """.trimIndent()

            val payload = JSONObject().apply {
                put("model", "openai"); put("seed", Random.nextInt(1, 999999))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "Отвечай строго валидным JSON массивом рецептов на русском языке."))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
            }

            val conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 12000; readTimeout = 16000
            }
            val responseText = try {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }

            val startBracket = responseText.indexOf('[')
            val endBracket = responseText.lastIndexOf(']')
            if (startBracket != -1 && endBracket > startBracket) {
                val jsonArray = JSONArray(responseText.substring(startBracket, endBracket + 1))
                val aiResult = mutableListOf<Recipe>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val ruTitle = translateEnToRu(obj.optString("title", "Авторское блюдо #${i + 1}"))
                    recentlyShownAiTitles.add(ruTitle)
                    val engDish = obj.optString("englishName", ruTitle)
                    val dishPhotos = CulinaryUtils.buildDishPhotoUrls(engDish, refreshSeed * 100 + i * 17)
                    val ingArr = obj.optJSONArray("ingredients") ?: JSONArray()
                    val ingList = List(ingArr.length()) { translateEnToRu(ingArr.getString(it)) }.ifEmpty { listOf("Основные ингредиенты - 300 г") }
                    val stepsList = extractStepsFromJsonObject(obj, ruTitle, ingList).map { translateEnToRu(it) }
                    val realCuisine = if (cuisine != "Все") cuisine else CulinaryUtils.resolveRealCuisine(obj.optString("cuisine", ""), ruTitle, cuisine)
                    val realCategory = if (category != "Все") category else CulinaryUtils.resolveRealCategory(obj.optString("category", ""), ruTitle, category)
                    val rawDesc = translateEnToRu(obj.optString("description", ""))
                    val finalDesc = if (rawDesc.length > 10) rawDesc else CulinaryUtils.buildDishDescription(ruTitle, realCuisine, realCategory, ingList)

                    aiResult.add(
                        Recipe(
                            id = "ai_${refreshSeed}_${ruTitle.hashCode()}_$i", title = ruTitle, description = finalDesc,
                            cuisine = realCuisine, category = realCategory,
                            cookingTime = CulinaryUtils.normalizeAndTranslateCulinary(obj.optString("cookingTime", "35 мин")),
                            difficulty = translateEnToRu(obj.optString("difficulty", "Средне")), calories = obj.optInt("calories", 250),
                            imageUrls = dishPhotos, ingredients = ingList, instructions = stepsList
                        )
                    )
                }
                if (aiResult.isNotEmpty()) {
                    if (aiResult.size < count) {
                        val existingTitles = aiResult.map { it.title }.toSet()
                        aiResult.addAll(buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed).filter { !existingTitles.contains(it.title) })
                    }
                    return@withContext aiResult.take(count)
                }
            }
        } catch (_: Exception) {}
        return@withContext buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
    }

    suspend fun fetchTranslatedMealDbRecipes(
        cuisine: String, category: String, fridgeItems: List<String>, customRecipes: List<Recipe>,
        count: Int, refreshSeed: Int
    ): List<Recipe> = withContext(Dispatchers.IO) {
        try {
            val areaMap = mapOf("Русская" to "Russian", "Французская" to "French", "Итальянская" to "Italian", "Японская" to "Japanese", "Мексиканская" to "Mexican", "Китайская" to "Chinese", "Тайская" to "Thai", "Индийская" to "Indian")
            if (cuisine != "Все" && !areaMap.containsKey(cuisine)) {
                return@withContext fetchAiRussianRecipes(cuisine, category, fridgeItems, customRecipes, null, count, refreshSeed)
            }
            val ingredientRuToEn = mapOf("курица" to "chicken", "яйца" to "egg", "картофель" to "potatoes", "сыр" to "cheese", "томаты" to "tomatoes", "молоко" to "milk", "рис" to "rice", "фарш" to "beef", "говядина" to "beef", "свинина" to "pork", "грибы" to "mushrooms", "капуста" to "cabbage")
            val categoryMap = mapOf("Супы" to "Starter", "Каши и завтраки" to "Breakfast", "Вторые блюда" to "Beef", "Выпечка" to "Dessert", "Что-то к чаю" to "Dessert", "Салаты" to "Side", "Паста и пицца" to "Pasta")

            val firstFridgeEn = fridgeItems.firstNotNullOfOrNull { ingredientRuToEn[it.trim().lowercase()] }
            val rotatedLetter = searchLetters[refreshSeed % searchLetters.size]
            val secondCategoryForVariety = listOf("Chicken", "Beef", "Seafood", "Pork", "Pasta")[refreshSeed % 5]

            val endpointUrl = when {
                cuisine != "Все" && areaMap.containsKey(cuisine) -> "https://www.themealdb.com/api/json/v1/1/filter.php?a=${areaMap[cuisine]}"
                category != "Все" && category == "Вторые блюда" -> "https://www.themealdb.com/api/json/v1/1/filter.php?c=$secondCategoryForVariety"
                category != "Все" && categoryMap.containsKey(category) -> "https://www.themealdb.com/api/json/v1/1/filter.php?c=${categoryMap[category]}"
                firstFridgeEn != null -> "https://www.themealdb.com/api/json/v1/1/filter.php?i=$firstFridgeEn"
                else -> "https://www.themealdb.com/api/json/v1/1/search.php?s=$rotatedLetter"
            }

            val listResponse = httpGet(endpointUrl, 5500)
            val mealsArray = JSONObject(listResponse).optJSONArray("meals")
            if (mealsArray != null && mealsArray.length() > 0) {
                val allCandidateObjects = List(mealsArray.length()) { mealsArray.getJSONObject(it) }
                val unseenCandidates = allCandidateObjects.filter { !recentlyShownMealDbIds.contains(it.optString("idMeal")) }.shuffled()
                val seenCandidates = allCandidateObjects.filter { recentlyShownMealDbIds.contains(it.optString("idMeal")) }.shuffled()
                val prioritizedCandidates = if (unseenCandidates.isNotEmpty()) unseenCandidates + seenCandidates else { recentlyShownMealDbIds.clear(); allCandidateObjects.shuffled() }
                val selectedBatch = prioritizedCandidates.take(minOf(count, minOf(prioritizedCandidates.size, 8)))

                val parallelResults = coroutineScope {
                    selectedBatch.mapIndexed { i, mealSummary ->
                        async {
                            val mealId = mealSummary.optString("idMeal", "")
                            if (mealId.isNotEmpty()) recentlyShownMealDbIds.add(mealId)
                            val detailObj = if (mealSummary.has("strInstructions") && mealSummary.has("strArea")) mealSummary else {
                                val detailResponse = httpGet("https://www.themealdb.com/api/json/v1/1/lookup.php?i=$mealId", 5500)
                                JSONObject(detailResponse).getJSONArray("meals").getJSONObject(0)
                            }
                            parseAndTranslateMealDbObject(detailObj, cuisine, category, i)
                        }
                    }.awaitAll().filterNotNull().filter { r -> cuisine == "Все" || r.cuisine.equals(cuisine, ignoreCase = true) }.toMutableList()
                }
                if (parallelResults.isNotEmpty()) {
                    if (parallelResults.size < count) {
                        val existingIds = parallelResults.map { it.id }.toSet()
                        parallelResults.addAll(buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed).filter { !existingIds.contains(it.id) })
                    }
                    return@withContext parallelResults.take(count)
                }
            }
        } catch (_: Exception) {}
        return@withContext buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
    }

    fun buildLocalDatabaseRecipes(
        cuisine: String, category: String, fridgeItems: List<String>, customRecipes: List<Recipe>,
        count: Int, refreshSeed: Int
    ): List<Recipe> {
        val randomForBatch = Random(refreshSeed * 31 + 7)
        val allRecipes = customRecipes + CURATED_LOCAL_RECIPES.shuffled(randomForBatch)
        val fridgeStems = CulinaryUtils.computeFridgeStems(fridgeItems)

        val exactMatches = allRecipes.filter { r ->
            (cuisine == "Все" || r.cuisine.equals(cuisine, ignoreCase = true)) &&
            (category == "Все" || r.category.equals(category, ignoreCase = true))
        }.toMutableList()

        if (exactMatches.size < count && cuisine != "Все") {
            val usedIds = exactMatches.map { it.id }.toMutableSet()
            allRecipes.filter { !usedIds.contains(it.id) && it.cuisine.equals(cuisine, ignoreCase = true) }.shuffled(randomForBatch)
                .forEach { if (exactMatches.size < count) { exactMatches.add(it); usedIds.add(it.id) } }
        } else if (exactMatches.size < count && cuisine == "Все" && category != "Все") {
            val usedIds = exactMatches.map { it.id }.toMutableSet()
            allRecipes.filter { !usedIds.contains(it.id) && it.category.equals(category, ignoreCase = true) }.shuffled(randomForBatch)
                .forEach { if (exactMatches.size < count) { exactMatches.add(it); usedIds.add(it.id) } }
        }

        if (exactMatches.size > 1 && refreshSeed > 0) {
            val shift = (refreshSeed * 2) % exactMatches.size
            val rotated = exactMatches.drop(shift) + exactMatches.take(shift)
            exactMatches.clear(); exactMatches.addAll(rotated)
        }

        if (fridgeStems.isNotEmpty()) {
            exactMatches.sortWith(compareByDescending<Recipe> { it.isCustom }.thenByDescending { r -> r.ingredients.count { CulinaryUtils.isIngredientMatchingStems(it, fridgeStems) } })
        }

        if (exactMatches.isNotEmpty() && exactMatches.size < count) {
            val baseList = exactMatches.toList()
            val styles = listOf("по-домашнему", "особый шеф-рецепт", "пикантный вариант", "традиционный рецепт", "быстрый вариант", "праздничная подача")
            var idx = 0
            while (exactMatches.size < count) {
                val template = baseList[idx % baseList.size]
                val style = styles[(idx + refreshSeed) % styles.size]
                exactMatches.add(template.copy(id = "${template.id}_var_${refreshSeed}_$idx", title = "${template.title} ($style)", cookingTime = "${(template.cookingMinutes - 5).coerceAtLeast(15) + (idx * 5) % 20} мин"))
                idx++
            }
        }
        return exactMatches.take(count)
    }

    private val CURATED_LOCAL_RECIPES = listOf(
        Recipe("loc_borscht", "Классический борщ с говядиной и сметаной", "Традиционный густой суп насыщенного рубинового цвета на наваристом говяжьем бульоне со свеклой, свежей капустой и чесноком.", "Русская", "Супы", "1 ч 20 мин", "Средне", 135, listOf("https://www.themealdb.com/images/media/meals/vvstvq1487342592.jpg", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600"), listOf("Говядина на кости - 500 г", "Свекла - 2 шт.", "Капуста белокочанная - 300 г", "Картофель - 4 шт.", "Томатная паста - 2 ст.л.", "Сметана - 3 ст.л."), listOf("Сварите наваристый бульон из говядины в течение 45 минут, затем достаньте мясо и нарежьте кусочками.", "В кипящий бульон отправьте нарезанный кубиками картофель и нашинкованную капусту, варите 15 минут.", "Обжарьте на сковороде тертую свеклу, морковь и лук с томатной пастой в течение 8 минут.", "Соедините зажарку с супом, добавьте чеснок и лавровый лист, томите под крышкой еще 10 минут.")),
        Recipe("loc_chicken_rice", "Курица с рисом и овощами на одной сковороде", "Удобное и сытное домашнее блюдо в одной посуде: сочные кусочки курицы и рассыпчатый рис, пропитанные ароматом чеснока и моркови.", "Русская", "Вторые блюда", "40 мин", "Легко", 240, listOf("https://www.themealdb.com/images/media/meals/sypxpx1515365095.jpg", "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=600"), listOf("Курица (филе или бедра) - 500 г", "Рис длиннозерный - 250 г", "Морковь - 1 шт.", "Лук репчатый - 1 шт.", "Чеснок - 3 зубчика", "Масло растительное - 2 ст.л."), listOf("Нарежьте курицу небольшими кусочками и обжарьте на сковороде до золотистой корочки в течение 8 минут.", "Добавьте нарезанный лук и тертую морковь, обжаривайте вместе еще 5 минут.", "Всыпьте промытый рис, разровняйте, влейте 500 мл горячей подсоленной воды и убавьте огонь до минимума.", "Накройте плотной крышкой и томите 20 минут, пока рис полностью не впитает бульон.")),
        Recipe("loc_kartoshka", "Домашнее пирожное «Картошка» с соленой карамелью", "Знаменитый шоколадно-песочный десерт без выпечки с бархатистой текстурой какао и насыщенным вкусом карамельной сгущенки.", "Русская", "Что-то к чаю", "30 мин", "Легко", 380, listOf("https://www.themealdb.com/images/media/meals/tqtywx1468317395.jpg", "https://www.themealdb.com/images/media/meals/qxutws1486978099.jpg"), listOf("Печенье песочное - 400 г", "Вареная сгущенка (соленая карамель) - 250 г", "Сливочное масло - 100 г", "Какао-порошок - 4 ст.л."), listOf("Измельчите песочное печенье в мелкую крошку с помощью блендера или скалки.", "Смешайте крошку с 3 столовыми ложками какао, мягким сливочным маслом и карамельной сгущенкой.", "Сформируйте руками аккуратные овальные пирожные и обваляйте их в оставшемся какао.", "Уберите пирожные в холодильник на 20 минут для застывания перед подачей к чаю.")),
        Recipe("loc_syrniki", "Пышные творожные сырники на завтрак", "Нежные домашние сырники с румяной золотистой корочкой и мягкой творожной серединкой, подаются со сметаной или ягодами.", "Русская", "Каши и завтраки", "25 мин", "Легко", 220, listOf("https://www.themealdb.com/images/media/meals/rwuyqx1511383174.jpg", "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600"), listOf("Творог 9% - 400 г", "Яйца - 1 шт.", "Мука пшеничная или рисовая - 3 ст.л.", "Сахар - 2 ст.л.", "Сметана - 3 ст.л."), listOf("Протрите творог вилкой, добавьте яйцо, сахар и щепотку ванилина, тщательно перемешайте.", "Всыпьте муку и замесите мягкую творожную массу, сформуйте ровные круглые шайбочки.", "Обжаривайте сырники на разогретой сковороде на среднем огне по 5 минут с каждой стороны до румяной корочки.")),
        Recipe("loc_stroganoff", "Бефстроганов из говядины с грибами в сметанном соусе", "Классическое блюдо русской кухни: тонкие ломтики сочной говядины, томленные с шампиньонами и луком в густом сливочно-сметанном соусе.", "Русская", "Вторые блюда", "45 мин", "Средне", 265, listOf("https://www.themealdb.com/images/media/meals/svprys1511176755.jpg", "https://www.themealdb.com/images/media/meals/ursuup1487348423.jpg"), listOf("Говядина (вырезка) - 500 г", "Грибы шампиньоны - 250 г", "Сметана 20% - 200 г", "Лук репчатый - 2 шт.", "Мука - 1 ст.л."), listOf("Нарежьте говядину тонкими брусочками и быстро обжарьте на сильном огне в течение 6 минут.", "Отдельно обжарьте нарезанный лук и ломтики шампиньонов в течение 8 минут до золотистости.", "Соедините мясо с грибами, присыпьте столовой ложкой муки, добавьте сметану и 100 мл теплой воды.", "Тушите под крышкой на слабом огне 15 минут до загустения нежного соуса.")),
        Recipe("loc_charlottka", "Пышная домашняя шарлотка с яблоками и корицей", "Воздушный бисквитный пирог с сочной кисло-сладкой яблочной начинкой и теплым пряным ароматом молотой корицы.", "Русская", "Выпечка", "45 мин", "Легко", 250, listOf("https://www.themealdb.com/images/media/meals/qtqvys1468573168.jpg", "https://www.themealdb.com/images/media/meals/xvsurr1511719182.jpg"), listOf("Яблоки кисло-сладкие - 4 шт.", "Яйца - 4 шт.", "Сахар - 150 г", "Мука - 160 г", "Корица молотая - 1 ч.л."), listOf("Взбейте яйца с сахаром миксером в пышную светлую пену в течение 5 минут.", "Аккуратно просейте муку и перемешайте лопаткой снизу вверх, чтобы тесто осталось воздушным.", "Выложите дольки яблок с корицей в смазанную форму, залейте тестом и выпекайте 35 минут при 180°C.")),
        Recipe("loc_olivier", "Домашний салат Оливье с куриным филе", "Любимый праздничный и повседневный салат из нежного куриного филе, отварных овощей, маринованных огурчиков и зеленого горошка.", "Русская", "Салаты", "30 мин", "Легко", 195, listOf("https://www.themealdb.com/images/media/meals/1549542994.jpg", "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=600"), listOf("Курица отварная - 300 г", "Картофель - 3 шт.", "Яйца - 3 шт.", "Морковь - 1 шт.", "Огурцы маринованные - 3 шт.", "Горошек зеленый - 150 г"), listOf("Отварите картофель, морковь и яйца в течение 20 минут, остудите и очистите.", "Нарежьте куриное филе, овощи и яйца одинаковыми мелкими кубиками.", "Добавьте зеленый горошек, заправьте сметаной или майонезом и охладите в холодильнике 10 минут.")),
        Recipe("loc_kimchi_soup", "Острый корейский суп Кимчи Тиге со свининой и тофу", "Знаменитый согревающий суп корейской кухни с пикантной ферментированной капустой кимчи, нежным сыром тофу и мясом.", "Корейская", "Супы", "35 мин", "Легко", 190, listOf("https://www.themealdb.com/images/media/meals/1529446352.jpg", "https://images.unsplash.com/photo-1583224964978-2257b960c3d3?w=600"), listOf("Капуста кимчи - 300 г", "Свинина или курица - 250 г", "Сыр тофу - 150 г", "Яйца - 1 шт.", "Лук зеленый - 1 пучок"), listOf("Нарежьте мясо и капусту кимчи кусочками и обжарьте в глубокой кастрюле в течение 7 минут.", "Влейте 600 мл горячей воды или бульона, доведите до кипения и варите на среднем огне 15 минут.", "Добавьте нарезанный кубиками сыр тофу, вбейте яйцо и варите еще 4 минуты, посыпав зеленым луком.")),
        Recipe("loc_korean_chicken", "Хрустящая курица по-корейски в кисло-сладком соусе Яннём", "Популярный корейский хит: кусочки курицы в тонкой хрустящей корочке, покрытые глянцевой медово-чесночной глазурью с кунжутом.", "Корейская", "Вторые блюда", "40 мин", "Средне", 310, listOf("https://www.themealdb.com/images/media/meals/1529444830.jpg", "https://www.themealdb.com/images/media/meals/tytyxu1515363282.jpg"), listOf("Курица (филе или крылышки) - 600 г", "Крахмал кукурузный - 4 ст.л.", "Чеснок - 3 зубчика", "Томатный соус - 3 ст.л.", "Мед - 2 ст.л.", "Кунжут - 1 ст.л."), listOf("Нарежьте курицу кусочками, обваляйте в кукурузном крахмале и обжарьте в масле 12 минут до хрустящей корочки.", "В отдельной сковороде смешайте измельченный чеснок, томатный соус, соевый соус и мед, прогрейте 3 минуты.", "Переложите хрустящую курицу в горячий соус, быстро перемешайте и посыпьте семенами кунжута.")),
        Recipe("loc_bibimbap", "Пибимпап (Корейский рис с говядиной, овощами и яйцом)", "Красочная корейская чаша на основе теплого риса, украшенная обжаренным пряным мясом, соломкой овощей и яичницей с жидким желтком.", "Корейская", "Вторые блюда", "30 мин", "Легко", 275, listOf("https://www.themealdb.com/images/media/meals/z0ageb1583189517.jpg", "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=600"), listOf("Рис круглозерный - 250 г", "Фарш говяжий - 250 г", "Яйца - 2 шт.", "Морковь - 1 шт.", "Огурец - 1 шт.", "Соевый соус - 2 ст.л."), listOf("Отварите рис в подсоленной воде в течение 15 минут до рассыпчатости.", "Обжарьте говяжий фарш с соевым соусом и чесноком в течение 8 минут, отдельно поджарьте соломку моркови 4 минуты.", "Поджарьте яичницу-глазунью в течение 3 минут, выложите на горячий рис вместе с мясом и овощами.")),
        Recipe("loc_bulgogi", "Пулькоги (Мраморная говядина по-корейски в кунжутном маринаде)", "Тончайшие ломтики говядины, маринованные в соевом соусе с чесноком и медом, быстро обжаренные на сильном огне с луком.", "Корейская", "Вторые блюда", "25 мин", "Легко", 260, listOf("https://www.themealdb.com/images/media/meals/1529443236.jpg", "https://www.themealdb.com/images/media/meals/1529444113.jpg"), listOf("Говядина - 500 г", "Соевый соус - 4 ст.л.", "Лук репчатый - 1 шт.", "Чеснок - 3 зубчика", "Сахар или мед - 1 ст.л.", "Кунжут - 1 ст.л."), listOf("Тонко нарежьте говядину и замаринуйте в соевом соусе с измельченным чесноком и медом на 10 минут.", "Разогрейте сковороду на сильном огне, выложите мясо с полукольцами лука и обжаривайте 8 минут, помешивая.", "Посыпьте готовое мясо обжаренным кунжутом и зеленым луком, подавайте с рисом.")),
        Recipe("loc_korean_carrots", "Пикантный корейский салат из моркови с чесноком и кориандром", "Хрустящая сочная морковь, нашинкованная тонкой соломкой и заправленная горячим пряным маслом с чесноком и молотым кориандром.", "Корейская", "Салаты", "20 мин", "Легко", 145, listOf("https://images.unsplash.com/photo-1540420773420-3366772f4999?w=600", "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=600"), listOf("Морковь сочная - 500 г", "Чеснок - 4 зубчика", "Масло растительное - 4 ст.л.", "Уксус яблочный - 2 ст.л.", "Кориандр молотый - 1 ч.л.", "Сахар - 1 ч.л."), listOf("Натрите морковь длинной тонкой соломкой, посыпьте солью и сахаром, слегка помните руками в течение 3 минут.", "Выложите сверху горкой измельченный чеснок и молотый кориандр.", "Раскалите растительное масло на сковороде в течение 4 минут, вылейте прямо на чеснок со специями, добавьте уксус и перемешайте.")),
        Recipe("loc_gyeran_mari", "Керан Мари (Пышный корейский яичный рулет с сыром и овощами)", "Традиционный корейский завтрак: многослойный нежный омлет-рулет с тягучим сыром внутри, нарезанный порционными кусочками.", "Корейская", "Каши и завтраки", "15 мин", "Легко", 210, listOf("https://www.themealdb.com/images/media/meals/1550440197.jpg", "https://images.unsplash.com/photo-1525351484163-7529414344d8?w=600"), listOf("Яйца - 4 шт.", "Молоко - 2 ст.л.", "Сыр твердый - 60 г", "Морковь - 0.5 шт.", "Лук зеленый - 2 пера"), listOf("Взбейте яйца с молоком и солью, вмешайте очень мелко нарезанную морковь и зеленый лук.", "Вылейте треть смеси тонким слоем на разогретую сковороду, жарьте 2 минуты, посыпьте сыром и сверните в рулет на край сковороды.", "Долейте оставшуюся яичную смесь в 2 приема, каждый раз оборачивая рулет новыми слоями в течение 5 минут, затем нарежьте ломтиками.")),
        Recipe("loc_french_gratin", "Французский картофельный гратен Дофинуа со сливками и сыром", "Изысканная французская запеканка из тончайших ломтиков картофеля, томленных в чесночных сливках под румяной сырной корочкой.", "Французская", "Вторые блюда", "50 мин", "Легко", 265, listOf("https://www.themealdb.com/images/media/meals/qwrtut1468418027.jpg", "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=600"), listOf("Картофель - 800 г", "Сыр твердый - 150 г", "Молоко или сливки - 300 мл", "Чеснок - 2 зубчика", "Сливочное масло - 30 г"), listOf("Очистите картофель и нарежьте тонкими кружочками толщиной около 3 миллиметров.", "Натрите форму для запекания зубчиком чеснока и сливочным маслом, уложите картофель внахлест.", "Залейте горячими сливками с солью, посыпьте тертым сыром и запекайте 40 минут при 190°C до золотистой корочки.")),
        Recipe("loc_french_soup", "Французский луковый суп с сырными гренками", "Классика парижских бистро: густой бульон из карамелизированного в сливочном масле лука, запеченный под шапкой из багета и тягучего сыра.", "Французская", "Супы", "45 мин", "Средне", 210, listOf("https://www.themealdb.com/images/media/meals/xvrrux1511783685.jpg", "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600"), listOf("Лук репчатый - 500 г", "Сыр твердый - 120 г", "Сливочное масло - 50 г", "Багет или белый хлеб - 4 ломтика", "Мясной бульон - 700 мл"), listOf("Нарежьте лук тонкими полукольцами и томите на сливочном масле на медленном огне 20 минут до карамельного оттенка.", "Влейте горячий бульон, посолите и варите на слабом огне еще 15 минут.", "Разлейте суп по формам, сверху положите подсушенный багет, щедро посыпьте сыром и запекайте 5 минут в духовке.")),
        Recipe("loc_croissants", "Французские круассаны с запеченным миндальным кремом", "Хрустящая слоеная выпечка с нежной орехово-сливочной начинкой франжипан и сахарной пудрой — идеальное дополнение к утреннему чаю или кофе.", "Французская", "Что-то к чаю", "30 мин", "Легко", 390, listOf("https://www.themealdb.com/images/media/meals/tlstrw1511724769.jpg", "https://www.themealdb.com/images/media/meals/wtqrqw1511639627.jpg"), listOf("Готовые круассаны - 4 шт.", "Миндальная мука или орехи - 100 г", "Сливочное масло - 80 г", "Яйца - 2 шт.", "Сахарная пудра - 80 г"), listOf("Взбейте размягченное сливочное масло с сахарной пудрой, яйцами и молотым миндалем в пышный крем.", "Разрежьте круассаны вдоль пополам, щедро смажьте миндальным кремом внутри и сверху.", "Запекайте в разогретой до 180°C духовке в течение 15 минут до хрустящей румяной корочки.")),
        Recipe("loc_julienne", "Французский жульен с курицей и грибами под сырной корочкой", "Горячая сливочно-грибная закуска с нежным куриным филе и тягучей золотистой корочкой запеченного сыра.", "Французская", "Вторые блюда", "30 мин", "Легко", 280, listOf("https://www.themealdb.com/images/media/meals/uuuspp1511297945.jpg", "https://www.themealdb.com/images/media/meals/1548772327.jpg"), listOf("Курица (филе) - 300 г", "Грибы шампиньоны - 300 г", "Сыр твердый - 150 г", "Сливки или сметана - 200 г", "Лук репчатый - 1 шт."), listOf("Мелко нарежьте куриное филе, грибы и лук, обжарьте на сковороде в течение 10 минут.", "Влейте сливки или сметану, посолите и прогрейте смесь еще 4 минуты до легкого загустения.", "Разложите по формам, обильно посыпьте тертым сыром и запекайте в духовке 12 минут при 190°C.")),
        Recipe("loc_carbonara", "Итальянская паста Карбонара с беконом и Пармезаном", "Знаменитая римская паста с хрустящими ломтиками бекона и шелковистым соусом на основе яичных желтков и тертого сыра Пармезан.", "Итальянская", "Паста и пицца", "20 мин", "Легко", 340, listOf("https://www.themealdb.com/images/media/meals/llcbn01574260722.jpg", "https://images.unsplash.com/photo-1612874742237-6526221588e3?w=600"), listOf("Спагетти - 300 г", "Бекон или грудинка - 150 г", "Яйца (желтки) - 3 шт.", "Сыр Пармезан - 80 г", "Чеснок - 2 зубчика"), listOf("Отварите спагетти в кипящей подсоленной воде в течение 9 минут до состояния аль денте.", "Нарежьте бекон ломтиками и обжарьте на сковороде с раздавленным зубчиком чеснока в течение 5 минут.", "В миске взбейте яичные желтки с мелко натертым сыром и черным перцем, добавьте 3 ложки воды от пасты.", "Переложите горячие спагетти к бекону, снимите с огня, влейте яично-сырный соус и энергично перемешайте.")),
        Recipe("loc_pizza", "Домашняя итальянская пицца Маргарита с томатами и Моцареллой", "Классическая неаполитанская пицца на тонком тесте с ароматным томатным соусом, тягучей Моцареллой и свежим базиликом.", "Итальянская", "Паста и пицца", "30 мин", "Легко", 285, listOf("https://www.themealdb.com/images/media/meals/x0lk931587671540.jpg", "https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=600"), listOf("Тесто для пиццы - 400 г", "Сыр Моцарелла - 250 г", "Томаты спелые - 3 шт.", "Томатный соус - 4 ст.л.", "Масло оливковое и базилик"), listOf("Раскатайте тесто в тонкую круглую лепешку и переложите на противень, застеленный пергаментом.", "Смажьте основу томатным соусом с орегано, разложите кружочки томатов и ломтики сыра Моцарелла.", "Выпекайте в максимально разогретой до 220°C духовке в течение 15 минут до расплавления сыра.")),
        Recipe("loc_caesar", "Салат Цезарь с обжаренной куриной грудкой, сыром и сухариками", "Легкий и хрустящий салат из свежих листьев с румяными ломтиками куриного филе, чесночными гренками, черри и тертым сыром.", "Итальянская", "Салаты", "25 мин", "Легко", 215, listOf("https://www.themealdb.com/images/media/meals/urtwux1486983078.jpg", "https://images.unsplash.com/photo-1550304943-4f24f54ddde9?w=600"), listOf("Курица (филе) - 300 г", "Салат листовой или пекинская капуста - 1 пучок", "Томаты черри - 150 г", "Сыр твердый - 60 г", "Хлеб белый - 3 ломтика"), listOf("Нарежьте белый хлеб кубиками и подсушите на сковороде с каплей масла и чесноком в течение 5 минут.", "Обжарьте куриное филе со специями по 6 минут с каждой стороны, затем нарежьте ломтиками.", "Порвите листья салата в глубокую тарелку, выложите курицу, половинки томатов, сухарики и посыпьте тертым сыром.")),
        Recipe("loc_khachapuri", "Грузинский хачапури по-аджарски с сыром и желтком", "Знаменитая грузинская открытая лепешка-лодочка с расплавленным тягучим сыром Сулугуни, сливочным маслом и жидким яичным желтком.", "Грузинская", "Выпечка", "45 мин", "Средне", 330, listOf("https://www.themealdb.com/images/media/meals/sywswr1511383814.jpg", "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600"), listOf("Тесто дрожжевое - 400 г", "Сыр Сулугуни или Моцарелла - 350 г", "Яйца - 2 шт.", "Сливочное масло - 30 г"), listOf("Раскатайте тесто в овал, сверните края трубочками к центру и защипните концы, формируя лодочку.", "Натрите сыр на крупной терке, смешайте с двумя ложками воды и щедро наполните лодочку.", "Выпекайте в разогретой до 200°C духовке в течение 20 минут до румяных бортиков.", "Выньте хачапури, сделайте углубление в сыре, влейте желток и верните в духовку еще на 2 минуты.")),
        Recipe("loc_chkmeruli", "Чкмерули (Курица по-грузински в сливочно-чесночном соусе)", "Румяная зажаренная курица, томленная в густом соусе из сливок, чеснока, кинзы и грузинских пряностей хмели-сунели.", "Грузинская", "Вторые блюда", "40 мин", "Легко", 290, listOf("https://www.themealdb.com/images/media/meals/qtuwxu1468233098.jpg", "https://images.unsplash.com/photo-1598515214211-89d3c73ae83b?w=600"), listOf("Курица - 700 г", "Сливки или молоко - 250 мл", "Чеснок - 5 зубчиков", "Кинза и петрушка - 1 пучок", "Хмели-сунели - 1 ч.л."), listOf("Натрите кусочки курицы солью и обжарьте на сковороде под гнетом по 8 минут с каждой стороны до золотистой корочки.", "Измельчите чеснок и зелень, смешайте с теплыми сливками и пряностью хмели-сунели.", "Залейте курицу сливочно-чесночным соусом и томите на слабом огне 12 минут.")),
        Recipe("loc_ramen", "Японский домашний Рамен с курицей, лапшой и яйцом", "Насыщенный японский суп с пшеничной лапшой, пряным соево-чесночным бульоном, ломтиками курицы, грибами и яйцом всмятку.", "Японская", "Супы", "35 мин", "Легко", 230, listOf("https://www.themealdb.com/images/media/meals/swttys1511385853.jpg", "https://images.unsplash.com/photo-1569718212165-3a8278d5f624?w=600"), listOf("Курица (филе) - 350 г", "Лапша пшеничная или рамен - 200 г", "Яйца - 2 шт.", "Грибы шампиньоны - 150 г", "Соевый соус - 3 ст.л."), listOf("Сварите куриное филе в 1 литре воды с добавлением соевого соуса, чеснока и ломтиков грибов в течение 20 минут.", "Отдельно отварите яйца всмятку ровно 6 минут после закипания, остудите в холодной воде и очистите.", "Отварите лапшу в течение 4 минут, разложите по глубоким мискам, залейте горячим бульоном и украсьте ломтиками курицы и половинками яйца.")),
        Recipe("loc_teriyaki", "Курица Терияки с кунжутом и рассыпчатым рисом", "Быстрое японское блюдо: сочные кусочки курицы, карамелизированные в густом сладко-соленом соусе терияки с рассыпчатым рисом.", "Японская", "Вторые блюда", "25 мин", "Легко", 245, listOf("https://www.themealdb.com/images/media/meals/wvpsxx1468256321.jpg", "https://www.themealdb.com/images/media/meals/tytyxu1515363282.jpg"), listOf("Курица (филе или бедра) - 500 г", "Рис - 200 г", "Соевый соус - 4 ст.л.", "Мед или сахар - 2 ст.л.", "Чеснок - 2 зубчика"), listOf("Отварите рис в подсоленной воде в течение 15 минут.", "Нарежьте курицу полосками и обжарьте на сильном огне в течение 7 минут до румяности.", "Влейте смесь соевого соуса, меда и чеснока, карамелизуйте курицу еще 4 минуты и подавайте с рисом.")),
        Recipe("loc_quesadilla", "Мексиканская Кесадилья с курицей, сыром и томатами", "Хрустящие пшеничные лепешки тортильи с горячей начинкой из обжаренного куриного филе, сладкой кукурузы, томатов и расплавленного сыра.", "Мексиканская", "Вторые блюда", "20 мин", "Легко", 290, listOf("https://www.themealdb.com/images/media/meals/uvuyxu1503067369.jpg", "https://www.themealdb.com/images/media/meals/ypxvwv1505333929.jpg"), listOf("Курица (филе) - 300 г", "Сыр твердый - 150 г", "Томаты - 2 шт.", "Лепешки тортильи или лаваш - 4 шт.", "Кукуруза консервированная - 100 г"), listOf("Мелко нарежьте куриное филе и обжарьте на сковороде с кубиками томатов и кукурузой в течение 8 минут.", "На половину каждой лепешки выложите слой тертого сыра, горячую куриную начинку и еще немного сыра, сложите пополам.", "Обжарьте сложенные кесадильи на сухой сковороде по 3 минуты с каждой стороны до хрустящей корочки и расплавления сыра.")),
        Recipe("loc_tomyum", "Тайский суп Том Ям с креветками, грибами и кокосовым молоком", "Знаменитый кисло-острый суп Таиланда на нежном кокосовом молоке с креветками, шампиньонами, томатами черри и свежим соком лайма.", "Тайская", "Супы", "25 мин", "Средне", 175, listOf("https://www.themealdb.com/images/media/meals/1529445434.jpg", "https://images.unsplash.com/photo-1548943487-a2e4e43b4853?w=600"), listOf("Креветки или курица - 300 г", "Грибы шампиньоны - 150 г", "Кокосовое молоко или сливки - 200 мл", "Томаты черри - 100 г", "Лимон или лайм - 1 шт."), listOf("Доведите 500 мл бульона до кипения, добавьте нарезанные грибы и варите 7 минут.", "Добавьте очищенные креветки, половинки томатов черри и влейте кокосовое молоко, варите еще 5 минут.", "Приправьте соком лайма и подавайте горячим с отварным рисом.")),
        Recipe("loc_tikka", "Индийская Курица Тикка Масала в пряном томатно-сливочном соусе", "Ароматное блюдо индийской кухни: мягкие кусочки куриного филе, тушенные в бархатистом соусе из сливок, томатов, чеснока и специй карри.", "Индийская", "Вторые блюда", "35 мин", "Легко", 260, listOf("https://www.themealdb.com/images/media/meals/wyxwsp1486979827.jpg", "https://www.themealdb.com/images/media/meals/qptpvt1487339892.jpg"), listOf("Курица (филе) - 500 г", "Сливки или сметана - 200 мл", "Томатный соус - 4 ст.л.", "Чеснок - 3 зубчика", "Карри и паприка - 2 ч.л."), listOf("Нарежьте куриное филе кубиками и обжарьте со специями карри и чесноком в течение 8 минут.", "Добавьте томатный соус и теплые сливки, перемешайте и тушите на слабом огне 15 минут до густоты.", "Подавайте ароматную курицу с рассыпчатым рисом или теплыми лепешками.")),
        Recipe("loc_chinese_chicken", "Курица в кисло-сладком соусе с болгарским перцем по-китайски", "Яркое блюдо китайской кухни на воке: сочные кусочки куриного филе, обжаренные с хрустящим сладким перцем в густом кисло-сладком соусе.", "Китайская", "Вторые блюда", "25 мин", "Легко", 255, listOf("https://www.themealdb.com/images/media/meals/1525873040.jpg", "https://www.themealdb.com/images/media/meals/1529444830.jpg"), listOf("Курица (филе) - 500 г", "Перец болгарский - 2 шт.", "Соевый соус - 3 ст.л.", "Томатный соус - 2 ст.л.", "Крахмал - 2 ст.л.", "Мед или сахар - 1 ст.л."), listOf("Нарежьте куриное филе кубиками, обваляйте в крахмале и быстро обжарьте на сильном огне 7 минут.", "Добавьте крупно нарезанный болгарский перец и обжаривайте еще 4 минуты.", "Влейте смесь соевого соуса, томатного соуса и меда, прогрейте 3 минуты до карамельной глазури."))
    )
}
