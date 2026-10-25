package com.example.recipeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
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
    LOCAL("📚 Офлайн база"),
    MEAL_DB_API("🌍 TheMealDB API"),
    FREE_AI("✨ Бесплатный ИИ")
}

// Данные распарсенного таймера из текста шага
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

// Умная проверка совпадения продукта с учетом русских окончаний
fun isIngredientInFridge(recipeIngredient: String, fridgeItems: List<String>): Boolean {
    val cleanRecipeIng = recipeIngredient.lowercase()
    return fridgeItems.any { fridgeItem ->
        val cleanFridge = fridgeItem.trim().lowercase()
        if (cleanFridge.isEmpty()) return@any false
        val stem = if (cleanFridge.length >= 5) cleanFridge.dropLast(2)
        else if (cleanFridge.length == 4) cleanFridge.dropLast(1)
        else cleanFridge
        cleanRecipeIng.contains(stem)
    }
}

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
    } catch (e: Exception) {
        // Игнорируем ошибку аудиосистемы
    }
}

fun humanizeCookingText(text: String): String {
    return text
        .replace(Regex("(\\d+)\\s*-\\s*(\\d+)"), "$1, $2")
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
        .replace(" - ", " — ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var currentlySpeakingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ruLocale = Locale("ru", "RU")
                ttsInstance?.language = ruLocale

                val bestHumanVoice = ttsInstance?.voices
                    ?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
                    ?: ttsInstance?.voices
                        ?.filter { it.locale.language == "ru" }
                        ?.maxByOrNull { it.quality }

                if (bestHumanVoice != null) {
                    ttsInstance?.voice = bestHumanVoice
                }

                ttsInstance?.setSpeechRate(0.96f)
                ttsInstance?.setPitch(1.02f)

                ttsInstance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        currentlySpeakingId = utteranceId
                    }

                    override fun onDone(utteranceId: String?) {
                        if (currentlySpeakingId == utteranceId) {
                            currentlySpeakingId = null
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        currentlySpeakingId = null
                    }
                })
            }
        }
        ttsEngine = ttsInstance

        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    fun speakHumanized(utteranceId: String, rawText: String) {
        val engine = ttsEngine ?: return
        if (currentlySpeakingId == utteranceId) {
            engine.stop()
            currentlySpeakingId = null
            return
        }
        engine.stop()
        currentlySpeakingId = utteranceId

        val sentences = humanizeCookingText(rawText)
            .split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }

        sentences.forEachIndexed { index, sentence ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (index == sentences.lastIndex) utteranceId else "${utteranceId}_part_$index"
            engine.speak(sentence, queueMode, null, id)
            if (index < sentences.lastIndex) {
                engine.playSilentUtterance(250, TextToSpeech.QUEUE_ADD, "${id}_pause")
            }
        }
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
        currentlySpeakingId = null
    }

    val cuisines = listOf("Все", "Русская", "Корейская", "Французская", "Итальянская", "Японская", "Грузинская", "Мексиканская")
    val categories = listOf("Все", "Супы", "Каши и завтраки", "Вторые блюда", "Выпечка", "Что-то к чаю", "Салаты", "Паста и пицца")
    val quickFridgeSuggestions = listOf("Курица", "Яйца", "Картофель", "Сыр", "Томаты", "Молоко", "Рис", "Фарш", "Творог", "Капуста", "Грибы")

    var selectedMode by remember { mutableStateOf(DataSourceMode.LOCAL) }
    var selectedCuisine by remember { mutableStateOf("Все") }
    var selectedCategory by remember { mutableStateOf("Все") }
    var limitCount by remember { mutableFloatStateOf(5f) }
    var showOnlyFavorites by remember { mutableStateOf(false) }
    var showShoppingListScreen by remember { mutableStateOf(false) }
    var isFiltersExpanded by remember { mutableStateOf(true) }

    var ingredientInput by remember { mutableStateOf("") }
    var lastVoiceCommand by remember { mutableStateOf<String?>(null) }
    val fridgeItems = remember { mutableStateListOf<String>() }

    // Состояния Списка покупок
    val shoppingListItems = remember { mutableStateListOf<String>() }
    val checkedShoppingItems = remember { mutableStateListOf<String>() }

    var recipes by remember { mutableStateOf(getSampleRecipes()) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadRecipes(
        modeToUse: DataSourceMode = selectedMode,
        customVoicePrompt: String? = lastVoiceCommand
    ) {
        stopSpeaking()
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val count = limitCount.roundToInt()
                recipes = when (modeToUse) {
                    DataSourceMode.LOCAL -> getSampleRecipes()
                    DataSourceMode.MEAL_DB_API -> fetchFromMealDbApi(selectedCuisine, selectedCategory, fridgeItems.toList(), count)
                    DataSourceMode.FREE_AI -> fetchFromFreeAi(selectedCuisine, selectedCategory, fridgeItems.toList(), customVoicePrompt, count)
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка загрузки: ${e.localizedMessage ?: "проверьте подключение к интернету"}"
            } finally {
                isLoading = false
            }
        }
    }

    fun addIngredientsFromInput() {
        val parts = ingredientInput.split(",", ";")
            .map { it.trim().replaceFirstChar { ch -> ch.uppercase() } }
            .filter { it.isNotEmpty() && !fridgeItems.contains(it) }
        fridgeItems.addAll(parts)
        ingredientInput = ""
    }

    fun processVoiceCommand(spokenText: String) {
        lastVoiceCommand = spokenText
        val lower = spokenText.lowercase()

        val cuisineRoots = mapOf(
            "русск" to "Русская",
            "корейск" to "Корейская",
            "французск" to "Французская",
            "итальянск" to "Итальянская",
            "японск" to "Японская",
            "грузинск" to "Грузинская",
            "мексиканск" to "Мексиканская"
        )
        cuisineRoots.forEach { (root, cuisineName) ->
            if (lower.contains(root)) selectedCuisine = cuisineName
        }

        val categoryRoots = mapOf(
            "суп" to "Супы",
            "бульон" to "Супы",
            "каш" to "Каши и завтраки",
            "завтрак" to "Каши и завтраки",
            "выпечк" to "Выпечка",
            "пирог" to "Выпечка",
            "к чаю" to "Что-то к чаю",
            "десерт" to "Что-то к чаю",
            "сладк" to "Что-то к чаю",
            "салат" to "Салаты",
            "паст" to "Паста и пицца",
            "пицц" to "Паста и пицца",
            "второе" to "Вторые блюда",
            "ужин" to "Вторые блюда"
        )
        categoryRoots.forEach { (root, categoryName) ->
            if (lower.contains(root)) selectedCategory = categoryName
        }

        val stopWords = setOf(
            "приготовь", "приготовить", "сделай", "сделать", "подбери", "подобрать",
            "найди", "найти", "покажи", "хочу", "блюдо", "блюда", "рецепт", "рецепты",
            "из", "с", "со", "у", "меня", "есть", "в", "холодильнике", "дома", "и", "а",
            "также", "или", "что", "нибудь", "что-нибудь", "мне", "пожалуйста", "на",
            "обед", "ужин", "завтрак", "суп", "салат", "второе", "десерт", "выпечка",
            "каша", "корейский", "корейская", "русский", "русская", "итальянский",
            "итальянская", "французский", "французская", "грузинский", "грузинская",
            "японский", "японская", "мексиканский", "мексиканская", "вкусное", "быстрое"
        )

        val normalizeMap = mapOf(
            "курицы" to "Курица", "курицу" to "Курица", "курочки" to "Курица", "курицей" to "Курица",
            "картошки" to "Картофель", "картошка" to "Картофель", "картофеля" to "Картофель", "картошкой" to "Картофель",
            "сыра" to "Сыр", "сыром" to "Сыр",
            "яиц" to "Яйца", "яйцо" to "Яйца", "яйцами" to "Яйца",
            "молока" to "Молоко", "молоком" to "Молоко",
            "риса" to "Рис", "рисом" to "Рис",
            "грибов" to "Грибы", "грибами" to "Грибы",
            "помидоров" to "Томаты", "помидоры" to "Томаты", "томатов" to "Томаты",
            "фарша" to "Фарш", "фаршем" to "Фарш",
            "творога" to "Творог", "творогом" to "Творог",
            "капусты" to "Капуста", "капустой" to "Капуста",
            "говядины" to "Говядина", "свинины" to "Свинина",
            "чеснока" to "Чеснок", "лука" to "Лук", "сметаны" to "Сметана"
        )

        val extractedIngredients = lower
            .replace(Regex("[^а-яёa-z\\s-]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 2 && !stopWords.contains(it) }
            .filter { word -> cuisineRoots.keys.none { word.startsWith(it) } }
            .map { word -> normalizeMap[word] ?: word.replaceFirstChar { ch -> ch.uppercase() } }
            .distinct()

        if (extractedIngredients.isNotEmpty()) {
            fridgeItems.clear()
            fridgeItems.addAll(extractedIngredients)
        }

        if (selectedMode == DataSourceMode.LOCAL) {
            val currentFridge = fridgeItems.toList()
            val localMatches = getSampleRecipes().filter { recipe ->
                val matchCuisine = selectedCuisine == "Все" || recipe.cuisine == selectedCuisine
                val matchCategory = selectedCategory == "Все" || recipe.category == selectedCategory
                val matchFridge = currentFridge.isEmpty() || recipe.ingredients.any { isIngredientInFridge(it, currentFridge) }
                matchCuisine && matchCategory && matchFridge
            }
            if (localMatches.isEmpty() && currentFridge.isNotEmpty()) {
                selectedMode = DataSourceMode.FREE_AI
                Toast.makeText(context, "В офлайн-базе нет совпадений — подбираем через ИИ ✨", Toast.LENGTH_SHORT).show()
                loadRecipes(DataSourceMode.FREE_AI, spokenText)
            } else {
                recipes = getSampleRecipes()
            }
        } else {
            loadRecipes(selectedMode, spokenText)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите, какие продукты есть дома или что приготовить...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "На устройстве не найден голосовой ввод Google", Toast.LENGTH_LONG).show()
        }
    }

    val displayedRecipes = remember(
        recipes, showOnlyFavorites, favoriteIds, selectedCuisine,
        selectedCategory, limitCount, selectedMode, fridgeItems.toList()
    ) {
        val currentFridge = fridgeItems.toList()
        val baseList = if (selectedMode == DataSourceMode.LOCAL) {
            recipes.filter { recipe ->
                val matchCuisine = selectedCuisine == "Все" || recipe.cuisine == selectedCuisine
                val matchCategory = selectedCategory == "Все" || recipe.category == selectedCategory
                val matchFridge = currentFridge.isEmpty() || recipe.ingredients.any { isIngredientInFridge(it, currentFridge) }
                matchCuisine && matchCategory && matchFridge
            }.sortedByDescending { recipe ->
                recipe.ingredients.count { isIngredientInFridge(it, currentFridge) }
            }.take(limitCount.roundToInt())
        } else {
            recipes.take(limitCount.roundToInt())
        }

        if (showOnlyFavorites) baseList.filter { favoriteIds.contains(it.id) } else baseList
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // Верхняя панель: Заголовок, Стоп озвучки, Список покупок, Избранное и сворачивание фильтров
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
                            contentDescription = "Свернуть или развернуть фильтры"
                        )
                    }
                }
            }
        }

        // Переключение между экраном Списка покупок и главным экраном Рецептов
        if (showShoppingListScreen) {
            Spacer(modifier = Modifier.height(8.dp))
            ShoppingListView(
                shoppingItems = shoppingListItems,
                checkedItems = checkedShoppingItems,
                onBackToRecipes = { showShoppingListScreen = false }
            )
        } else {
            AnimatedVisibility(visible = isFiltersExpanded) {
                Column {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(DataSourceMode.entries) { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    if (mode == DataSourceMode.LOCAL) {
                                        recipes = getSampleRecipes()
                                        errorMessage = null
                                    }
                                },
                                label = { Text(mode.label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🧊 Что есть в холодильнике:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (fridgeItems.isNotEmpty() || lastVoiceCommand != null) {
                                    Text(
                                        text = "Сбросить",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            fridgeItems.clear()
                                            lastVoiceCommand = null
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = ingredientInput,
                                    onValueChange = { ingredientInput = it },
                                    placeholder = { Text("Курица, грибы... или нажми 🎤", fontSize = 13.sp) },
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
                                    Icon(Icons.Default.Add, contentDescription = "Добавить продукт")
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                FilledIconButton(
                                    onClick = { startVoiceRecognition() },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Сказать голосом")
                                }
                            }

                            if (lastVoiceCommand != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "🗣 Вы сказали: «$lastVoiceCommand»",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            if (fridgeItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(fridgeItems) { item ->
                                        InputChip(
                                            selected = true,
                                            onClick = { fridgeItems.remove(item) },
                                            label = { Text(item, fontSize = 12.sp) },
                                            trailingIcon = {
                                                Icon(Icons.Default.Clear, contentDescription = "Удалить", modifier = Modifier.size(16.dp))
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Быстрый выбор:", fontSize = 11.sp, color = Color.Gray)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(quickFridgeSuggestions) { suggestion ->
                                    val isSelected = fridgeItems.contains(suggestion)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (isSelected) fridgeItems.remove(suggestion)
                                            else fridgeItems.add(suggestion)
                                        },
                                        label = { Text(if (isSelected) "✓ $suggestion" else "+ $suggestion", fontSize = 12.sp) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Кухня:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cuisines) { cuisine ->
                            FilterChip(
                                selected = selectedCuisine == cuisine,
                                onClick = { selectedCuisine = cuisine },
                                label = { Text(cuisine, fontSize = 12.sp) }
                            )
                        }
                    }

                    Text("Тип блюда:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category, fontSize = 12.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Вариантов: ${limitCount.roundToInt()}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Slider(
                                value = limitCount,
                                onValueChange = { limitCount = it },
                                valueRange = 5f..20f,
                                steps = 14
                            )
                        }
                        if (selectedMode != DataSourceMode.LOCAL) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { loadRecipes() },
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Найти")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Подобрать")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (selectedMode == DataSourceMode.FREE_AI)
                                "ИИ подбирает блюда по вашему запросу..."
                            else "Ищем рецепты в мировой базе..."
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { loadRecipes() }) { Text("Повторить") }
                    }
                }
            } else if (displayedRecipes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (selectedMode != DataSourceMode.LOCAL)
                                "Нажмите «Подобрать» или скажите запрос голосом 🎤"
                            else "В офлайн-базе нет блюд с таким набором продуктов."
                        )
                        if (selectedMode == DataSourceMode.LOCAL && fridgeItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                selectedMode = DataSourceMode.FREE_AI
                                loadRecipes(DataSourceMode.FREE_AI)
                            }) {
                                Text("✨ Подобрать через бесплатный ИИ")
                            }
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
                                Toast.makeText(
                                    context,
                                    if (addedCount > 0) "Добавлено в список покупок: $addedCount шт. 🛒"
                                    else "Все эти продукты уже есть в списке покупок!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onToggleFavorite = {
                                favoriteIds = if (favoriteIds.contains(recipe.id)) {
                                    favoriteIds - recipe.id
                                } else {
                                    favoriteIds + recipe.id
                                }
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
    onBackToRecipes: () -> Unit
) {
    val context = LocalContext.current
    var newItemText by remember { mutableStateOf("") }

    fun addCustomItem() {
        val trimmed = newItemText.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isNotEmpty() && !shoppingItems.contains(trimmed)) {
            shoppingItems.add(trimmed)
            newItemText = ""
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Шапка экрана покупок
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
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Очистить", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Поле ручного добавления товара в список покупок
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItemText,
                onValueChange = { newItemText = it },
                placeholder = { Text("Добавить свой продукт в список...", fontSize = 13.sp) },
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

        Spacer(modifier = Modifier.height(12.dp))

        if (shoppingItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒 Список покупок пока пуст", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Нажмите кнопку «Добавить недостающие в список покупок» внутри любого рецепта!",
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

    // Недостающие продукты — те, которые не отмечены галочкой в карточке
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
                    fontSize = 20.sp,
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

            FilledTonalButton(
                onClick = {
                    val fullSpeech = buildString {
                        append("Рецепт: ${recipe.title}. ")
                        append("Кухня: ${recipe.cuisine}. Время приготовления: ${recipe.cookingTime}. ")
                        append("Нам понадобятся следующие ингредиенты: ")
                        append(recipe.ingredients.joinToString(". "))
                        append(". Переходим к приготовлению. ")
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
                Text(if (isSpeakingFullRecipe) "Остановить озвучку рецепта" else "🔊 Озвучить весь рецепт голосом")
            }

            if (fridgeItems.isNotEmpty()) {
                Surface(
                    color = if (needToBuyCount == 0) Color(0xFFE8F5E9) else Color(0xFFFFF8E1),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (needToBuyCount == 0)
                            "🎉 Все ингредиенты есть дома! ($matchedCount из ${recipe.ingredients.size})"
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
                Text("⏱ ${recipe.cookingTime}", fontSize = 13.sp, color = Color.Gray)
                Text("🌍 ${recipe.cuisine}", fontSize = 13.sp, color = Color.Gray)
                Text("📊 ${recipe.difficulty}", fontSize = 13.sp, color = Color.Gray)
                Text("🔥 ${recipe.calories} ккал", fontSize = 13.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                    )
                }
                if (safeImages.size > 1) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(safeImages.size) { iteration ->
                            val color = if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.5f)
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(color)
                                    .size(8.dp)
                            )
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
                        inFridge -> Text(
                            text = "✅ Есть дома",
                            fontSize = 11.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium
                        )
                        inCart -> Text(
                            text = "🛒 В списке",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Кнопка добавления всех недостающих продуктов одной кнопкой в Список покупок
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Surface(
                        color = if (isStepSpeaking) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSpeakText(stepUtteranceId, "Шаг ${stepIndex + 1}. $step")
                            }
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
// ИНТЕРАКТИВНЫЙ КОМПОНЕНТ ТАЙМЕРА ДЛЯ ШАГА
// =========================================================================
@Composable
fun StepTimerWidget(timerInfo: ParsedTimer) {
    var timeLeft by remember(timerInfo) { mutableStateOf(timerInfo.durationSeconds) }
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
        modifier = Modifier
            .padding(start = 24.dp, top = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
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
                        isFinished -> "✅ Время истекло (${timerInfo.label})"
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
                            contentDescription = "Старт/Пауза",
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
// СЕТЕВОЙ СЛОЙ 1: БЕСПЛАТНЫЙ ИИ
// =========================================================================
suspend fun fetchFromFreeAi(
    cuisine: String,
    category: String,
    fridgeItems: List<String>,
    voicePrompt: String?,
    count: Int
): List<Recipe> = withContext(Dispatchers.IO) {
    val fridgeCondition = if (fridgeItems.isNotEmpty()) {
        "Обязательно используй следующие продукты, которые есть у пользователя: ${fridgeItems.joinToString(", ")}."
    } else ""

    val voiceCondition = if (!voicePrompt.isNullOrBlank()) {
        "Учти голосовой запрос пользователя: «$voicePrompt»."
    } else ""

    val prompt = """
        Сгенерируй ровно $count рецептов на русском языке.
        Кухня: ${if (cuisine == "Все") "любая мировая" else cuisine}.
        Тип блюда: ${if (category == "Все") "любой" else category}.
        $fridgeCondition
        $voiceCondition
        В текстах шагов приготовления (instructions) обязательно указывай точное время в минутах (например: "Варите 15 минут", "Запекайте 40 минут"), чтобы работал кухонный таймер.
        Верни ТОЛЬКО валидный JSON массив без markdown-обертки в таком формате:
        [
          {
            "title": "Название блюда",
            "cuisine": "Кухня",
            "category": "Тип",
            "cookingTime": "40 мин",
            "difficulty": "Легко",
            "calories": 240,
            "englishName": "chicken potato dish",
            "ingredients": ["Курица - 300 г", "Картофель - 4 шт."],
            "instructions": ["Шаг 1", "Варите 15 минут на среднем огне", "Шаг 3"]
          }
        ]
    """.trimIndent()

    val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
    val url = URL("https://text.pollinations.ai/$encodedPrompt")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 25000
        readTimeout = 25000
    }

    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
    val cleanJson = responseText
        .substringAfter("[", "[")
        .substringBeforeLast("]", "]")
        .let { "[$it]" }

    val jsonArray = JSONArray(cleanJson)
    val result = mutableListOf<Recipe>()

    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        val engName = URLEncoder.encode(obj.optString("englishName", "delicious meal"), "UTF-8")

        val img1 = "https://image.pollinations.ai/prompt/$engName%20professional%20food%20photography?width=600&height=400&nologo=true&seed=${i + 1}"
        val img2 = "https://image.pollinations.ai/prompt/$engName%20closeup%20dish%20serving?width=600&height=400&nologo=true&seed=${i + 100}"

        val ingredientsArr = obj.optJSONArray("ingredients") ?: JSONArray()
        val ingredientsList = List(ingredientsArr.length()) { ingredientsArr.getString(it) }

        val instructionsArr = obj.optJSONArray("instructions") ?: JSONArray()
        val instructionsList = List(instructionsArr.length()) { instructionsArr.getString(it) }

        result.add(
            Recipe(
                id = "ai_${System.currentTimeMillis()}_$i",
                title = obj.optString("title", "Блюдо #${i + 1}"),
                cuisine = obj.optString("cuisine", cuisine),
                category = obj.optString("category", category),
                cookingTime = obj.optString("cookingTime", "35 мин"),
                difficulty = obj.optString("difficulty", "Средне"),
                calories = obj.optInt("calories", 220),
                imageUrls = listOf(img1, img2),
                ingredients = ingredientsList,
                instructions = instructionsList
            )
        )
    }
    return@withContext result
}

// =========================================================================
// СЕТЕВОЙ СЛОЙ 2: ПУБЛИЧНЫЙ КУЛИНАРНЫЙ API (TheMealDB)
// =========================================================================
suspend fun fetchFromMealDbApi(
    cuisine: String,
    category: String,
    fridgeItems: List<String>,
    count: Int
): List<Recipe> = withContext(Dispatchers.IO) {
    val ingredientRuToEn = mapOf(
        "курица" to "chicken",
        "яйца" to "egg",
        "картофель" to "potatoes",
        "сыр" to "cheese",
        "томаты" to "tomatoes",
        "помидоры" to "tomatoes",
        "молоко" to "milk",
        "рис" to "rice",
        "фарш" to "beef",
        "говядина" to "beef",
        "свинина" to "pork",
        "грибы" to "mushrooms",
        "чеснок" to "garlic",
        "капуста" to "cabbage",
        "творог" to "cheese"
    )

    val areaMap = mapOf(
        "Русская" to "Russian",
        "Французская" to "French",
        "Итальянская" to "Italian",
        "Японская" to "Japanese",
        "Мексиканская" to "Mexican"
    )

    val firstFridgeEn = fridgeItems.firstNotNullOfOrNull { item ->
        ingredientRuToEn[item.trim().lowercase()]
    }

    val endpointUrl = if (firstFridgeEn != null) {
        "https://www.themealdb.com/api/json/v1/1/filter.php?i=${URLEncoder.encode(firstFridgeEn, "UTF-8")}"
    } else {
        val area = areaMap[cuisine] ?: "Italian"
        "https://www.themealdb.com/api/json/v1/1/filter.php?a=$area"
    }

    val listResponse = URL(endpointUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
    val mealsArray = JSONObject(listResponse).optJSONArray("meals") ?: return@withContext emptyList()

    val result = mutableListOf<Recipe>()
    val limit = minOf(count, mealsArray.length())

    for (i in 0 until limit) {
        val mealSummary = mealsArray.getJSONObject(i)
        val mealId = mealSummary.getString("idMeal")

        val detailUrl = URL("https://www.themealdb.com/api/json/v1/1/lookup.php?i=$mealId")
        val detailResponse = detailUrl.openConnection().getInputStream().bufferedReader().use { it.readText() }
        val detailObj = JSONObject(detailResponse).getJSONArray("meals").getJSONObject(0)

        val ingredients = mutableListOf<String>()
        for (idx in 1..20) {
            val ing = detailObj.optString("strIngredient$idx", "").trim()
            val measure = detailObj.optString("strMeasure$idx", "").trim()
            if (ing.isNotEmpty() && ing != "null") {
                ingredients.add("$ing - $measure")
            }
        }

        val thumb = detailObj.optString("strMealThumb", "")
        val instructions = detailObj.optString("strInstructions", "")
            .split("\r\n", "\n")
            .map { it.trim() }
            .filter { it.length > 5 }

        result.add(
            Recipe(
                id = "mealdb_$mealId",
                title = detailObj.optString("strMeal", "Блюдо"),
                cuisine = cuisine.takeIf { it != "Все" } ?: detailObj.optString("strArea", "Мировая"),
                category = category.takeIf { it != "Все" } ?: detailObj.optString("strCategory", "Основное"),
                cookingTime = "${(25..60).random()} мин",
                difficulty = "Средне",
                calories = (180..450).random(),
                imageUrls = listOf(thumb, "https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=600"),
                ingredients = ingredients,
                instructions = instructions.ifEmpty { listOf("Приготовьте согласно инструкции в течение 20 минут.") }
            )
        )
    }
    return@withContext result
}

// =========================================================================
// ЛОКАЛЬНАЯ БАЗА РЕЦЕПТОВ
// =========================================================================
fun getSampleRecipes(): List<Recipe> {
    return listOf(
        Recipe(
            id = "loc_1",
            title = "Классический борщ",
            cuisine = "Русская",
            category = "Супы",
            cookingTime = "1 ч 30 мин",
            difficulty = "Средне",
            calories = 120,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1547592166-23ac45744acd?w=600",
                "https://images.unsplash.com/photo-1603105037880-880cd4edfb0d?w=600"
            ),
            ingredients = listOf("Говядина - 500 г", "Свекла - 2 шт.", "Капуста - 300 г", "Картофель - 4 шт.", "Томаты или паста - 2 ст.л."),
            instructions = listOf(
                "Сварите мясной бульон из говядины в течение 40 минут.",
                "Нарежьте картофель и добавьте в кипящий бульон.",
                "Сделайте зажарку из свеклы, моркови, лука и томатной пасты.",
                "Добавьте капусту и зажарку в кастрюлю, варите еще 15 минут."
            )
        ),
        Recipe(
            id = "loc_2",
            title = "Кимчи Тиге (Острый суп)",
            cuisine = "Корейская",
            category = "Супы",
            cookingTime = "40 мин",
            difficulty = "Легко",
            calories = 180,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1583224964978-2257b960c3d3?w=600",
                "https://images.unsplash.com/photo-1553163147-622ab57be1c7?w=600"
            ),
            ingredients = listOf("Капуста кимчи - 300 г", "Свинина или курица - 200 г", "Сыр тофу - 150 г", "Яйца - 1 шт.", "Зеленый лук"),
            instructions = listOf(
                "Обжарьте мясо и нарезанную кимчи в кастрюле в течение 7 минут.",
                "Залейте водой или бульоном и варите 20 минут.",
                "Добавьте нарезанный кубиками тофу, яйцо и зеленый лук перед подачей."
            )
        ),
        Recipe(
            id = "loc_3",
            title = "Круассаны с миндальным кремом",
            cuisine = "Французская",
            category = "Что-то к чаю",
            cookingTime = "45 мин",
            difficulty = "Средне",
            calories = 410,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1555507036-ab1f4038808a?w=600",
                "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=600"
            ),
            ingredients = listOf("Круассаны - 4 шт.", "Миндальная мука - 100 г", "Сливочное масло - 80 г", "Молоко - 50 мл", "Яйца - 2 шт."),
            instructions = listOf(
                "Взбейте масло с сахаром, яйцами и миндальной мукой в крем.",
                "Разрежьте круассаны вдоль, смажьте кремом внутри и сверху.",
                "Посыпьте лепестками миндаля и запекайте 15 минут при 180°C."
            )
        ),
        Recipe(
            id = "loc_4",
            title = "Хачапури по-аджарски",
            cuisine = "Грузинская",
            category = "Выпечка",
            cookingTime = "50 мин",
            difficulty = "Средне",
            calories = 340,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600",
                "https://images.unsplash.com/photo-1574071318508-1cdbab80d002?w=600"
            ),
            ingredients = listOf("Тесто дрожжевое - 400 г", "Сыр Сулугуни - 350 г", "Яйца - 2 шт.", "Молоко - 50 мл"),
            instructions = listOf(
                "Сформируйте из теста лодочку с бортиками.",
                "Натрите сыр и выложите внутрь лодочки.",
                "Выпекайте при 200°C около 20 минут, затем вбейте в центр желток на 2 минуты."
            )
        ),
        Recipe(
            id = "loc_5",
            title = "Сырники со сметаной",
            cuisine = "Русская",
            category = "Каши и завтраки",
            cookingTime = "25 мин",
            difficulty = "Легко",
            calories = 230,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600",
                "https://images.unsplash.com/photo-1528207776546-365bb710ee93?w=600"
            ),
            ingredients = listOf("Творог 9% - 400 г", "Яйца - 1 шт.", "Мука - 3 ст.л.", "Сахар - 2 ст.л."),
            instructions = listOf(
                "Разомните творог вилкой, смешайте с яйцом, сахаром и мукой.",
                "Сформируйте шайбочки, обваляйте в муке.",
                "Обжарьте на среднем огне по 4 минуты с каждой стороны до золотистой корочки."
            )
        ),
        Recipe(
            id = "loc_6",
            title = "Запеченная курица с картофелем и грибами",
            cuisine = "Русская",
            category = "Вторые блюда",
            cookingTime = "55 мин",
            difficulty = "Легко",
            calories = 210,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1598515214211-89d3c73ae83b?w=600",
                "https://images.unsplash.com/photo-1604908176997-125f25cc6f3d?w=600"
            ),
            ingredients = listOf("Курица - 600 г", "Картофель - 6 шт.", "Грибы шампиньоны - 250 г", "Сыр - 100 г", "Чеснок - 2 зубчика"),
            instructions = listOf(
                "Нарежьте курицу, картофель и грибы крупными кусочками.",
                "Выложите в форму для запекания, добавьте измельченный чеснок, соль и специи.",
                "Посыпьте тертым сыром и запекайте 45 минут при 190°C."
            )
        )
    )
}
