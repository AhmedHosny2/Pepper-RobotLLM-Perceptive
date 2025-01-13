package com.example.chatgptwithpepper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // UI Elements
    private lateinit var startButton: Button
    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    // Speech Components
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    // OkHttp Client for API calls
    private val client = OkHttpClient()

    // Flags to manage speech recognition state
    private var isEndOfSpeech = false
    private var isListening = false

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val TRIGGER_PHRASE = "hey zack" // Updated trigger phrase
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Initialize UI elements
        startButton = findViewById(R.id.startButton)
        messageContainer = findViewById(R.id.messageContainer)
        scrollView = findViewById(R.id.scrollView)

        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Speech recognition is not available on this device.",
                Toast.LENGTH_LONG
            ).show()
            startButton.isEnabled = false
            return
        }

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)

        // Set Start Button Click Listener
        startButton.setOnClickListener {
            checkAudioPermissionAndStart()
        }
    }

    /**
     * RecognitionListener to handle speech recognition callbacks
     */
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            isEndOfSpeech = false
            isListening = true
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Optional: Handle real-time RMS changes (e.g., visualizing audio levels)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Optional: Handle buffer received
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            isEndOfSpeech = true
            isListening = false
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Speech recognition error: $error")

            // Handle specific errors
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    "Speech timed out. Please try speaking again."
                }
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    "No match found. Please try again."
                }
                SpeechRecognizer.ERROR_NETWORK -> {
                    "Network error. Check your connection."
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    "Recognizer is busy. Please wait a moment."
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    "Client error. Possibly no mic input in emulator."
                }
                else -> {
                    "An unknown error occurred."
                }
            }

            Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()

            // Prevent restarting listening immediately to avoid loops
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Restart listening after a short delay if end of speech was detected
                    if (isEndOfSpeech) {
                        lifecycleScope.launch {
                            delay(1000) // Delay to prevent rapid restarts
                            startListening()
                        }
                    } else {
                        Log.d(TAG, "Error occurred before end of speech. Not restarting.")
                    }
                }
                else -> {
                    Log.d(TAG, "Not restarting for error code $error.")
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val transcript = matches[0].trim()
                Log.d(TAG, "Recognized Speech: $transcript")
                addMessage(true, "You: $transcript")
                sendTextToChatGpt(transcript)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No speech recognized. Please try again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // Restart listening to keep the conversation going
            lifecycleScope.launch {
                delay(500)
                startListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle) {
            val data = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

            if (!data.isNullOrEmpty()) {
                val partialTranscript = data[0].trim()
                Log.d(TAG, "Partial Result: $partialTranscript")

                // Check for the trigger phrase "hey zack"
                if (partialTranscript.contains(TRIGGER_PHRASE, ignoreCase = true)) {
                    informUserSpeechRecognized()
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Speech Recognizer Event: $eventType")
        }
    }

    /**
     * Inform the user that "hey zack" was recognized
     */
    private fun informUserSpeechRecognized() {
        val message = "Hey Zack recognized your speech!"
        addMessage(false, "Pepper: $message")
        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        Log.d(TAG, "Trigger phrase detected: $TRIGGER_PHRASE")
    }

    /**
     * Check for audio permissions and start listening if granted
     */
    private fun checkAudioPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startListening()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(
                    this,
                    "Audio permission is needed for speech recognition.",
                    Toast.LENGTH_SHORT
                ).show()
                requestAudioPermission()
            }
            else -> {
                requestAudioPermission()
            }
        }
    }

    /**
     * Request audio recording permission
     */
    private fun requestAudioPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Start listening for user speech
     */
    private fun startListening() {
        try {
            Log.d(TAG, "Starting listening")
            isListening = true
            isEndOfSpeech = false
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

                // Adjust timeout periods
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    4000 // Increased from 2000ms
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    2000 // Increased from 1000ms
                )
            }
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening: ${e.message}")
            Toast.makeText(this, "Failed to start listening. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Send the recognized text to ChatGPT and handle the response
     */
    private fun sendTextToChatGpt(userText: String) {
        Log.d(TAG, "Sending text to ChatGPT: $userText")

        // Build the messages array
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are Pepper, a helpful and friendly robot.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        // Create JSON body for the request
        val requestBodyJson = JSONObject().apply {
            put("model", "gpt-4")
            put("messages", messagesArray)
            put("max_tokens", 150)
            put("temperature", 0.7)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toString().toRequestBody(mediaType)

        // *** Replace "YOUR_API_KEY" with your actual API key ***
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer YOUR_API_KEY") // <-- Replace here
            .post(body)
            .build()

        // Use lifecycleScope for coroutine
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Unexpected code $response")
                        withContext(Dispatchers.Main) {
                            addMessage(false, "Pepper: I'm sorry, I couldn't process that.")
                        }
                        return@use
                    }

                    val responseBody = response.body?.string().orEmpty()
                    Log.d(TAG, "ChatGPT Response: $responseBody")

                    val jsonObject = JSONObject(responseBody)
                    val choices = jsonObject.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val messageObj = firstChoice.getJSONObject("message")
                        val content = messageObj.getString("content").trim()

                        withContext(Dispatchers.Main) {
                            addMessage(false, "Pepper: $content")
                            // Use TTS to speak the response
                            textToSpeech.speak(content, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addMessage(false, "Pepper: I didn't receive a response.")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    addMessage(false, "Pepper: Something went wrong while connecting.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    addMessage(false, "Pepper: An unexpected error occurred.")
                }
            }
        }
    }

    /**
     * Add a message to the chat UI
     * @param isUser Indicates if the message is from the user
     * @param message The message text
     */
    private fun addMessage(isUser: Boolean, message: String) {
        val textView = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            setPadding(16, 16, 16, 16)
            setBackgroundResource(
                if (isUser)
                    R.drawable.right_bubble_background
                else
                    R.drawable.left_bubble_background
            )
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                if (isUser) {
                    gravity = Gravity.END
                    marginEnd = 16
                    marginStart = 50
                } else {
                    gravity = Gravity.START
                    marginStart = 16
                    marginEnd = 50
                }
            }
            layoutParams = params
        }

        messageContainer.addView(textView)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Hide system UI for full-screen experience
     */
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

    /**
     * Initialize TextToSpeech
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.e(TAG, "Language not supported for TTS")
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startListening()
            } else {
                Toast.makeText(
                    this,
                    "Permission denied. Cannot use speech recognition.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Clean up resources
     */
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }
}