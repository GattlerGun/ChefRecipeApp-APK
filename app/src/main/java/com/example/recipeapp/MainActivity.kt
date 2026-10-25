package com.example.recipeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// 1. Улучшенная модель данных рецепта
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAppScreen() {
    val coroutineScope = rememberCoroutineScope()

    // Списки фильтров
    val cuisines = listOf("Все", "Русская", "Корейская", "Французская", "Итальянская", "Японская", "Грузинская", "Мексиканская")
    val categories = listOf("Все", "Супы", "Каши и завтраки", "Вторые блюда", "Выпечка", "Что-то к чаю", "Салаты", "Паста и пицца")

    // Состояния фильтров и режимов
    var selectedMode by remember { mutableStateOf(DataSourceMode.LOCAL) }
    var selectedCuisine by remember { mutableStateOf("Все") }
    var selectedCategory by remember { mutableStateOf("Все") }
    var limitCount by remember { mutableFloatStateOf(5f) }
    var showOnlyFavorites by remember { mutableStateOf(false) }

    // Состояния данных
    var recipes by remember { mutableStateOf(getSampleRecipes()) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Функция загрузки рецептов в зависимости от выбранного режима
    fun loadRecipes() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val count = limitCount.roundToInt()
                recipes = when (selectedMode) {
                    DataSourceMode.LOCAL -> {
                        getSampleRecipes().filter {
                            (selectedCuisine == "Все" || it.cuisine == selectedCuisine) &&
                            (selectedCategory == "Все" || it.category == selectedCategory)
                        }.take(count)
                    }
                    DataSourceMode.MEAL_DB_API -> {
                        fetchFromMealDbApi(selectedCuisine, selectedCategory, count)
                    }
                    DataSourceMode.FREE_AI -> {
                        fetchFromFreeAi(selectedCuisine, selectedCategory, count)
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка загрузки: ${e.localizedMessage ?: "проверьте интернет"}"
            } finally {
                isLoading = false
            }
        }
    }

    // Фильтрация избранного
    val displayedRecipes = remember(recipes, showOnlyFavorites, favoriteIds, selectedCuisine, selectedCategory, limitCount, selectedMode) {
        val baseList = if (selectedMode == DataSourceMode.LOCAL) {
            recipes.filter {
                (selectedCuisine == "Все" || it.cuisine == selectedCuisine) &&
                (selectedCategory == "Все" || it.category == selectedCategory)
            }.take(limitCount.roundToInt())
        } else {
            recipes.take(limitCount.roundToInt())
        }
        if (showOnlyFavorites) baseList.filter { favoriteIds.contains(it.id) } else baseList
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Шапка с кнопкой Избранного
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Шеф-Повар 🍲", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            FilterChip(
                selected = showOnlyFavorites,
                onClick = { showOnlyFavorites = !showOnlyFavorites },
                label = { Text("❤️ Избранное (${favoriteIds.size})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Переключатель режима: База / API / Бесплатный ИИ
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DataSourceMode.entries.forEach { mode ->
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

        // Фильтр: Кухня мира
        Text("Кухня:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cuisines) { cuisine ->
                FilterChip(
                    selected = selectedCuisine == cuisine,
                    onClick = { selectedCuisine = cuisine },
                    label = { Text(cuisine) }
                )
            }
        }

        // Фильтр: Тип блюда
        Text("Тип блюда:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category) }
                )
            }
        }

        // Слайдер количества (5 - 20) + Кнопка поиска для API и ИИ
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Количество вариантов: ${limitCount.roundToInt()}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                    Text("Найти")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Индикатор загрузки, ошибки или список рецептов
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (selectedMode == DataSourceMode.FREE_AI)
                            "ИИ составляет рецепты и генерирует фото..."
                        else "Загружаем рецепты из API..."
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
                Text(
                    if (selectedMode != DataSourceMode.LOCAL)
                        "Нажмите кнопку «Найти», чтобы загрузить рецепты!"
                    else "Рецепты по заданным фильтрам не найдены 😔"
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(
                    items = displayedRecipes,
                    key = { _, item -> item.id } // Исправляет баг со сбросом состояния карточек!
                ) { index, recipe ->
                    RecipeCard(
                        index = index + 1,
                        recipe = recipe,
                        isFavorite = favoriteIds.contains(recipe.id),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipeCard(
    index: Int,
    recipe: Recipe,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    // Состояние для чекбоксов ингредиентов
    val checkedIngredients = remember(recipe.id) { mutableStateListOf<String>() }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. Номер, Название и кнопка "В избранное"
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

            // 2. Метаданные: Время, кухня, сложность, калории
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⏱ ${recipe.cookingTime}", fontSize = 13.sp, color = Color.Gray)
                Text("🌍 ${recipe.cuisine}", fontSize = 13.sp, color = Color.Gray)
                Text("📊 ${recipe.difficulty}", fontSize = 13.sp, color = Color.Gray)
                Text("🔥 ${recipe.calories} ккал", fontSize = 13.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Безопасная карусель фотографий
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
                // Индикатор точек
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

            // 4. Ингредиенты с интерактивными чекбоксами
            Text("Ингредиенты (нажми, чтобы отметить):", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            recipe.ingredients.forEach { ingredient ->
                val isChecked = checkedIngredients.contains(ingredient)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isChecked) checkedIngredients.remove(ingredient)
                            else checkedIngredients.add(ingredient)
                        }
                        .padding(vertical = 2.dp)
                ) {
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 5. Способ готовки
            Text("Способ приготовления:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            recipe.instructions.forEachIndexed { stepIndex, step ->
                Text(
                    text = "${stepIndex + 1}. $step",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}

// =========================================================================
// СЕТЕВОЙ СЛОЙ 1: БЕСПЛАТНЫЙ ИИ БЕЗ КЛЮЧЕЙ (Pollinations AI)
// =========================================================================
suspend fun fetchFromFreeAi(cuisine: String, category: String, count: Int): List<Recipe> = withContext(Dispatchers.IO) {
    val prompt = """
        Сгенерируй ровно $count рецептов на русском языке.
        Кухня: ${if (cuisine == "Все") "любая мировая" else cuisine}.
        Тип блюда: ${if (category == "Все") "любой" else category}.
        Верни ТОЛЬКО валидный JSON массив без markdown-обертки в таком формате:
        [
          {
            "title": "Название блюда",
            "cuisine": "Кухня",
            "category": "Тип",
            "cookingTime": "45 мин",
            "difficulty": "Средне",
            "calories": 250,
            "englishName": "borscht soup",
            "ingredients": ["Ингредиент 1 - 200 г", "Ингредиент 2 - 1 шт."],
            "instructions": ["Шаг 1", "Шаг 2", "Шаг 3"]
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
        val engName = URLEncoder.encode(obj.optString("englishName", "delicious food"), "UTF-8")

        // Генерируем 2 реальные фотки для карусели через бесплатный генератор изображений
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
                cookingTime = obj.optString("cookingTime", "30 мин"),
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
suspend fun fetchFromMealDbApi(cuisine: String, category: String, count: Int): List<Recipe> = withContext(Dispatchers.IO) {
    val areaMap = mapOf(
        "Русская" to "Russian",
        "Французская" to "French",
        "Итальянская" to "Italian",
        "Японская" to "Japanese",
        "Мексиканская" to "Mexican"
    )
    val area = areaMap[cuisine] ?: "Italian"
    val listUrl = URL("https://www.themealdb.com/api/json/v1/1/filter.php?a=$area")
    val listResponse = listUrl.openConnection().getInputStream().bufferedReader().use { it.readText() }
    val mealsArray = JSONObject(listResponse).optJSONArray("meals") ?: return@withContext emptyList()

    val result = mutableListOf<Recipe>()
    val limit = minOf(count, mealsArray.length())

    for (i in 0 until limit) {
        val mealSummary = mealsArray.getJSONObject(i)
        val mealId = mealSummary.getString("idMeal")

        // Загружаем детали каждого рецепта
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
                instructions = instructions.ifEmpty { listOf("Приготовьте согласно инструкции.") }
            )
        )
    }
    return@withContext result
}

// =========================================================================
// ЛОКАЛЬНАЯ БАЗА РЕЦЕПТОВ (РАБОТАЕТ БЕЗ ИНТЕРНЕТА)
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
            ingredients = listOf("Говядина - 500 г", "Свекла - 2 шт.", "Капуста - 300 г", "Картофель - 4 шт.", "Томатная паста - 2 ст.л."),
            instructions = listOf(
                "Сварите мясной бульон из говядины.",
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
            ingredients = listOf("Кимчи - 300 г", "Свинина - 200 г", "Тофу - 150 г", "Паста кочхуджан - 1 ст.л.", "Зеленый лук"),
            instructions = listOf(
                "Обжарьте свинину и нарезанную кимчи в кастрюле.",
                "Залейте водой или бульоном, добавьте пасту кочхуджан и варите 20 минут.",
                "Добавьте нарезанный кубиками тофу и зеленый лук перед подачей."
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
            ingredients = listOf("Круассаны - 4 шт.", "Миндальная мука - 100 г", "Сливочное масло - 80 г", "Сахарная пудра - 100 г", "Яйца - 2 шт."),
            instructions = listOf(
                "Взбейте масло с сахарной пудрой, яйцами и миндальной мукой в крем.",
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
            ingredients = listOf("Тесто дрожжевое - 400 г", "Сыр Сулугуни - 350 г", "Яйцо - 2 шт.", "Сливочное масло - 30 г"),
            instructions = listOf(
                "Сформируйте из теста лодочку с бортиками.",
                "Натрите сулугуни и выложите внутрь лодочки.",
                "Выпекайте при 200°C около 20 минут, затем вбейте в центр желток на 2 минуты."
            )
        ),
        Recipe(
            id = "loc_5",
            title = "Сырники со сметаной и ягодами",
            cuisine = "Русская",
            category = "Каши и завтраки",
            cookingTime = "25 мин",
            difficulty = "Легко",
            calories = 230,
            imageUrls = listOf(
                "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600",
                "https://images.unsplash.com/photo-1528207776546-365bb710ee93?w=600"
            ),
            ingredients = listOf("Творог 9% - 400 г", "Яйцо - 1 шт.", "Мука рисовая - 3 ст.л.", "Сахар - 2 ст.л.", "Ванилин"),
            instructions = listOf(
                "Разомните творог вилкой, смешайте с яйцом, сахаром и мукой.",
                "Сформируйте шайбочки, обваляйте в муке.",
                "Обжарьте на среднем огне по 4 минуты с каждой стороны до золотистой корочки."
            )
        )
    )
}
