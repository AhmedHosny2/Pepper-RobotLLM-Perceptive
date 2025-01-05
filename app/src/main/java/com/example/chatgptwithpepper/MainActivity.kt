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

@RequiresApi(Build.VERSION_CODES.DONUT)
class MainActivity : AppCompatActivity(),
    TextToSpeech.OnInitListener,
    RobotLifecycleCallbacks {

    // UI
    private lateinit var startButton: Button
    private lateinit var messageContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    // Speech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    // QiSDK
    private var qiContext: QiContext? = null

    // OkHttp Client
    private val client = OkHttpClient()

    // Flags
    private var listeningMode = ListeningMode.WAKE_WORD  // Start in wake-word mode

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val WAKE_WORD = "hey pepper"
    }

    // Simple enum to distinguish between listening for the wake word vs. user commands
    enum class ListeningMode { WAKE_WORD, COMMAND }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()

        // QiSDK
        QiSDK.register(this, this)

        // TTS
        textToSpeech = TextToSpeech(this, this)

        // Initialize layout references
        startButton = findViewById(R.id.startButton)
        messageContainer = findViewById(R.id.messageContainer)
        scrollView = findViewById(R.id.scrollView)

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
                // Restart listening if there's an error to keep it "always on"
                restartListening()
            }

            @SuppressLint("SetTextI18n")
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val transcript = matches[0].lowercase()
                    Log.d(TAG, "onResults: $transcript")

                    when (listeningMode) {
                        ListeningMode.WAKE_WORD -> {
                            // Check if user said "hey pepper"
                            if (transcript.contains(WAKE_WORD)) {
                                // Wake word detected
                                addMessage(false, "Pepper: I'm listening! What do you want to say?")
                                sayText("I'm listening! What do you want to say?")
                                listeningMode = ListeningMode.COMMAND
                            }
                        }
                        ListeningMode.COMMAND -> {
                            // We have an actual user command
                            addMessage(true, "You: $transcript")

                            // Send to ChatGPT or your function
                            sendTextToChatGpt(transcript)

                            // Switch back to listening for wake word
                            listeningMode = ListeningMode.WAKE_WORD
                        }
                    }
                }

                // Once we have results, we start listening again to remain "always on"
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startButton.setOnClickListener {
            checkAudioPermissionAndStart()
        }
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
        Log.d(TAG, "startListening in mode: $listeningMode")
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
     * This function sends text to ChatGPT using the /chat/completions endpoint
     * and processes the result, showing it on-screen and letting Pepper speak it.
     */
    private fun sendTextToChatGpt(userText: String) {
        Log.d(TAG, "sendTextToChatGpt: $userText")

        // Build minimal messages array for ChatGPT
        val systemMessage = JSONObject().apply {
            put("role", "system")
            put("content", "You are Pepper, a helpful and friendly cute robot.")
        }
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", userText)
        }
        val messagesArray = JSONArray().apply {
            put(systemMessage)
            put(userMessage)
        }

        // Create JSON body
        val requestBodyJson = JSONObject().apply {
            put("model", "gpt-4o")
            // or "gpt-4" if your account can access GPT-4
            put("max_tokens", 100)
            put("messages", messagesArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toString().toRequestBody(mediaType)

        // Replace with your actual API key
        val apiKey = BuildConfig.OPENAI_API_KEY

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        // Call the API asynchronously
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    Log.d(TAG, "ChatGPT Response: $responseBody")

                    val jsonObject = JSONObject(responseBody)
                    val choices = jsonObject.getJSONArray("choices")
                    val firstChoice = choices.getJSONObject(0)
                    val messageObj = firstChoice.getJSONObject("message")
                    val content = messageObj.getString("content").trim()

                    withContext(Dispatchers.Main) {
                        addMessage(false, "Pepper: $content")
                        sayText(content)
                    }
                } else {
                    val err = response.body?.string().orEmpty()
                    Log.e(TAG, "ChatGPT Error: ${response.code}\n$err")
                    withContext(Dispatchers.Main) {
                        addMessage(false, "Pepper: I'm sorry, I couldn't process that.")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "sendTextToChatGpt Exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    addMessage(false, "Pepper: Something went wrong.")
                }
            }
        }
    }

    /**
     * Pepper speaks the given text (if QiContext is available).
     */
    private fun sayText(text: String) {
        // Also show in logs
        Log.d(TAG, "sayText: $text")

        // If QiContext is available, use QiSDK TTS
        qiContext?.let { context ->
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val say: Say = SayBuilder.with(context).withText(text).build()
                    val sayFuture: SayFuture = say.run()
                    sayFuture.get() // Wait for completion
                } catch (e: Exception) {
                    Log.e(TAG, "sayText error: ${e.message}")
                }
            }
        } ?: run {
            // Fallback to local TTS
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    /**
     * Add a message bubble to the UI.
     * @param isUser: whether it's user or Pepper
     * @param message: the text to display
     */
    private fun addMessage(isUser: Boolean, message: String) {
        val textView = TextView(this)
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        if (isUser) {
            layoutParams.gravity = Gravity.END
            layoutParams.marginStart = 200
            layoutParams.marginEnd = 20
            textView.setBackgroundResource(R.drawable.right_bubble_background)
        } else {
            layoutParams.gravity = Gravity.START
            layoutParams.marginStart = 20
            layoutParams.marginEnd = 200
            textView.setBackgroundResource(R.drawable.left_bubble_background)
        }
        layoutParams.topMargin = 16
        textView.layoutParams = layoutParams
        textView.textSize = 18f
        textView.text = message
        textView.setTextColor(getColor(android.R.color.black))

        messageContainer.addView(textView)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
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
        // Greet user
        sayText("Hello! I am Pepper, ready to assist. Say 'Hey Pepper' anytime to talk to me.")
    }

    override fun onRobotFocusLost() {
        qiContext = null
        addMessage(false, "Pepper: I've lost focus. Please wait...")
    }

    override fun onRobotFocusRefused(reason: String?) {
        qiContext = null
        addMessage(false, "Pepper: I couldn't get focus, sorry.")
    }
}