package com.example.moxing

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults.elevatedCardElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// --- 数据模型 ---
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val para: String
)



// --- UI 组件 ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSelectionScreen(navController: NavController) {
    var allModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf(TextFieldValue("")) }
    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val parameterTags = allModels.map { it.para }.toSet()
    val allTags = (allModels.flatMap { it.tags }.toSet() + parameterTags).toSet()

    val filteredModels = allModels.filter { model ->
        (selectedTags.isEmpty() || model.tags.any { it in selectedTags } || model.para in selectedTags) &&
                (searchText.text.isBlank() ||
                        model.name.contains(searchText.text, true) ||
                        model.description.contains(searchText.text, true) ||
                        model.para.contains(searchText.text, true))
    }


    suspend fun fetchModels(): List<ModelInfo>? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("$CHAT_API_URL/models")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("模型列表请求失败", "状态码: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val type = object : TypeToken<List<ModelInfo>>() {}.type
                return@withContext Gson().fromJson(body, type)
            }
        } catch (e: Exception) {
            Log.e("模型列表请求异常", "错误: ${e.localizedMessage}")
            return@withContext null
        }
    }

    suspend fun sendSelectedModelNameToBackend(modelName: String) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val jsonBody = """{"model_name": "$modelName"}"""
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$CHAT_API_URL/select_model") // 替换为你后端的接收地址
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("发送模型名称失败", "状态码: ${response.code}")
                    } else {
                        Log.d("发送模型名称成功", "已发送模型: $modelName")
                    }
                }
            } catch (e: Exception) {
                Log.e("发送模型名称异常", "错误: ${e.localizedMessage}")
            }
        }
    }


    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val result = fetchModels()
            result?.let { allModels = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型选择") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {

            var expanded by remember { mutableStateOf(false) }
            val tagsList = allTags.toList()

            // 搜索框
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("搜索模型") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            // 选择器部分，默认显示前三个或全部，自动换行
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val displayTags = if (expanded) tagsList else tagsList.take(3)

                displayTags.forEach { tag ->
                    FilterChip(
                        modifier = Modifier.padding(end = 16.dp, bottom = 4 .dp), // 这里加间距
                        selected = tag in selectedTags,
                        onClick = {
                            selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                        },
                        label = { Text(tag) }
                    )
                }
            }

// 单独一行显示展开/收起按钮
            if (tagsList.size > 3) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开全部"
                        )
                    }
                }
            }


            LazyColumn {
                items(filteredModels) { model ->
                    val isSelected = selectedModelId == model.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                coroutineScope.launch {
                                    sendSelectedModelNameToBackend(model.name)
                                }

                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("selected_model_name", model.name)
                                navController.navigateUp()
                            }
                        ,
                        elevation = elevatedCardElevation(4.dp),
                        colors = if (isSelected)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else
                            CardDefaults.cardColors()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(model.description, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("参数: ${model.para}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

