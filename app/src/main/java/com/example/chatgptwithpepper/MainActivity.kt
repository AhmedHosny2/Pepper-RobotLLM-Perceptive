package com.example.chatgptwithpepper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.Qi
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.conversation.Say
//import com.aldebaran.qi.sdk.`object`.conversation.SayFuture
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeoutException
import android.util.Base64.encodeToString
import android.util.Base64.DEFAULT
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
    private lateinit var takePicButton: Button

    // Speech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private val API_URL = "https://api.openai.com/v1/chat/completions"
    private var pictureBitmap: Bitmap? = null


    // QiSDK
    private var qiContext: QiContext? = null

    // OkHttp Client
    private val client = OkHttpClient()

    // Flags

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101

    }

    private val apiKey: String by lazy {
        "OPEN-AI-API-KEY" // <-- Replace with secured API key retrieval
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
        takePicButton = findViewById(R.id.take_pic_button)

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
                    runOnUiThread {
                        addMessage(true, "You: $result")
                    }
                    Log.d(TAG, "User: $result")

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
        // Take picture button
        takePicButton.setOnClickListener { takePic() }

    }


    /**
     * Actually takes the picture via Pepper’s camera and displays it, then calls sendImageToChatGPT().
     */
    private fun takePic() {
        if (qiContext == null) {
            return
        }

        // Clear old bitmap


        Log.i(TAG, "build take picture")

        // Build the TakePicture action asynchronously
        val takePictureFuture = TakePictureBuilder.with(qiContext).buildAsync()

        // Chain the calls so that the picture is taken on the UI thread (via Qi.onUiThread)
        takePictureFuture
            .andThenCompose<TimestampedImageHandle>(Qi.onUiThread<TakePicture, Future<TimestampedImageHandle>> { takePicture ->
                Log.i(TAG, "take picture launched!")
                // Show progress bar, disable button
                takePicButton.isEnabled = false
                // Actually run the action
                takePicture.async().run()
            })
            .andThenConsume { timestampedImageHandle ->
                Log.i(TAG, "Picture taken")
                val encodedImageHandle = timestampedImageHandle.image
                val encodedImage = encodedImageHandle.value
                Log.i(TAG, "PICTURE RECEIVED!")

                // Return to UI thread to hide progress
                runOnUiThread {
                    takePicButton.isEnabled = true
                }

                // Read the byte array
                val buffer = encodedImage.data
                buffer.rewind()
                val pictureBufferSize = buffer.remaining()
                val pictureArray = ByteArray(pictureBufferSize)
                buffer.get(pictureArray)

                Log.i(TAG, "PICTURE RECEIVED! ($pictureBufferSize Bytes)")

                // Decode into a Bitmap
                val bitmap = BitmapFactory.decodeByteArray(pictureArray, 0, pictureBufferSize)
                pictureBitmap = bitmap

                // Display the image on Pepper’s tablet
                runOnUiThread {
                   addImageBubble(true, bitmap)

                }

                // Convert to Base64
                val base64 = Base64.encodeToString(pictureArray, Base64.DEFAULT)
                Log.i(TAG, "PICTURE RECEIVED! ($base64)")


                    Log.d("SendImageToApi", "Starting to send image to API.")

                    lifecycleScope.launch {
                        sendImageToChatGPT(base64)
                        // send image to normal gpt for images
                        // get resposne
                        // let pepper say the reposne
                        // attach system reposne into the thread
                    }
                    Log.d("SendImageToApi", "Request sent.")
                }


    }

    suspend fun  sendImageToChatGPT(image64Base: String) {
        Log.d("SendImageToApi", "Starting to send image to API.")

        // Prepare JSON payload
        val jsonObject = JSONObject().apply {
            try {
                val contentArray = JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", "What is in this image? give me answer that will be said by a robot so make it human feeling with complments with simple english like I can see a man with an awesome tshirt and great glasses doing something (like working)  in his place (like office) and some more details  make it cute and simple english so that it can be said by a robot.")                        }
                    )
                    put(
                        JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$image64Base")
                                }
                            )
                        }
                    )
                }

                val messageObject = JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                }

                val messagesArray = JSONArray().apply {
                    put(messageObject)
                }

                put("messages", messagesArray)
                put("model", "gpt-4o-mini")
            } catch (e: Exception) {
                Log.e("SendImageToApi", "Error while preparing JSON payload: ${e.message}")
            }
        }

        Log.d("SendImageToApi", "JSON payload prepared: $jsonObject")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, jsonObject.toString())
        Log.d("SendImageToApi", "Request body created.")
        // get api key from build config
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        Log.d("SendImageToApi", "HTTP request built.")

        // Execute the request asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SendImageToApi", "HTTP request failed: ${e.message}")

            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val choicesArray = jsonResponse.getJSONArray("choices")
                        val firstChoice = choicesArray.getJSONObject(0)
                        val message = firstChoice.getJSONObject("message")
                        val content = message.getString("content")

                        Log.d("SendImageToApi", "Content: $content")
                        runOnUiThread {
                    Log.e("SendImageToApi", "Content: $content")
                            sayText(content) // Speak the content
                            runOnUiThread {
                                addMessage(false, "Pepper: $content") // Add the content to the chat

                            }
                            lifecycleScope.launch {
                                val updatedContent = "$content. I will use this message in my future conversations. If someone asks me about their t-shirt color, their location, or anything from their environment, I will use it to enhance my responses."
                                addMessageToThread(updatedContent, false)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SendImageToApi", "Error parsing JSON response: ${e.message}")
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.e("SendImageToApi", "HTTP response failed. Code: ${response.code}, Message: ${response.message}, Body: $errorBody")

                }
            }  })
        Log.d("SendImageToApi", "Request sent.")
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
    private suspend fun submitMessage(userText: String, isImage : Boolean = false) {
        try {
            if(isImage)
                addMessageToThread(userText, false)
            else             // Step 3: Add message to thread
            addMessageToThread(userText, true)

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
                    put("instructions", """
            You are operating on a robot named Pepper. Pepper is helpful, friendly, and cute, and people love him. 
            You are stateful, meaning you can remember the context of conversations. Demonstrate this by using someone's 
            name or their job when appropriate. You can incorporate jokes and dark comedy about overcoming challenges 
            and (mockingly) conquering the world, followed by a playful remark like "just kidding" or "or not?" ,
            " dont' use the dark comedy every message just once or twice in the conversation" ,
            "try to make each message has a flavour or something",
            Keep your responses concise, no longer than three lines. If I provide an image, describe it in a nice, 
            cute way. Additionally, since people often think Pepper might take over the world, feel free to play 
            with this idea in a funny and darkly comedic manner. but don't assume names just use if given
        """.trimIndent())
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
    private suspend fun addMessageToThread(messageText: String, isUser: Boolean = true) = withContext(Dispatchers.IO) {
        if(isUser)
            Log.d(TAG, "Sending text to ChatGPT: $messageText")
            else
        {

            Log.d(TAG, "store gpt response : $messageText")

        }


            var requestBodyJson = JSONObject().toString();
        // Create JSON body for the request
        if(isUser) {


             requestBodyJson = JSONObject().apply {
                put("role", "user")
                put("content", messageText)
            }.toString()
        }else
        {
             requestBodyJson = JSONObject().apply {
                put("role", "assistant")
                put("content", messageText)
            }.toString()

        }

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
                            //i wanna attache all images and display them
                            // for each on messages and add them
//                                for( string mesg in messages)
//                            {
//                                    addMessage(true, mesg )
//                            }

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
     * Adds an image bubble to the UI (similar to addMessage, but for images).
     *
     * @param isUser True if this is the user’s image; false if it’s from Pepper/assistant.
     * @param bitmap The image to display in the bubble.
     */
    private fun addImageBubble(isUser: Boolean, bitmap: Bitmap) {
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = 12
                marginStart = if (isUser) 100 else 20
                marginEnd = if (isUser) 20 else 100
            }
        }

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            // Adjust size or scale as needed
            layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                // optional margins, etc.
            }
            // Optionally apply background or padding for a bubble look
            // setBackgroundResource(R.drawable.right_bubble_background)
            setPadding(20, 12, 20, 12)
        }

        // Timestamp below the image
        val timestampTextView = TextView(this).apply {
            text = getCurrentTimestamp()
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            }
        }

        messageLayout.addView(imageView)
        messageLayout.addView(timestampTextView)
        messageContainer.addView(messageLayout)

        scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
    }


    /**
     * Add a message bubble to the UI.
     * @param isUser: whether it's user or Pepper
     * @param message: the text to display
     */
    /**
     * Adds a text message bubble to the UI.
     *
     * @param isUser True if this is the user’s message; false if it’s Pepper/assistant.
     * @param message The text to display in the bubble.
     */
    private fun addMessage(isUser: Boolean, message: String) {
        // A vertical container for text bubble + timestamp
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = 12
                marginStart = if (isUser) 100 else 20
                marginEnd = if (isUser) 20 else 100
            }
        }

        // The TextView for the message itself
        val messageTextView = TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(20, 12, 20, 12)
            setTextColor(
                if (isUser) getColor(android.R.color.white) else getColor(android.R.color.black)
            )
            setBackgroundResource(
                if (isUser) R.drawable.right_bubble_background
                else R.drawable.left_bubble_background
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
        }

        // A small timestamp label below the message bubble
        val timestampTextView = TextView(this).apply {
            text = getCurrentTimestamp()
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END // Align timestamp to the end of the bubble
            }
        }

        // Combine them and add to our chat container
        messageLayout.addView(messageTextView)
        messageLayout.addView(timestampTextView)
        messageContainer.addView(messageLayout)

        // Scroll to the bottom so new messages are visible
        scrollView.post { scrollView.smoothScrollTo(0, scrollView.bottom) }
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun getCurrentTimestamp(): String {
        val currentTime = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(currentTime)
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
                // take picture
                takePic()
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
                runOnUiThread {
                addMessage(false, "Pepper: I've lost focus. Please wait...")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying focus loss: ${e.message}")
            }
        }

        // Do not start a new session here; wait for focus to be regained
    }


    override fun onRobotFocusRefused(reason: String?) {
        qiContext = null
        runOnUiThread {
            addMessage(false, "Pepper: I couldn't get focus, sorry.")
        }
    }
}