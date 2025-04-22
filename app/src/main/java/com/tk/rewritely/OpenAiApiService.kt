package com.tk.rewritely

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApiService {
    @POST("v1/chat/completions") // Use the correct endpoint for GPT-4o Mini
    suspend fun getCompletion(
        @Header("Authorization") apiKey: String,
        @Body requestBody: OpenAiRequest
    ): Response<OpenAiResponse>
}

// --- Data Classes for Request and Response ---

data class OpenAiRequest(
    val model: String = "gpt-4o-mini", // Specify the model
    val messages: List<Message>,
    val max_tokens: Int = 150 // Adjust as needed
)

data class Message(
    val role: String = "user",
    val content: String
)

data class OpenAiResponse(
    val choices: List<Choice>?,
    val error: ApiError?
)

data class Choice(
    val message: ResponseMessage?
)

data class ResponseMessage(
    val role: String?,
    val content: String?
)

data class ApiError(
    val message: String?,
    val type: String?,
    val param: String?,
    val code: String?
)