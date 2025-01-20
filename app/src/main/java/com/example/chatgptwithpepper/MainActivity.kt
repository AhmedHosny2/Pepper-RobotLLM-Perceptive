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
    private var assistanceId = "";
    private var runId = "";
    // Flags to manage speech recognition state
    private var isEndOfSpeech = false
    private var isListening = false
    private var isTriggerDetected = false

    companion object {
        private const val TAG = "MainActivity"

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
//        hideSystemUI()

        lifecycleScope.launch {
            try {

                // first  create assistance
                assistanceId = CreateAssistance()
                Log.d(TAG, "Assistance id: $assistanceId")
                // second create thread
                 threadId = CreateChatGPTThread()
                Log.d(TAG, "Thread id: $threadId")
                // third add message to thread
                AddMessageToThread("my name is ahmed")
                // forth run the thread with the assistance
                runId= RunThreadWithAssistance()
                Log.d(TAG, "Run id: $runId")
                // last thing is to get the response from the thread through streaming
                // Use threadId as needed after this point
                GetGPTResponse()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating thread: ${e.message}")
            }
        }

    }
private suspend fun RunThreadWithAssistance():String = withContext(Dispatchers.IO)
{
    var runIdResponce = "";
    Log.d(TAG, "Run ChatGPT thread with assistance")
    // build body it should contain assitance id
    val requestBodyJson = JSONObject().apply {
        put("assistant_id", assistanceId)
    }.toString().toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/runs")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA")
        .addHeader("OpenAI-Beta", "assistants=v2")
        .post(requestBodyJson)
        .build()

    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Unexpected code $response")
                throw IOException("Unexpected HTTP response: ${response.code}")
            }

            val responseBody = response.body?.string().orEmpty()
            Log.d(TAG, "ChatGPT Response for running thread with assistance: $responseBody")
            runIdResponce = JSONObject(responseBody).getString("id")
            Log.d(TAG, "Run id: $runIdResponce")
        }
    } catch (e: IOException) {
        Log.e(TAG, "Network Error: ${e.message}")
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error: ${e.message}")
        throw e
    }
    return@withContext runIdResponce
}

private suspend fun  GetGPTResponse()= withContext(Dispatchers.IO)
{
    Log.d(TAG, "get messages from running thread")

    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/runs/$runId")
        .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA")
        .addHeader("OpenAI-Beta", "assistants=v2")
        .get()
        .build()

    try {
        // fetch with streaming while status != completed keep fetching
          // while true
              while (true) {
                  val response = client.newCall(request).execute()
                  if (!response.isSuccessful) {
                      Log.e(TAG, "Unexpected code $response")
                      throw IOException("Unexpected HTTP response: ${response.code}")


                  }

                  val responseBody = response.body?.string().orEmpty()
                  Log.d(TAG, "ChatGPT Response for running thread: $responseBody")
                  val responseJson = JSONObject(responseBody)
                  val messages = responseJson.getJSONArray("messages")
                  for (i in 0 until messages.length()) {
                      val message = messages.getJSONObject(i)
                      val content = message.getString("content")
                      Log.d(TAG, "Message: $content")
                      // Add message to UI
//            addMessageToUI(content)
                  }
                  if (responseJson.getString("status") == "completed") {
                      Log.d(TAG, "ChatGPT thread completed")
                        break
                  }
              }

    } catch (e: IOException) {
        Log.e(TAG, "Network Error: ${e.message}")
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error: ${e.message}")
        throw e
    }

}





                    
// first thing create assistance
private suspend fun CreateAssistance():String = withContext(Dispatchers.IO)
    {
        Log.d(TAG, "Create new ChatGPT Assistance")
        var assitanceIdResponce  = ""
        // create body json
        val requestBodyJson = JSONObject().apply {
            put("instructions", "Your are running on a robot called pepper he is helpful friendly and cute people love him")
            put("name", "Pepper")
            put("description", "ChatGPT Assistant")
            put("model", "gpt-4o")
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/assistants")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA")
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(requestBodyJson)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Unexpected code $response")
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }

                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "ChatGPT Response for creating Assistance: $responseBody")

                val jsonObject = JSONObject(responseBody)
                assitanceIdResponce = jsonObject.getString("id")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network Error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            throw e
        }

        return@withContext assitanceIdResponce
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
    private fun AddMessageToThread(userText: String) {
        Log.d(TAG, "Sending text to ChatGPT: $userText")

        // Create JSON body for the request
        val requestBodyJson = JSONObject().apply {
           put("role", "user")
            put("content", userText)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toString().toRequestBody(mediaType)

        // *** Replace "YOUR_API_KEY" with your actual API key ***
        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA") // <-- Replace here
            .addHeader("OpenAI-Beta", "assistants=v2")
            .post(body)
            .build()
        Log.d("HTTP Request", "URL: ${request.url}, Body: $requestBodyJson")
        // Use lifecycleScope for coroutine
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Unexpected code $response")
                        withContext(Dispatchers.Main) {
//                            addMessage(false, "Pepper: I'm sorry, I couldn't process that.")
                            Log.d(TAG, "Error: ${response.code}")
                        }
                        return@use
                    }

                    val responseBody = response.body?.string().orEmpty()
                    Log.d(TAG, "ChatGPT Response from adding message into thread: $responseBody")

                }
            } catch (e: IOException) {
                Log.e(TAG, "Network Error: ${e.message}")
                withContext(Dispatchers.Main) {
Log.e(TAG, "Network Error: ${e.message}")
                               }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error: ${e.message}")
                }
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }

    override fun onInit(p0: Int) {
        TODO("Not yet implemented")
    }
}