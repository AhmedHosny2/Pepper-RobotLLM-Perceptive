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
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.Qi
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.*

@RequiresApi(Build.VERSION_CODES.DONUT)
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, RobotLifecycleCallbacks {
    // UI elements
    private lateinit var helloUlmButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var takePicButton: Button
    private lateinit var pictureView: ImageView
    private lateinit var startButton: Button
    private lateinit var messageContainer: LinearLayout

    // Speech / TTS
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private val client = OkHttpClient()

    // ChatGPT conversation tracking
    private lateinit var responseTextView: String
    private lateinit var resultText: String
    private var seclastuser = "xyz"
    private var seclastsystem = "xyz"
    private var lastuser = "xyz"
    private var lastsystem = "xyz"
    private val API_URL = "https://api.openai.com/v1/chat/completions"

    // QiSDK
    private var qiContext: QiContext? = null

    // Picture
    private var timestampedImageHandleFuture: Future<TimestampedImageHandle>? = null
    private var pictureBitmap: Bitmap? = null

    // Flags and constants
    private var activation = false
    private val TAG = "TakePictureActivity"

    companion object {
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
    }

    @DelicateCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hide system UI for Pepper full screen
        hideSystemUI()

        // Register for QiSDK events
        QiSDK.register(this, this)

        // Initialize TTS
        textToSpeech = TextToSpeech(this, this)

        // Hook up UI elements
        messageContainer = findViewById(R.id.messageContainer)
        startButton = findViewById(R.id.startButton)
        helloUlmButton = findViewById(R.id.helloUlmButton)
        progressBar = findViewById(R.id.progress_bar)
        takePicButton = findViewById(R.id.take_pic_button)
        pictureView = findViewById(R.id.picture_view)

        // Prepare speech recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}

            @SuppressLint("SetTextI18n")
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val result = matches[0]
                    resultText = result
                    addMessage(false, "Du: $result") // Show user message

                    // Build the conversation with previous messages
                    val systemprompt = JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are the friendly Robot Pepper. You make friendly conversation... " +
                                    "You like your colleagues at the university, etc."
                        )
                    }
                    val secluser = JSONObject().apply {
                        put("role", "user")
                        put("content", seclastuser)
                    }
                    val seclsys = JSONObject().apply {
                        put("role", "assistant")
                        put("content", seclastsystem)
                    }
                    val luser = JSONObject().apply {
                        put("role", "user")
                        put("content", lastuser)
                    }
                    val lsys = JSONObject().apply {
                        put("role", "assistant")
                        put("content", lastsystem)
                    }
                    val currentmessage = JSONObject().apply {
                        put("role", "user")
                        put("content", resultText)
                    }

                    // Build final messages array
                    val previousMessages = JSONArray().apply {
                        put(systemprompt)
                        if (seclastuser != "xyz") put(secluser)
                        if (seclastsystem != "xyz") put(seclsys)
                        if (lastuser != "xyz") put(luser)
                        if (lastsystem != "xyz") put(lsys)
                        put(currentmessage)
                    }

                    val content = JSONObject().apply {
                        // ** Adjust your model name if needed; "gpt-4" or "gpt-4-0314" etc.
                        //   If you intend GPT-4, do "gpt-4"; if you only have GPT-3.5, do "gpt-3.5-turbo"
                        put("model", "gpt-4o")
                        put("max_tokens", 100)
                        put("messages", previousMessages)
                    }.toString()

                    val client = OkHttpClient()
                    val mediaType = "application/json".toMediaType()
                    val requestBody = content.toRequestBody(mediaType)
                    val apiKey = "sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA"

                    Log.d("myTag", apiKey.toString())
                    // If addMessage touches UI, wrap in runOnUiThread or do it on the main thread
                    runOnUiThread {
                        addMessage(true, apiKey.toString())
                    }

                    val request = Request.Builder()
                        .url("https://api.openai.com/v1/chat/completions")
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()

                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val responseBody = response.body?.string()
                                Log.d("myTag", responseBody.toString())

                                val jsonObject = JSONObject(responseBody ?: "{}")
                                val choicesArray = jsonObject.getJSONArray("choices")
                                val firstChoiceObject = choicesArray.getJSONObject(0)
                                val messageObject = firstChoiceObject.getJSONObject("message")
                                val text = messageObject.getString("content")
                                val formattedText = text.replace("\n", " ")

                                // Update the conversation memory
                                seclastuser = lastuser
                                seclastsystem = lastsystem
                                lastuser = resultText
                                lastsystem = formattedText

                                // Show the response in chat, set Pepper to speak
                                runOnUiThread {
                                    responseTextView = formattedText
                                    addMessage(true, "Pepper: $formattedText")
                                    activation = true

                                    // If you want Pepper to speak the new text immediately:
                                    sayText(formattedText)
                                }
                            } else {
                                // Handle error response
                                runOnUiThread {
                                    addMessage(true, "Pepper: Sorry, I couldn't process that.")
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                addMessage(true, "Error: ${e.message}")
                            }
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // Start speech recognition
        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_REQUEST_CODE
                )
            } else {
                startSpeechRecognition()
            }
        }

        // Simple "Hello from Ulm" button
        helloUlmButton.setOnClickListener {
            sayText("Hello from Ulm")
            val apiKey ="sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA"
            Log.d("myTag", apiKey.toString())
            runOnUiThread {
                addMessage(true, apiKey.toString())
            }
        }

        // Take picture button
        takePicButton.setOnClickListener { takePic() }
    }

    /**
     * Send an image in Base64 form to ChatGPT for analysis.
     */
    fun sendImageToChatGPT(image64Base: String) {
              Log.d("SendImageToApi", "Starting to send image to API.")

            // Prepare JSON payload
            val jsonObject = JSONObject().apply {
                try {
                    val contentArray = JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("type", "text")
                                put("type", "text")
                                put("text", "What is in this image? give me answer that will be siad by a robot so make it human feeling with complments with simple english like I can see a man with an awesome tshirt and great glasses drinking cofffee in his office and some omre details ")
                            }
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
            val API_KEY = "sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA"
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(body)
                .build()

            Log.d("SendImageToApi", "HTTP request built.")

            // Execute the request asynchronously
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("SendImageToApi", "HTTP request failed: ${e.message}")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to upload image: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
                                Toast.makeText(
                                    this@MainActivity,
                                    "Content: $content",
                                    Toast.LENGTH_LONG
                                ).show()
                                println("Content: $content") // Print content to the console
                                sayText(content) // Speak the content
                                addMessage(true, "Pepper: $content") // Add the content to the chat
                            }
                        } catch (e: Exception) {
                            Log.e("SendImageToApi", "Error parsing JSON response: ${e.message}")
                        }
                    } else {
                        val errorBody = response.body?.string()
                        Log.e("SendImageToApi", "HTTP response failed. Code: ${response.code}, Message: ${response.message}, Body: $errorBody")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Upload failed: ${response.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }  })
            Log.d("SendImageToApi", "Request sent.")
        }


    /**
     * Actually takes the picture via Pepper’s camera and displays it, then calls sendImageToChatGPT().
     */
    private fun takePic() {
        if (qiContext == null) {
            return
        }

        // Clear old bitmap
        pictureBitmap?.let {
            it.recycle()
            pictureBitmap = null
            pictureView.setImageBitmap(null)
        }

        Log.i(TAG, "build take picture")

        // Build the TakePicture action asynchronously
        val takePictureFuture = TakePictureBuilder.with(qiContext).buildAsync()

        // Chain the calls so that the picture is taken on the UI thread (via Qi.onUiThread)
        takePictureFuture
            .andThenCompose<TimestampedImageHandle>(Qi.onUiThread<TakePicture, Future<TimestampedImageHandle>> { takePicture ->
                Log.i(TAG, "take picture launched!")
                // Show progress bar, disable button
                progressBar.visibility = View.VISIBLE
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
                    progressBar.visibility = View.GONE
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
                    pictureView.setImageBitmap(bitmap)
                }

                // Convert to Base64
                val base64 = Base64.encodeToString(pictureArray, Base64.DEFAULT)
                Log.i(TAG, "PICTURE RECEIVED! ($base64)")

                // Send to ChatGPT
                sendImageToChatGPT(base64)
            }
    }

    /**
     * Pepper says a text string asynchronously.
     */
    private fun sayText(text: String = "Hello from ULM!") {
        // Also show in the chat bubble
        runOnUiThread {
            addMessage(false, "Pepper: $text")
        }

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
     * Start speech recognition if permissions are granted.
     */
    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak something...")
        speechRecognizer.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition()
            }
        }
    }

    /**
     * Hide system UI for a more immersive Pepper experience.
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
     * Add a message bubble to the chat container (UI thread only).
     */
    private fun addMessage(isUser: Boolean, message: String) {
        val textView = TextView(this)
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Different alignment for user vs Pepper
        if (isUser) {
            layoutParams.gravity = android.view.Gravity.END
            layoutParams.marginStart = 200
            layoutParams.marginEnd = 20
            layoutParams.topMargin = 16
            textView.setBackgroundResource(R.drawable.right_bubble_background)
            textView.textSize = 18F
        } else {
            layoutParams.gravity = android.view.Gravity.START
            layoutParams.marginStart = 20
            layoutParams.marginEnd = 200
            layoutParams.topMargin = 16
            textView.textSize = 18F
            textView.setBackgroundResource(R.drawable.left_bubble_background)
        }

        textView.layoutParams = layoutParams
        textView.text = message
        textView.setTextColor(resources.getColor(android.R.color.black))

        // Add to container
        messageContainer.addView(textView)

        // Scroll to bottom
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }

    /**
     * TTS init callback (for Android’s built-in TTS engine).
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.getDefault()
        }
    }

    /**
     * Called when Pepper gains focus: store QiContext and greet once.
     * No infinite loops here!
     */
    override fun onRobotFocusGained(qiContext: QiContext?) {
        this.qiContext = qiContext

        // Greet immediately
        val systemLanguage = Locale.getDefault().language
        val greetText = if (systemLanguage == "de") {
            "Hallo!"
        } else {
            "Hello!"
        }

        // Quick synchronous run
        SayBuilder.with(qiContext).withText(greetText).build().run()

        // Or in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            try {
                SayBuilder.with(qiContext)
                    .withText("Pepper is ready to help you!")
                    .build()
                    .run()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in onRobotFocusGained: ${e.message}")
                runOnUiThread {
                    addMessage(true, "Pepper: Sorry, I couldn't greet you.")
                }
            }
        }
    }

    /**
     * If Pepper loses focus, clear the QiContext reference.
     */
    override fun onRobotFocusLost() {
        qiContext = null
        runOnUiThread {
            addMessage(true, "Pepper: I've lost focus. Please wait...")
        }
    }

    /**
     * If Pepper focus is refused, also clear QiContext.
     */
    override fun onRobotFocusRefused(reason: String?) {
        qiContext = null
        runOnUiThread {
            addMessage(true, "Pepper: Focus was refused.")
        }
    }
}