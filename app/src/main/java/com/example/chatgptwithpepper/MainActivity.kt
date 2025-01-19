// MainActivity.kt

package com.example.chatgptwithpepper

import android.Manifest
import kotlinx.coroutines.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var threadId = "";
    // Flags to manage speech recognition state
    private var isEndOfSpeech = false
    private var isListening = false
    private var isTriggerDetected = false

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val TRIGGER_PHRASE = "hey zack" // Trigger phrase
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()

        lifecycleScope.launch {
            try {
                // Call the function and wait for its result
                val threadId = CreateChatGPTThread()
                Log.d("yaya", "Thread id: $threadId")

                // Use threadId as needed after this point
            } catch (e: Exception) {
                Log.e("yaya", "Error creating thread: ${e.message}")
            }
        }
    }


    /**
     * Create a thread with ChatGPT and return the thread ID as a string
     */
    private suspend fun CreateChatGPTThread(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Create new ChatGPT thread")
        var threadIdResponse = ""

        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA")
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post("{}".toRequestBody())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Unexpected code $response")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response for creating thread: $responseBody")

                val jsonObject = JSONObject(responseBody)
                threadIdResponse = jsonObject.getString("id")
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
     * Clean up resources
     */
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }

    override fun onInit(p0: Int) {
        TODO("Not yet implemented")
    }
}