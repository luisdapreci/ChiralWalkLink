# CHIRAL WALK LINK (Death Stranding 2 Walk Controller)

This project bridges the gap between reality and the game by connecting your Android phone's physical hardware pedometer to your PC. It seamlessly turns physical real-life steps into "forward" movement ('W' key) inside **Death Stranding 2: On The Beach**. 

By tracking your movement in the background and firing UDP packets across your local network to a Python receiver natively, you can physically walk around your room to move the character while still seamlessly utilizing your standard game controller to steer, balance, and interact.

## Features
- **Chiral Network Aesthetic**: Fully themed authentic "Drawbridge" terminal UI that matches Kojima Productions' sleek, dark utilitarian interfaces.
- **Zero-Latency Protocol**: Operates via lightning-fast UDP packets, ensuring negligible latency between your foot hitting the floor and your character stepping forward.
- **Background Processing**: Employs an aggressive WakeLock and Foreground Notification Service. You can lock your phone screen, slip it in your pocket, and keep walking for hours without the OS killing the app.
- **Q-Pid Link Validation**: The app performs a verified ping-pong handshake sequence with the PC server before triggering the sensors, completely removing the possibility of silent connection failures.

---

## 1. Running the PC Receiver
The dedicated PC Python script hosts a local UDP server and processes step data into keystrokes via `pydirectinput`, ensuring the inputs are recognized by low-level game engines.

### Setup
1. Open a terminal (Command Prompt or PowerShell) and navigate inside the server directory:
   ```cmd
   cd pc_server
   ```
2. Make sure you have the required dependency installed (ideally in a Python virtual environment):
   ```cmd
   pip install -r requirements.txt
   ```
3. Boot the server terminal:
   ```cmd
   python server.py
   ```
4. The server will launch the Drawbridge initialize sequence and proudly output its **Required Terminal IP** address.

*Note: You may be prompted by Windows Defender Firewall to allow Python to communicate on private networks. Allow it, or the UDP packets will get blocked!*

---

## 2. Building the Android Application
This is a standard Gradle-based Android project. 

If you are using Android Studio, just open the root directory and deploy directly to your device via USB debugging.

If you are compiling via the terminal, ensure you are utilizing Java 17+ and execute the Gradle Wrapper:
```powershell
./gradlew assembleDebug
```
Once completed, locate the APK at `app/build/outputs/apk/debug/app-debug.apk`, transfer it to your phone, and install.

---

## 3. How to Connect
1. Start the Python Server on your gaming PC.
2. Open the **CHIRAL WALK LINK** app on your Android device. 
   *(You must grant Activity Recognition / notification permissions on first boot).*
3. Type the exact IP Address presented by the Python Server into your app.
4. Press **ACTIVATE LINK**.
   - The app will flash `ESTABLISHING LINK...` and attempt to ping the server.
   - If successful, the server logs the handshake, and the app officially locks into `CHIRAL NETWORK: LINK ESTABLISHED`.
5. Adjust the sensitivity slider if needed, secure the phone in your pocket, and keep on keeping on!
