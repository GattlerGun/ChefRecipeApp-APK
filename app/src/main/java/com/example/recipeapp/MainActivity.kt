package com.example.recipeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.random.Random

// =========================================================================
// 1. ТЕМА И ЦВЕТОВЫЕ ПАЛИТРЫ
// =========================================================================
private val LightCulinaryColorScheme = lightColorScheme(
    primary = Color(0xFFD84315),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFCCBC),
    onPrimaryContainer = Color(0xFF3E2723),
    secondary = Color(0xFFEF6C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF4E342E),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color.White,
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5EBE6),
    onSurfaceVariant = Color(0xFF49454F)
)

private val DarkCulinaryColorScheme = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFF5D4037),
    onPrimaryContainer = Color(0xFFFFCCBC),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF4E342E),
    onSecondaryContainer = Color(0xFFFFE0B2),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF1B5E20),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDE0DC),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEDE0DC),
    surfaceVariant = Color(0xFF2C2624),
    onSurfaceVariant = Color(0xFFD7CCC8)
)

// =========================================================================
// 2. IMMUTABLE МОДЕЛИ ДАННЫХ
// =========================================================================
@Immutable
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
    val instructions: List<String>,
    val isCustom: Boolean = false
) {
    val cookingMinutes: Int
        get() {
            val lower = cookingTime.lowercase()
            val hours = Regex("(\\d+)\\s*ч").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mins = Regex("(\\d+)\\s*мин").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 30
            return hours * 60 + mins
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("cuisine", cuisine)
        put("category", category)
        put("cookingTime", cookingTime)
        put("difficulty", difficulty)
        put("calories", calories)
        put("isCustom", isCustom)
        put("imageUrls", JSONArray(imageUrls))
        put("ingredients", JSONArray(ingredients))
        put("instructions", JSONArray(instructions))
    }

    companion object {
        fun fromJson(obj: JSONObject): Recipe {
            val imgs = obj.optJSONArray("imageUrls") ?: JSONArray()
            val ings = obj.optJSONArray("ingredients") ?: JSONArray()
            val insts = obj.optJSONArray("instructions") ?: JSONArray()
            return Recipe(
                id = obj.optString("id", "rec_${System.currentTimeMillis()}"),
                title = obj.optString("title", "Домашнее блюдо"),
                cuisine = obj.optString("cuisine", "Домашняя"),
                category = obj.optString("category", "Вторые блюда"),
                cookingTime = obj.optString("cookingTime", "30 мин"),
                difficulty = obj.optString("difficulty", "Легко"),
                calories = obj.optInt("calories", 250),
                imageUrls = List(imgs.length()) { imgs.getString(it) },
                ingredients = List(ings.length()) { ings.getString(it) },
                instructions = List(insts.length()) { insts.getString(it) },
                isCustom = obj.optBoolean("isCustom", false)
            )
        }
    }
}

@Immutable
data class ParsedTimer(val durationSeconds: Int, val label: String)

enum class DataSourceMode(val label: String) {
    LOCAL("📚 Локальная база"),
    MEAL_DB_API("🌍 Мировая база (RU)"),
    FREE_AI("✨ ИИ Шеф (RU)")
}

enum class SortOrder(val label: String) {
    FRESH_BATCH("🎲 По новизне"),
    FRIDGE_MATCH("🧊 По холодильнику"),
    FASTEST("⚡ Быстрые"),
    LOW_CALORIE("🥗 Легкие")
}

enum class AppScreen {
    RECIPES,
    SHOPPING_LIST,
    ADD_RECIPE
}

// =========================================================================
// 3. КУЛИНАРНЫЕ УТИЛИТЫ, МАППЕР РЕАЛЬНЫХ КУХОНЬ МИРА И ПЕРЕВОДЧИК
// =========================================================================
object CulinaryUtils {
    val CUISINES = listOf(
        "Все", "Русская", "Корейская", "Французская", "Итальянская",
        "Японская", "Грузинская", "Мексиканская", "Китайская", "Тайская", "Индийская"
    )
    val CATEGORIES = listOf("Все", "Супы", "Каши и завтраки", "Вторые блюда", "Выпечка", "Что-то к чаю", "Салаты", "Паста и пицца")
    val QUICK_FRIDGE = listOf("Курица", "Яйца", "Картофель", "Сыр", "Томаты", "Молоко", "Рис", "Фарш", "Творог", "Капуста", "Грибы")

    // Полная карта стран происхождения из TheMealDB -> реальное название кухни на русском
    private val AREA_TO_RUSSIAN_CUISINE = mapOf(
        "russian" to "Русская",
        "korean" to "Корейская",
        "french" to "Французская",
        "italian" to "Итальянская",
        "japanese" to "Японская",
        "georgian" to "Грузинская",
        "mexican" to "Мексиканская",
        "chinese" to "Китайская",
        "thai" to "Тайская",
        "indian" to "Индийская",
        "greek" to "Греческая",
        "spanish" to "Испанская",
        "turkish" to "Турецкая",
        "vietnamese" to "Вьетнамская",
        "moroccan" to "Марокканская",
        "american" to "Американская",
        "british" to "Британская",
        "polish" to "Польская",
        "ukrainian" to "Украинская",
        "croatian" to "Хорватская",
        "irish" to "Ирландская",
        "jamaican" to "Ямайская",
        "egyptian" to "Египетская",
        "dutch" to "Голландская",
        "portuguese" to "Португальская",
        "canadian" to "Канадская",
        "malaysian" to "Малайзийская",
        "tunisian" to "Тунисская",
        "filipino" to "Филиппинская",
        "kenyan" to "Кенийская",
        "norwegian" to "Норвежская",
        "australian" to "Австралийская"
    )

    // Флаги для красивого отображения реальной кухни на карточке
    private val CUISINE_FLAGS = mapOf(
        "Русская" to "🇷🇺",
        "Корейская" to "🇰🇷",
        "Французская" to "🇫🇷",
        "Итальянская" to "🇮🇹",
        "Японская" to "🇯🇵",
        "Грузинская" to "🇬🇪",
        "Мексиканская" to "🇲🇽",
        "Китайская" to "🇨🇳",
        "Тайская" to "🇹🇭",
        "Индийская" to "🇮🇳",
        "Греческая" to "🇬🇷",
        "Испанская" to "🇪🇸",
        "Турецкая" to "🇹🇷",
        "Вьетнамская" to "🇻🇳",
        "Марокканская" to "🇲🇦",
        "Американская" to "🇺🇸",
        "Британская" to "🇬🇧",
        "Польская" to "🇵🇱",
        "Украинская" to "🇺🇦",
        "Хорватская" to "🇭🇷",
        "Ирландская" to "🇮🇪",
        "Ямайская" to "🇯🇲",
        "Египетская" to "🇪🇬",
        "Голландская" to "🇳🇱",
        "Португальская" to "🇵🇹",
        "Канадская" to "🇨🇦",
        "Малайзийская" to "🇲🇾",
        "Тунисская" to "🇹🇳",
        "Филиппинская" to "🇵🇭",
        "Домашняя" to "🏠"
    )

    // Определение реальной кухни по данным API и ключевым словам в названии блюда
    fun resolveRealCuisine(rawArea: String?, dishTitle: String, selectedFilterCuisine: String): String {
        val cleanArea = rawArea?.trim()?.lowercase() ?: ""
        if (cleanArea.isNotEmpty() && cleanArea != "unknown") {
            AREA_TO_RUSSIAN_CUISINE[cleanArea]?.let { return it }
            // Если ИИ уже вернул русское название кухни
            if (!hasLatin(cleanArea)) {
                val cap = cleanArea.replaceFirstChar { it.uppercase() }
                if (cap != "Все" && cap != "Любая" && cap != "Разные") return cap
            }
        }

        // Распознавание по характерным словам в названии блюда, если поле страны спорное/пустое
        val lowerTitle = dishTitle.lowercase()
        when {
            lowerTitle.contains("кимчи") || lowerTitle.contains("корейск") || lowerTitle.contains("пибимпап") || lowerTitle.contains("токпокки") || lowerTitle.contains("яннём") -> return "Корейская"
            lowerTitle.contains("борщ") || lowerTitle.contains("сырник") || lowerTitle.contains("строганов") || lowerTitle.contains("пельмен") || lowerTitle.contains("оливье") || lowerTitle.contains("блин") -> return "Русская"
            lowerTitle.contains("рамен") || lowerTitle.contains("терияки") || lowerTitle.contains("суши") || lowerTitle.contains("удон") || lowerTitle.contains("мисо") || lowerTitle.contains("японск") -> return "Японская"
            lowerTitle.contains("паста") || lowerTitle.contains("карбонара") || lowerTitle.contains("пицца") || lowerTitle.contains("лазанья") || lowerTitle.contains("ризотто") || lowerTitle.contains("тирамису") -> return "Итальянская"
            lowerTitle.contains("круассан") || lowerTitle.contains("гратен") || lowerTitle.contains("жюльен") || lowerTitle.contains("жульен") || lowerTitle.contains("рататуй") || lowerTitle.contains("французск") -> return "Французская"
            lowerTitle.contains("хачапури") || lowerTitle.contains("чкмерули") || lowerTitle.contains("хинкали") || lowerTitle.contains("харчо") || lowerTitle.contains("грузинск") -> return "Грузинская"
            lowerTitle.contains("кесадилья") || lowerTitle.contains("тако") || lowerTitle.contains("буррито") || lowerTitle.contains("гуакамоле") || lowerTitle.contains("мексиканск") -> return "Мексиканская"
            lowerTitle.contains("том ям") || lowerTitle.contains("пад тай") || lowerTitle.contains("тайск") -> return "Тайская"
            lowerTitle.contains("карри") || lowerTitle.contains("масала") || lowerTitle.contains("индийск") -> return "Индийская"
            lowerTitle.contains("вок") || lowerTitle.contains("кисло-сладк") || lowerTitle.contains("китайск") -> return "Китайская"
        }

        if (selectedFilterCuisine != "Все") return selectedFilterCuisine
        return "Мировая"
    }

    // Определение реальной категории блюда из TheMealDB
    fun resolveRealCategory(rawCategory: String?, dishTitle: String, selectedFilterCategory: String): String {
        if (selectedFilterCategory != "Все") return selectedFilterCategory
        val lowerTitle = dishTitle.lowercase()
        if (lowerTitle.contains("суп") || lowerTitle.contains("борщ") || lowerTitle.contains("рамен") || lowerTitle.contains("бульон") || lowerTitle.contains("том ям")) {
            return "Супы"
        }
        if (lowerTitle.contains("салат") || lowerTitle.contains("цезарь") || lowerTitle.contains("оливье")) {
            return "Салаты"
        }
        val cleanCat = rawCategory?.trim()?.lowercase() ?: ""
        return when (cleanCat) {
            "starter" -> "Супы и закуски"
            "breakfast" -> "Каши и завтраки"
            "dessert" -> "Что-то к чаю"
            "pasta" -> "Паста и пицца"
            "side" -> "Салаты и гарниры"
            "beef", "chicken", "pork", "lamb", "seafood", "goat", "miscellaneous" -> "Вторые блюда"
            "vegan", "vegetarian" -> "Овощные блюда"
            else -> if (!hasLatin(cleanCat) && cleanCat.isNotEmpty()) cleanCat.replaceFirstChar { it.uppercase() } else "Вторые блюда"
        }
    }

    fun formatCuisineWithFlag(cuisine: String): String {
        val flag = CUISINE_FLAGS[cuisine] ?: "🌍"
        return "$flag $cuisine"
    }

    private val TIMER_REGEX = Regex("(\\d+)\\s*(мин(?:ут[а-хей]?)?|час(?:[а-ов]?)?|сек(?:унд[а-хей]?)?)", RegexOption.IGNORE_CASE)
    private val NUMBER_REGEX = Regex("(\\d+(?:[.,]\\d+)?)")
    private val STEP_SPLIT_REGEX = Regex("(?=\\b\\d+\\.)|\\r?\\n")
    private val STEP_PREFIX_REGEX = Regex("^\\d+[.)]\\s*")
    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
    private val LATIN_REGEX = Regex("[a-zA-Z]{2,}")
    private val NON_WORD_REGEX = Regex("[^а-яёa-z\\s-]")
    private val WHITESPACE_REGEX = Regex("\\s+")

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
        Regex("(?i)(\\d+)\\s*g\\b") to "$1 г",
        Regex("(?i)(\\d+)\\s*kg\\b") to "$1 кг",
        Regex("(?i)(\\d+)\\s*ml\\b") to "$1 мл",
        Regex("(?i)(\\d+)\\s*oz\\b") to "$1 унц.",
        Regex("(?i)(\\d+)\\s*lb(?:s)?\\b") to "$1 фунт.",
        Regex("(?i)\\btbsp(?:s)?\\b") to "ст.л.",
        Regex("(?i)\\btblsp(?:s)?\\b") to "ст.л.",
        Regex("(?i)\\btablespoon(?:s)?\\b") to "ст.л.",
        Regex("(?i)\\btsp(?:s)?\\b") to "ч.л.",
        Regex("(?i)\\bteaspoon(?:s)?\\b") to "ч.л.",
        Regex("(?i)\\b1/2\\b") to "0.5",
        Regex("(?i)\\b1/4\\b") to "0.25",
        Regex("(?i)\\b3/4\\b") to "0.75",
        Regex("(?i)\\b1/3\\b") to "0.3"
    )

    private val CULINARY_DICTIONARY: List<Pair<Regex, String>> = listOf(
        "extra virgin olive oil" to "оливковое масло первого отжима",
        "sunflower oil" to "подсолнечное масло",
        "vegetable oil" to "растительное масло",
        "sesame seed oil" to "кунжутное масло",
        "sesame oil" to "кунжутное масло",
        "coconut oil" to "кокосовое масло",
        "olive oil" to "оливковое масло",
        "peanut oil" to "арахисовое масло",
        "canola oil" to "рапсовое масло",
        "unsalted butter" to "сливочное масло",
        "salted butter" to "соленое сливочное масло",
        "melted butter" to "растопленное сливочное масло",
        "chicken breast" to "куриная грудка",
        "chicken breasts" to "куриные грудки",
        "chicken thighs" to "куриные бедра",
        "chicken thigh" to "куриное бедро",
        "chicken wings" to "куриные крылышки",
        "chicken stock" to "куриный бульон",
        "chicken broth" to "куриный бульон",
        "beef stock" to "говяжий бульон",
        "beef broth" to "говяжий бульон",
        "vegetable stock" to "овощной бульон",
        "vegetable broth" to "овощной бульон",
        "minced beef" to "говяжий фарш",
        "ground beef" to "говяжий фарш",
        "minced pork" to "свиной фарш",
        "ground pork" to "свиной фарш",
        "heavy cream" to "жирные сливки",
        "double cream" to "жирные сливки",
        "single cream" to "сливки",
        "sour cream" to "сметана",
        "whipping cream" to "сливки для взбивания",
        "cream cheese" to "сливочный сыр",
        "cheddar cheese" to "сыр Чеддер",
        "parmesan cheese" to "сыр Пармезан",
        "mozzarella cheese" to "сыр Моцарелла",
        "feta cheese" to "сыр Фета",
        "goat cheese" to "козий сыр",
        "spring onions" to "зеленый лук",
        "spring onion" to "зеленый лук",
        "green onions" to "зеленый лук",
        "green onion" to "зеленый лук",
        "red onion" to "красный лук",
        "red onions" to "красный лук",
        "yellow onion" to "репчатый лук",
        "garlic cloves" to "зубчики чеснока",
        "garlic clove" to "зубчик чеснока",
        "cloves garlic" to "зубчика чеснока",
        "clove garlic" to "зубчик чеснока",
        "garlic powder" to "сушеный чеснок",
        "onion powder" to "луковый порошок",
        "cherry tomatoes" to "томаты черри",
        "plum tomatoes" to "сливовидные томаты",
        "chopped tomatoes" to "рубленые томаты",
        "canned tomatoes" to "консервированные томаты",
        "tomato puree" to "томатное пюре",
        "tomato paste" to "томатная паста",
        "tomato sauce" to "томатный соус",
        "soy sauce" to "соевый соус",
        "fish sauce" to "рыбный соус",
        "oyster sauce" to "устричный соус",
        "worcestershire sauce" to "вустерский соус",
        "hot sauce" to "острый соус",
        "chili sauce" to "соус чили",
        "white wine vinegar" to "белый винный уксус",
        "red wine vinegar" to "красный винный уксус",
        "apple cider vinegar" to "яблочный уксус",
        "balsamic vinegar" to "бальзамический уксус",
        "rice vinegar" to "рисовый уксус",
        "white wine" to "белое сухое вино",
        "red wine" to "красное вино",
        "lemon juice" to "лимонный сок",
        "lime juice" to "сок лайма",
        "orange juice" to "апельсиновый сок",
        "lemon zest" to "цедра лимона",
        "orange zest" to "цедра апельсина",
        "all-purpose flour" to "пшеничная мука",
        "all purpose flour" to "пшеничная мука",
        "plain flour" to "пшеничная мука",
        "self-raising flour" to "мука с разрыхлителем",
        "self raising flour" to "мука с разрыхлителем",
        "bread flour" to "хлебопекарная мука",
        "corn flour" to "кукурузный крахмал",
        "cornflour" to "кукурузный крахмал",
        "cornstarch" to "кукурузный крахмал",
        "corn starch" to "кукурузный крахмал",
        "baking powder" to "разрыхлитель",
        "baking soda" to "пищевая сода",
        "bicarbonate of soda" to "пищевая сода",
        "caster sugar" to "мелкий сахар",
        "brown sugar" to "тростниковый сахар",
        "icing sugar" to "сахарная пудра",
        "powdered sugar" to "сахарная пудра",
        "granulated sugar" to "сахарный песок",
        "vanilla extract" to "ванильный экстракт",
        "black pepper" to "черный перец",
        "white pepper" to "белый перец",
        "cayenne pepper" to "кайенский перец",
        "red pepper flakes" to "хлопья красного перца",
        "chilli flakes" to "хлопья чили",
        "chili powder" to "порошок чили",
        "bell pepper" to "болгарский перец",
        "red pepper" to "красный сладкий перец",
        "green pepper" to "зеленый перец",
        "sweet potatoes" to "батат",
        "sweet potato" to "батат",
        "bay leaf" to "лавровый лист",
        "bay leaves" to "лавровый лист",
        "egg yolks" to "яичные желтки",
        "egg yolk" to "яичный желток",
        "egg whites" to "яичные белки",
        "egg white" to "яичный белок",
        "free-range eggs" to "домашние яйца",
        "coconut milk" to "кокосовое молоко",
        "condensed milk" to "сгущенное молоко",
        "almond milk" to "миндальное молоко",
        "bread crumbs" to "панировочные сухари",
        "breadcrumbs" to "панировочные сухари",
        "sesame seeds" to "семена кунжута",
        "sunflower seeds" to "семечки подсолнуха",
        "pine nuts" to "кедровые орешки",
        "peanut butter" to "арахисовая паста",
        "maple syrup" to "кленовый сироп",
        "to taste" to "по вкусу",
        "for garnish" to "для украшения",
        "to serve" to "для подачи",
        "finely chopped" to "мелко нарезанный",
        "roughly chopped" to "крупно нарезанный",
        "thinly sliced" to "тонко нарезанный",
        "freshly ground" to "свежемолотый",
        "room temperature" to "комнатной температуры",
        "preheat the oven" to "разогрейте духовку",
        "preheat oven" to "разогрейте духовку",
        "medium heat" to "средний огонь",
        "high heat" to "сильный огонь",
        "low heat" to "слабый огонь",
        "golden brown" to "золотистая корочка",
        "bring to a boil" to "доведите до кипения",
        "in a large bowl" to "в большой миске",
        "in a bowl" to "в миске",
        "frying pan" to "сковорода",
        "baking tray" to "противень",
        "baking sheet" to "противень",
        "chicken" to "курица", "beef" to "говядина", "pork" to "свинина", "lamb" to "баранина",
        "turkey" to "индейка", "duck" to "утка", "veal" to "телятина", "bacon" to "бекон",
        "sausage" to "колбаски", "sausages" to "колбаски", "ham" to "ветчина", "salami" to "салями",
        "prosciutto" to "прошутто", "chorizo" to "чоризо", "meat" to "мясо", "mince" to "фарш",
        "salmon" to "лосось", "tuna" to "тунец", "cod" to "треска", "fish" to "рыба",
        "shrimp" to "креветки", "prawns" to "креветки", "prawn" to "креветка", "crab" to "краб",
        "squid" to "кальмар", "mussels" to "мидии", "seafood" to "морепродукты",
        "potatoes" to "картофель", "potato" to "картофель", "carrots" to "морковь", "carrot" to "морковь",
        "onions" to "лук", "onion" to "лук", "shallots" to "лук-шалот", "shallot" to "лук-шалот",
        "leek" to "лук-порей", "leeks" to "лук-порей", "garlic" to "чеснок", "ginger" to "имбирь",
        "tomatoes" to "томаты", "tomato" to "томат", "cucumber" to "огурец", "cucumbers" to "огурцы",
        "cabbage" to "капуста", "broccoli" to "брокколи", "cauliflower" to "цветная капуста",
        "spinach" to "шпинат", "lettuce" to "листья салата", "celery" to "сельдерей",
        "zucchini" to "кабачок", "courgette" to "кабачок", "courgettes" to "кабачки",
        "eggplant" to "баклажан", "aubergine" to "баклажан", "pumpkin" to "тыква",
        "mushrooms" to "грибы", "mushroom" to "грибы", "peas" to "зеленый горошек",
        "beans" to "фасоль", "lentils" to "чечевица", "chickpeas" to "нут", "corn" to "кукуруза",
        "sweetcorn" to "сладкая кукуруза", "avocado" to "авокадо", "olives" to "оливки",
        "capers" to "каперсы", "beetroot" to "свекла", "beets" to "свекла", "radish" to "редис",
        "asparagus" to "спаржа", "artichoke" to "артишок", "peppers" to "перец",
        "parsley" to "петрушка", "cilantro" to "кинза", "coriander" to "кориандр",
        "dill" to "укроп", "basil" to "базилик", "mint" to "мята", "rosemary" to "розмарин",
        "thyme" to "тимьян", "oregano" to "орегано", "sage" to "шалфей", "chives" to "зеленый лук",
        "tarragon" to "эстрагон", "paprika" to "паприка", "cumin" to "зира (кумин)",
        "turmeric" to "куркума", "cinnamon" to "корица", "nutmeg" to "мускатный орех",
        "cloves" to "зубчика", "clove" to "зубчик", "cardamom" to "кардамон", "saffron" to "шафран",
        "vanilla" to "ваниль", "curry" to "карри", "chili" to "чили", "chilli" to "чили",
        "mustard" to "горчица", "mayonnaise" to "майонез", "ketchup" to "кетчуп",
        "vinegar" to "уксус", "sauce" to "соус", "paste" to "паста", "stock" to "бульон",
        "broth" to "бульон", "bouillon" to "бульон", "water" to "вода", "ice" to "лед",
        "milk" to "молоко", "cream" to "сливки", "butter" to "сливочное масло",
        "yogurt" to "йогурт", "yoghurt" to "йогурт", "kefir" to "кефир", "cheese" to "сыр",
        "parmesan" to "Пармезан", "mozzarella" to "Моцарелла", "cheddar" to "Чеддер",
        "ricotta" to "Рикотта", "mascarpone" to "Маскарпоне", "tofu" to "сыр тофу",
        "eggs" to "яйца", "egg" to "яйцо", "yolk" to "желток", "yolks" to "желтки",
        "flour" to "мука", "sugar" to "сахар", "salt" to "соль", "pepper" to "перец",
        "honey" to "мёд", "syrup" to "сироп", "jam" to "джем", "chocolate" to "шоколад",
        "cocoa" to "какао", "yeast" to "дрожжи", "gelatin" to "желатин", "starch" to "крахмал",
        "rice" to "рис", "pasta" to "паста", "spaghetti" to "спагетти", "macaroni" to "макароны",
        "noodles" to "лапша", "noodle" to "лапша", "penne" to "пенне", "lasagne" to "лазанья",
        "lasagna" to "лазанья", "oats" to "овсяные хлопья", "oatmeal" to "овсянка",
        "couscous" to "кускус", "quinoa" to "киноа", "bulgur" to "булгур", "bread" to "хлеб",
        "baguette" to "багет", "tortillas" to "лепешки тортильи", "tortilla" to "тортилья",
        "pita" to "пита", "dough" to "тесто", "pastry" to "слоеное тесто",
        "apple" to "яблоко", "apples" to "яблоки", "banana" to "банан", "bananas" to "бананы",
        "lemon" to "лимон", "lemons" to "лимоны", "lime" to "лайм", "limes" to "лаймы",
        "orange" to "апельсин", "oranges" to "апельсины", "peach" to "персик", "pear" to "груша",
        "pineapple" to "ананас", "mango" to "манго", "strawberries" to "клубника",
        "strawberry" to "клубника", "raspberries" to "малина", "blueberries" to "черника",
        "berries" to "ягоды", "cherries" to "вишня", "grapes" to "виноград", "raisins" to "изюм",
        "walnuts" to "грецкие орехи", "walnut" to "грецкий орех", "almonds" to "миндаль",
        "almond" to "миндаль", "hazelnuts" to "фундук", "peanuts" to "арахис",
        "cashews" to "кешью", "pistachios" to "фисташки", "pecans" to "пекан", "nuts" to "орехи",
        "coconut" to "кокос", "oil" to "растительное масло",
        "cups" to "стакана", "cup" to "стакан", "tablespoons" to "ст.л.", "tablespoon" to "ст.л.",
        "tbsp" to "ст.л.", "tbs" to "ст.л.", "teaspoons" to "ч.л.", "teaspoon" to "ч.л.",
        "tsp" to "ч.л.", "ounces" to "унц.", "ounce" to "унц.", "oz" to "унц.",
        "pounds" to "фунт.", "pound" to "фунт.", "lb" to "фунт.", "lbs" to "фунт.",
        "grams" to "г", "gram" to "г", "kilograms" to "кг", "milliliters" to "мл",
        "liters" to "л", "litre" to "л", "litres" to "л", "pint" to "пинта",
        "pinch" to "щепотка", "dash" to "щепотка", "handful" to "горсть", "bunch" to "пучок",
        "slice" to "ломтик", "slices" to "ломтика", "piece" to "кусочек", "pieces" to "кусочка",
        "can" to "банка", "tin" to "банка", "jar" to "баночка", "packet" to "упаковка",
        "package" to "упаковка", "pkg" to "уп.", "sprig" to "веточка", "sprigs" to "веточки",
        "stalk" to "стебель", "stalks" to "стебля", "head" to "головка", "fillet" to "филе",
        "fillets" to "филе", "breast" to "грудка", "thighs" to "бедра", "wings" to "крылышки",
        "large" to "крупный", "medium" to "средний", "small" to "мелкий", "fresh" to "свежий",
        "dried" to "сушеный", "dry" to "сухой", "frozen" to "замороженный", "canned" to "консервированный",
        "smoked" to "копченый", "salted" to "соленый", "unsalted" to "несоленый",
        "sweet" to "сладкий", "hot" to "острый", "spicy" to "пряный", "warm" to "теплый",
        "cold" to "холодный", "boiling" to "кипящий", "melted" to "растопленный",
        "softened" to "размягченный", "chopped" to "нарезанный", "diced" to "нарезанный кубиками",
        "sliced" to "нарезанный ломтиками", "minced" to "измельченный", "grated" to "тертый",
        "shredded" to "нашинкованный", "crushed" to "раздавленный", "peeled" to "очищенный",
        "beaten" to "взбитый", "mashed" to "размятый", "roasted" to "запеченный",
        "fried" to "обжаренный", "boiled" to "отварной", "baked" to "печеный",
        "cooked" to "готовый", "raw" to "сырой", "whole" to "целый", "halved" to "разрезанный пополам",
        "quartered" to "разрезанный на четвертинки", "finely" to "мелко", "roughly" to "крупно",
        "thinly" to "тонко", "optional" to "по желанию",
        "preheat" to "разогрейте", "heat" to "нагрейте", "boil" to "варите", "simmer" to "томите",
        "fry" to "обжарьте", "saute" to "обжарьте", "bake" to "выпекайте", "roast" to "запекайте",
        "grill" to "обжарьте на гриле", "steam" to "готовьте на пару", "mix" to "смешайте",
        "stir" to "перемешайте", "whisk" to "взбейте венчиком", "beat" to "взбейте",
        "blend" to "измельчите блендером", "chop" to "нарежьте", "slice" to "нарежьте ломтиками",
        "dice" to "нарежьте кубиками", "peel" to "очистите", "grate" to "натрите на терке",
        "pour" to "влейте", "add" to "добавьте", "place" to "поместите", "put" to "положите",
        "remove" to "выньте", "drain" to "слейте жидкость", "strain" to "процедите",
        "cover" to "накройте крышкой", "season" to "приправьте", "sprinkle" to "посыпьте",
        "garnish" to "украсьте", "serve" to "подавайте", "cook" to "готовьте",
        "leave" to "оставьте", "let" to "дайте", "cool" to "остудите", "chill" to "охладите",
        "oven" to "духовка", "pan" to "сковорода", "skillet" to "сковорода", "pot" to "кастрюля",
        "saucepan" to "сотейник", "bowl" to "миска", "plate" to "тарелка", "dish" to "блюдо",
        "knife" to "нож", "spoon" to "ложка", "fork" to "вилка", "lid" to "крышка",
        "minutes" to "минут", "minute" to "минуту", "mins" to "минут", "min" to "мин",
        "hours" to "часа", "hour" to "час", "seconds" to "секунд", "until" to "до",
        "tender" to "мягкости", "golden" to "золотистости", "brown" to "румяности",
        "with" to "с", "and" to "и", "or" to "или", "in" to "в", "on" to "на", "for" to "в течение",
        "over" to "на", "into" to "в", "from" to "из", "then" to "затем", "after" to "после"
    ).sortedByDescending { it.first.length }
        .map { (en, ru) -> Regex("(?i)(?<![a-zA-Z])${Regex.escape(en)}(?![a-zA-Z])") to ru }

    fun hasLatin(text: String): Boolean = LATIN_REGEX.containsMatchIn(text)

    fun normalizeAndTranslateCulinary(text: String): String {
        var res = text.trim()
        if (res.isEmpty()) return res

        for ((regex, replacement) in UNIT_PRE_REPLACEMENTS) {
            res = regex.replace(res, replacement)
        }
        if (!hasLatin(res)) return res

        for ((regex, ru) in CULINARY_DICTIONARY) {
            res = regex.replace(res, ru)
            if (!hasLatin(res)) break
        }

        res = res.replace(Regex("(?i)\\b(the|a|an|of|to)\\b"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return res
    }

    fun isIngredientInFridge(recipeIngredient: String, fridgeItems: List<String>): Boolean {
        if (fridgeItems.isEmpty()) return false
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

    fun scaleIngredientAmount(ingredient: String, portions: Int, basePortions: Int = 2): String {
        if (portions == basePortions) return ingredient
        val ratio = portions.toDouble() / basePortions.toDouble()
        return NUMBER_REGEX.replace(ingredient) { matchResult ->
            val num = matchResult.value.replace(",", ".").toDoubleOrNull() ?: return@replace matchResult.value
            val scaled = num * ratio
            if (scaled >= 10 || scaled % 1.0 == 0.0) {
                scaled.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.1f", scaled).replace(".0", "")
            }
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
        for ((regex, replacement) in SPEECH_REPLACEMENTS) {
            result = regex.replace(result, replacement)
        }
        return result
            .replace("°C", " градусов Цельсия")
            .replace("°", " градусов")
            .replace(" - ", " — ")
    }

    fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }

    fun ensureValidInstructions(rawSteps: List<String>, title: String, ingredients: List<String>): List<String> {
        val cleaned = rawSteps
            .flatMap { it.split(STEP_SPLIT_REGEX) }
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

    fun parseVoiceCommand(
        spokenText: String,
        currentCuisine: String,
        currentCategory: String
    ): Triple<String, String, List<String>> {
        val lower = spokenText.lowercase()
        var newCuisine = currentCuisine
        var newCategory = currentCategory

        val cuisineRoots = mapOf(
            "русск" to "Русская", "корейск" to "Корейская", "французск" to "Французская",
            "итальянск" to "Итальянская", "японск" to "Японская", "грузинск" to "Грузинская",
            "мексиканск" to "Мексиканская", "китайск" to "Китайская", "тайск" to "Тайская", "индийск" to "Индийская"
        )
        cuisineRoots.forEach { (root, name) -> if (lower.contains(root)) newCuisine = name }

        val categoryRoots = mapOf(
            "суп" to "Супы", "бульон" to "Супы", "каш" to "Каши и завтраки", "завтрак" to "Каши и завтраки",
            "выпечк" to "Выпечка", "пирог" to "Выпечка", "к чаю" to "Что-то к чаю", "десерт" to "Что-то к чаю",
            "сладк" to "Что-то к чаю", "салат" to "Салаты", "паст" to "Паста и пицца", "пицц" to "Паста и пицца",
            "второе" to "Вторые блюда", "ужин" to "Вторые блюда"
        )
        categoryRoots.forEach { (root, name) -> if (lower.contains(root)) newCategory = name }

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
            .replace(NON_WORD_REGEX, " ")
            .split(WHITESPACE_REGEX)
            .map { it.trim() }
            .filter { it.length > 2 && !stopWords.contains(it) }
            .filter { w -> cuisineRoots.keys.none { w.startsWith(it) } }
            .map { w -> normalizeMap[w] ?: w.replaceFirstChar { it.uppercase() } }
            .distinct()

        return Triple(newCuisine, newCategory, extracted)
    }

    fun playAlarmSound() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        } catch (_: Exception) {}
    }
}

// =========================================================================
// 4. АСИНХРОННЫЙ МЕНЕДЖЕР ХРАНИЛИЩА
// =========================================================================
class RecipeStorage(context: Context) {
    private val prefs = context.getSharedPreferences("chef_app_v8_storage", Context.MODE_PRIVATE)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isDarkTheme(systemDefault: Boolean): Boolean =
        if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", systemDefault) else systemDefault

    fun saveDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun loadStringSet(key: String): List<String> =
        prefs.getStringSet(key, emptySet())?.toList() ?: emptyList()

    fun loadRecipeList(key: String): List<Recipe> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { Recipe.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAllAsync(
        fridge: List<String>,
        cart: List<String>,
        favorites: List<Recipe>,
        customRecipes: List<Recipe>
    ) {
        val fridgeCopy = fridge.toSet()
        val cartCopy = cart.toSet()
        val favCopy = favorites.toList()
        val customCopy = customRecipes.toList()

        ioScope.launch {
            val favArr = JSONArray().apply { favCopy.forEach { put(it.toJson()) } }
            val customArr = JSONArray().apply { customCopy.forEach { put(it.toJson()) } }
            prefs.edit()
                .putStringSet("fridge", fridgeCopy)
                .putStringSet("cart", cartCopy)
                .putString("fav_recipes_json", favArr.toString())
                .putString("custom_recipes_json", customArr.toString())
                .apply()
        }
    }
}

// =========================================================================
// 5. КОНТРОЛЛЕР ГОЛОСОВОЙ ОЗВУЧКИ
// =========================================================================
class SpeechManager(
    private val context: Context,
    private val onSpeakingStateChanged: (String?) -> Unit
) {
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
                        val voices = ttsEngine?.voices
                            ?.filter { it.locale.language.equals("ru", ignoreCase = true) }
                            ?.sortedWith(
                                compareByDescending<Voice> { it.name.contains("network", ignoreCase = true) }
                                    .thenByDescending { it.name.contains("ruf", ignoreCase = true) || it.name.contains("rud", ignoreCase = true) }
                                    .thenByDescending { it.quality }
                            ) ?: emptyList()

                        if (voices.isNotEmpty()) {
                            availableRuVoices = voices
                            ttsEngine?.voice = voices[0]
                        }
                    } catch (_: Exception) {}

                    ttsEngine?.setSpeechRate(0.98f)
                    ttsEngine?.setPitch(0.98f)
                    ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            val root = utteranceId?.substringBefore("__part_")
                            mainHandler.post { updateState(root) }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null && utteranceId.endsWith("_last")) {
                                mainHandler.post { updateState(null) }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post { updateState(null) }
                        }
                    })
                }
            }
        }
        ttsEngine = try {
            TextToSpeech(context, initListener, "com.google.android.tts")
        } catch (_: Exception) {
            TextToSpeech(context, initListener)
        }
    }

    private fun updateState(newRootId: String?) {
        currentRootId = newRootId
        onSpeakingStateChanged(newRootId)
    }

    fun stop() {
        updateState(null)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (_: Exception) {}
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {}
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
        } else {
            Toast.makeText(context, "Доступен 1 системный голосовой профиль", Toast.LENGTH_SHORT).show()
        }
    }

    fun speak(rootUtteranceId: String, rawText: String) {
        if (currentRootId == rootUtteranceId) {
            stop()
            return
        }
        stop()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Toast.makeText(context, "🔊 Прибавьте громкость мультимедиа на телефоне!", Toast.LENGTH_LONG).show()
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
                if (engine.speak(sentence, queueMode, params, partId) == TextToSpeech.SUCCESS) {
                    spokeOk = true
                }
            }
            if (spokeOk) return
        }

        playCloudChunksSequentially(rootUtteranceId, sentences, 0)
    }

    private fun playCloudChunksSequentially(rootId: String, chunks: List<String>, index: Int) {
        if (currentRootId != rootId || index >= chunks.size) {
            if (currentRootId == rootId) updateState(null)
            return
        }
        try {
            val encoded = URLEncoder.encode(chunks[index].take(180), "UTF-8")
            val streamUrl = "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ru&q=$encoded"
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(streamUrl)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    playCloudChunksSequentially(rootId, chunks, index + 1)
                }
                setOnErrorListener { _, _, _ ->
                    updateState(null)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            updateState(null)
        }
    }

    fun release() {
        stop()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        try {
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
    }
}

// =========================================================================
// 6. ACTIVITY И ГЛАВНЫЙ ЭКРАН
// =========================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val storage = remember { RecipeStorage(context) }
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(storage.isDarkTheme(systemDark)) }

            MaterialTheme(
                colorScheme = if (isDarkTheme) DarkCulinaryColorScheme else LightCulinaryColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecipeAppScreen(
                        storage = storage,
                        isDarkTheme = isDarkTheme,
                        onToggleDarkTheme = {
                            isDarkTheme = !isDarkTheme
                            storage.saveDarkTheme(isDarkTheme)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAppScreen(
    storage: RecipeStorage,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentlySpeakingId by remember { mutableStateOf<String?>(null) }
    val speechManager = remember(context) {
        SpeechManager(context) { newId -> currentlySpeakingId = newId }
    }

    DisposableEffect(speechManager) {
        onDispose { speechManager.release() }
    }

    var currentScreen by remember { mutableStateOf(AppScreen.RECIPES) }
    var selectedMode by remember { mutableStateOf(DataSourceMode.LOCAL) }
    var selectedCuisine by remember { mutableStateOf("Все") }
    var selectedCategory by remember { mutableStateOf("Все") }
    var selectedSort by remember { mutableStateOf(SortOrder.FRESH_BATCH) }
    var sliderPosition by remember { mutableFloatStateOf(10f) }
    var committedLimitCount by remember { mutableIntStateOf(10) }
    var refreshSeed by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyFavorites by remember { mutableStateOf(false) }
    var isFiltersExpanded by remember { mutableStateOf(true) }

    var ingredientInput by remember { mutableStateOf("") }
    var lastVoiceCommand by remember { mutableStateOf<String?>(null) }

    val fridgeItems = remember { mutableStateListOf<String>().apply { addAll(storage.loadStringSet("fridge")) } }
    val shoppingListItems = remember { mutableStateListOf<String>().apply { addAll(storage.loadStringSet("cart")) } }
    val checkedShoppingItems = remember { mutableStateListOf<String>() }
    val favoriteRecipes = remember { mutableStateListOf<Recipe>().apply { addAll(storage.loadRecipeList("fav_recipes_json")) } }
    val customUserRecipes = remember { mutableStateListOf<Recipe>().apply { addAll(storage.loadRecipeList("custom_recipes_json")) } }

    fun persistAll() {
        storage.saveAllAsync(fridgeItems, shoppingListItems, favoriteRecipes, customUserRecipes)
    }

    var recipes by remember {
        mutableStateOf(
            RecipeRepository.buildLocalDatabaseRecipes("Все", "Все", emptyList(), customUserRecipes, 10, 0)
        )
    }
    var isLoading by remember { mutableStateOf(false) }
    var activeLoadJob by remember { mutableStateOf<Job?>(null) }

    // При каждом вызове с forceNewBatch = true увеличивается refreshSeed и ищутся НОВЫЕ блюда!
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
        if (forceNewBatch) {
            refreshSeed += 1
            selectedSort = SortOrder.FRESH_BATCH
        }
        val currentSeed = refreshSeed
        val currentFridge = fridgeItems.toList()
        val currentCustom = customUserRecipes.toList()

        activeLoadJob?.cancel()
        activeLoadJob = coroutineScope.launch {
            if (modeToUse == DataSourceMode.LOCAL) {
                recipes = RecipeRepository.buildLocalDatabaseRecipes(
                    cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed
                )
                isLoading = false
                return@launch
            }

            isLoading = true
            try {
                recipes = when (modeToUse) {
                    DataSourceMode.LOCAL -> {
                        RecipeRepository.buildLocalDatabaseRecipes(
                            cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed
                        )
                    }
                    DataSourceMode.MEAL_DB_API -> {
                        RecipeRepository.fetchTranslatedMealDbRecipes(
                            cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed
                        )
                    }
                    DataSourceMode.FREE_AI -> {
                        RecipeRepository.fetchAiRussianRecipes(
                            cuisineToUse, categoryToUse, currentFridge, currentCustom, voiceText, targetCount, currentSeed
                        )
                    }
                }
            } catch (_: Exception) {
                recipes = RecipeRepository.buildLocalDatabaseRecipes(
                    cuisineToUse, categoryToUse, currentFridge, currentCustom, targetCount, currentSeed
                )
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedCuisine, selectedCategory, committedLimitCount, fridgeItems.size, customUserRecipes.size, selectedMode) {
        if (selectedMode == DataSourceMode.LOCAL) {
            recipes = RecipeRepository.buildLocalDatabaseRecipes(
                selectedCuisine,
                selectedCategory,
                fridgeItems.toList(),
                customUserRecipes.toList(),
                committedLimitCount,
                refreshSeed
            )
        }
    }

    fun addIngredientsFromInput() {
        val parts = ingredientInput.split(",", ";")
            .map { it.trim().replaceFirstChar { ch -> ch.uppercase() } }
            .filter { it.isNotEmpty() && !fridgeItems.contains(it) }
        if (parts.isNotEmpty()) {
            fridgeItems.addAll(parts)
            persistAll()
        }
        ingredientInput = ""
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                lastVoiceCommand = spokenText
                val (newCuisine, newCategory, extractedIngs) = CulinaryUtils.parseVoiceCommand(
                    spokenText, selectedCuisine, selectedCategory
                )
                selectedCuisine = newCuisine
                selectedCategory = newCategory
                if (extractedIngs.isNotEmpty()) {
                    fridgeItems.clear()
                    fridgeItems.addAll(extractedIngs)
                    persistAll()
                }
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
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Голосовой ввод Google не установлен", Toast.LENGTH_LONG).show()
        }
    }

    // Мгновенная фильтрация и сортировка с сохранением новизны партии (FRESH_BATCH)
    val displayedRecipes by remember(recipes, showOnlyFavorites, favoriteRecipes.size, searchQuery, selectedSort, fridgeItems.size) {
        derivedStateOf {
            val currentFridge = fridgeItems.toList()
            val base = if (showOnlyFavorites) favoriteRecipes.toList() else recipes
            val searched = if (searchQuery.isBlank()) base else base.filter {
                it.title.contains(searchQuery.trim(), ignoreCase = true) ||
                it.cuisine.contains(searchQuery.trim(), ignoreCase = true) ||
                it.ingredients.any { ing -> ing.contains(searchQuery.trim(), ignoreCase = true) }
            }
            when (selectedSort) {
                SortOrder.FRESH_BATCH -> searched
                SortOrder.FRIDGE_MATCH -> searched.sortedWith(
                    compareByDescending<Recipe> { it.isCustom }
                        .thenByDescending { r -> r.ingredients.count { CulinaryUtils.isIngredientInFridge(it, currentFridge) } }
                )
                SortOrder.FASTEST -> searched.sortedBy { it.cookingMinutes }
                SortOrder.LOW_CALORIE -> searched.sortedBy { it.calories }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Верхняя навигационная панель
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Шеф-Повар 🍲",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        currentScreen = AppScreen.RECIPES
                        showOnlyFavorites = false
                    }
                )
                IconButton(
                    onClick = onToggleDarkTheme,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Переключить тему",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentlySpeakingId != null) {
                    AssistChip(
                        onClick = { speechManager.stop() },
                        label = { Text("Стоп 🔇", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    )
                } else {
                    AssistChip(
                        onClick = { speechManager.switchVoiceProfile() },
                        label = { Text("🗣", fontSize = 11.sp) }
                    )
                }

                FilterChip(
                    selected = currentScreen == AppScreen.ADD_RECIPE,
                    onClick = {
                        currentScreen = if (currentScreen == AppScreen.ADD_RECIPE) AppScreen.RECIPES else AppScreen.ADD_RECIPE
                    },
                    label = { Text("➕", fontSize = 11.sp) }
                )

                FilterChip(
                    selected = currentScreen == AppScreen.SHOPPING_LIST,
                    onClick = {
                        currentScreen = if (currentScreen == AppScreen.SHOPPING_LIST) AppScreen.RECIPES else AppScreen.SHOPPING_LIST
                    },
                    label = { Text("🛒 (${shoppingListItems.size})", fontSize = 11.sp) }
                )

                FilterChip(
                    selected = showOnlyFavorites && currentScreen == AppScreen.RECIPES,
                    onClick = {
                        currentScreen = AppScreen.RECIPES
                        showOnlyFavorites = !showOnlyFavorites
                    },
                    label = { Text("❤️ (${favoriteRecipes.size})", fontSize = 11.sp) }
                )
            }
        }

        when (currentScreen) {
            AppScreen.SHOPPING_LIST -> {
                Spacer(modifier = Modifier.height(6.dp))
                ShoppingListView(
                    shoppingItems = shoppingListItems,
                    checkedItems = checkedShoppingItems,
                    onUpdatePrefs = { persistAll() },
                    onBackToRecipes = { currentScreen = AppScreen.RECIPES }
                )
            }

            AppScreen.ADD_RECIPE -> {
                Spacer(modifier = Modifier.height(6.dp))
                AddCustomRecipeView(
                    cuisines = CulinaryUtils.CUISINES.filter { it != "Все" } + "Домашняя",
                    categories = CulinaryUtils.CATEGORIES.filter { it != "Все" },
                    onSaveRecipe = { newRecipe ->
                        customUserRecipes.add(0, newRecipe)
                        favoriteRecipes.add(0, newRecipe)
                        persistAll()
                        selectedMode = DataSourceMode.LOCAL
                        currentScreen = AppScreen.RECIPES
                        loadRecipes(DataSourceMode.LOCAL)
                        Toast.makeText(context, "✅ Рецепт «${newRecipe.title}» сохранен!", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { currentScreen = AppScreen.RECIPES }
                )
            }

            AppScreen.RECIPES -> {
                if (!isFiltersExpanded && !showOnlyFavorites) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { isFiltersExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${selectedMode.label} • $selectedCuisine • $selectedCategory",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Найдено: ${displayedRecipes.size} блюд (нажми для фильтров ▼)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    loadRecipes(
                                        targetCount = committedLimitCount,
                                        collapseHeader = true,
                                        forceNewBatch = true
                                    )
                                },
                                enabled = !isLoading,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Новые блюда", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Новые", fontSize = 12.sp)
                            }
                        }
                    }
                } else if (!showOnlyFavorites) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            items(DataSourceMode.entries) { mode ->
                                FilterChip(
                                    selected = selectedMode == mode,
                                    onClick = {
                                        selectedMode = mode
                                        loadRecipes(mode, selectedCuisine, selectedCategory, forceNewBatch = true)
                                    },
                                    label = { Text(mode.label, fontSize = 12.sp) }
                                )
                            }
                        }
                        IconButton(onClick = { isFiltersExpanded = false }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Свернуть фильтры")
                        }
                    }
                }

                AnimatedVisibility(visible = isFiltersExpanded && !showOnlyFavorites) {
                    Column {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🧊 Что есть в холодильнике:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    if (fridgeItems.isNotEmpty() || lastVoiceCommand != null) {
                                        Text(
                                            text = "Очистить всё",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                fridgeItems.clear()
                                                lastVoiceCommand = null
                                                persistAll()
                                                loadRecipes(forceNewBatch = true)
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
                                                    persistAll()
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
                                    items(CulinaryUtils.QUICK_FRIDGE) { suggestion ->
                                        val isSelected = fridgeItems.contains(suggestion)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (isSelected) fridgeItems.remove(suggestion)
                                                else fridgeItems.add(suggestion)
                                                persistAll()
                                            },
                                            label = { Text(if (isSelected) "✓ $suggestion" else "+ $suggestion", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text("Кухня мира:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CulinaryUtils.CUISINES) { cuisine ->
                                FilterChip(
                                    selected = selectedCuisine == cuisine,
                                    onClick = {
                                        selectedCuisine = cuisine
                                        loadRecipes(selectedMode, cuisine, selectedCategory, forceNewBatch = true)
                                    },
                                    label = {
                                        Text(
                                            if (cuisine == "Все") "🌍 Все" else CulinaryUtils.formatCuisineWithFlag(cuisine),
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                            }
                        }

                        Text("Тип блюда:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CulinaryUtils.CATEGORIES) { category ->
                                FilterChip(
                                    selected = selectedCategory == category,
                                    onClick = {
                                        selectedCategory = category
                                        loadRecipes(selectedMode, selectedCuisine, category, forceNewBatch = true)
                                    },
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
                                Text("Вариантов: ${sliderPosition.roundToInt()}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Slider(
                                    value = sliderPosition,
                                    onValueChange = { sliderPosition = it },
                                    onValueChangeFinished = {
                                        val newLimit = sliderPosition.roundToInt()
                                        if (newLimit != committedLimitCount) {
                                            committedLimitCount = newLimit
                                            if (selectedMode != DataSourceMode.LOCAL) {
                                                loadRecipes(targetCount = newLimit, forceNewBatch = false)
                                            }
                                        }
                                    },
                                    valueRange = 5f..20f,
                                    steps = 14
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    committedLimitCount = sliderPosition.roundToInt()
                                    loadRecipes(
                                        targetCount = committedLimitCount,
                                        collapseHeader = true,
                                        forceNewBatch = true
                                    )
                                },
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Подобрать новые", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Подобрать")
                            }
                        }
                    }
                }

                // Поиск + Сортировка
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по названию, кухне или продукту...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Сбросить", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    items(SortOrder.entries) { sortOption ->
                        FilterChip(
                            selected = selectedSort == sortOption,
                            onClick = { selectedSort = sortOption },
                            label = { Text(sortOption.label, fontSize = 11.sp) }
                        )
                    }
                }

                if (showOnlyFavorites) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("❤️ Мои сохраненные рецепты (${favoriteRecipes.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        TextButton(onClick = { showOnlyFavorites = false }) {
                            Text("Ко всем рецептам")
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
                                    DataSourceMode.FREE_AI -> "✨ ИИ придумывает новые блюда (партия #${refreshSeed + 1})..."
                                    DataSourceMode.MEAL_DB_API -> "🌍 Ищем новые блюда в мировой базе и переводим..."
                                    DataSourceMode.LOCAL -> "Обновляем подборку рецептов..."
                                }
                            )
                        }
                    }
                } else if (displayedRecipes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (showOnlyFavorites) "Вы еще не добавили ни одного рецепта в Избранное ❤️"
                                else "По вашему запросу ничего не найдено"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (showOnlyFavorites || searchQuery.isNotEmpty()) {
                                Button(onClick = {
                                    showOnlyFavorites = false
                                    searchQuery = ""
                                }) {
                                    Text("Показать все рецепты")
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
                            val isFav = favoriteRecipes.any { it.id == recipe.id || it.title == recipe.title }
                            RecipeCard(
                                index = index + 1,
                                recipe = recipe,
                                isDarkTheme = isDarkTheme,
                                fridgeItems = fridgeItems.toList(),
                                shoppingListItems = shoppingListItems,
                                isFavorite = isFav,
                                currentlySpeakingId = currentlySpeakingId,
                                onSpeakText = { utteranceId, text -> speechManager.speak(utteranceId, text) },
                                onAddMissingToCart = { missingItems ->
                                    var addedCount = 0
                                    missingItems.forEach { item ->
                                        if (!shoppingListItems.contains(item)) {
                                            shoppingListItems.add(item)
                                            addedCount++
                                        }
                                    }
                                    persistAll()
                                    Toast.makeText(
                                        context,
                                        if (addedCount > 0) "Добавлено в список покупок: $addedCount шт. 🛒"
                                        else "Все эти продукты уже есть в списке покупок!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleFavorite = {
                                    val existingIdx = favoriteRecipes.indexOfFirst { it.id == recipe.id || it.title == recipe.title }
                                    if (existingIdx != -1) {
                                        favoriteRecipes.removeAt(existingIdx)
                                    } else {
                                        favoriteRecipes.add(0, recipe)
                                    }
                                    persistAll()
                                },
                                onDeleteCustom = if (recipe.isCustom) {
                                    {
                                        customUserRecipes.removeAll { it.id == recipe.id }
                                        favoriteRecipes.removeAll { it.id == recipe.id }
                                        persistAll()
                                        loadRecipes()
                                        Toast.makeText(context, "Рецепт удален", Toast.LENGTH_SHORT).show()
                                    }
                                } else null
                            )
                        }

                        // Кнопка в самом низу списка для удобной подгрузки следующей партии блюд
                        if (!showOnlyFavorites) {
                            item {
                                OutlinedButton(
                                    onClick = {
                                        loadRecipes(
                                            targetCount = committedLimitCount,
                                            collapseHeader = true,
                                            forceNewBatch = true
                                        )
                                    },
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

// =========================================================================
// 7. ЭКРАН ДОБАВЛЕНИЯ СОБСТВЕННОГО РЕЦЕПТА
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomRecipeView(
    cuisines: List<String>,
    categories: List<String>,
    onSaveRecipe: (Recipe) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var selectedCuisine by remember { mutableStateOf(cuisines.firstOrNull() ?: "Русская") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Вторые блюда") }
    var cookingTime by remember { mutableStateOf("35 мин") }
    var calories by remember { mutableStateOf("250") }
    var customImageUrl by remember { mutableStateOf("") }
    var ingredientsText by remember { mutableStateOf("") }
    var instructionsText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("👨‍🍳 Добавить свой рецепт", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Отмена")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название блюда *") },
            placeholder = { Text("Например: Домашние пельмени") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Кухня:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cuisines) { c ->
                FilterChip(
                    selected = selectedCuisine == c,
                    onClick = { selectedCuisine = c },
                    label = { Text(CulinaryUtils.formatCuisineWithFlag(c), fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text("Категория:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = cookingTime,
                onValueChange = { cookingTime = it },
                label = { Text("Время готовки") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = calories,
                onValueChange = { calories = it },
                label = { Text("Ккал на 100 г") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ingredientsText,
            onValueChange = { ingredientsText = it },
            label = { Text("Ингредиенты (с новой строки или через запятую) *") },
            placeholder = { Text("Курица - 500 г\nКартофель - 4 шт.\nСыр - 100 г") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = instructionsText,
            onValueChange = { instructionsText = it },
            label = { Text("Шаги приготовления (каждый шаг с новой строки) *") },
            placeholder = { Text("Нарежьте курицу и картофель.\nОбжарьте на среднем огне 10 минут.\nЗапекайте в духовке 25 минут при 180°C.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = customImageUrl,
            onValueChange = { customImageUrl = it },
            label = { Text("Ссылка на фото (необязательно — сгенерируется само)") },
            placeholder = { Text("https://...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (title.isBlank() || ingredientsText.isBlank()) {
                    Toast.makeText(context, "Заполните название и ингредиенты!", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val parsedIngs = ingredientsText.split("\n", ",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val rawSteps = instructionsText.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val finalSteps = CulinaryUtils.ensureValidInstructions(rawSteps, title.trim(), parsedIngs)

                val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8").replace("+", "%20")
                val autoImg1 = "https://image.pollinations.ai/prompt/$encodedTitle%20delicious%20dish%20food%20photography?width=600&height=400&nologo=true&seed=10"
                val autoImg2 = "https://image.pollinations.ai/prompt/$encodedTitle%20closeup%20home%20cooking?width=600&height=400&nologo=true&seed=20"

                val imgs = if (customImageUrl.isNotBlank()) {
                    listOf(customImageUrl.trim(), autoImg1)
                } else {
                    listOf(autoImg1, autoImg2)
                }

                val newRecipe = Recipe(
                    id = "custom_${System.currentTimeMillis()}",
                    title = title.trim(),
                    cuisine = selectedCuisine,
                    category = selectedCategory,
                    cookingTime = cookingTime.ifBlank { "30 мин" },
                    difficulty = "Домашний",
                    calories = calories.toIntOrNull() ?: 250,
                    imageUrls = imgs,
                    ingredients = parsedIngs,
                    instructions = finalSteps,
                    isCustom = true
                )
                onSaveRecipe(newRecipe)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = "Сохранить")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Сохранить в Локальную базу и Избранное")
        }
    }
}

// =========================================================================
// 8. ЭКРАН СПИСКА ПОКУПОК
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

        Spacer(modifier = Modifier.height(10.dp))

        if (shoppingItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒 Список покупок пуст", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Нажмите кнопку «Добавить недостающие в список покупок» в карточке любого рецепта!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                text = "Куплено: ${checkedItems.size} из ${shoppingItems.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = {
                                shoppingItems.remove(item)
                                checkedItems.remove(item)
                                onUpdatePrefs()
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 9. КАРТОЧКА РЕЦЕПТА С ПРОГРЕССОМ ШАГОВ И РЕАЛЬНЫМИ ФЛАГАМИ СТРАН
// =========================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipeCard(
    index: Int,
    recipe: Recipe,
    isDarkTheme: Boolean,
    fridgeItems: List<String>,
    shoppingListItems: List<String>,
    isFavorite: Boolean,
    currentlySpeakingId: String?,
    onSpeakText: (String, String) -> Unit,
    onAddMissingToCart: (List<String>) -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteCustom: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var portions by remember(recipe.id) { mutableIntStateOf(2) }
    val scaledIngredients = remember(recipe.ingredients, portions) {
        recipe.ingredients.map { CulinaryUtils.scaleIngredientAmount(it, portions) }
    }
    val safeInstructions = remember(recipe.instructions, recipe.title) {
        CulinaryUtils.ensureValidInstructions(recipe.instructions, recipe.title, recipe.ingredients)
    }

    val checkedIngredients = remember(recipe.id, fridgeItems, portions) {
        mutableStateListOf<String>().apply {
            scaledIngredients.forEach { ing ->
                if (CulinaryUtils.isIngredientInFridge(ing, fridgeItems)) add(ing)
            }
        }
    }
    val completedSteps = remember(recipe.id) { mutableStateListOf<Int>() }

    val missingIngredients = scaledIngredients.filter { !checkedIngredients.contains(it) }
    val matchedCount = scaledIngredients.count { CulinaryUtils.isIngredientInFridge(it, fridgeItems) }
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (recipe.isCustom) {
                        Text(
                            text = "👨‍🍳 Мой домашний рецепт",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "#$index. ${recipe.title}",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    if (onDeleteCustom != null) {
                        IconButton(onClick = onDeleteCustom) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Избранное",
                            tint = if (isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Красивые капсульные бейджи с реальной страной/кухней, категорией, временем и калориями
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = CulinaryUtils.formatCuisineWithFlag(recipe.cuisine),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "🍽 ${recipe.category}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "⏱ ${recipe.cookingTime}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "🔥 ${recipe.calories} ккал",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            FilledTonalButton(
                onClick = {
                    val fullSpeech = buildString {
                        append("Рецепт: ${recipe.title}. ")
                        append("Кухня: ${recipe.cuisine}. Время готовки: ${recipe.cookingTime}. ")
                        append("Ингредиенты на $portions порции: ")
                        append(scaledIngredients.joinToString(". "))
                        append(". Способ приготовления: ")
                        safeInstructions.forEachIndexed { i, step ->
                            append("Шаг ${i + 1}. $step. ")
                        }
                        append("Приятного аппетита!")
                    }
                    onSpeakText(fullRecipeUtteranceId, fullSpeech)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
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
                    color = badgeBgColor,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (needToBuyCount == 0)
                            "🎉 Все продукты есть дома! ($matchedCount из ${scaledIngredients.size})"
                        else
                            "🧊 Дома есть: $matchedCount из ${scaledIngredients.size} • 🛒 Докупить: $needToBuyCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val safeImages = recipe.imageUrls.ifEmpty {
                listOf("https://www.themealdb.com/images/media/meals/llcbn01574260722.jpg")
            }
            val pagerState = rememberPagerState(pageCount = { safeImages.size })

            Box(modifier = Modifier.fillMaxWidth().height(205.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val imgReq = remember(safeImages[page]) {
                        ImageRequest.Builder(context)
                            .data(safeImages[page])
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = imgReq,
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ингредиенты:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Порций: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedIconButton(
                        onClick = { if (portions > 1) portions-- },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("-", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = " $portions ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    OutlinedIconButton(
                        onClick = { if (portions < 12) portions++ },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            scaledIngredients.forEach { ingredient ->
                val isChecked = checkedIngredients.contains(ingredient)
                val inFridge = CulinaryUtils.isIngredientInFridge(ingredient, fridgeItems)
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
                        inFridge -> Text("✅ Дома", fontSize = 11.sp, color = inFridgeColor, fontWeight = FontWeight.Medium)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Пошаговое приготовление:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (completedSteps.isNotEmpty()) {
                    Text(
                        text = "Готово: ${completedSteps.size}/${safeInstructions.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isStepDone) completedSteps.remove(stepIndex)
                                else completedSteps.add(stepIndex)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isStepDone) "✓ ${stepIndex + 1}. $step" else "${stepIndex + 1}. $step",
                                fontSize = 14.sp,
                                fontWeight = if (isStepSpeaking) FontWeight.SemiBold else FontWeight.Normal,
                                textDecoration = if (isStepDone) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (isStepDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onSpeakText(stepUtteranceId, "Шаг ${stepIndex + 1}. $step") },
                                modifier = Modifier.size(30.dp)
                            ) {
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
                        StepTimerWidget(
                            timerKey = stepUtteranceId,
                            timerInfo = timerInfo,
                            isDarkTheme = isDarkTheme
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepTimerWidget(
    timerKey: String,
    timerInfo: ParsedTimer,
    isDarkTheme: Boolean
) {
    var timeLeft by remember(timerKey) { mutableIntStateOf(timerInfo.durationSeconds) }
    var isRunning by remember(timerKey) { mutableStateOf(false) }
    var isFinished by remember(timerKey) { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && timeLeft > 0) {
            delay(1000L)
            timeLeft -= 1
            if (timeLeft == 0) {
                isRunning = false
                isFinished = true
                CulinaryUtils.playAlarmSound()
            }
        }
    }

    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    val containerBg = when {
        isFinished && isDarkTheme -> Color(0xFF1B3B22)
        isFinished && !isDarkTheme -> Color(0xFFE8F5E9)
        isRunning && isDarkTheme -> Color(0xFF3E2D14)
        isRunning && !isDarkTheme -> Color(0xFFFFF8E1)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val accentDoneColor = if (isDarkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)

    Surface(
        color = containerBg,
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
                    tint = if (isFinished) accentDoneColor else MaterialTheme.colorScheme.primary,
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
                    color = if (isFinished) accentDoneColor else MaterialTheme.colorScheme.onSurface
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
// 10. УМНЫЙ РЕПОЗИТОРИЙ С РОТАЦИЕЙ НОВЫХ БЛЮД И ОПРЕДЕЛЕНИЕМ РЕАЛЬНОЙ КУХНИ
// =========================================================================
object RecipeRepository {
    private val translationSemaphore = Semaphore(4)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val mealDbDetailCache = ConcurrentHashMap<String, Recipe>()

    // Трекеры просмотренных блюд, чтобы каждое нажатие "Подобрать" выдавало НОВЫЕ блюда!
    private val recentlyShownMealDbIds = Collections.synchronizedSet( LinkedHashSet<String>() )
    private val recentlyShownAiTitles = Collections.synchronizedSet( LinkedHashSet<String>() )
    private val searchLetters = listOf('b', 'c', 's', 'p', 'm', 't', 'f', 'g', 'r', 'l', 'k', 'a', 'd', 'h', 'n', 'w')

    private fun httpGet(urlStr: String, timeoutMs: Int = 6000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun translateEnToRu(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        translationCache[trimmed]?.let { return it }

        val preCleaned = CulinaryUtils.normalizeAndTranslateCulinary(trimmed)
        if (!CulinaryUtils.hasLatin(preCleaned)) {
            translationCache[trimmed] = preCleaned
            return preCleaned
        }

        try {
            val encoded = URLEncoder.encode(preCleaned.take(1800), "UTF-8")
            val conn = (URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val response = try {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write("q=$encoded") }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
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

        try {
            val encoded = URLEncoder.encode(preCleaned.take(800), "UTF-8")
            val response = httpGet("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ru&q=$encoded", 4500)
            val arr = JSONArray(response)
            val first = arr.optString(0)
            if (first.isNotBlank()) {
                val finalClean = CulinaryUtils.normalizeAndTranslateCulinary(first)
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
        if (sanitizedLines.none { CulinaryUtils.hasLatin(it) }) {
            return@withPermit sanitizedLines
        }

        val combined = sanitizedLines.joinToString("\n")
        val translatedBlock = translateEnToRu(combined)
        val splitBack = translatedBlock.split("\n").map { CulinaryUtils.normalizeAndTranslateCulinary(it.trim()) }

        if (splitBack.size == lines.size) {
            return@withPermit splitBack
        }
        return@withPermit sanitizedLines.map { translateEnToRu(it) }
    }

    private fun extractStepsFromJsonObject(obj: JSONObject, title: String, ingredients: List<String>): List<String> {
        val rawList = mutableListOf<String>()
        val arrayField = obj.optJSONArray("instructions")
            ?: obj.optJSONArray("steps")
            ?: obj.optJSONArray("directions")

        if (arrayField != null) {
            for (i in 0 until arrayField.length()) {
                rawList.add(arrayField.optString(i, ""))
            }
        } else {
            val strField = obj.optString("instructions", obj.optString("steps", ""))
            if (strField.isNotBlank()) rawList.addAll(strField.split("\n"))
        }
        return CulinaryUtils.ensureValidInstructions(rawList, title, ingredients)
    }

    // ИИ-ГЕНЕРАТОР С ЗАПРЕТОМ ПОВТОРОВ И РЕАЛЬНОЙ КУХНЕЙ КАЖДОГО БЛЮДА
    suspend fun fetchAiRussianRecipes(
        cuisine: String,
        category: String,
        fridgeItems: List<String>,
        customRecipes: List<Recipe>,
        voicePrompt: String?,
        count: Int,
        refreshSeed: Int
    ): List<Recipe> = withContext(Dispatchers.IO) {
        try {
            val fridgeText = if (fridgeItems.isNotEmpty()) "Обязательно используй продукты: ${fridgeItems.joinToString(", ")}." else ""
            val voiceText = if (!voicePrompt.isNullOrBlank()) "Запрос: $voicePrompt." else ""
            val excludeText = if (recentlyShownAiTitles.isNotEmpty()) {
                "НЕ ПОВТОРЯЙ эти ранее показанные блюда: ${recentlyShownAiTitles.takeLast(12).joinToString(", ")}. Придумай СОВЕРШЕННО НОВЫЕ блюда!"
            } else ""

            val culinaryStyles = listOf(
                "традиционные и аутентичные блюда",
                "популярные ресторанные хиты и стрит-фуд",
                "ароматные домашние блюда с насыщенным вкусом",
                "необычные и оригинальные рецепты шеф-поваров"
            )
            val styleHint = culinaryStyles[refreshSeed % culinaryStyles.size]
            val askCount = minOf(count, 6)

            val userPrompt = """
                Партия №${refreshSeed + 1} (акцент: $styleHint).
                Создай $askCount новых разных рецептов строго на русском языке.
                Кухня: ${if (cuisine == "Все") "разные страны мира (укажи реальную кухню для каждого блюда: Русская, Корейская, Итальянская, Японская, Французская, Грузинская, Мексиканская, Китайская, Тайская и т.д.)" else cuisine}.
                Категория: ${if (category == "Все") "разные категории (Супы, Вторые блюда, Салаты, Выпечка, Что-то к чаю)" else category}.
                $fridgeText $voiceText $excludeText
                У каждого блюда должно быть минимум 3 подробных шага в массиве instructions с временем в минутах, и поле englishName (название на английском для фото).
                Верни ТОЛЬКО валидный JSON массив:
                [{"title":"Название на русском","englishName":"dish name","cuisine":"Точная кухня (например Корейская)","category":"Точная категория","cookingTime":"35 мин","difficulty":"Легко","calories":250,"ingredients":["Продукт - 200 г"],"instructions":["Шаг 1. Подготовьте продукты.","Шаг 2. Обжарьте 10 минут.","Шаг 3. Варите 15 минут."]}]
            """.trimIndent()

            val payload = JSONObject().apply {
                put("model", "openai")
                put("seed", Random.nextInt(1, 999999))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "Отвечай строго валидным JSON массивом новых рецептов исключительно на русском языке."))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
            }

            val conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 12000
                readTimeout = 16000
            }

            val responseText = try {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

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
                    val encodedPhoto = URLEncoder.encode(engDish, "UTF-8").replace("+", "%20")

                    val img1 = "https://image.pollinations.ai/prompt/$encodedPhoto%20appetizing%20dish%20food%20photography?width=600&height=400&nologo=true&seed=${refreshSeed * 100 + i + 11}"
                    val img2 = "https://image.pollinations.ai/prompt/$encodedPhoto%20restaurant%20serving%20closeup?width=600&height=400&nologo=true&seed=${refreshSeed * 100 + i + 77}"

                    val ingArr = obj.optJSONArray("ingredients") ?: JSONArray()
                    val ingList = List(ingArr.length()) { translateEnToRu(ingArr.getString(it)) }
                        .ifEmpty { listOf("Основные ингредиенты - 300 г", "Специи по вкусу") }

                    val stepsList = extractStepsFromJsonObject(obj, ruTitle, ingList)
                        .map { translateEnToRu(it) }

                    val realCuisine = CulinaryUtils.resolveRealCuisine(
                        obj.optString("cuisine", ""),
                        ruTitle,
                        cuisine
                    )
                    val realCategory = CulinaryUtils.resolveRealCategory(
                        obj.optString("category", ""),
                        ruTitle,
                        category
                    )

                    aiResult.add(
                        Recipe(
                            id = "ai_${refreshSeed}_${ruTitle.hashCode()}_$i",
                            title = ruTitle,
                            cuisine = realCuisine,
                            category = realCategory,
                            cookingTime = CulinaryUtils.normalizeAndTranslateCulinary(obj.optString("cookingTime", "35 мин")),
                            difficulty = translateEnToRu(obj.optString("difficulty", "Средне")),
                            calories = obj.optInt("calories", 250),
                            imageUrls = listOf(img1, img2),
                            ingredients = ingList,
                            instructions = stepsList
                        )
                    )
                }
                if (aiResult.isNotEmpty()) {
                    if (aiResult.size < count) {
                        val existingTitles = aiResult.map { it.title }.toSet()
                        val extra = buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
                            .filter { !existingTitles.contains(it.title) }
                        aiResult.addAll(extra)
                    }
                    return@withContext aiResult.take(count)
                }
            }
        } catch (_: Exception) {}

        return@withContext buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
    }

    // МИРОВАЯ БАЗА TheMealDB: КАЖДЫЙ РАЗ НОВЫЕ БЛЮДА + ОТОБРАЖЕНИЕ РЕАЛЬНОЙ КУХНИ (strArea)
    suspend fun fetchTranslatedMealDbRecipes(
        cuisine: String,
        category: String,
        fridgeItems: List<String>,
        customRecipes: List<Recipe>,
        count: Int,
        refreshSeed: Int
    ): List<Recipe> = withContext(Dispatchers.IO) {
        try {
            val ingredientRuToEn = mapOf(
                "курица" to "chicken", "яйца" to "egg", "картофель" to "potatoes", "сыр" to "cheese",
                "томаты" to "tomatoes", "молоко" to "milk", "рис" to "rice", "фарш" to "beef",
                "говядина" to "beef", "свинина" to "pork", "грибы" to "mushrooms", "капуста" to "cabbage"
            )
            val areaMap = mapOf(
                "Русская" to "Russian", "Французская" to "French", "Итальянская" to "Italian",
                "Японская" to "Japanese", "Мексиканская" to "Mexican", "Китайская" to "Chinese",
                "Тайская" to "Thai", "Индийская" to "Indian"
            )
            val categoryMap = mapOf(
                "Супы" to "Starter", "Каши и завтраки" to "Breakfast", "Вторые блюда" to "Beef",
                "Выпечка" to "Dessert", "Что-то к чаю" to "Dessert", "Салаты" to "Side", "Паста и пицца" to "Pasta"
            )

            val firstFridgeEn = fridgeItems.firstNotNullOfOrNull { ingredientRuToEn[it.trim().lowercase()] }

            // При фильтрах (Все, Все) меняем поисковую букву при каждом нажатии "Подобрать", чтобы открывать сотни разных блюд!
            val rotatedLetter = searchLetters[refreshSeed % searchLetters.size]
            val secondCategoryForVariety = listOf("Chicken", "Beef", "Seafood", "Pork", "Pasta")[refreshSeed % 5]

            val endpointUrl = when {
                firstFridgeEn != null -> "https://www.themealdb.com/api/json/v1/1/filter.php?i=$firstFridgeEn"
                cuisine != "Все" && areaMap.containsKey(cuisine) -> "https://www.themealdb.com/api/json/v1/1/filter.php?a=${areaMap[cuisine]}"
                category != "Все" && category == "Вторые блюда" -> "https://www.themealdb.com/api/json/v1/1/filter.php?c=$secondCategoryForVariety"
                category != "Все" && categoryMap.containsKey(category) -> "https://www.themealdb.com/api/json/v1/1/filter.php?c=${categoryMap[category]}"
                else -> "https://www.themealdb.com/api/json/v1/1/search.php?s=$rotatedLetter"
            }

            val listResponse = httpGet(endpointUrl, 6000)
            val mealsArray = JSONObject(listResponse).optJSONArray("meals")

            if (mealsArray != null && mealsArray.length() > 0) {
                val allCandidateObjects = List(mealsArray.length()) { mealsArray.getJSONObject(it) }

                // Разделяем кандидатов на "еще НЕ показанные" и "уже показанные ранее" и перемешиваем!
                val unseenCandidates = allCandidateObjects
                    .filter { !recentlyShownMealDbIds.contains(it.optString("idMeal")) }
                    .shuffled()
                val seenCandidates = allCandidateObjects
                    .filter { recentlyShownMealDbIds.contains(it.optString("idMeal")) }
                    .shuffled()

                // Если все блюда в этой узкой категории уже были показаны — сбрасываем историю для нового круга
                val prioritizedCandidates = if (unseenCandidates.isNotEmpty()) {
                    unseenCandidates + seenCandidates
                } else {
                    recentlyShownMealDbIds.clear()
                    allCandidateObjects.shuffled()
                }

                val fetchLimit = minOf(count, minOf(prioritizedCandidates.size, 8))
                val selectedBatch = prioritizedCandidates.take(fetchLimit)

                val parallelResults = coroutineScope {
                    selectedBatch.mapIndexed { i, mealSummary ->
                        async {
                            try {
                                val mealId = mealSummary.getString("idMeal")
                                recentlyShownMealDbIds.add(mealId)

                                mealDbDetailCache[mealId]?.let { cached ->
                                    return@async cached
                                }

                                // Если search.php уже вернул полные детали (есть strInstructions и strArea) — не тратим время на второй запрос!
                                val detailObj = if (mealSummary.has("strInstructions") && mealSummary.has("strArea")) {
                                    mealSummary
                                } else {
                                    val detailResponse = httpGet("https://www.themealdb.com/api/json/v1/1/lookup.php?i=$mealId", 6000)
                                    JSONObject(detailResponse).getJSONArray("meals").getJSONObject(0)
                                }

                                val enTitle = detailObj.optString("strMeal", "Dish")
                                val enArea = detailObj.optString("strArea", "")
                                val enCategory = detailObj.optString("strCategory", "")
                                val enInstructions = detailObj.optString("strInstructions", "")

                                val rawIngredients = mutableListOf<String>()
                                var firstIngNameEn = "Chicken"
                                for (idx in 1..15) {
                                    val ing = detailObj.optString("strIngredient$idx", "").trim()
                                    val measure = detailObj.optString("strMeasure$idx", "").trim()
                                    if (ing.isNotEmpty() && ing != "null") {
                                        if (idx == 1) firstIngNameEn = ing
                                        val cleanMeasure = if (measure.isNotEmpty() && measure != "null") " — $measure" else ""
                                        rawIngredients.add(CulinaryUtils.normalizeAndTranslateCulinary("$ing$cleanMeasure"))
                                    }
                                }

                                val enSteps = enInstructions
                                    .replace("\r\n", "\n")
                                    .split(Regex("(?<=[.!?])\\s+|\\n+"))
                                    .map { it.trim() }
                                    .filter { it.length > 8 }
                                    .take(6)

                                val allLinesToTranslate = mutableListOf<String>().apply {
                                    add(enTitle)
                                    addAll(rawIngredients)
                                    addAll(enSteps)
                                }
                                val translatedLines = translateBatchLinesEnToRu(allLinesToTranslate)

                                val ruTitle = translatedLines.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Блюдо мировой кухни"
                                val ingCount = rawIngredients.size
                                val ruIngredients = translatedLines.drop(1).take(ingCount)
                                    .map { it.replaceFirstChar { ch -> ch.uppercase() } }
                                    .ifEmpty { listOf("Основные ингредиенты — 300 г") }

                                val ruStepsRaw = translatedLines.drop(1 + ingCount)
                                val finalSteps = CulinaryUtils.ensureValidInstructions(ruStepsRaw, ruTitle, ruIngredients)

                                // ИСПРАВЛЕНО: Определяем РЕАЛЬНУЮ кухню и категорию каждого блюда из API!
                                val realRussianCuisine = CulinaryUtils.resolveRealCuisine(enArea, ruTitle, cuisine)
                                val realRussianCategory = CulinaryUtils.resolveRealCategory(enCategory, ruTitle, category)

                                val mainPhoto = detailObj.optString("strMealThumb", "")
                                val encodedIng = URLEncoder.encode(firstIngNameEn, "UTF-8").replace("+", "%20")
                                val ingPhoto = "https://www.themealdb.com/images/ingredients/$encodedIng.png"

                                val builtRecipe = Recipe(
                                    id = "mealdb_$mealId",
                                    title = ruTitle,
                                    cuisine = realRussianCuisine,
                                    category = realRussianCategory,
                                    cookingTime = "${25 + (i * 5) % 35} мин",
                                    difficulty = "Средне",
                                    calories = 220 + (i * 25) % 180,
                                    imageUrls = listOf(mainPhoto, ingPhoto).filter { it.isNotBlank() },
                                    ingredients = ruIngredients,
                                    instructions = finalSteps
                                )
                                mealDbDetailCache[mealId] = builtRecipe
                                builtRecipe
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().toMutableList()
                }

                if (parallelResults.isNotEmpty()) {
                    if (parallelResults.size < count) {
                        val existingIds = parallelResults.map { it.id }.toSet()
                        val extra = buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
                            .filter { !existingIds.contains(it.id) }
                        parallelResults.addAll(extra)
                    }
                    return@withContext parallelResults.take(count)
                }
            }
        } catch (_: Exception) {}

        return@withContext buildLocalDatabaseRecipes(cuisine, category, fridgeItems, customRecipes, count, refreshSeed)
    }

    // ЛОКАЛЬНАЯ БАЗА С АКТИВНОЙ РОТАЦИЕЙ НОВЫХ БЛЮД ПРИ ПОВТОРНОМ НАЖАТИИ "ПОДОБРАТЬ"
    fun buildLocalDatabaseRecipes(
        cuisine: String,
        category: String,
        fridgeItems: List<String>,
        customRecipes: List<Recipe>,
        count: Int,
        refreshSeed: Int
    ): List<Recipe> {
        val randomForBatch = Random(refreshSeed * 31 + 7)
        val shuffledPool = CURATED_LOCAL_RECIPES.shuffled(randomForBatch)
        val allRecipes = customRecipes + shuffledPool

        val exactMatches = allRecipes.filter { r ->
            (cuisine == "Все" || r.cuisine.equals(cuisine, ignoreCase = true)) &&
            (category == "Все" || r.category.equals(category, ignoreCase = true))
        }.toMutableList()

        // Смещаем стартовую позицию на величину refreshSeed, чтобы каждый клик "Подобрать" менял состав выдачи!
        if (exactMatches.size > 1 && refreshSeed > 0) {
            Collections.rotate(exactMatches, -(refreshSeed * 3))
        }

        // Если указаны продукты в холодильнике — поднимаем блюда с совпадениями наверх, сохраняя свежее перемешивание
        if (fridgeItems.isNotEmpty()) {
            exactMatches.sortWith(
                compareByDescending<Recipe> { it.isCustom }
                    .thenByDescending { r -> r.ingredients.count { CulinaryUtils.isIngredientInFridge(it, fridgeItems) } }
            )
        }

        if (exactMatches.size < count) {
            val usedIds = exactMatches.map { it.id }.toMutableSet()
            val sameCategoryOrCuisine = allRecipes.filter { r ->
                !usedIds.contains(r.id) &&
                (r.category.equals(category, ignoreCase = true) || r.cuisine.equals(cuisine, ignoreCase = true))
            }.shuffled(randomForBatch)

            for (r in sameCategoryOrCuisine) {
                if (exactMatches.size < count) {
                    exactMatches.add(r)
                    usedIds.add(r.id)
                }
            }
            for (r in allRecipes.shuffled(randomForBatch)) {
                if (!usedIds.contains(r.id) && exactMatches.size < count) {
                    exactMatches.add(r)
                    usedIds.add(r.id)
                }
            }
        }

        return exactMatches.take(count)
    }

    private val CURATED_LOCAL_RECIPES = listOf(
        Recipe(
            id = "loc_borscht",
            title = "Классический борщ с говядиной и сметаной",
            cuisine = "Русская",
            category = "Супы",
            cookingTime = "1 ч 20 мин",
            difficulty = "Средне",
            calories = 135,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/vvstvq1487342592.jpg",
                "https://www.themealdb.com/images/ingredients/Beetroot.png"
            ),
            ingredients = listOf("Говядина на кости - 500 г", "Свекла - 2 шт.", "Капуста белокочанная - 300 г", "Картофель - 4 шт.", "Томатная паста - 2 ст.л.", "Сметана - 3 ст.л."),
            instructions = listOf(
                "Сварите наваристый бульон из говядины в течение 45 минут, затем достаньте мясо и нарежьте кусочками.",
                "В кипящий бульон отправьте нарезанный кубиками картофель и нашинкованную капусту, варите 15 минут.",
                "Обжарьте на сковороде тертую свеклу, морковь и лук с томатной пастой в течение 8 минут.",
                "Соедините зажарку с супом, добавьте чеснок и лавровый лист, томите под крышкой еще 10 минут."
            )
        ),
        Recipe(
            id = "loc_chicken_rice",
            title = "Курица с рисом и овощами на одной сковороде",
            cuisine = "Русская",
            category = "Вторые блюда",
            cookingTime = "40 мин",
            difficulty = "Легко",
            calories = 240,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/sypxpx1515365095.jpg",
                "https://www.themealdb.com/images/ingredients/Chicken.png"
            ),
            ingredients = listOf("Курица (филе или бедра) - 500 г", "Рис длиннозерный - 250 г", "Морковь - 1 шт.", "Лук репчатый - 1 шт.", "Чеснок - 3 зубчика", "Масло растительное - 2 ст.л."),
            instructions = listOf(
                "Нарежьте курицу небольшими кусочками и обжарьте на сковороде до золотистой корочки в течение 8 минут.",
                "Добавьте нарезанный лук и тертую морковь, обжаривайте вместе еще 5 минут.",
                "Всыпьте промытый рис, разровняйте, влейте 500 мл горячей подсоленной воды и убавьте огонь до минимума.",
                "Накройте плотной крышкой и томите 20 минут, пока рис полностью не впитает бульон."
            )
        ),
        Recipe(
            id = "loc_kartoshka",
            title = "Домашнее пирожное «Картошка» с соленой карамелью",
            cuisine = "Русская",
            category = "Что-то к чаю",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 380,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/tqtywx1468317395.jpg",
                "https://www.themealdb.com/images/media/meals/qxutws1486978099.jpg"
            ),
            ingredients = listOf("Печенье песочное - 400 г", "Вареная сгущенка (соленая карамель) - 250 г", "Сливочное масло - 100 г", "Какао-порошок - 4 ст.л."),
            instructions = listOf(
                "Измельчите песочное печенье в мелкую крошку с помощью блендера или скалки.",
                "Смешайте крошку с 3 столовыми ложками какао, мягким сливочным маслом и карамельной сгущенкой.",
                "Сформируйте руками аккуратные овальные пирожные и обваляйте их в оставшемся какао.",
                "Уберите пирожные в холодильник на 20 минут для застывания перед подачей к чаю."
            )
        ),
        Recipe(
            id = "loc_syrniki",
            title = "Пышные творожные сырники на завтрак",
            cuisine = "Русская",
            category = "Каши и завтраки",
            cookingTime = "25 мин",
            difficulty = "Легко",
            calories = 220,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/rwuyqx1511383174.jpg",
                "https://www.themealdb.com/images/media/meals/sywswr1511383814.jpg"
            ),
            ingredients = listOf("Творог 9% - 400 г", "Яйца - 1 шт.", "Мука пшеничная или рисовая - 3 ст.л.", "Сахар - 2 ст.л.", "Сметана - 3 ст.л."),
            instructions = listOf(
                "Протрите творог вилкой, добавьте яйцо, сахар и щепотку ванилина, тщательно перемешайте.",
                "Всыпьте муку и замесите мягкую творожную массу, сформуйте ровные круглые шайбочки.",
                "Обжаривайте сырники на разогретой сковороде на среднем огне по 5 минут с каждой стороны до румяной корочки."
            )
        ),
        Recipe(
            id = "loc_stroganoff",
            title = "Бефстроганов из говядины с грибами в сметанном соусе",
            cuisine = "Русская",
            category = "Вторые блюда",
            cookingTime = "45 мин",
            difficulty = "Средне",
            calories = 265,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/svprys1511176755.jpg",
                "https://www.themealdb.com/images/ingredients/Mushrooms.png"
            ),
            ingredients = listOf("Говядина (вырезка) - 500 г", "Грибы шампиньоны - 250 г", "Сметана 20% - 200 г", "Лук репчатый - 2 шт.", "Мука - 1 ст.л."),
            instructions = listOf(
                "Нарежьте говядину тонкими брусочками и быстро обжарьте на сильном огне в течение 6 минут.",
                "Отдельно обжарьте нарезанный лук и ломтики шампиньонов в течение 8 минут до золотистости.",
                "Соедините мясо с грибами, присыпьте столовой ложкой муки, добавьте сметану и 100 мл теплой воды.",
                "Тушите под крышкой на слабом огне 15 минут до загустения нежного соуса."
            )
        ),
        Recipe(
            id = "loc_charlottka",
            title = "Пышная домашняя шарлотка с яблоками и корицей",
            cuisine = "Русская",
            category = "Выпечка",
            cookingTime = "45 мин",
            difficulty = "Легко",
            calories = 250,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/qtqvys1468573168.jpg",
                "https://www.themealdb.com/images/media/meals/xvsurr1511719182.jpg"
            ),
            ingredients = listOf("Яблоки кисло-сладкие - 4 шт.", "Яйца - 4 шт.", "Сахар - 150 г", "Мука - 160 г", "Корица молотая - 1 ч.л."),
            instructions = listOf(
                "Взбейте яйца с сахаром миксером в пышную светлую пену в течение 5 минут.",
                "Аккуратно просейте муку и перемешайте лопаткой снизу вверх, чтобы тесто осталось воздушным.",
                "Выложите дольки яблок с корицей в смазанную форму, залейте тестом и выпекайте 35 минут при 180°C."
            )
        ),
        Recipe(
            id = "loc_olivier",
            title = "Домашний салат Оливье с куриным филе",
            cuisine = "Русская",
            category = "Салаты",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 195,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/1549542994.jpg",
                "https://www.themealdb.com/images/ingredients/Potatoes.png"
            ),
            ingredients = listOf("Курица отварная - 300 г", "Картофель - 3 шт.", "Яйца - 3 шт.", "Морковь - 1 шт.", "Огурцы маринованные - 3 шт.", "Горошек зеленый - 150 г"),
            instructions = listOf(
                "Отварите картофель, морковь и яйца в течение 20 минут, остудите и очистите.",
                "Нарежьте куриное филе, овощи и яйца одинаковыми мелкими кубиками.",
                "Добавьте зеленый горошек, заправьте сметаной или майонезом и охладите в холодильнике 10 минут."
            )
        ),
        Recipe(
            id = "loc_kimchi_soup",
            title = "Острый корейский суп Кимчи Тиге со свининой и тофу",
            cuisine = "Корейская",
            category = "Супы",
            cookingTime = "35 мин",
            difficulty = "Легко",
            calories = 190,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/1529446352.jpg",
                "https://www.themealdb.com/images/ingredients/Tofu.png"
            ),
            ingredients = listOf("Капуста кимчи - 300 г", "Свинина или курица - 250 г", "Сыр тофу - 150 г", "Яйца - 1 шт.", "Лук зеленый - 1 пучок"),
            instructions = listOf(
                "Нарежьте мясо и капусту кимчи кусочками и обжарьте в глубокой кастрюле в течение 7 минут.",
                "Влейте 600 мл горячей воды или бульона, доведите до кипения и варите на среднем огне 15 минут.",
                "Добавьте нарезанный кубиками сыр тофу, вбейте яйцо и варите еще 4 минуты, посыпав зеленым луком."
            )
        ),
        Recipe(
            id = "loc_korean_chicken",
            title = "Хрустящая курица по-корейски в кисло-сладком соусе Яннём",
            cuisine = "Корейская",
            category = "Вторые блюда",
            cookingTime = "40 мин",
            difficulty = "Средне",
            calories = 310,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/1529444830.jpg",
                "https://www.themealdb.com/images/media/meals/tytyxu1515363282.jpg"
            ),
            ingredients = listOf("Курица (филе или крылышки) - 600 г", "Крахмал кукурузный - 4 ст.л.", "Чеснок - 3 зубчика", "Томатный соус - 3 ст.л.", "Мед - 2 ст.л.", "Кунжут - 1 ст.л."),
            instructions = listOf(
                "Нарежьте курицу кусочками, обваляйте в кукурузном крахмале и обжарьте в масле 12 минут до хрустящей корочки.",
                "В отдельной сковороде смешайте измельченный чеснок, томатный соус, соевый соус и мед, прогрейте 3 минуты.",
                "Переложите хрустящую курицу в горячий соус, быстро перемешайте и посыпьте семенами кунжута."
            )
        ),
        Recipe(
            id = "loc_bibimbap",
            title = "Пибимпап (Корейский рис с говядиной, овощами и яйцом)",
            cuisine = "Корейская",
            category = "Вторые блюда",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 275,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/z0ageb1583189517.jpg",
                "https://www.themealdb.com/images/ingredients/Rice.png"
            ),
            ingredients = listOf("Рис круглозерный - 250 г", "Фарш говяжий - 250 г", "Яйца - 2 шт.", "Морковь - 1 шт.", "Огурец - 1 шт.", "Соевый соус - 2 ст.л."),
            instructions = listOf(
                "Отварите рис в подсоленной воде в течение 15 минут до рассыпчатости.",
                "Обжарьте говяжий фарш с соевым соусом и чесноком в течение 8 минут, отдельно поджарьте соломку моркови 4 минуты.",
                "Поджарьте яичницу-глазунью в течение 3 минут, выложите на горячий рис вместе с мясом и овощами."
            )
        ),
        Recipe(
            id = "loc_french_gratin",
            title = "Французский картофельный гратен Дофинуа со сливками и сыром",
            cuisine = "Французская",
            category = "Вторые блюда",
            cookingTime = "50 мин",
            difficulty = "Легко",
            calories = 265,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/qwrtut1468418027.jpg",
                "https://www.themealdb.com/images/ingredients/Potatoes.png"
            ),
            ingredients = listOf("Картофель - 800 г", "Сыр твердый - 150 г", "Молоко или сливки - 300 мл", "Чеснок - 2 зубчика", "Сливочное масло - 30 г"),
            instructions = listOf(
                "Очистите картофель и нарежьте тонкими кружочками толщиной около 3 миллиметров.",
                "Натрите форму для запекания зубчиком чеснока и сливочным маслом, уложите картофель внахлест.",
                "Залейте горячими сливками с солью, посыпьте тертым сыром и запекайте 40 минут при 190°C до золотистой корочки."
            )
        ),
        Recipe(
            id = "loc_french_soup",
            title = "Французский луковый суп с сырными гренками",
            cuisine = "Французская",
            category = "Супы",
            cookingTime = "45 мин",
            difficulty = "Средне",
            calories = 210,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/xvrrux1511783685.jpg",
                "https://www.themealdb.com/images/ingredients/Onions.png"
            ),
            ingredients = listOf("Лук репчатый - 500 г", "Сыр твердый - 120 г", "Сливочное масло - 50 г", "Багет или белый хлеб - 4 ломтика", "Мясной бульон - 700 мл"),
            instructions = listOf(
                "Нарежьте лук тонкими полукольцами и томите на сливочном масле на медленном огне 20 минут до карамельного оттенка.",
                "Влейте горячий бульон, посолите и варите на слабом огне еще 15 минут.",
                "Разлейте суп по формам, сверху положите подсушенный багет, щедро посыпьте сыром и запекайте 5 минут в духовке."
            )
        ),
        Recipe(
            id = "loc_croissants",
            title = "Французские круассаны с запеченным миндальным кремом",
            cuisine = "Французская",
            category = "Что-то к чаю",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 390,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/tlstrw1511724769.jpg",
                "https://www.themealdb.com/images/media/meals/wtqrqw1511639627.jpg"
            ),
            ingredients = listOf("Готовые круассаны - 4 шт.", "Миндальная мука или орехи - 100 г", "Сливочное масло - 80 г", "Яйца - 2 шт.", "Сахарная пудра - 80 г"),
            instructions = listOf(
                "Взбейте размягченное сливочное масло с сахарной пудрой, яйцами и молотым миндалем в пышный крем.",
                "Разрежьте круассаны вдоль пополам, щедро смажьте миндальным кремом внутри и сверху.",
                "Запекайте в разогретой до 180°C духовке в течение 15 минут до хрустящей румяной корочки."
            )
        ),
        Recipe(
            id = "loc_julienne",
            title = "Французский жульен с курицей и грибами под сырной корочкой",
            cuisine = "Французская",
            category = "Вторые блюда",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 280,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/uuuspp1511297945.jpg",
                "https://www.themealdb.com/images/ingredients/Mushrooms.png"
            ),
            ingredients = listOf("Курица (филе) - 300 г", "Грибы шампиньоны - 300 г", "Сыр твердый - 150 г", "Сливки или сметана - 200 г", "Лук репчатый - 1 шт."),
            instructions = listOf(
                "Мелко нарежьте куриное филе, грибы и лук, обжарьте на сковороде в течение 10 минут.",
                "Влейте сливки или сметану, посолите и прогрейте смесь еще 4 минуты до легкого загустения.",
                "Разложите по формам, обильно посыпьте тертым сыром и запекайте в духовке 12 минут при 190°C."
            )
        ),
        Recipe(
            id = "loc_carbonara",
            title = "Итальянская паста Карбонара с беконом и Пармезаном",
            cuisine = "Итальянская",
            category = "Паста и пицца",
            cookingTime = "20 мин",
            difficulty = "Легко",
            calories = 340,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/llcbn01574260722.jpg",
                "https://www.themealdb.com/images/ingredients/Parmesan.png"
            ),
            ingredients = listOf("Спагетти - 300 г", "Бекон или грудинка - 150 г", "Яйца (желтки) - 3 шт.", "Сыр Пармезан - 80 г", "Чеснок - 2 зубчика"),
            instructions = listOf(
                "Отварите спагетти в кипящей подсоленной воде в течение 9 минут до состояния аль денте.",
                "Нарежьте бекон ломтиками и обжарьте на сковороде с раздавленным зубчиком чеснока в течение 5 минут.",
                "В миске взбейте яичные желтки с мелко натертым сыром и черным перцем, добавьте 3 ложки воды от пасты.",
                "Переложите горячие спагетти к бекону, снимите с огня, влейте яично-сырный соус и энергично перемешайте."
            )
        ),
        Recipe(
            id = "loc_pizza",
            title = "Домашняя итальянская пицца Маргарита с томатами и Моцареллой",
            cuisine = "Итальянская",
            category = "Паста и пицца",
            cookingTime = "30 мин",
            difficulty = "Легко",
            calories = 285,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/x0lk931587671540.jpg",
                "https://www.themealdb.com/images/ingredients/Mozzarella.png"
            ),
            ingredients = listOf("Тесто для пиццы - 400 г", "Сыр Моцарелла - 250 г", "Томаты спелые - 3 шт.", "Томатный соус - 4 ст.л.", "Масло оливковое и базилик"),
            instructions = listOf(
                "Раскатайте тесто в тонкую круглую лепешку и переложите на противень, застеленный пергаментом.",
                "Смажьте основу томатным соусом с орегано, разложите кружочки томатов и ломтики сыра Моцарелла.",
                "Выпекайте в максимально разогретой до 220°C духовке в течение 15 минут до расплавления сыра."
            )
        ),
        Recipe(
            id = "loc_caesar",
            title = "Салат Цезарь с обжаренной куриной грудкой, сыром и сухариками",
            cuisine = "Итальянская",
            category = "Салаты",
            cookingTime = "25 мин",
            difficulty = "Легко",
            calories = 215,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/urtwux1486983078.jpg",
                "https://www.themealdb.com/images/ingredients/Chicken%20Breast.png"
            ),
            ingredients = listOf("Курица (филе) - 300 г", "Салат листовой или пекинская капуста - 1 пучок", "Томаты черри - 150 г", "Сыр твердый - 60 г", "Хлеб белый - 3 ломтика"),
            instructions = listOf(
                "Нарежьте белый хлеб кубиками и подсушите на сковороде с каплей масла и чесноком в течение 5 минут.",
                "Обжарьте куриное филе со специями по 6 минут с каждой стороны, затем нарежьте ломтиками.",
                "Порвите листья салата в глубокую тарелку, выложите курицу, половинки томатов, сухарики и посыпьте тертым сыром."
            )
        ),
        Recipe(
            id = "loc_khachapuri",
            title = "Грузинский хачапури по-аджарски с сыром и желтком",
            cuisine = "Грузинская",
            category = "Выпечка",
            cookingTime = "45 мин",
            difficulty = "Средне",
            calories = 330,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/sywswr1511383814.jpg",
                "https://www.themealdb.com/images/ingredients/Cheese.png"
            ),
            ingredients = listOf("Тесто дрожжевое - 400 г", "Сыр Сулугуни или Моцарелла - 350 г", "Яйца - 2 шт.", "Сливочное масло - 30 г"),
            instructions = listOf(
                "Раскатайте тесто в овал, сверните края трубочками к центру и защипните концы, формируя лодочку.",
                "Натрите сыр на крупной терке, смешайте с двумя ложками воды и щедро наполните лодочку.",
                "Выпекайте в разогретой до 200°C духовке в течение 20 минут до румяных бортиков.",
                "Выньте хачапури, сделайте углубление в сыре, влейте желток и верните в духовку еще на 2 минуты."
            )
        ),
        Recipe(
            id = "loc_chkmeruli",
            title = "Чкмерули (Курица по-грузински в сливочно-чесночном соусе)",
            cuisine = "Грузинская",
            category = "Вторые блюда",
            cookingTime = "40 мин",
            difficulty = "Легко",
            calories = 290,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/qtuwxu1468233098.jpg",
                "https://www.themealdb.com/images/ingredients/Garlic.png"
            ),
            ingredients = listOf("Курица - 700 г", "Сливки или молоко - 250 мл", "Чеснок - 5 зубчиков", "Кинза и петрушка - 1 пучок", "Хмели-сунели - 1 ч.л."),
            instructions = listOf(
                "Натрите кусочки курицы солью и обжарьте на сковороде под гнетом по 8 минут с каждой стороны до золотистой корочки.",
                "Измельчите чеснок и зелень, смешайте с теплыми сливками и пряностью хмели-сунели.",
                "Залейте курицу сливочно-чесночным соусом и томите на слабом огне 12 минут."
            )
        ),
        Recipe(
            id = "loc_ramen",
            title = "Японский домашний Рамен с курицей, лапшой и яйцом",
            cuisine = "Японская",
            category = "Супы",
            cookingTime = "35 мин",
            difficulty = "Легко",
            calories = 230,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/swttys1511385853.jpg",
                "https://www.themealdb.com/images/ingredients/Egg.png"
            ),
            ingredients = listOf("Курица (филе) - 350 г", "Лапша пшеничная или рамен - 200 г", "Яйца - 2 шт.", "Грибы шампиньоны - 150 г", "Соевый соус - 3 ст.л."),
            instructions = listOf(
                "Сварите куриное филе в 1 литре воды с добавлением соевого соуса, чеснока и ломтиков грибов в течение 20 минут.",
                "Отдельно отварите яйца всмятку ровно 6 минут после закипания, остудите в холодной воде и очистите.",
                "Отварите лапшу в течение 4 минут, разложите по глубоким мискам, залейте горячим бульоном и украсьте ломтиками курицы и половинками яйца."
            )
        ),
        Recipe(
            id = "loc_teriyaki",
            title = "Курица Терияки с кунжутом и рассыпчатым рисом",
            cuisine = "Японская",
            category = "Вторые блюда",
            cookingTime = "25 мин",
            difficulty = "Легко",
            calories = 245,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/wvpsxx1468256321.jpg",
                "https://www.themealdb.com/images/ingredients/Chicken.png"
            ),
            ingredients = listOf("Курица (филе или бедра) - 500 г", "Рис - 200 г", "Соевый соус - 4 ст.л.", "Мед или сахар - 2 ст.л.", "Чеснок - 2 зубчика"),
            instructions = listOf(
                "Отварите рис в подсоленной воде в течение 15 минут.",
                "Нарежьте курицу полосками и обжарьте на сильном огне в течение 7 минут до румяности.",
                "Влейте смесь соевого соуса, меда и чеснока, карамелизуйте курицу еще 4 минуты и подавайте с рисом."
            )
        ),
        Recipe(
            id = "loc_quesadilla",
            title = "Мексиканская Кесадилья с курицей, сыром и томатами",
            cuisine = "Мексиканская",
            category = "Вторые блюда",
            cookingTime = "20 мин",
            difficulty = "Легко",
            calories = 290,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/uvuyxu1503067369.jpg",
                "https://www.themealdb.com/images/media/meals/ypxvwv1505333929.jpg"
            ),
            ingredients = listOf("Курица (филе) - 300 г", "Сыр твердый - 150 г", "Томаты - 2 шт.", "Лепешки тортильи или лаваш - 4 шт.", "Кукуруза консервированная - 100 г"),
            instructions = listOf(
                "Мелко нарежьте куриное филе и обжарьте на сковороде с кубиками томатов и кукурузой в течение 8 минут.",
                "На половину каждой лепешки выложите слой тертого сыра, горячую куриную начинку и еще немного сыра, сложите пополам.",
                "Обжарьте сложенные кесадильи на сухой сковороде по 3 минуты с каждой стороны до хрустящей корочки и расплавления сыра."
            )
        ),
        Recipe(
            id = "loc_tomyum",
            title = "Тайский суп Том Ям с креветками, грибами и кокосовым молоком",
            cuisine = "Тайская",
            category = "Супы",
            cookingTime = "25 мин",
            difficulty = "Средне",
            calories = 175,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/1529445434.jpg",
                "https://www.themealdb.com/images/ingredients/King%20Prawns.png"
            ),
            ingredients = listOf("Креветки или курица - 300 г", "Грибы шампиньоны - 150 г", "Кокосовое молоко или сливки - 200 мл", "Томаты черри - 100 г", "Лимон или лайм - 1 шт."),
            instructions = listOf(
                "Доведите 500 мл бульона до кипения, добавьте нарезанные грибы и варите 7 минут.",
                "Добавьте очищенные креветки, половинки томатов черри и влейте кокосовое молоко, варите еще 5 минут.",
                "Приправьте соком лайма и подавайте горячим с отварным рисом."
            )
        ),
        Recipe(
            id = "loc_tikka",
            title = "Индийская Курица Тикка Масала в пряном томатно-сливочном соусе",
            cuisine = "Индийская",
            category = "Вторые блюда",
            cookingTime = "35 мин",
            difficulty = "Легко",
            calories = 260,
            imageUrls = listOf(
                "https://www.themealdb.com/images/media/meals/wyxwsp1486979827.jpg",
                "https://www.themealdb.com/images/ingredients/Chicken.png"
            ),
            ingredients = listOf("Курица (филе) - 500 г", "Сливки или сметана - 200 мл", "Томатный соус - 4 ст.л.", "Чеснок - 3 зубчика", "Карри и паприка - 2 ч.л."),
            instructions = listOf(
                "Нарежьте куриное филе кубиками и обжарьте со специями карри и чесноком в течение 8 минут.",
                "Добавьте томатный соус и теплые сливки, перемешайте и тушите на слабом огне 15 минут до густоты.",
                "Подавайте ароматную курицу с рассыпчатым рисом или теплыми лепешками."
            )
        )
    )
}
