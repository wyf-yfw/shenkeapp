package com.example.moxing

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


fun saveMessagesToFile(context: Context, filename: String, messages: List<ChatMessage>) {
    val json = Json.encodeToString(messages)
    val file = File(context.getExternalFilesDir(null), filename)
    file.writeText(json)
}


fun loadMessagesFromFile(context: Context, filename: String): List<ChatMessage> {
    Log.d("文件调试", "开始加载文件: $filename")
    val file = File(context.getExternalFilesDir(null), filename)

    return try {
        val json = file.readText()
        Log.d("文件调试", "文件内容: $json")
        val messages = Json.decodeFromString<List<ChatMessage>>(json)
        Log.d("文件调试", "解析成功，消息数量: ${messages.size}")
        messages
    } catch (e: Exception) {
        Log.e("文件调试", "加载消息失败", e)
        emptyList()
    }
}
fun getNewChatFileName(): String {

    return "cache_file.json"
}
