package com.example.moxing  // 声明包名

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.rememberDismissState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import com.example.moxing.ModelSelectionScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.BufferedSource
import okio.IOException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val sender: String,
    val message: String,
    val base64Image: String? = null  // 序列化的是 Base64 字符串
)


// MainActivity 是程序入口 Activity，继承 ComponentActivity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppEntryPoint() // 替换为包含导航的入口
            }
        }
    }
}

// 页面导航
@Composable
fun AppEntryPoint() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatGPTAppUI(navController = navController)
        }
        composable("model_select") {
            ModelSelectionScreen(navController = navController)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ChatGPTAppUI(navController: NavController) {

    // 定义参数
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentChatFileName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current  // 👈 这里获取 context
    val chatListState = rememberLazyListState()       // ✅ 聊天窗口用这个
    val drawerListState = rememberLazyListState()     // ✅ 抽屉用这个
    var nowUserInput: String
    // 使用 mutableStateList 更高效
    val chatFiles = remember { mutableStateListOf<File>() }
    // 当前会话对应的文件名，只创建一次
    var shouldRenameFile by remember { mutableStateOf(true) }
    val currentApiJob = remember { mutableStateOf<Job?>(null) }
    var call: Call? = null  // 保存请求实例

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var userInput by remember { mutableStateOf(TextFieldValue("")) }
    var isFirstMessageSent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    var title by remember { mutableStateOf("请选择模型") }
    var shouldAutoScroll by remember { mutableStateOf(true) }
    // 监听滚动位置，当用户手动向上滚动时暂停自动滚动
    val scrollState by remember {
        derivedStateOf {
            val firstVisible = chatListState.firstVisibleItemIndex
            val visibleItems = chatListState.layoutInfo.visibleItemsInfo.size
            val totalItems = chatListState.layoutInfo.totalItemsCount

            // 如果用户滚动到接近底部，恢复自动滚动
            if (firstVisible + visibleItems >= totalItems - 2) {
                shouldAutoScroll = true
            } else {
                shouldAutoScroll = false
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val quantOptions = listOf("fp16", "int8", "int4")
    var selectedQuant by remember { mutableStateOf("fp16") }
    // 刷新函数 - 使用外部存储路径
    fun refreshFiles() {
        try {
            val files = context.getExternalFilesDir(null)
                ?.listFiles()
                ?.filter { it.name.endsWith(".json") && it.name != "cache_file.json" } // 排除缓存文件
                ?.sortedByDescending { it.lastModified() } ?: emptyList()

            chatFiles.clear()
            chatFiles.addAll(files)
        } catch (e: Exception) {
            Log.e("错误", "刷新文件列表失败: ${e.localizedMessage}")
        }
    }

    fun cancelRequest() {
        isLoading = false
        call?.cancel() // 立即关闭网络流
        call = null

        // 告知后端终止生成
        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("$CHAT_API_URL/cancel")
                .post("".toRequestBody())
                .build()
            client.newCall(request).execute().close()
        }

        // 不清除已显示的内容
    }

    fun sendMessage() {
        try {
            if (userInput.text.isNotBlank() && !isLoading) {
                // 保存原始输入内容
                val userInputText = userInput.text
                nowUserInput = userInput.text
                // 创建用户消息对象
                val newMessage = ChatMessage(sender = "user", message = userInputText)

                // 文件重命名逻辑（仅当是第一条消息时）
                if (!isFirstMessageSent) {
                    isFirstMessageSent = true
                    if (shouldRenameFile && currentChatFileName != null) {
                        try {
                            val rawName = userInputText.take(10)
                            val newFileName = "$rawName.json"

                            val externalDir = context.getExternalFilesDir(null)
                            if (externalDir != null) {
                                val oldFile = File(externalDir, currentChatFileName!!)
                                val newFile = File(externalDir, newFileName)

                                if (oldFile.exists() && oldFile.renameTo(newFile)) {
                                    Log.d("文件重命名", "成功重命名为: $newFileName")
                                    currentChatFileName = newFileName
                                } else {
                                    Log.e("文件重命名", "重命名失败")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("重命名异常", e.toString())
                        }
                        shouldRenameFile = false
                    }
                }

                // 添加用户消息到列表
                messages = if (isFirstMessageSent) messages + newMessage else listOf(newMessage)

                // 清空输入框并设置加载状态
                userInput = TextFieldValue("")
                isLoading = true

                scope.launch {
                    currentApiJob.value = scope.launch {
                        try {
                            // 添加AI消息占位符（带光标效果）
                            var aiMessage = ChatMessage(sender = "ai", message = "")
                            messages = messages + aiMessage

                            callModelUnified(
                                input = nowUserInput,
                                quantization = selectedQuant,
                                onTextToken = { newToken ->
                                    aiMessage = aiMessage.copy(message = aiMessage.message.dropLast(1) + newToken + "▌")
                                    messages = messages.dropLast(1) + aiMessage
                                    launch(Dispatchers.Main) {
                                        if (shouldAutoScroll) chatListState.animateScrollToItem(499)
                                    }
                                },
                                // 修改 onImageBase64 回调，直接更新当前消息
                                onImageBase64 = { base64Image ->
                                    aiMessage = aiMessage.copy(

                                        base64Image = base64Image
                                    )
                                    messages = messages.dropLast(1) + aiMessage
                                }
                            )

                            // 流式传输完成后移除光标
                            aiMessage = aiMessage.copy(message = aiMessage.message.dropLast(1))
                            messages = messages.dropLast(1) + aiMessage
                            saveMessagesToFile(context, currentChatFileName!!, messages) // 手动保存

                        } catch (e: Exception) {
                            // 错误处理：替换占位消息为错误信息
                            val errorMessage =
                                ChatMessage("ai", "AI 回复失败: ${e.localizedMessage}，请重新更换模型或者检查网络")
                            messages = messages.dropLast(1) + errorMessage
                            Log.e("API错误", "请求失败", e)
                        } finally {
                            isLoading = false
                            currentApiJob.value = null

                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("发送异常", "处理消息时出错", e)
        }
    }
    // 在UI构建中应用这个状态
    LaunchedEffect(scrollState) {
        // 这个LaunchedEffect只是为了确保scrollState被监听
    }
    LaunchedEffect(Unit) {
        snapshotFlow { messages }
            .collectLatest {
                if (messages.isNotEmpty() && shouldAutoScroll) {
                    chatListState.animateScrollToItem(messages.size - 1)
                }
            }
    }

    // 监听 selected_model_name 的变化
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.getLiveData<String>("selected_model_name")?.observeForever { selectedName ->
            if (selectedName != null) {
                title = selectedName
                // 监听完后清空，避免重复触发
                savedStateHandle.remove<String>("selected_model_name")
            }
        }
    }
    // 关键：在抽屉打开时自动刷新
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            refreshFiles()
        }
    }
    // 程序启动时生成新的聊天文件名
    LaunchedEffect(Unit) {
        if (currentChatFileName == null) {
            currentChatFileName = "cache_file.json"
            // 可以初始化欢迎消息，也可以空列表
            messages = emptyList()
        }
    }

    // 消息变化时，保存当前聊天记录
    LaunchedEffect(messages.size) {
        currentChatFileName?.let { filename ->
            saveMessagesToFile(context, filename, messages)

        }
        if (messages.isNotEmpty()) {
            drawerListState.animateScrollToItem(messages.size - 1)
        }
    }
    LaunchedEffect(currentChatFileName) {

        if (currentChatFileName != null) {

            val externalDir = context.getExternalFilesDir(null)
            if (externalDir == null) {
                return@LaunchedEffect
            }

            val file = File(externalDir, currentChatFileName)

            if (file.exists()) {
                try {
                    val loaded = loadMessagesFromFile(context, file.name)
                    messages = loaded
                    isFirstMessageSent = messages.isNotEmpty()
                    shouldRenameFile = true

                } catch (e: Exception) {
                    messages = listOf(
                        ChatMessage("ai", "加载聊天记录失败"),
                        ChatMessage("ai", "错误: ${e.localizedMessage}")
                    )
                }
            }
        } else {
            currentChatFileName = getNewChatFileName()
            messages = emptyList()
            saveMessagesToFile(context, currentChatFileName!!, messages)
            isFirstMessageSent = false
            shouldRenameFile = true  // ✅ 新建聊天后，标记要重命名
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 左侧抽屉
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(240.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            TopAppBar(
                                title = { Text("") },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        scope.launch {
                                            drawerListState.animateScrollToItem(0)
                                        }
                                    }) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.a),
                                            contentDescription = "菜单",
                                            tint = Color.Unspecified
                                        )
                                    }
                                },
                                actions = {
                                    IconButton(onClick = {
                                        scope.launch { drawerState.close() }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Filled.ArrowBack,
                                            contentDescription = "导航返回",
                                            tint = Color.Black,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "新建聊天",
                                        tint = Color.Black,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                label = { Text("新建聊天") },
                                selected = false,
                                onClick = {
                                    Log.d("回调调试", "接收到文件选择: null")
                                    val newFileName = getNewChatFileName()
                                    val initialMessages = emptyList<ChatMessage>()
                                    saveMessagesToFile(context, newFileName, initialMessages)
                                    currentChatFileName = newFileName
                                    Log.d("回调调试", "新建聊天，文件名: $currentChatFileName")
                                    scope.launch { drawerState.close() }
                                }
                            )
                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = "搜索",
                                        tint = Color.Black,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                label = { Text("搜索聊天") },
                                selected = false,
                                onClick = { /*...*/ }
                            )
                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.ViewInAr,
                                        contentDescription = "选择模型",
                                        tint = Color.Black,
                                        modifier = Modifier.size(28.dp)
                                    )
                                },
                                label = { Text("模型选择") },
                                selected = false,
                                onClick = { navController.navigate("model_select") }
                            )
                        }
                    }
                    LazyColumn(
                        state = drawerListState,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(chatFiles.filter { it.name != "cache_file.json" }, key = { it.name }) { file ->
                            val dismissState = rememberDismissState(
                                confirmStateChange = { dismissValue ->
                                    if (dismissValue == DismissValue.DismissedToStart) {
                                        // 1. 删除文件
                                        val deleted = file.delete()
                                        Log.d("删除调试", "尝试删除文件: ${file.name}, 结果: $deleted")

                                        // 2. 更新列表
                                        chatFiles.remove(file)

                                        // 3. 如果删除的是当前文件，重置为新文件
                                        if (file.name == currentChatFileName) {
                                            val newFileName = getNewChatFileName()
                                            saveMessagesToFile(context, newFileName, emptyList())
                                            currentChatFileName = newFileName
                                            messages = emptyList()
                                            isFirstMessageSent = false
                                            shouldRenameFile = true
                                        }

                                        true
                                    } else {
                                        false
                                    }
                                }
                            )

                            SwipeToDismiss(
                                state = dismissState,
                                directions = setOf(DismissDirection.EndToStart), // 👈 仅允许左滑删除
                                background = {
                                },
                                dismissContent = {

                                    NavigationDrawerItem(
                                        label = {
                                            val displayName = file.name
                                                .removePrefix("chat_")
                                                .removeSuffix(".json")
                                                .replace("_", " ")
                                            Text(displayName, modifier = Modifier.padding(16.dp))
                                        },
                                        selected = false,
                                        onClick = {
                                            Log.d("回调调试", "接收到文件选择: ${file.name}")
                                            currentChatFileName = file.name
                                            scope.launch { drawerState.close() }
                                        }
                                    )
                                }
                            )
                        }
                    }

                }
            }
        }
    ) {

        // 主页面
        Scaffold(
            //主页面顶部
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            modifier = Modifier.clickable {
                                // 跳转到模型选择页
                                navController.navigate("model_select")
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    },                    // 左侧菜单
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Menu"
                            )
                        }
                    },

                    actions = {
                        // ▼ 量化方式选择下拉菜单按钮 ▼
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(selectedQuant)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }

                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                quantOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectedQuant = option
                                            expanded = false
                                            // 可选：发送给后端或保存状态
                                        }
                                    )
                                }
                            }
                        }

                        // 右侧头像
                        IconButton(onClick = { /* TODO */ }) {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = "用户头像",
                                tint = Color.Black,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                )
            }
        )
        // 主页面中心

        { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding(innerPadding)  // ✅ 正确使用 Scaffold 的内边距
            ) {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    items(messages.size) { index ->
                        val message = messages[index]
                        if (message.sender == "user") {
                            UserMessageBubble(text = message.message)
                        } else {
                            AiMessageBubble(message = message)  // 传递整个消息对象
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (isLoading) {
                        item {
                            Text("AI 正在回复...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // 主页面底部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    // 输入框
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("请输入...") },
                        enabled = !isLoading,
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {

                                sendMessage()
                            }
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isLoading) {
                        // 加载时显示中止按钮
                        Button(
                            onClick = { cancelRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("中止")
                        }
                    } else {
                        // 正常状态显示发送按钮
                        Button(
                            onClick = { sendMessage() },
                            enabled = userInput.text.isNotBlank()
                        ) {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }
}

suspend fun callModelUnified(
    input: String,
    quantization: String,
    onTextToken: (String) -> Unit,
    onImageBase64: (String) -> Unit  // 改为传Base64字符串
): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // 连接超时，默认10秒
        .readTimeout(300, TimeUnit.SECONDS)   // 读取超时，改大一点，比如300秒（5分钟）
        .writeTimeout(60, TimeUnit.SECONDS)   // 写超时
        .build()
    val json = Json { ignoreUnknownKeys = true }

    val requestBody = json.encodeToString(mapOf("message" to input))
        .toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("$CHAT_API_URL/chat")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        val contentType = response.header("Content-Type")
        if (!response.isSuccessful) throw IOException("请求失败: ${response.code}")

        return@use when {
            contentType?.startsWith("text/event-stream") == true -> {
                val source = response.body?.source() ?: throw IOException("空响应体")
                val sb = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val content = line.removePrefix("data: ").trim()
                        if (content == "[DONE]") break
                        onTextToken(content)
                        sb.append(content)
                    }
                }
                sb.toString()
            }

            contentType?.startsWith("application/json") == true -> {
                val jsonStr = response.body?.string() ?: throw IOException("空响应体")
                val obj = JSONObject(jsonStr)
                val base64 = obj.getString("image")
                onImageBase64(base64)
                "[IMAGE_RECEIVED]"
            }

            else -> throw IOException("未知响应类型: $contentType")
        }
    }
}

fun bitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    val byteArray = outputStream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.DEFAULT)
}


// 用户输入显示
@Composable
fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 250.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

//输出显示
@Composable
fun AiMessageBubble(message: ChatMessage) {  // 改为接收整个ChatMessage对象
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 250.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 添加图像显示逻辑
                message.base64Image?.let { base64 ->
                    val bitmap = remember {
                        try {
                            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "AI生成的图像",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                    }
                }
            }
        }
    }
}