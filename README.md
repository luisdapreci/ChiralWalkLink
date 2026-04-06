# CHIRAL WALK LINK
### *Death Stranding 2: On The Beach — Physical Walk Controller*

> Bridge the gap between reality and the game. Walk in real life. Move in the game.

**Chiral Walk Link** is a two-part system (Android app + Python PC server) that turns your phone's accelerometer into a physical walk controller for **Death Stranding 2: On The Beach**. Every real step you take fires a UDP packet across your local network to a Python receiver on your PC, which then holds down the `W` key for as long as you keep walking. Use your game controller normally for everything else — steering, balancing, interactions — while your legs handle the actual movement.

---

## How It Works

```
[Android Phone]                        [Gaming PC]
  Accelerometer                          server.py
  ─────────────                          ──────────
  Detects step  ──UDP {"event":"step"}─► Holds 'W' key
  Stops moving  ──UDP {"event":"stop"}─► Releases 'W' key
  Connects      ──UDP {"event":"ping"}─► Returns pong to validate link
```

1. The **Android app** registers an accelerometer listener as a background Foreground Service.
2. It uses a peak-detection algorithm (magnitude vs. configurable threshold + 300 ms cooldown) to detect footsteps reliably without false positives.
3. Each detected step fires a `{"event": "step"}` JSON UDP packet to port **5005** on your PC.
4. 400 ms after the last step, it fires `{"event": "stop"}` to smoothly release the key.
5. The **Python server** listens on UDP `0.0.0.0:5005`, parses the JSON packets, and controls the `W` key via `pydirectinput` — which sends low-level DirectInput keystrokes recognized by game engines.
6. A dedicated key-manager thread holds `W` continuously if steps arrive faster than `0.55s`, creating smooth walking rather than individual key presses.

---

## Features

| Feature | Details |
|---|---|
| 🔗 **Q-Pid Handshake** | App sends a `ping` and waits for `pong` before starting sensors. Silent connection failures are impossible. |
| ⚡ **Zero-Latency UDP** | No TCP overhead. Packets go straight from your pocket to the game. |
| 🔒 **Background WakeLock** | `PARTIAL_WAKE_LOCK` + Foreground Service keeps the app running with the screen off. |
| 🎚️ **Adjustable Sensitivity** | Slider sets the accelerometer sensitivity threshold from `0.5` to `10.5`. Tune it to your walking style. |
| 💾 **IP Persistence** | Last-used PC IP is saved to SharedPreferences and auto-filled on next launch. |
| 📋 **Crash Reporter** | Uncaught exceptions are written to SharedPreferences and displayed as a dialog on the next app launch with a copy-to-clipboard option. |
| 🖥️ **Chiral Network UI** | Dark `#0a121d` terminal aesthetic with cyan `#00E5FF` accents matching Kojima Productions' style. |
| 🖱️ **start_server.bat** | One-click batch file that auto-detects `.venv` or `venv` and launches the server without manual activation. |

---

## Project Structure

```
ChiralWalkLink/
├── app/                                # Android application (Kotlin)
│   └── src/main/
│       ├── AndroidManifest.xml         # Permissions & service declarations
│       ├── java/com/example/ds2stepcounter/
│       │   ├── MainActivity.kt         # UI, permission requests, service control
│       │   └── StepTrackerService.kt   # Foreground service: sensor + UDP logic
│       └── res/layout/
│           └── activity_main.xml       # Drawbridge Terminal UI layout
├── pc_server/
│   ├── server.py                       # Python UDP receiver → pydirectinput
│   └── requirements.txt                # pydirectinput==1.0.4
├── start_server.bat                    # One-click server launcher (venv-aware)
├── build.gradle.kts                    # Root Gradle build file
├── settings.gradle.kts                 # Gradle project settings
└── gradlew / gradlew.bat               # Gradle wrapper
```

---

## Prerequisites

### PC
- **Python 3.8+**
- **Windows** (required — `pydirectinput` is Windows-only)
- Both PC and Android device on the **same local Wi-Fi network**

### Android
- Android **7.0+ (API 24)** minimum
- Android **8.0+ (API 26)** recommended (required for `startForegroundService`)
- Android **10+ (API 29)** for `ACTIVITY_RECOGNITION` runtime permission
- **USB Debugging** enabled (for sideloading the APK)
- **Java 17+** on the build machine (if compiling from terminal)

---

## Setup & Usage

### Step 1 — Set Up the PC Server

**Option A: One-click (recommended)**

Double-click `start_server.bat`. It will auto-activate `.venv` or `venv` if present, then start the server.

**Option B: Manual**

```powershell
cd pc_server

# Create and activate a virtual environment (recommended)
python -m venv ../.venv
../.venv/Scripts/activate

# Install dependency
pip install -r requirements.txt

# Run the server
python server.py
```

The terminal will print the **DRAWBRIDGE TERMINAL** banner and display your required IP address:

```
   [ DRAWBRIDGE TERMINAL INITIALIZING ]
----------------------------------------------------
    CHIRAL NETWORK NODE - WALK LINK RECEIVER
----------------------------------------------------
 > EXECUTING Q-PID CONNECTION PROTOCOL...
 > REQUIRED TERMINAL IP  : 192.168.1.XX
 > DESIGNATED PORT       : 5005
----------------------------------------------------
 [ AWAITING CHIRAL NETWORK HANDSHAKE... ]
```

> **Firewall Note:** Windows Defender may prompt you to allow Python through the firewall. Click **Allow access** — otherwise UDP packets will be blocked and the handshake will time out.

---

### Step 2 — Build & Install the Android App

**Option A: Android Studio**

Open the project root in Android Studio, connect your phone via USB with debugging enabled, and click **Run ▶**.

**Option B: Command Line**

```powershell
# From the project root
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

Transfer this file to your Android device and install it (you may need to enable **Install from unknown sources** in settings).

---

### Step 3 — Connect & Walk

1. **Start the Python server** on your PC (Step 1 above).
2. **Open the CHIRAL WALK LINK app** on your Android device.
3. **Grant permissions** on first launch:
   - *Activity Recognition* — required for step detection
   - *Post Notifications* — required for the foreground service notification
4. **Enter the IP address** shown in the server terminal (e.g. `192.168.1.25`).
5. **Adjust the Sensitivity Threshold** slider if needed. Lower = more sensitive (detects smaller movements); higher = requires more forceful steps.
6. Tap **ACTIVATE LINK**.
   - The app flashes `CHIRAL NETWORK: CONNECTING...` while the ping-pong handshake runs.
   - On success: `CHIRAL NETWORK: LINK ESTABLISHED` ✅
   - On failure: Toast error with connection details.
7. **Lock your screen, slip the phone in your pocket, and walk.**

To stop, tap **DEACTIVATE LINK** (or kill the app — the service sends a `stop` event and releases `W` cleanly).

---

## Configuration Reference

### Sensitivity Threshold (Android App)

Controlled by the **SENSOR SETTINGS** slider. The default is `3.0`.

The service subtracts Earth's gravity from the raw accelerometer magnitude and compares it against the threshold. A step is registered when:
- The magnitude exceeds the threshold
- At least **300 ms** have passed since the last step

| Slider Position | Threshold | Recommended For |
|---|---|---|
| Low (0–20) | 0.5 – 2.5 | Light walkers, carpeted floors |
| Mid (20–40) | 2.5 – 4.5 | Normal walking, most surfaces |
| High (40+) | 4.5+ | Running, hard tile, reduce false positives |

### Hold Duration (PC Server)

Defined in `server.py` as `HOLD_DURATION = 0.55` seconds. If a new step packet arrives within 550 ms of the previous one, the `W` key stays held continuously (no stutter). Tune this if the character stops walking between steps.

### UDP Port

Fixed at `5005` on both client and server. Ensure this port is open on the PC firewall.

---

## Android Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | UDP socket communication |
| `ACTIVITY_RECOGNITION` | Step/motion sensor access on Android 10+ |
| `WAKE_LOCK` | Keep CPU active while screen is off |
| `FOREGROUND_SERVICE` | Run the tracking service in the background |
| `FOREGROUND_SERVICE_DATA_SYNC` | Required foreground service type for Android 14+ |
| `POST_NOTIFICATIONS` | Show the persistent foreground service notification |
| `HIGH_SAMPLING_RATE_SENSORS` | Access accelerometer at `SENSOR_DELAY_GAME` rate |

---

## Troubleshooting

**"Chiral Network Link offline. Check IP."**
- Verify the PC and phone are on the same Wi-Fi network.
- Confirm the IP shown in the server terminal matches what you entered.
- Check Windows Defender Firewall — ensure Python is allowed on private networks, port 5005.
- Make sure `server.py` is actually running before tapping Activate Link.

**Character stops walking between steps**
- Increase `HOLD_DURATION` in `server.py` (e.g. `0.7` or `0.8`).

**Too many false positives / character walks without stepping**
- Raise the Sensitivity Threshold slider in the app.

**App crashes on start**
- A crash log dialog will appear on the next launch. Tap **Copy** to capture the stack trace for debugging.

**"No virtual environment found" warning from start_server.bat**
- Create a `.venv` in the project root: `python -m venv .venv`
- Or run `pip install -r requirements.txt` globally.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Android App | Kotlin, AndroidX AppCompat, Material 3 |
| Sensor | `TYPE_ACCELEROMETER` via `SensorManager` at `SENSOR_DELAY_GAME` |
| Background Service | Android `ForegroundService` + `PARTIAL_WAKE_LOCK` |
| Communication | UDP Sockets (`DatagramSocket` / `DatagramPacket`) |
| PC Server | Python 3, `socket`, `threading`, `pydirectinput` |
| Key Injection | `pydirectinput 1.0.4` (DirectInput, Windows only) |
| Build System | Gradle 8.5, Kotlin DSL |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 34 (Android 14) |
