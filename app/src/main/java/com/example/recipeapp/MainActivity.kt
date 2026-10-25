package com.example.recipeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

// 1. Модель данных рецепта
data class Recipe(
    val id: String,
    val title: String,
    val cuisine: String,
    val category: String,
    val cookingTime: String,
    val difficulty: String,
    val calories: Int,
    val imageUrls: List<String>,
    val ingredients: List<String>,
    val instructions: List<String>
)

// Режимы работы приложения
enum class DataSourceMode(val label: String) {
    LOCAL("📚 База (РФ)"),
    MEAL_DB_API("🌍 Мировая БД (RU)"),
    FREE_AI("✨ Умный ИИ (RU)")
}

// Данные таймера
data class ParsedTimer(val durationSeconds: Int, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecipeAppScreen()
                }
            }
        }
    }
}

// Умное сопоставление ингредиентов с учетом русских окончаний
fun isIngredientInFridge(recipeIngredient: String, fridgeItems: List<String>): Boolean {
    val cleanRecipeIng = recipeIngredient.lowercase()
    return fridgeItems.any { fridgeItem ->
        val cleanFridge = fridgeItem.trim().lowercase()
        if (cleanFridge.isEmpty()) return@any false
        val stem = when {
            cleanFridge.length >= 5 -> cleanFridge.dropLast(2)
            cleanFridge.length == 4 -> cleanFridge.dropLast(1)
            else -> cleanFridge
        }
        cleanRecipeIng.contains(stem)
    }
}

// Извлечение времени из текста шага для таймера
fun extractTimerInfo(stepText: String): ParsedTimer? {
    val regex = Regex("(\\d+)\\s*(мин(?:ут[а-хей]?)?|час(?:[а-ов]?)?|сек(?:унд[а-хей]?)?)", RegexOption.IGNORE_CASE)
    val match = regex.find(stepText) ?: return null
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

fun playAlarmSound() {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
    } catch (_: Exception) {}
}

// Преобразование сокращений для живого человеческого произношения
fun humanizeCookingText(text: String): String {
    return text
        .replace(Regex("(\\d+)\\s*-\\s*(\\d+)"), "$1 или $2")
        .replace(Regex("(?i)\\bст\\.?\\s*л\\.?"), "столовых ложек")
        .replace(Regex("(?i)\\bч\\.?\\s*л\\.?"), "чайных ложек")
        .replace(Regex("(?i)(\\d+)\\s*г\\b\\.?"), "$1 граммов")
        .replace(Regex("(?i)(\\d+)\\s*гр\\b\\.?"), "$1 граммов")
        .replace(Regex("(?i)(\\d+)\\s*кг\\b\\.?"), "$1 килограммов")
        .replace(Regex("(?i)(\\d+)\\s*мл\\b\\.?"), "$1 миллилитров")
        .replace(Regex("(?i)(\\d+)\\s*л\\b\\.?"), "$1 литров")
        .replace(Regex("(?i)(\\d+)\\s*шт\\.?"), "$1 штуки")
        .replace(Regex("(?i)(\\d+)\\s*мин\\.?"), "$1 минут")
        .replace(Regex("(?i)(\\d+)\\s*ч\\b\\.?"), "$1 час")
        .replace("°C", " градусов Цельсия")
        .replace("°", " градусов")
        .replace(" - ", ", ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("chef_app_prefs", Context.MODE_PRIVATE) }

    // =========================================================================
    // НАДЕЖНЫЙ СИНТЕЗАТОР РЕЧИ (TTS) С ПРОВЕРКОЙ ИСТОЧНИКА И ГРОМКОСТИ
    // =========================================================================
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var currentlySpeakingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ruLocale = Locale("ru", "RU")
                val langResult = ttsInstance?.setLanguage(ruLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsInstance?.setLanguage(Locale.getDefault())
                }

                // Безопасный выбор лучшего установленного голоса
                try {
                    val bestVoice = ttsInstance?.voices?.filter { voice ->
                        voice.locale.language == "ru" &&
                        !voice.isNetworkConnectionRequired &&
                        (voice.features == null || !voice.features.contains("notInstalled"))
                    }?.maxByOrNull { it.quality }

                    if (bestVoice != null) {
                        ttsInstance?.voice = bestVoice
                    }
                } catch (_: Exception) {}

                ttsInstance?.setSpeechRate(0.95f)
                ttsInstance?.setPitch(1.0f)

                ttsInstance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { currentlySpeakingId = utteranceId }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (currentlySpeakingId == utteranceId) {
                                currentlySpeakingId = null
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { currentlySpeakingId = null }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { currentlySpeakingId = null }
                    }
                })
                isTtsReady = true
            } else {
                mainHandler.post {
                    Toast.makeText(context, "Голосовой движок не инициализирован в системе", Toast.LENGTH_SHORT).show()
                }
            }
        }
        ttsEngine = ttsInstance

        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    fun speakHumanized(utteranceId: String, rawText: String) {
        val engine = ttsEngine
        if (engine == null || !isTtsReady) {
            Toast.makeText(context, "Подождите секунду, голосовой движок загружается...", Toast.LENGTH_SHORT).show()
            return
        }

        // Если уже читаем этот же блок — останавливаем
        if (currentlySpeakingId == utteranceId) {
            engine.stop()
            currentlySpeakingId = null
            return
        }

        // Проверяем, не выключен ли звук мультимедиа на телефоне
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Toast.makeText(context, "🔊 Прибавьте громкость мультимедиа на телефоне!", Toast.LENGTH_LONG).show()
        }

        engine.stop()
        currentlySpeakingId = utteranceId

        val cleanSpeech = humanizeCookingText(rawText)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val res = engine.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res == TextToSpeech.ERROR) {
            currentlySpeakingId = null
            Toast.makeText(context, "Не удалось запустить озвучку. Проверьте настройки TTS в телефоне.", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
        currentlySpeakingId = null
    }

    // Списки фильтров
    val cuisines = listOf("Все", "Русская", "Корейская", "Французская", "Итальянская", "Японская", "Грузинская", "Мексиканская")
    val categories = listOf("Все", "Супы", "Каши и завтраки", "Вторые блюда", "Выпечка", "Что-то к чаю", "Салаты", "Паста и пицца")
    val quickFridgeSuggestions = listOf("Курица", "Яйца", "Картофель", "Сыр", "Томаты", "Молоко", "Рис", "Фарш", "Творог", "Капуста", "Грибы")

    // Состояния фильтров
    var selectedMode by remember { mutableStateOf(DataSourceMode.LOCAL) }
    var selectedCuisine by remember { mutableStateOf("Все") }
    var selectedCategory by remember { mutableStateOf("Все") }
    var limitCount by remember { mutableFloatStateOf(10f) }
    var showOnlyFavorites by remember { mutableStateOf(false) }
    var showShoppingListScreen by remember { mutableStateOf(false) }
    var isFiltersExpanded by remember { mutableStateOf(true) }

    // Состояния с автосохранением в память телефона
    var ingredientInput by remember { mutableStateOf("") }
    var lastVoiceCommand by remember { mutableStateOf<String?>(null) }

    val fridgeItems = remember {
        mutableStateListOf<String>().apply {
            prefs.getStringSet("fridge", emptySet())?.let { addAll(it) }
        }
    }
    val shoppingListItems = remember {
        mutableStateListOf<String>().apply {
            prefs.getStringSet("cart", emptySet())?.let { addAll(it) }
        }
    }
    val checkedShoppingItems = remember { mutableStateListOf<String>() }
    var favoriteIds by remember {
        mutableStateOf(prefs.getStringSet("favs", emptySet()) ?: emptySet())
    }

    fun savePrefs() {
        prefs.edit()
            .putStringSet("fridge", fridgeItems.toSet())
            .putStringSet("cart", shoppingListItems.toSet())
            .putStringSet("favs", favoriteIds)
            .apply()
    }

    // Основной список рецептов
    var recipes by remember {
        mutableStateOf(buildLocalRussianRecipes("Все", "Все", emptyList(), 10))
    }
    var isLoading by remember { mutableStateOf(false) }

    // Универсальная функция загрузки и фильтрации рецептов (РАБОТАЕТ ВО ВСЕХ 3 РЕЖИМАХ)
    fun loadRecipes(
        modeToUse: DataSourceMode = selectedMode,
        cuisineToUse: String = selectedCuisine,
        categoryToUse: String = selectedCategory,
        voiceText: String? = lastVoiceCommand,
        collapseHeader: Boolean = false
    ) {
        stopSpeaking()
        if (collapseHeader) isFiltersExpanded = false
        val targetCount = limitCount.roundToInt()
        val currentFridge = fridgeItems.toList()

        coroutineScope.launch {
            isLoading = true
            try {
                recipes = when (modeToUse) {
                    DataSourceMode.LOCAL -> {
                        buildLocalRussianRecipes(cuisineToUse, categoryToUse, currentFridge, targetCount)
                    }
                    DataSourceMode.MEAL_DB_API -> {
                        fetchTranslatedMealDbRecipes(cuisineToUse, categoryToUse, currentFridge, targetCount)
                    }
                    DataSourceMode.FREE_AI -> {
                        fetchAiRussianRecipes(cuisineToUse, categoryToUse, currentFridge, voiceText, targetCount)
                    }
                }
            } catch (_: Exception) {
                // Безотказный резерв: никогда не оставляем пользователя с пустым экраном или ошибкой
                recipes = buildLocalRussianRecipes(cuisineToUse, categoryToUse, currentFridge, targetCount)
            } finally {
                isLoading = false
            }
        }
    }

    // Автообновление в режиме локальной базы при любом клике по фильтрам
    LaunchedEffect(selectedCuisine, selectedCategory, limitCount.roundToInt(), fridgeItems.size, selectedMode) {
        if (selectedMode == DataSourceMode.LOCAL) {
            recipes = buildLocalRussianRecipes(
                selectedCuisine,
                selectedCategory,
                fridgeItems.toList(),
                limitCount.roundToInt()
            )
        }
    }

    fun addIngredientsFromInput() {
        val parts = ingredientInput.split(",", ";")
            .map { it.trim().replaceFirstChar { ch -> ch.uppercase() } }
            .filter { it.isNotEmpty() && !fridgeItems.contains(it) }
        if (parts.isNotEmpty()) {
            fridgeItems.addAll(parts)
            savePrefs()
        }
        ingredientInput = ""
    }

    // Разбор голосовой команды
    fun processVoiceCommand(spokenText: String) {
        lastVoiceCommand = spokenText
        val lower = spokenText.lowercase()

        val cuisineRoots = mapOf(
            "русск" to "Русская", "корейск" to "Корейская", "французск" to "Французская",
            "итальянск" to "Итальянская", "японск" to "Японская", "грузинск" to "Грузинская", "мексиканск" to "Мексиканская"
        )
        cuisineRoots.forEach { (root, name) -> if (lower.contains(root)) selectedCuisine = name }

        val categoryRoots = mapOf(
            "суп" to "Супы", "бульон" to "Супы", "каш" to "Каши и завтраки", "завтрак" to "Каши и завтраки",
            "выпечк" to "Выпечка", "пирог" to "Выпечка", "к чаю" to "Что-то к чаю", "десерт" to "Что-то к чаю",
            "сладк" to "Что-то к чаю", "салат" to "Салаты", "паст" to "Паста и пицца", "пицц" to "Паста и пицца",
            "второе" to "Вторые блюда", "ужин" to "Вторые блюда"
        )
        categoryRoots.forEach { (root, name) -> if (lower.contains(root)) selectedCategory = name }

        val stopWords = setOf(
            "приготовь", "приготовить", "сделай", "сделать", "подбери", "подобрать", "найди", "найти",
            "покажи", "хочу", "блюдо", "блюда", "рецепт", "рецепты", "из", "с", "со", "у", "меня",
            "есть", "в", "холодильнике", "дома", "и", "а", "или", "что", "нибудь", "мне", "пожалуйста",
            "на", "обед", "ужин", "завтрак", "суп", "салат", "второе", "десерт", "выпечка", "каша"
        )

        val normalizeMap = mapOf(
            "курицы" to "Курица", "курицу" to "Курица", "курочки" to "Курица", "курицей" to "Курица",
            "картошки" to "Картофель", "картошка" to "Картофель", "картофеля" to "Картофель", "картошкой" to "Картофель",
            "сыра" to "Сыр", "сыром" to "Сыр", "яиц" to "Яйца", "яйцо" to "Яйца", "яйцами" to "Яйца",
            "молока" to "Молоко", "молоком" to "Молоко", "риса" to "Рис", "рисом" to "Рис",
            "грибов" to "Грибы", "грибами" to "Грибы", "помидоров" to "Томаты", "помидоры" to "Томаты",
            "фарша" to "Фарш", "фаршем" to "Фарш", "творога" to "Творог", "творогом" to "Творог",
            "капусты" to "Капуста", "капустой" to "Капуста", "говядины" to "Говядина", "свинины" to "Свинина"
        )

        val extracted = lower
            .replace(Regex("[^а-яёa-z\\s-]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 2 && !stopWords.contains(it) }
            .filter { w -> cuisineRoots.keys.none { w.startsWith(it) } }
            .map { w -> normalizeMap[w] ?: w.replaceFirstChar { it.uppercase() } }
            .distinct()

        if (extracted.isNotEmpty()) {
            fridgeItems.clear()
            fridgeItems.addAll(extracted)
            savePrefs()
        }

        loadRecipes(selectedMode, selectedCuisine, selectedCategory, spokenText, collapseHeader = true)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                processVoiceCommand(spokenText)
            }
        }
    }

    fun startVoiceRecognition() {
        stopSpeaking()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите, какие продукты есть дома или какое блюдо найти...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Голосовой ввод Google не установлен на устройстве", Toast.LENGTH_LONG).show()
        }
    }

    val displayedRecipes = remember(recipes, showOnlyFavorites, favoriteIds) {
        if (showOnlyFavorites) recipes.filter { favoriteIds.contains(it.id) } else recipes
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // Верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Шеф-Повар 🍲", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentlySpeakingId != null) {
                    AssistChip(
                        onClick = { stopSpeaking() },
                        label = { Text("Стоп 🔇", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    )
                }
                FilterChip(
                    selected = showShoppingListScreen,
                    onClick = { showShoppingListScreen = !showShoppingListScreen },
                    label = { Text("🛒 (${shoppingListItems.size})", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = showOnlyFavorites,
                    onClick = {
                        showOnlyFavorites = !showOnlyFavorites
                        if (showShoppingListScreen) showShoppingListScreen = false
                    },
                    label = { Text("❤️ (${favoriteIds.size})", fontSize = 12.sp) }
                )
                if (!showShoppingListScreen) {
                    IconButton(onClick = { isFiltersExpanded = !isFiltersExpanded }) {
                        Icon(
                            imageVector = if (isFiltersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Свернуть фильтры"
                        )
                    }
                }
            }
        }

        // Кнопка быстрого разворота фильтров, когда шапка свернута
        if (!isFiltersExpanded && !showShoppingListScreen) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { isFiltersExpanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔍 ${selectedMode.label} • $selectedCuisine • $selectedCategory (${displayedRecipes.size} блюд)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text("Изменить ▼", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (showShoppingListScreen) {
            Spacer(modifier = Modifier.height(6.dp))
            ShoppingListView(
                shoppingItems = shoppingListItems,
                checkedItems = checkedShoppingItems,
                onUpdatePrefs = { savePrefs() },
                onBackToRecipes = { showShoppingListScreen = false }
            )
        } else {
            AnimatedVisibility(visible = isFiltersExpanded) {
                Column {
                    // Переключатель источника: при клике сразу загружает данные!
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(DataSourceMode.entries) { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    loadRecipes(mode, selectedCuisine, selectedCategory)
                                },
                                label = { Text(mode.label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Блок холодильника + голосовой поиск
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🧊 Продукты в холодильнике:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (fridgeItems.isNotEmpty() || lastVoiceCommand != null) {
                                    Text(
                                        text = "Очистить всё",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            fridgeItems.clear()
                                            lastVoiceCommand = null
                                            savePrefs()
                                            loadRecipes()
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = ingredientInput,
                                    onValueChange = { ingredientInput = it },
                                    placeholder = { Text("Курица, рис... или нажми 🎤", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { addIngredientsFromInput() })
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FilledIconButton(
                                    onClick = { addIngredientsFromInput() },
                                    enabled = ingredientInput.isNotBlank()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                FilledIconButton(
                                    onClick = { startVoiceRecognition() },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Голосовой подбор")
                                }
                            }

                            if (lastVoiceCommand != null) {
                                Text(
                                    text = "🗣 Запрос: «$lastVoiceCommand»",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            if (fridgeItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(fridgeItems) { item ->
                                        InputChip(
                                            selected = true,
                                            onClick = {
                                                fridgeItems.remove(item)
                                                savePrefs()
                                            },
                                            label = { Text(item, fontSize = 11.sp) },
                                            trailingIcon = {
                                                Icon(Icons.Default.Clear, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                            }
                                        )
                                    }
                                }
                            }

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                                items(quickFridgeSuggestions) { suggestion ->
                                    val isSelected = fridgeItems.contains(suggestion)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (isSelected) fridgeItems.remove(suggestion)
                                            else fridgeItems.add(suggestion)
                                            savePrefs()
                                        },
                                        label = { Text(if (isSelected) "✓ $suggestion" else "+ $suggestion", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Фильтр: Кухня мира
                    Text("Кухня мира:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(cuisines) { cuisine ->
                            FilterChip(
                                selected = selectedCuisine == cuisine,
                                onClick = {
                                    selectedCuisine = cuisine
                                    if (selectedMode != DataSourceMode.LOCAL) {
                                        loadRecipes(selectedMode, cuisine, selectedCategory)
                                    }
                                },
                                label = { Text(cuisine, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Фильтр: Категория
                    Text("Тип блюда:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = category
                                    if (selectedMode != DataSourceMode.LOCAL) {
                                        loadRecipes(selectedMode, selectedCuisine, category)
                                    }
                                },
                                label = { Text(category, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Слайдер количества (5 - 20) + Кнопка обновления
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Количество рецептов: ${limitCount.roundToInt()}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Slider(
                                value = limitCount,
                                onValueChange = { limitCount = it },
                                valueRange = 5f..20f,
                                steps = 14
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { loadRecipes(collapseHeader = true) },
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Подобрать", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Подобрать")
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            when (selectedMode) {
                                DataSourceMode.FREE_AI -> "✨ ИИ составляет рецепты на русском языке..."
                                DataSourceMode.MEAL_DB_API -> "🌍 Загружаем и переводим рецепты на русский..."
                                DataSourceMode.LOCAL -> "Подбираем рецепты..."
                            }
                        )
                    }
                }
            } else if (displayedRecipes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Список избранного пока пуст ❤️")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showOnlyFavorites = false }) {
                            Text("Показать все рецепты")
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(
                        items = displayedRecipes,
                        key = { _, item -> item.id }
                    ) { index, recipe ->
                        RecipeCard(
                            index = index + 1,
                            recipe = recipe,
                            fridgeItems = fridgeItems.toList(),
                            shoppingListItems = shoppingListItems,
                            isFavorite = favoriteIds.contains(recipe.id),
                            currentlySpeakingId = currentlySpeakingId,
                            onSpeakText = { utteranceId, text -> speakHumanized(utteranceId, text) },
                            onAddMissingToCart = { missingItems ->
                                var addedCount = 0
                                missingItems.forEach { item ->
                                    if (!shoppingListItems.contains(item)) {
                                        shoppingListItems.add(item)
                                        addedCount++
                                    }
                                }
                                savePrefs()
                                Toast.makeText(
                                    context,
                                    if (addedCount > 0) "Добавлено в список покупок: $addedCount шт. 🛒"
                                    else "Все эти продукты уже в списке покупок!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onToggleFavorite = {
                                favoriteIds = if (favoriteIds.contains(recipe.id)) {
                                    favoriteIds - recipe.id
                                } else {
                                    favoriteIds + recipe.id
                                }
                                savePrefs()
                            }
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// ЭКРАН "СПИСОК ПОКУПОК"
// =========================================================================
@Composable
fun ShoppingListView(
    shoppingItems: MutableList<String>,
    checkedItems: MutableList<String>,
    onUpdatePrefs: () -> Unit,
    onBackToRecipes: () -> Unit
) {
    val context = LocalContext.current
    var newItemText by remember { mutableStateOf("") }

    fun addCustomItem() {
        val trimmed = newItemText.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isNotEmpty() && !shoppingItems.contains(trimmed)) {
            shoppingItems.add(trimmed)
            onUpdatePrefs()
            newItemText = ""
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToRecipes) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                Spacer(modifier = Modifier.width(4.dp))
                Text("К рецептам")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shoppingItems.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            val shareText = buildString {
                                append("🛒 Мой список покупок (Шеф-Повар):\n\n")
                                shoppingItems.forEachIndexed { index, item ->
                                    val mark = if (checkedItems.contains(item)) "✅" else "▫️"
                                    append("${index + 1}. $mark $item\n")
                                }
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Отправить список покупок"))
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Поделиться", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Поделиться", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            shoppingItems.clear()
                            checkedItems.clear()
                            onUpdatePrefs()
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Очистить", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItemText,
                onValueChange = { newItemText = it },
                placeholder = { Text("Добавить свой товар в список...", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addCustomItem() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = { addCustomItem() },
                enabled = newItemText.isNotBlank()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (shoppingItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒 Список покупок пуст", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Нажмите кнопку «Добавить недостающие в список покупок» в карточке любого рецепта!",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
        } else {
            Text(
                text = "Куплено: ${checkedItems.size} из ${shoppingItems.size}",
                fontSize = 13.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shoppingItems, key = { it }) { item ->
                    val isChecked = checkedItems.contains(item)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChecked)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) checkedItems.remove(item)
                                    else checkedItems.add(item)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (it) checkedItems.add(item)
                                        else checkedItems.remove(item)
                                    }
                                )
                                Text(
                                    text = item,
                                    fontSize = 15.sp,
                                    textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (isChecked) Color.Gray else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = {
                                shoppingItems.remove(item)
                                checkedItems.remove(item)
                                onUpdatePrefs()
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Удалить", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// КАРТОЧКА РЕЦЕПТА
// =========================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipeCard(
    index: Int,
    recipe: Recipe,
    fridgeItems: List<String>,
    shoppingListItems: List<String>,
    isFavorite: Boolean,
    currentlySpeakingId: String?,
    onSpeakText: (String, String) -> Unit,
    onAddMissingToCart: (List<String>) -> Unit,
    onToggleFavorite: () -> Unit
) {
    val checkedIngredients = remember(recipe.id, fridgeItems) {
        mutableStateListOf<String>().apply {
            recipe.ingredients.forEach { ing ->
                if (isIngredientInFridge(ing, fridgeItems)) add(ing)
            }
        }
    }

    val missingIngredients = recipe.ingredients.filter { !checkedIngredients.contains(it) }
    val matchedCount = recipe.ingredients.count { isIngredientInFridge(it, fridgeItems) }
    val needToBuyCount = (recipe.ingredients.size - matchedCount).coerceAtLeast(0)
    val fullRecipeUtteranceId = "full_${recipe.id}"
    val isSpeakingFullRecipe = currentlySpeakingId == fullRecipeUtteranceId

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#$index. ${recipe.title}",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Избранное",
                        tint = if (isFavorite) Color.Red else Color.Gray
                    )
                }
            }

            // Кнопка озвучки всего рецепта
            FilledTonalButton(
                onClick = {
                    val fullSpeech = buildString {
                        append("Рецепт номер $index: ${recipe.title}. ")
                        append("Кухня: ${recipe.cuisine}. Категория: ${recipe.category}. ")
                        append("Время приготовления: ${recipe.cookingTime}. ")
                        append("Ингредиенты: ")
                        append(recipe.ingredients.joinToString(". "))
                        append(". Способ приготовления: ")
                        recipe.instructions.forEachIndexed { i, step ->
                            append("Шаг ${i + 1}. $step. ")
                        }
                        append("Приятного аппетита!")
                    }
                    onSpeakText(fullRecipeUtteranceId, fullSpeech)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (isSpeakingFullRecipe) Icons.Default.Stop else Icons.Default.VolumeUp,
                    contentDescription = "Озвучить рецепт"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSpeakingFullRecipe) "⏹ Остановить озвучку" else "🔊 Озвучить весь рецепт голосом")
            }

            if (fridgeItems.isNotEmpty()) {
                Surface(
                    color = if (needToBuyCount == 0) Color(0xFFE8F5E9) else Color(0xFFFFF8E1),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (needToBuyCount == 0)
                            "🎉 Все продукты есть дома! ($matchedCount из ${recipe.ingredients.size})"
                        else
                            "🧊 Дома есть: $matchedCount из ${recipe.ingredients.size} • 🛒 Докупить: $needToBuyCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (needToBuyCount == 0) Color(0xFF2E7D32) else Color(0xFFF57F17),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⏱ ${recipe.cookingTime}", fontSize = 12.sp, color = Color.Gray)
                Text("🌍 ${recipe.cuisine}", fontSize = 12.sp, color = Color.Gray)
                Text("🍽 ${recipe.category}", fontSize = 12.sp, color = Color.Gray)
                Text("🔥 ${recipe.calories} ккал", fontSize = 12.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Карусель фотографий
            val safeImages = recipe.imageUrls.ifEmpty {
                listOf("https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=600")
            }
            val pagerState = rememberPagerState(pageCount = { safeImages.size })

            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    AsyncImage(
                        model = safeImages[page],
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.LightGray.copy(alpha = 0.3f))
                    )
                }
                // Счетчик фото и точки карусели
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Text(
                        text = "📷 ${pagerState.currentPage + 1}/${safeImages.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (safeImages.size > 1) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(safeImages.size) { iteration ->
                            val color = if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.5f)
                            Box(modifier = Modifier.clip(CircleShape).background(color).size(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Ингредиенты:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            recipe.ingredients.forEach { ingredient ->
                val isChecked = checkedIngredients.contains(ingredient)
                val inFridge = isIngredientInFridge(ingredient, fridgeItems)
                val inCart = shoppingListItems.contains(ingredient)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isChecked) checkedIngredients.remove(ingredient)
                            else checkedIngredients.add(ingredient)
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                if (it) checkedIngredients.add(ingredient)
                                else checkedIngredients.remove(ingredient)
                            }
                        )
                        Text(
                            text = ingredient,
                            fontSize = 14.sp,
                            textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                        )
                    }
                    when {
                        inFridge -> Text("✅ Дома", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                        inCart -> Text("🛒 В списке", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (missingIngredients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onAddMissingToCart(missingIngredients) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = "В корзину", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Добавить недостающие в список покупок (${missingIngredients.size})")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Способ приготовления (нажми на шаг для озвучки 🔈):",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            recipe.instructions.forEachIndexed { stepIndex, step ->
                val stepUtteranceId = "step_${recipe.id}_$stepIndex"
                val isStepSpeaking = currentlySpeakingId == stepUtteranceId
                val timerInfo = remember(step) { extractTimerInfo(step) }

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Surface(
                        color = if (isStepSpeaking) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSpeakText(stepUtteranceId, "Шаг ${stepIndex + 1}. $step") }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${stepIndex + 1}. $step",
                                fontSize = 14.sp,
                                fontWeight = if (isStepSpeaking) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (isStepSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                contentDescription = "Озвучить шаг",
                                tint = if (isStepSpeaking) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(18.dp).padding(start = 4.dp)
                            )
                        }
                    }

                    if (timerInfo != null) {
                        StepTimerWidget(timerInfo = timerInfo)
                    }
                }
            }
        }
    }
}

// =========================================================================
// ИНТЕРАКТИВНЫЙ ТАЙМЕР ШАГА
// =========================================================================
@Composable
fun StepTimerWidget(timerInfo: ParsedTimer) {
    var timeLeft by remember(timerInfo) { mutableIntStateOf(timerInfo.durationSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, timeLeft) {
        if (isRunning && timeLeft > 0) {
            delay(1000L)
            timeLeft -= 1
            if (timeLeft == 0) {
                isRunning = false
                isFinished = true
                playAlarmSound()
            }
        }
    }

    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Surface(
        color = when {
            isFinished -> Color(0xFFE8F5E9)
            isRunning -> Color(0xFFFFF8E1)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "Таймер",
                    tint = if (isFinished) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isFinished -> "✅ Готово! (${timerInfo.label})"
                        isRunning -> "⏳ Осталось: $timeFormatted"
                        else -> "⏱ Таймер на ${timerInfo.label}"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFinished) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isFinished) {
                    FilledTonalButton(
                        onClick = { isRunning = !isRunning },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Старт",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRunning) "Пауза" else "Старт", fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        isRunning = false
                        isFinished = false
                        timeLeft = timerInfo.durationSeconds
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Сброс", fontSize = 11.sp)
                }
            }
        }
    }
}

// =========================================================================
// БЕСПЛАТНЫЙ АВТОПЕРЕВОДЧИК НА РУССКИЙ ЯЗЫК (ДЛЯ МИРОВОЙ БАЗЫ API)
// =========================================================================
fun translateEnToRu(text: String): String {
    if (text.isBlank()) return text
    return try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = 8000
            readTimeout = 8000
        }
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val outerArray = JSONArray(response).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until outerArray.length()) {
            sb.append(outerArray.getJSONArray(i).getString(0))
        }
        sb.toString()
    } catch (_: Exception) {
        text
    }
}

// =========================================================================
// СЕТЕВОЙ СЛОЙ 1: НАДЕЖНЫЙ POST-ЗАПРОС К БЕСПЛАТНОМУ ИИ + ЗАЩИТНЫЙ ГЕНЕРАТОР
// =========================================================================
suspend fun fetchAiRussianRecipes(
    cuisine: String,
    category: String,
    fridgeItems: List<String>,
    voicePrompt: String?,
    count: Int
): List<Recipe> = withContext(Dispatchers.IO) {
    try {
        val fridgeText = if (fridgeItems.isNotEmpty()) {
            "Используй продукты из холодильника: ${fridgeItems.joinToString(", ")}."
        } else ""
        val voiceText = if (!voicePrompt.isNullOrBlank()) "Запрос пользователя: $voicePrompt." else ""

        val userPrompt = """
            Создай ровно $count разных рецептов блюд строго на русском языке.
            Кухня: ${if (cuisine == "Все") "разные кухни мира" else cuisine}.
            Категория: ${if (category == "Все") "разные категории" else category}.
            $fridgeText $voiceText
            В каждом шаге instructions обязательно пиши время в минутах (например: "Варите 15 минут").
            Ответь ТОЛЬКО валидным JSON массивом в формате:
            [{"title":"Название","cuisine":"Кухня","category":"Категория","cookingTime":"35 мин","difficulty":"Легко","calories":250,"englishName":"dish photo","ingredients":["Продукт - 200 г"],"instructions":["Шаг 1. Варите 15 минут."]}]
        """.trimIndent()

        val payload = JSONObject().apply {
            put("model", "openai")
            put("jsonMode", true)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", "Отвечай только чистым JSON массивом рецептов на русском языке."))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val url = URL("https://text.pollinations.ai/")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            connectTimeout = 20000
            readTimeout = 25000
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }

        val startBracket = responseText.indexOf('[')
        val endBracket = responseText.lastIndexOf(']')
        if (startBracket != -1 && endBracket > startBracket) {
            val cleanArrayStr = responseText.substring(startBracket, endBracket + 1)
            val jsonArray = JSONArray(cleanArrayStr)
            val aiResult = mutableListOf<Recipe>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawEng = obj.optString("englishName", "delicious cooked meal")
                val safeEng = URLEncoder.encode(rawEng, "UTF-8").replace("+", "%20")

                val img1 = "https://image.pollinations.ai/prompt/$safeEng%20food%20photography?width=600&height=400&nologo=true&seed=${i + 11}"
                val img2 = "https://image.pollinations.ai/prompt/$safeEng%20restaurant%20dish?width=600&height=400&nologo=true&seed=${i + 77}"

                val ingArr = obj.optJSONArray("ingredients") ?: JSONArray()
                val ingList = List(ingArr.length()) { ingArr.getString(it) }

                val instArr = obj.optJSONArray("instructions") ?: JSONArray()
                val instList = List(instArr.length()) { instArr.getString(it) }

                aiResult.add(
                    Recipe(
                        id = "ai_${System.currentTimeMillis()}_$i",
                        title = obj.optString("title", "Авторское блюдо #${i + 1}"),
                        cuisine = obj.optString("cuisine", if (cuisine == "Все") "Авторская" else cuisine),
                        category = obj.optString("category", if (category == "Все") "Основное" else category),
                        cookingTime = obj.optString("cookingTime", "35 мин"),
                        difficulty = obj.optString("difficulty", "Средне"),
                        calories = obj.optInt("calories", 240),
                        imageUrls = listOf(img1, img2),
                        ingredients = ingList.ifEmpty { listOf("Основные ингредиенты по вкусу") },
                        instructions = instList.ifEmpty { listOf("Готовьте в течение 20 минут до готовности.") }
                    )
                )
            }
            if (aiResult.isNotEmpty()) {
                // Если ИИ сгенерировал меньше запрошенного, дополняем из умного русского генератора до точного числа count
                if (aiResult.size < count) {
                    val extra = buildLocalRussianRecipes(cuisine, category, fridgeItems, count - aiResult.size)
                    aiResult.addAll(extra)
                }
                return@withContext aiResult.take(count)
            }
        }
    } catch (_: Exception) {
        // Если сеть или сервер ИИ недоступен, используем встроенный генератор русских рецептов без выброса ошибки!
    }
    return@withContext buildLocalRussianRecipes(cuisine, category, fridgeItems, count)
}

// =========================================================================
// СЕТЕВОЙ СЛОЙ 2: МИРОВАЯ БАЗА TheMealDB С АВТОПЕРЕВОДОМ НА РУССКИЙ ЯЗЫК
// =========================================================================
suspend fun fetchTranslatedMealDbRecipes(
    cuisine: String,
    category: String,
    fridgeItems: List<String>,
    count: Int
): List<Recipe> = withContext(Dispatchers.IO) {
    try {
        val ingredientRuToEn = mapOf(
            "курица" to "chicken", "яйца" to "egg", "картофель" to "potatoes", "сыр" to "cheese",
            "томаты" to "tomatoes", "молоко" to "milk", "рис" to "rice", "фарш" to "beef",
            "говядина" to "beef", "свинина" to "pork", "грибы" to "mushrooms", "капуста" to "cabbage"
        )
        val areaMap = mapOf(
            "Русская" to "Russian", "Французская" to "French", "Итальянская" to "Italian",
            "Японская" to "Japanese", "Мексиканская" to "Mexican"
        )
        val categoryMap = mapOf(
            "Супы" to "Starter", "Каши и завтраки" to "Breakfast", "Вторые блюда" to "Beef",
            "Выпечка" to "Dessert", "Что-то к чаю" to "Dessert", "Салаты" to "Side", "Паста и пицца" to "Pasta"
        )

        val firstFridgeEn = fridgeItems.firstNotNullOfOrNull { ingredientRuToEn[it.trim().lowercase()] }

        val endpointUrl = when {
            firstFridgeEn != null -> "https://www.themealdb.com/api/json/v1/1/filter.php?i=$firstFridgeEn"
            cuisine != "Все" && areaMap.containsKey(cuisine) -> "https://www.themealdb.com/api/json/v1/1/filter.php?a=${areaMap[cuisine]}"
            category != "Все" && categoryMap.containsKey(category) -> "https://www.themealdb.com/api/json/v1/1/filter.php?c=${categoryMap[category]}"
            else -> "https://www.themealdb.com/api/json/v1/1/search.php?s="
        }

        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = 10000
            readTimeout = 10000
        }
        val listResponse = conn.inputStream.bufferedReader().use { it.readText() }
        val mealsArray = JSONObject(listResponse).optJSONArray("meals")

        if (mealsArray != null && mealsArray.length() > 0) {
            val result = mutableListOf<Recipe>()
            val limit = minOf(count, mealsArray.length())

            for (i in 0 until limit) {
                val mealSummary = mealsArray.getJSONObject(i)
                val mealId = mealSummary.getString("idMeal")

                val detailUrl = URL("https://www.themealdb.com/api/json/v1/1/lookup.php?i=$mealId")
                val detailResponse = detailUrl.openConnection().getInputStream().bufferedReader().use { it.readText() }
                val detailObj = JSONObject(detailResponse).getJSONArray("meals").getJSONObject(0)

                val enTitle = detailObj.optString("strMeal", "Dish")
                val enInstructions = detailObj.optString("strInstructions", "")

                val rawIngredients = mutableListOf<String>()
                for (idx in 1..15) {
                    val ing = detailObj.optString("strIngredient$idx", "").trim()
                    val measure = detailObj.optString("strMeasure$idx", "").trim()
                    if (ing.isNotEmpty() && ing != "null") {
                        rawIngredients.add("$ing ($measure)")
                    }
                }

                // Переводим название, ингредиенты и инструкцию на русский язык!
                val ruTitle = translateEnToRu(enTitle)
                val ruIngredients = translateEnToRu(rawIngredients.joinToString(" | "))
                    .split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val ruInstructions = translateEnToRu(enInstructions)
                    .split(Regex("(?<=[.!?])\\s+"))
                    .map { it.trim() }
                    .filter { it.length > 5 }
                    .take(8)

                val thumb = detailObj.optString("strMealThumb", "")
                val secondImg = "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=600"

                result.add(
                    Recipe(
                        id = "mealdb_$mealId",
                        title = ruTitle,
                        cuisine = if (cuisine != "Все") cuisine else "Мировая",
                        category = if (category != "Все") category else "Основное",
                        cookingTime = "${(25..55).random()} мин",
                        difficulty = "Средне",
                        calories = (210..420).random(),
                        imageUrls = listOf(thumb, secondImg),
                        ingredients = ruIngredients.ifEmpty { listOf("Ингредиенты по вкусу") },
                        instructions = ruInstructions.ifEmpty { listOf("Готовьте в течение 25 минут до полной готовности.") }
                    )
                )
            }
            if (result.size < count) {
                result.addAll(buildLocalRussianRecipes(cuisine, category, fridgeItems, count - result.size))
            }
            return@withContext result.take(count)
        }
    } catch (_: Exception) {}

    return@withContext buildLocalRussianRecipes(cuisine, category, fridgeItems, count)
}

// =========================================================================
// БОГАТАЯ БАЗА И УМНЫЙ КОНСТРУКТОР РУССКИХ РЕЦЕПТОВ (ГАРАНТИРУЕТ 5-20 БЛЮД)
// =========================================================================
fun buildLocalRussianRecipes(
    cuisine: String,
    category: String,
    fridgeItems: List<String>,
    count: Int
): List<Recipe> {
    val allBase = getRichRussianDatabase()

    // 1. Фильтруем по выбранной кухне и категории
    val filtered = allBase.filter { recipe ->
        val matchCuisine = cuisine == "Все" || recipe.cuisine.equals(cuisine, ignoreCase = true)
        val matchCategory = category == "Все" || recipe.category.equals(category, ignoreCase = true)
        matchCuisine && matchCategory
    }.sortedByDescending { recipe ->
        recipe.ingredients.count { isIngredientInFridge(it, fridgeItems) }
    }.toMutableList()

    // 2. Если нужно ровно от 5 до 20 вариантов, генерируем дополнительные уникальные рецепты под фильтр пользователя
    val targetCuisine = if (cuisine == "Все") "Домашняя" else cuisine
    val targetCategory = if (category == "Все") "Вторые блюда" else category

    val dishStyles = listOf(
        "по-домашнему в духовке", "в сливочно-чесночном соусе", "на сковороде за 20 минут",
        "по-царски с зеленью", "в пряном маринаде", "под золотистой сырной корочкой",
        "по старинному рецепту", "с ароматными специями", "в нежном соусе", "на скорую руку",
        "с хрустящей корочкой", "в горшочке", "по-деревенски", "шеф-рецепт", "фирменный вариант"
    )

    val foodPhotos = listOf(
        "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600",
        "https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=600",
        "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=600",
        "https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=600",
        "https://images.unsplash.com/photo-1555939594-58d7cb561ad1?w=600",
        "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600",
        "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=600"
    )

    var idx = 0
    while (filtered.size < count) {
        val style = dishStyles[idx % dishStyles.size]
        val mainIng = if (fridgeItems.isNotEmpty()) {
            fridgeItems[idx % fridgeItems.size]
        } else {
            when (targetCategory) {
                "Супы" -> listOf("Курица", "Говядина", "Грибы", "Сыр и овощи")[idx % 4]
                "Каши и завтраки" -> listOf("Творог", "Яйца и сыр", "Овсянка с ягодами", "Рисовая каша")[idx % 4]
                "Выпечка" -> listOf("Сыр и зелень", "Творог", "Яблоки и корица", "Мясная начинка")[idx % 4]
                "Что-то к чаю" -> listOf("Шоколад и орехи", "Творожный крем", "Ягоды и мед", "Карамель")[idx % 4]
                "Салаты" -> listOf("Куриное филе", "Свежие томаты и сыр", "Креветки и салат", "Запеченные овощи")[idx % 4]
                "Паста и пицца" -> listOf("Сыр Моцарелла и томаты", "Курица и грибы", "Бекон и сливки", "Четыре сыра")[idx % 4]
                else -> listOf("Курица с картофелем", "Мясо с овощами", "Рис с курицей", "Филе с грибами")[idx % 4]
            }
        }

        val generatedIngredients = mutableListOf<String>()
        if (fridgeItems.isNotEmpty()) {
            fridgeItems.forEach { generatedIngredients.add("$it - 250 г") }
        } else {
            generatedIngredients.add("$mainIng - 350 г")
        }
        generatedIngredients.addAll(
            listOf(
                "Лук репчатый или зеленый - 1 шт.",
                "Масло сливочное или оливковое - 2 ст.л.",
                "Чеснок и ароматные специи - по вкусу"
            )
        )

        val img1 = foodPhotos[idx % foodPhotos.size]
        val img2 = foodPhotos[(idx + 2) % foodPhotos.size]

        filtered.add(
            Recipe(
                id = "gen_${targetCuisine}_${targetCategory}_$idx",
                title = "$targetCategory ($targetCuisine кухня): $mainIng $style",
                cuisine = targetCuisine,
                category = targetCategory,
                cookingTime = "${20 + (idx * 5) % 40} мин",
                difficulty = if (idx % 2 == 0) "Легко" else "Средне",
                calories = 190 + (idx * 25) % 220,
                imageUrls = listOf(img1, img2),
                ingredients = generatedIngredients.distinct(),
                instructions = listOf(
                    "Подготовьте и нарежьте основные ингредиенты ($mainIng) удобными кусочками.",
                    "Обжарьте или потомите основу с маслом и специями на среднем огне в течение 10 минут.",
                    "Доведите блюдо до полной готовности в течение 15 минут при 180°C и подавайте горячим."
                )
            )
        )
        idx++
    }

    return filtered.take(count)
}

fun getRichRussianDatabase(): List<Recipe> {
    return listOf(
        Recipe(
            id = "ru_1", title = "Классический русский борщ с говядиной", cuisine = "Русская", category = "Супы",
            cookingTime = "1 ч 20 мин", difficulty = "Средне", calories = 135,
            imageUrls = listOf("https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600", "https://images.unsplash.com/photo-1603105037880-880cd4edfb0d?w=600"),
            ingredients = listOf("Говядина - 500 г", "Свекла - 2 шт.", "Капуста - 300 г", "Картофель - 4 шт.", "Томаты или паста - 2 ст.л.", "Сметана - 3 ст.л."),
            instructions = listOf("Сварите наваристый бульон из говядины в течение 45 минут.", "Добавьте нарезанный картофель и капусту, варите 15 минут.", "Обжарьте тертую свеклу с томатной пастой в течение 7 минут и добавьте в суп.", "Настаивайте под крышкой 10 минут перед подачей со сметаной.")
        ),
        Recipe(
            id = "ru_2", title = "Курица с рисом и овощами на одной сковороде", cuisine = "Русская", category = "Вторые блюда",
            cookingTime = "40 мин", difficulty = "Легко", calories = 240,
            imageUrls = listOf("https://images.unsplash.com/photo-1598515214211-89d3c73ae83b?w=600", "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=600"),
            ingredients = listOf("Курица - 500 г", "Рис - 250 г", "Морковь - 1 шт.", "Лук - 1 шт.", "Чеснок - 3 зубчика", "Специи - 1 ч.л."),
            instructions = listOf("Обжарьте кусочки курицы с луком и морковью в сковороде в течение 10 минут.", "Всыпьте промытый рис, залейте горячей водой на 1 см выше уровня риса.", "Тушите под крышкой на слабом огне 20 минут до полной готовности риса.")
        ),
        Recipe(
            id = "ru_3", title = "Пирожное «Картошка» с соленой карамелью", cuisine = "Русская", category = "Что-то к чаю",
            cookingTime = "30 мин", difficulty = "Легко", calories = 380,
            imageUrls = listOf("https://images.unsplash.com/photo-1578985545062-69928b1d9587?w=600", "https://images.unsplash.com/photo-1551024601-bec78aea704b?w=600"),
            ingredients = listOf("Печенье песочное - 400 г", "Вареная сгущенка (соленая карамель) - 250 г", "Сливочное масло - 100 г", "Какао-порошок - 4 ст.л."),
            instructions = listOf("Измельчите печенье в мелкую крошку и смешайте с какао.", "Добавьте размягченное сливочное масло и карамельную сгущенку, тщательно вымесите.", "Сформируйте пирожные и уберите в холодильник на 20 минут.")
        ),
        Recipe(
            id = "ru_4", title = "Пышные сырники из творога на завтрак", cuisine = "Русская", category = "Каши и завтраки",
            cookingTime = "25 мин", difficulty = "Легко", calories = 220,
            imageUrls = listOf("https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600", "https://images.unsplash.com/photo-1528207776546-365bb710ee93?w=600"),
            ingredients = listOf("Творог 9% - 400 г", "Яйца - 1 шт.", "Мука - 3 ст.л.", "Сахар - 2 ст.л.", "Сметана - 2 ст.л."),
            instructions = listOf("Разомните творог с яйцом, сахаром и мукой в однородную массу.", "Сформируйте аккуратные шайбочки и обжарьте на среднем огне по 5 минут с каждой стороны.")
        ),
        Recipe(
            id = "kr_1", title = "Острый корейский суп Кимчи Тиге", cuisine = "Корейская", category = "Супы",
            cookingTime = "35 мин", difficulty = "Легко", calories = 185,
            imageUrls = listOf("https://images.unsplash.com/photo-1583224964978-2257b960c3d3?w=600", "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=600"),
            ingredients = listOf("Капуста кимчи - 300 г", "Свинина или курица - 250 г", "Сыр тофу - 150 г", "Яйца - 1 шт.", "Паста кочхуджан - 1 ст.л."),
            instructions = listOf("Обжарьте мясо с нарезанной капустой кимчи в кастрюле в течение 7 минут.", "Влейте бульон или воду, добавьте пасту кочхуджан и варите 20 минут.", "Добавьте кубики тофу и яйцо, прогрейте еще 3 минуты.")
        ),
        Recipe(
            id = "kr_2", title = "Курица по-корейски в кисло-сладком соусе (Яннём)", cuisine = "Корейская", category = "Вторые блюда",
            cookingTime = "40 мин", difficulty = "Средне", calories = 310,
            imageUrls = listOf("https://images.unsplash.com/photo-1562967914-608f82629710?w=600", "https://images.unsplash.com/photo-1626082927389-6cd097cdc6ec?w=600"),
            ingredients = listOf("Курица - 600 г", "Крахмал кукурузный - 4 ст.л.", "Чеснок - 3 зубчика", "Томатный соус - 3 ст.л.", "Мед или сахар - 2 ст.л.", "Кунжут - 1 ст.л."),
            instructions = listOf("Обваляйте кусочки курицы в крахмале и обжарьте до хрустящей корочки в течение 12 минут.", "Смешайте на сковороде чеснок, томатный соус и мед, прогрейте 3 минуты.", "Соедините курицу с соусом и посыпьте кунжутом.")
        ),
        Recipe(
            id = "fr_1", title = "Французские круассаны с миндальным кремом", cuisine = "Французская", category = "Что-то к чаю",
            cookingTime = "35 мин", difficulty = "Легко", calories = 390,
            imageUrls = listOf("https://images.unsplash.com/photo-1555507036-ab1f4038808a?w=600", "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=600"),
            ingredients = listOf("Круассаны или слоеное тесто - 4 шт.", "Миндальная мука - 100 г", "Сливочное масло - 80 г", "Яйца - 2 шт.", "Сахарная пудра - 80 г"),
            instructions = listOf("Взбейте мягкое масло с сахарной пудрой, яйцами и миндальной мукой.", "Начините круассаны кремом и запекайте 15 минут при 180°C.")
        ),
        Recipe(
            id = "fr_2", title = "Картофельный гратен Дофинуа с сыром и сливками", cuisine = "Французская", category = "Вторые блюда",
            cookingTime = "50 мин", difficulty = "Легко", calories = 265,
            imageUrls = listOf("https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=600", "https://images.unsplash.com/photo-1585238342024-78d387f4a707?w=600"),
            ingredients = listOf("Картофель - 800 г", "Сыр твердый - 150 г", "Молоко или сливки - 300 мл", "Чеснок - 2 зубчика", "Сливочное масло - 30 г"),
            instructions = listOf("Нарежьте картофель тонкими кружочками и выложите слоями в форму, натертую чесноком.", "Залейте молоком со сливками и посыпьте тертым сыром.", "Запекайте в духовке 40 минут при 190°C до румяной корочки.")
        ),
        Recipe(
            id = "it_1", title = "Паста Карбонара со сливочно-сырным соусом", cuisine = "Итальянская", category = "Паста и пицца",
            cookingTime = "20 мин", difficulty = "Легко", calories = 340,
            imageUrls = listOf("https://images.unsplash.com/photo-1612874742237-6526221588e3?w=600", "https://images.unsplash.com/photo-1551183053-bf91a1d81141?w=600"),
            ingredients = listOf("Спагетти - 300 г", "Бекон или грудинка - 150 г", "Яйца - 3 шт.", "Сыр Пармезан - 80 г", "Чеснок - 2 зубчика"),
            instructions = listOf("Отварите спагетти в подсоленной воде в течение 9 минут.", "Обжарьте нарезанный бекон с чесноком на сковороде в течение 5 минут.", "Смешайте горячую пасту с беконом и взбитыми желтками с тертым сыром.")
        ),
        Recipe(
            id = "ge_1", title = "Хачапури по-аджарски с тягучим сыром и яйцом", cuisine = "Грузинская", category = "Выпечка",
            cookingTime = "45 мин", difficulty = "Средне", calories = 330,
            imageUrls = listOf("https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600", "https://images.unsplash.com/photo-1574071318508-1cdbab80d002?w=600"),
            ingredients = listOf("Тесто дрожжевое - 400 г", "Сыр Сулугуни - 350 г", "Яйца - 2 шт.", "Сливочное масло - 30 г"),
            instructions = listOf("Сформируйте из теста лодочку и наполните тертым сыром.", "Выпекайте в духовке 20 минут при 200°C.", "Добавьте в центр сырой желток и кусочек масла, верните в духовку на 2 минуты.")
        ),
        Recipe(
            id = "jp_1", title = "Домашний куриный Рамен с яйцом и лапшой", cuisine = "Японская", category = "Супы",
            cookingTime = "35 мин", difficulty = "Легко", calories = 230,
            imageUrls = listOf("https://images.unsplash.com/photo-1569718212165-3a8278d5f624?w=600", "https://images.unsplash.com/photo-1557872943-16a5ac26437e?w=600"),
            ingredients = listOf("Курица - 400 г", "Лапша пшеничная - 200 г", "Яйца - 2 шт.", "Грибы - 150 г", "Соевый соус - 3 ст.л.", "Зеленый лук"),
            instructions = listOf("Сварите куриный бульон с соевым соусом и грибами в течение 25 минут.", "Отварите яйца всмятку ровно 6 минут и разрежьте пополам.", "Отварите лапшу 4 минуты, залейте горячим бульоном, выложите курицу и яйцо.")
        ),
        Recipe(
            id = "mx_1", title = "Мексиканская Кесадилья с курицей, сыром и томатами", cuisine = "Мексиканская", category = "Вторые блюда",
            cookingTime = "20 мин", difficulty = "Легко", calories = 290,
            imageUrls = listOf("https://images.unsplash.com/photo-1618040996337-56904b7850b9?w=600", "https://images.unsplash.com/photo-1565299585323-38d6b0865b47?w=600"),
            ingredients = listOf("Курица - 300 г", "Сыр твердый - 150 г", "Томаты - 2 шт.", "Лепешки тортильи - 4 шт.", "Кукуруза или фасоль - 100 г"),
            instructions = listOf("Обжарьте мелко нарезанную курицу с томатами и специями в течение 8 минут.", "Выложите начинку и тертый сыр на половину лепешки, накройте второй половиной.", "Подрумяньте на сухой сковороде по 3 минуты с каждой стороны до расплавления сыра.")
        )
    )
}
