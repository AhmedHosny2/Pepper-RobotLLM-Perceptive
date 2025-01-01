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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.builder.SayBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.*

@RequiresApi(Build.VERSION_CODES.DONUT)
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, RobotLifecycleCallbacks {
    // declare the button
    private lateinit var helloUlmButton: Button
    private lateinit var responseTextView: String
    private lateinit var resultText: String
    private var seclastuser = "xyz"
    private var seclastsystem = "xyz"
    private var lastuser = "xyz"
    private var lastsystem = "xyz"
    private lateinit var startButton: Button
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var messageContainer: LinearLayout
    private lateinit var textToSpeech: TextToSpeech
    var activation = false
    private var qiContext: QiContext? = null


    @DelicateCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemUI()
        QiSDK.register(this, this)

        textToSpeech = TextToSpeech(this, this)
        messageContainer = findViewById(R.id.messageContainer)
        startButton = findViewById(R.id.startButton)
        // init the button
        helloUlmButton = findViewById(R.id.helloUlmButton)

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
                    addMessage(false, "Du: $result")


                        val client = OkHttpClient()



                        val systemprompt = JSONObject().apply {
                            put("role", "system")
                            put("content", "You are the friendly Robot Pepper. As you cannot move yourself or physically interact with things or people, you dont ask them how you can assist. You just make friendly conversations. You work at the Institut für Business Analytics at the Universität Ulm. You like your collegues there a lot. You are excited to be at the university and greet people here.")
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

// Create a JSON array and add the previous messages
                        val previousMessages = JSONArray().apply {
                            put(systemprompt)
                            if(seclastuser !="xyz") {put(secluser)}
                            if(seclastsystem !="xyz") put(seclsys)
                            if(lastuser !="xyz") put(luser)
                            if(lastsystem !="xyz") put(lsys)
                            put(currentmessage)
                        }
                        val content = JSONObject().apply {
                            put("model", "gpt-4o")
                            put("max_tokens", 100) // Adjust the number of tokens as needed
                            put("messages", previousMessages)
                        }.toString()
                        Log.d("myTag", content);
                        val mediaType = "application/json".toMediaType()
                        val requestBody = content.toRequestBody(mediaType)

                        val request = Request.Builder()
                            .url("https://api.openai.com/v1/chat/completions")
                            .post(requestBody)
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Authorization", "Bearer sk-BUMxb1U5tb7_GCSflMR67ihzYDCI7yqGbekCP0KQY1T3BlbkFJ369mt7GouL0cBfVZy1dpT2ZkOLeWtJMYBY_TvVGWAA")
                            .build()

                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                Log.d("myTag", "Testnachricht1");
                                val response = client.newCall(request).execute()
                                Log.d("myTag", "Testnachricht2");
                                if (response.isSuccessful) {
                                    Log.d("myTag", "Testnachricht3");
                                    val responseBody = response.body?.string()
                                    Log.d("myTag", responseBody.toString());
                                    val jsonObject = JSONObject(responseBody)
                                    val choicesArray = jsonObject.getJSONArray("choices")
                                    val firstChoiceObject = choicesArray.getJSONObject(0)
                                    val messageObject = firstChoiceObject.getJSONObject("message")
                                    val text = messageObject.getString("content")
                                    val formattedText = text.replace("\n", " ")

                                    //Update der früheren Nachrichten
                                    seclastuser = lastuser
                                    seclastsystem = lastsystem
                                    lastuser = resultText
                                    lastsystem = formattedText

                                    runOnUiThread {
                                        responseTextView = formattedText
                                        addMessage(true, "Pepper: $formattedText")
                                        activation = true
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
                //activation = true
                startSpeechRecognition()

            }
        }

        // set on click listen for the button
        helloUlmButton.setOnClickListener {
            sayHelloFromUlm()
        }


    }

    // declare the function to use
    private fun sayHelloFromUlm() {
        // Display message in UI
        addMessage(false, "Pepper: Hello from ULM!")

        // Use QiContext to make Pepper speak asynchronously
        qiContext?.let { context ->
            // Launch a coroutine on the IO dispatcher
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    SayBuilder.with(context)
                        .withText("Hello from ULM!")
                        .build()
                        .run()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in sayHelloFromUlm: ${e.message}")
                    // Update UI on the main thread
                    runOnUiThread {
                        addMessage(true, "Pepper: Sorry, I couldn't say hello.")
                    }
                }
            }
        } ?: run {
            // Handle the case where QiContext is not available
            addMessage(true, "Pepper: Sorry, I'm not ready yet.")
        }
    }
    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak something...")
        speechRecognizer.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition()
            }
        }
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
    }



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


    private fun addMessage(isUser: Boolean, message: String) {
        val textView = TextView(this)
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

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
            textView.textSize = 18F
            layoutParams.topMargin = 16
            textView.setBackgroundResource(R.drawable.left_bubble_background)
        }

        textView.layoutParams = layoutParams
        textView.text = message
        textView.setTextColor(resources.getColor(android.R.color.black))

        messageContainer.addView(textView)
        // Automatically scroll to the bottom
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN) // Scroll to the bottom
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            //val text = ""
            textToSpeech.language = Locale.getDefault() // Set the language, you can use other locales
            //textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onRobotFocusGained(qiContext: QiContext?) {
        this.qiContext = qiContext  // Store QiContext globally

        val systemLanguage = Locale.getDefault().language
        var greetText = ""
        greetText = if (systemLanguage == "de") {
            "Hallo!"
        } else {
            "Hello!"
        }

        val sayInt = SayBuilder.with(qiContext)
            .withText(greetText)
            .build()
        sayInt.run()



        GlobalScope.launch(Dispatchers.IO) {
            try {
                SayBuilder.with(qiContext)
                    .withText(greetText)
                    .build()
                    .run()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in onRobotFocusGained: ${e.message}")
                // Update UI on the main thread
                runOnUiThread {
                    addMessage(true, "Pepper: Sorry, I couldn't greet you.")
                }
            }
        }


        while (true){
            if (activation){
                val say = SayBuilder.with(qiContext)
                    .withText(responseTextView)
                    .build()
                say.run()
                activation = false
            }
        }
    }

    override fun onRobotFocusLost() {
        // Clear the QiContext reference
        qiContext = null

        // Optionally, provide feedback to the user
        runOnUiThread {
            addMessage(true, "Pepper: I've lost focus. Please wait...")
        }
    }
    override fun onRobotFocusRefused(reason: String?) {
        qiContext = null  // Clear QiContext when focus is lost

    }

}