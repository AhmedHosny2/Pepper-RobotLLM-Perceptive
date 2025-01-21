package com.example.chatgptwithpepper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.conversation.Say
//import com.aldebaran.qi.sdk.`object`.conversation.SayFuture
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.SayBuilder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeoutException

@RequiresApi(Build.VERSION_CODES.DONUT)
class MainActivity : AppCompatActivity(),
    TextToSpeech.OnInitListener,
    RobotLifecycleCallbacks {
    // OkHttp Client for API calls
    private var threadId = ""
    private var assistanceId = ""
    private var runId = ""

    // UI
    private lateinit var startButton: Button
    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var resultText: String
    private lateinit var newChatButton: Button

    // Speech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    // QiSDK
    private var qiContext: QiContext? = null

    // OkHttp Client
    private val client = OkHttpClient()

    // Flags

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101

    }

    // Simple enum to distinguish between listening for the wake word vs. user commands
    private val apiKey: String by lazy {
        // Replace this with your secure method of retrieving the API key
        // Example: BuildConfig.OPENAI_API_KEY
        "sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA" // <-- Replace with secured API key retrieval
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()
        // Initialize Assistance on App Launch
        lifecycleScope.launch {
            try {
                // Step 1: Create assistance
                assistanceId = createAssistance()
                Log.d(TAG, "Assistance ID: $assistanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating assistance: ${e.message}")
                // Optional: Show error message to the user
            }
        }


        // QiSDK
        QiSDK.register(this, this)

        // TTS
        textToSpeech = TextToSpeech(this, this)

        // Initialize layout references
        startButton = findViewById(R.id.startButton)
        messageContainer = findViewById(R.id.messageContainer)
        scrollView = findViewById(R.id.scrollView)
        newChatButton = findViewById(R.id.newChatButton)

        // SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
            }
            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")

            }

            @SuppressLint("SetTextI18n")
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val transcript = matches[0].lowercase()
                    Log.d(TAG, "onResults: $transcript")
                    val result = matches[0]
                    resultText = result
                    addMessage(true, "You: $result")

                    lifecycleScope.launch {
                        submitMessage(result)
                    }
                }

            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startButton.setOnClickListener {
            checkAudioPermissionAndStart()
        }
        newChatButton.setOnClickListener(
            View.OnClickListener {
                messageContainer.removeAllViews()
                lifecycleScope.launch {
                    startSession()
                }
            }
        )
    }
    /**
     * Starts a new session by creating a new thread.
     */
    private suspend fun startSession() {
        try {
            // Step 2: Create thread
            threadId = createChatGPTThread()
            Log.d(TAG, "Thread ID: $threadId")
            // Optional: Notify user that the session has started
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session: ${e.message}")
            // Optional: Show error message to the user
        }
    }

    /**
     * Submits the user's message, runs the thread, and fetches the GPT response.
     *
     * @param userText The text input from the user.
     */
    private suspend fun submitMessage(userText: String) {
        try {
            // Step 3: Add message to thread
            addMessageToThread(userText)

            // Step 4: Run thread with assistance
            runId = runThreadWithAssistance()
            Log.d(TAG, "Run ID: $runId")

            // Step 5: Get GPT response
            val gptResponse = getGPTResponse()
            Log.d(TAG, "Received GPT Response: $gptResponse")

            // Update UI with the GPT response and let pepper say it
            withContext(Dispatchers.Main) {
                addMessage(false, "Pepper: $gptResponse")
                sayText(gptResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting message: ${e.message}")
            // Optional: Show error message to the user
        }
    }

    /**
     * Creates a new ChatGPT Assistance and returns the assistance ID.
     */
    private suspend fun createAssistance(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Creating new ChatGPT Assistance")
        var assistanceIdResponse = ""

        // Create JSON body
        val requestBodyJson = JSONObject().apply {
            put("instructions", "You are running on a robot called Pepper. He is helpful, friendly, and cute. People love him. you are stateful and you can remember the context of the conversation try to show this while talking like if you got someone name use it if you ot his jobs use it you can add some jokes as well you don't need any long message just simple answers like 3 lines max  ")
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
        var gptMessage = ""

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
                            gptMessage = messages.first()// Get the latest message
                            Log.d(TAG, "Latest GPT message: $gptMessage")
                            return@withContext gptMessage
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

            if (gptMessage.isEmpty()) {
                throw TimeoutException("ChatGPT thread did not complete within expected time.")
            }

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

        return@withContext gptMessage
    }

    /**
     * Fetches all messages from the specified thread.
     */
    private suspend fun fetchMessages(): List<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching messages from the thread")

        val messagesRequest = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/messages")
            .addHeader("Authorization", "Bearer $apiKey") // Use secured API key
            .addHeader("OpenAI-Beta", "assistants=v2")
            .get()
            .build()

        val messagesList = mutableListOf<String>()

        try {
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

                for (i in 0 until dataArray.length()) {
                    val messageObj = dataArray.getJSONObject(i)

                    val contentArray = messageObj.optJSONArray("content") ?: JSONArray()
                    var messageContent = ""

                    for (j in 0 until 1) {
                        val contentObj = contentArray.getJSONObject(j)
                        if (contentObj.optString("type") == "text") {
                            val textObj = contentObj.optJSONObject("text")
                            val value = textObj?.optString("value") ?: ""
                            messageContent += value
                        }
                    }

                    Log.d("GPT message", "The right message: $messageContent")

                    messagesList.add(messageContent)
                }

                Log.d(TAG, "Total messages fetched: ${messagesList.size}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }

        return@withContext messagesList
    }


    /**
     * Check permission for RECORD_AUDIO, then start listening.
     */
    private fun checkAudioPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            startListening()
        }
    }

    /**
     * Start listening for speech, in whichever mode we are in.
     */
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    /**
     * Stop, then restart listening to keep "always on" behavior.
     */
    private fun restartListening() {
        Log.d(TAG, "restartListening called")
        speechRecognizer.stopListening()
        startListening()
    }


    /**
     * Pepper speaks the given text (if QiContext is available).
     */
    private fun sayText(text: String = "Hello from ULM!") {
        // Also show in the chat bubble
//        runOnUiThread {
//            addMessage(false, "Pepper: $text")
//        }

        // If we have QiContext, do the actual TTS
        qiContext?.let { context ->
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    SayBuilder.with(context)
                        .withText(text)
                        .build()
                        .run()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in sayText: ${e.message}")
                    runOnUiThread {
                        addMessage(true, "Pepper: Sorry, I couldn't say that.")
                    }
                }
            }
        } ?: run {
            // If QiContext is null
            runOnUiThread {
                addMessage(true, "Pepper: Sorry, I'm not ready yet.")
            }
        }
    }

    /**
     * Add a message bubble to the UI.
     * @param isUser: whether it's user or Pepper
     * @param message: the text to display
     */
    private fun addMessage(isUser: Boolean, message: String) {
        // Create a container for the message and timestamp
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = 12
                marginStart = if (isUser) 150 else 20
                marginEnd = if (isUser) 20 else 150
            }
        }

        // Message TextView
        val messageTextView = TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(20, 12, 20, 12)
            setTextColor(
                if (isUser) getColor(android.R.color.white) else getColor(android.R.color.black)
            )
            setBackgroundResource(
                if (isUser) R.drawable.right_bubble_background else R.drawable.left_bubble_background
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
        }




        // Add views to the container
        messageLayout.addView(messageTextView)

        // Add the container to the message container
        messageContainer.addView(messageLayout)

        // Smooth scroll to the bottom
        scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
    }
    /**
     * Hide system UI for Pepper screen.
     */
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

    /**
     * TTS init callback (Android local TTS).
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.getDefault()
        }
    }

    /**
     * Handle permission results.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Android lifecycle: cleanup.
     */
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech.shutdown()
        QiSDK.unregister(this, this)
    }

    // RobotLifecycleCallbacks
    override fun onRobotFocusGained(qiContext: QiContext?) {
        this.qiContext = qiContext
        Log.i(TAG, "Robot focus gained.")

        lifecycleScope.launch {
            try {
                // Ensure a session is started or resumed
                startSession()
                Log.i(TAG, "Session started successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session on focus gained: ${e.message}")
            }
        }

        // Greet the user
        sayText("Hello! I am Pepper, ready to assist.")
    }

    override fun onRobotFocusLost() {
        Log.i(TAG, "Robot focus lost.")

        // Clean up QiContext
        this.qiContext = null

        // Notify the user about focus loss
        lifecycleScope.launch {
            try {
                addMessage(false, "Pepper: I've lost focus. Please wait...")
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying focus loss: ${e.message}")
            }
        }

        // Do not start a new session here; wait for focus to be regained
    }


    override fun onRobotFocusRefused(reason: String?) {
        qiContext = null
        addMessage(false, "Pepper: I couldn't get focus, sorry.")
    }
}