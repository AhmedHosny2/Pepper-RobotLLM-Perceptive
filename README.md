# Integrating LLM with Pepper Robot

This project is a Kotlin-based demo that integrates a large language model (LLM) with a Pepper robot. The app uses OpenAI's API to process images and text, allowing Pepper to interact dynamically with users.

---

## Overview

- **Automatic Image Analysis:**  
  When Pepper gains focus, he takes a picture using the camera on his head. The image is sent to the OpenAI API for analysis, and the response is then vocalized using his speaker.

- **Interactive Conversations:**  
  After the initial image analysis, users can tap the "Start Talking" button. Pepper will convert your speech to text (using Google's built-in service), send it to the API, and then speak the response. He also moves while interacting.

- **Stateful Conversations:**  
  Each conversation starts by creating an "assistance" thread where all text messages are stored and processed. Note that image requests are handled separately (as threads cannot manage images). The image analysis response is stored with the role of "assistance" so that Pepper remains contextually aware.

- **Image on Demand:**  
  A dedicated button lets Pepper take a new image from his head, analyze it, and update the conversation context.

---

## Setup & Requirements

1. **API Key:**  
   Add your OpenAI API key (it starts with `sk-`) to the project configuration.

2. **Pepper Robot Startup:**  
   - Press the button on Pepper's chest (located under his screen) to launch him.  
   - **Note:** Just press the button—holding it triggers another mode that takes longer to start.

3. **Network Connection:**  
   - Connect Pepper's Wi-Fi to your hotspot (or use your own secure network).  
   - Connect your laptop to the same network.

4. **ADB Connection:**  
   - Enable ADB on Pepper by pulling down the notification panel on Pepper to find his IP address.
   - Open a terminal and run (replace `<PEPPER_IP>` with Pepper’s IP; typically `172.20.1.0.2`):
     ```bash
     adb connect <PEPPER_IP>:5555
     ```

5. **Android Studio:**  
   - Use **Android Studio Bumblebee | 2021.1.1 Patch 1** (the most stable version for this project).
   - Ensure Pepper appears in your list of connected devices.
   - If Pepper isn’t detected, try:
     ```bash
     adb kill-server
     adb start-server
     adb connect <PEPPER_IP>:5555
     ```

---

## Running the Project

1. **Configure the API Key:**  
   Insert your OpenAI API key into the appropriate configuration file in the project.

2. **Launch Pepper:**  
   Press the chest button (do not hold) to start Pepper in the correct mode.

3. **Establish ADB Connection:**  
   Connect Pepper to your network and run the ADB command as described above.

4. **Open and Run in Android Studio:**  
   - Open the project in Android Studio.
   - Confirm that Pepper is recognized as a running device.
   - Run the project on Pepper.

---

## How It Works

1. **Initial Image Capture & Analysis:**  
   - Upon gaining focus, Pepper uses his head camera to take a picture.
   - The image is sent to the OpenAI API.
   - The response is vocalized via Pepper’s speaker.

2. **Interactive Text Conversations:**  
   - The user taps the "Start Talking" button.
   - Speech is converted to text using Google’s built-in speech recognition.
   - The text message is sent to the API.
   - The response is added to a stateful conversation thread and spoken by Pepper while he moves.

3. **Image Requests:**  
   - A separate image analysis is available on-demand.
   - This response is stored with the role "assistance" to maintain context without interfering with the main conversation thread.

---

## Demo Video & Screenshots

- **Demo Video:**  
  *(A demo video of Pepper in action will be added soon.)*

- **App Screenshot on Pepper:**  
  ![Pepper App Screenshot](link_to_app_image)

---

## Conclusion

This demo shows how to integrate LLM capabilities with the Pepper robot, creating a stateful, interactive experience that combines image analysis and conversational AI. Enjoy exploring and feel free to modify the project to suit your needs!
