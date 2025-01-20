package com.example.chatgptwithpepper

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeoutException

class MainActivity : AppCompatActivity() {



    // OkHttp Client for API calls
    private val client = OkHttpClient()
    private var threadId = ""
    private var assistanceId = ""
    private var runId = ""

    // Flags to manage speech recognition state
    private var isEndOfSpeech = false
    private var isListening = false
    private var isTriggerDetected = false

    companion object {
        private const val TAG = "MainActivity"
    }

    // Securely retrieve your API key
    private val apiKey: String by lazy {
        // Replace this with your secure method of retrieving the API key
        // Example: BuildConfig.OPENAI_API_KEY
        "sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA" // <-- Replace with secured API key retrieval
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Initialize UI components here if needed

        // Initialize Speech Recognizer and TextToSpeech

        lifecycleScope.launch {
            try {
                // Step 1: Create assistance
                assistanceId = createAssistance()
                Log.d(TAG, "Assistance id: $assistanceId")

                // Step 2: Create thread
                threadId = createChatGPTThread()
                Log.d(TAG, "Thread id: $threadId")

                // Step 3: Add message to thread
                addMessageToThread("my name is ahmed")

                // Step 4: Run thread with assistance
                runId = runThreadWithAssistance()
                Log.d(TAG, "Run id: $runId")

                // Step 5: Get GPT response
                val gptResponse = getGPTResponse()
                Log.d(TAG, "Received GPT Response: $gptResponse")

                // Update UI with the GPT response
                withContext(Dispatchers.Main) {
                    // Example: display in a TextView
                    // findViewById<TextView>(R.id.gptResponseTextView).text = gptResponse
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in coroutine: ${e.message}")
                // Optional: Show error message to the user
            }
        }
    }


    /**
     * Creates a new ChatGPT Assistance and returns the assistance ID.
     *
     * @return The assistance ID as a string.
     * @throws IOException If there is a network error or an unexpected HTTP response.
     * @throws Exception For any other errors encountered during processing.
     */
    private suspend fun createAssistance(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Creating new ChatGPT Assistance")
        var assistanceIdResponse = ""

        // Create JSON body
        val requestBodyJson = JSONObject().apply {
            put("instructions", "Your are running on a robot called pepper he is helpful friendly and cute people love him")
            put("name", "Pepper")
            put("description", "ChatGPT Assistant")
            put("model", "gpt-4o")
        }.toString()

        val requestBody = requestBodyJson.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/assistants")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Unexpected response code: ${response.code}, Body: $errorBody")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response for creating Assistance: $responseBody")

                val jsonObject = JSONObject(responseBody)
                assistanceIdResponse = jsonObject.getString("id")
                Log.d(TAG, "Created assistance ID: $assistanceIdResponse")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }

        return@withContext assistanceIdResponse
    }

    /**
     * Creates a new ChatGPT thread and returns the thread ID.
     *
     * @return The thread ID as a string.
     * @throws IOException If there is a network error or an unexpected HTTP response.
     * @throws Exception For any other errors encountered during processing.
     */
    private suspend fun createChatGPTThread(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Creating new ChatGPT thread")
        var threadIdResponse = ""

        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post("{}".toRequestBody())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Unexpected response code: ${response.code}, Body: $errorBody")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response for creating thread: $responseBody")

                val jsonObject = JSONObject(responseBody)
                threadIdResponse = jsonObject.getString("id")
                Log.d(TAG, "Created thread ID: $threadIdResponse")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }

        return@withContext threadIdResponse
    }

    /**
     * Sends the user's message to the thread.
     *
     * @param userText The text input from the user.
     */
    private suspend fun addMessageToThread(userText: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending text to ChatGPT: $userText")

        // Create JSON body for the request
        val requestBodyJson = JSONObject().apply {
            put("role", "user")
            put("content", userText)
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(body)
            .build()

        Log.d("HTTP Request", "URL: ${request.url}, Body: $requestBodyJson")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Unexpected response code: ${response.code}, Body: $errorBody")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response from adding message into thread: $responseBody")
                // Optionally parse and handle the response if needed
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }
    }

    /**
     * Runs the thread with the specified assistance and returns the run ID.
     *
     * @return The run ID as a string.
     * @throws IOException If there is a network error or an unexpected HTTP response.
     * @throws Exception For any other errors encountered during processing.
     */
    private suspend fun runThreadWithAssistance(): String = withContext(Dispatchers.IO) {
        var runIdResponse = ""
        Log.d(TAG, "Running ChatGPT thread with assistance")

        // Build request body with assistance ID
        val requestBodyJson = JSONObject().apply {
            put("assistant_id", assistanceId)
        }.toString()

        val requestBody = requestBodyJson.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/runs")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Unexpected response code: ${response.code}, Body: $errorBody")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response for running thread with assistance: $responseBody")
                runIdResponse = JSONObject(responseBody).getString("id")
                Log.d(TAG, "Run ID: $runIdResponse")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }

        return@withContext runIdResponse
    }

    /**
     * Fetches the GPT response message from the API.
     *
     * @return The message from the GPT API when the status is "completed".
     * @throws IOException If there is a network error or an unexpected HTTP response.
     * @throws Exception For any other errors encountered during processing.
     * @throws TimeoutException If the thread does not complete within the expected time.
     */
    private suspend fun getGPTResponse(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching messages from the running thread")

        val runRequest = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/runs/$runId")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .get()
            .build()

        val maxRetries = 60 // e.g., 60 attempts (~1 minute)
        var attempt = 0

        try {
            while (attempt < maxRetries) {
                client.newCall(runRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Log.e(TAG, "Unexpected response code: ${response.code}, Body: $errorBody")
                        throw IOException("Unexpected HTTP response: ${response.code}")
                    }

                    val responseBody = response.body?.string().orEmpty()
                    Log.d(TAG, "Run API Response: $responseBody")

                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.optString("status") == "completed") {
                        Log.d(TAG, "ChatGPT thread completed")

                        // Fetch messages
                        val messages = fetchMessages()
                        if (messages.isNotEmpty()) {
                                           Log.d(TAG, "Messages: $messages")

                        } else {
                            Log.e(TAG, "No messages found in the thread.")
                            throw Exception("No messages found in the thread.")
                        }
                    } else {
                        Log.d(TAG, "ChatGPT thread not completed. Retrying in 1 second...")
                        kotlinx.coroutines.delay(1000)
                        attempt++
                    }
                }
            }

            throw TimeoutException("ChatGPT thread did not complete within expected time.")
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: TimeoutException) {
            Log.e(TAG, "Timeout Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }
    }

    /**
     * Fetches all messages from the specified thread.
     *
     * @return A list of Message objects.
     * @throws IOException If there is a network error or an unexpected HTTP response.
     */
    private suspend fun fetchMessages(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching messages from the thread")

        val messagesRequest = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/messages")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .get()
            .build()
var message =""
        client.newCall(messagesRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "Failed to fetch messages: ${response.code}, Body: $errorBody")
                throw IOException("Failed to fetch messages: ${response.code}")
            }

            val responseBody = response.body?.string().orEmpty()
            Log.d(TAG, "Messages Response: $responseBody")
            val jsonResponse = JSONObject(responseBody)

            val dataArray = jsonResponse.optJSONArray("data") ?: JSONArray()

            val messagesList = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val messageObj = dataArray.getJSONObject(i)

                val contentArray = messageObj.optJSONArray("content") ?: JSONArray()
                var messageContent = ""

                for (j in 0 until contentArray.length()) {
                    val contentObj = contentArray.getJSONObject(j)
                    if (contentObj.optString("type") == "text") {
                        val textObj = contentObj.optJSONObject("text")
                        val value = textObj?.optString("value") ?: ""
                        messageContent += value
                    }
                }
                Log.d("GPT message", "the right message: $messageContent")

                messagesList.add( messageContent)
            }

            Log.d(TAG, "Total messages fetched: ${messagesList.size}")
            }

            return@withContext message
        }
    }



