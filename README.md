# Project Charlie 🎖️

**100% Offline, Voice-Activated AI Memory Assistant for Android**

Built for Samsung Galaxy M15 5G or similar (6GB RAM / 2GB app budget). Zero cloud APIs. Zero internet.

---

## What It Does

1. **Tap the mic** → speak a command
2. **"Remember my sister's name is Priya"** → saves to local database
3. **"What's my sister's name?"** → queries the DB, generates an answer in character
4. **Charlie responds** in a gruff Army Cop persona via text-to-speech

All processing happens on-device:
- **STT**: whisper.cpp (tiny.en, ~75MB model)
- **LLM**: Qwen2.5-0.5B-Instruct via llama.cpp (~350MB model, Q4_K_M)
- **TTS**: Android's built-in offline speech engine
- **DB**: Room/SQLite for fact storage

### RAM Strategy
Only ONE heavy model is loaded at a time:
```
Record Audio → [Load Whisper] → Transcribe → [Unload Whisper]
→ [Load LLM] → Generate Response → [Unload LLM]
→ Speak via TTS → Idle
```
Peak RAM: ~650-800 MB — well under the 2GB budget.

---

## Download the Apk file:
- **<a href="https://github.com/vinaytz/ProjectCharlie/releases/tag/app">Click Here</a>**

  OR

## Quick Start

### Prerequisites
- **Android Studio** (Hedgehog 2023.1+ or newer)
- **Android NDK** (install via: SDK Manager → SDK Tools → NDK Side-by-side)
- **CMake** (install via: SDK Manager → SDK Tools → CMake)
- **Git** and **curl** (for setup script)
- ~2 GB free disk space for models

### 1. Clone & Setup

```bash
cd "/home/vinaytz/MY Project in Linux/AlphaAI"
chmod +x setup.sh
./setup.sh
```

This clones whisper.cpp + llama.cpp and downloads the AI models (~425 MB).

### 2. Generate Gradle Wrapper

If you don't have `gradlew` yet:
```bash
gradle wrapper --gradle-version 8.5
```

Or open the project in Android Studio — it will handle this automatically.

### 3. Build in Android Studio

1. Open this folder in Android Studio
2. Wait for Gradle sync to complete
3. **First build will take ~10-15 minutes** (compiles llama.cpp & whisper.cpp from source)
4. Subsequent builds are fast (~30 seconds)

### 4. Push Models to Device

After installing the app on your phone:

```bash
# Connect phone via USB (enable USB debugging in Developer Options)
adb shell mkdir -p /data/data/com.alpha.charlie/files/models/
adb push models/ggml-tiny.en.bin /data/data/com.alpha.charlie/files/models/
adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.alpha.charlie/files/models/
```

### 5. Run!

Launch the "Charlie" app → tap the mic → start talking.

---

## Project Structure

```
AlphaAI/
├── setup.sh                           # Clones deps + downloads models
├── build.gradle.kts                   # Root Gradle config
├── settings.gradle.kts
├── gradle.properties
│
├── third_party/                       # Created by setup.sh
│   ├── llama.cpp/                     # LLM engine + ggml
│   └── whisper.cpp/                   # STT engine
│
├── models/                            # Created by setup.sh
│   ├── ggml-tiny.en.bin               # Whisper STT model (~75 MB)
│   └── qwen2.5-0.5b-instruct-q4_k_m.gguf  # LLM model (~350 MB)
│
└── app/src/main/
    ├── AndroidManifest.xml
    ├── cpp/
    │   ├── CMakeLists.txt             # Builds whisper + llama + JNI bridge
    │   ├── charlie-whisper.cpp        # JNI: Kotlin ↔ whisper.cpp
    │   └── charlie-llama.cpp          # JNI: Kotlin ↔ llama.cpp
    │
    ├── java/com/alpha/charlie/
    │   ├── App.kt                     # Application class
    │   ├── MainActivity.kt            # Compose UI
    │   ├── brain/
    │   │   └── CharlieOrchestrator.kt # The pipeline brain
    │   ├── stt/
    │   │   └── WhisperEngine.kt       # Whisper wrapper
    │   ├── llm/
    │   │   └── LlamaEngine.kt         # Llama wrapper
    │   ├── tts/
    │   │   └── CharlieTts.kt          # Android TTS wrapper
    │   ├── audio/
    │   │   └── AudioCapture.kt        # Mic recording + silence detection
    │   ├── memory/
    │   │   ├── Fact.kt                # Room entity
    │   │   ├── FactDao.kt             # Data access
    │   │   ├── FactDatabase.kt        # Database singleton
    │   │   └── MemoryManager.kt       # Fact CRUD operations
    │   └── service/
    │       └── CharlieService.kt      # Foreground service
    │
    └── res/                           # Android resources
```

---

## Tech Stack

| Component | Technology | RAM Usage |
|-----------|-----------|-----------|
| Language | Kotlin + C++ (NDK/JNI) | — |
| STT | whisper.cpp tiny.en | ~273 MB (loaded on demand) |
| LLM | llama.cpp + Qwen2.5-0.5B Q4_K_M | ~500 MB (loaded on demand) |
| TTS | Android native TextToSpeech | ~0 MB (system process) |
| Database | Room (SQLite) | ~5 MB |
| UI | Jetpack Compose | — |
| Background | ForegroundService | — |

---

## Troubleshooting

### Build fails with "whisper.cpp source incompatible with ggml"
The project compiles whisper's source against llama.cpp's ggml. If versions drift too far apart:
```bash
# Pin both to compatible dates
cd third_party/llama.cpp && git checkout <tag>
cd third_party/whisper.cpp && git checkout <tag>
```

### "Models not found" after installation
Models must be in the app's internal storage:
```
/data/data/com.alpha.charlie/files/models/
```
Use the `adb push` commands above. On Samsung, you may need to disable "Auto Blocker" in Settings.

### App gets killed in background
Samsung's aggressive battery optimization. Go to:
Settings → Battery → Background usage limits → Add "Charlie" to "Never sleeping" list.

### Slow first inference
The first LLM inference after model load takes a few seconds longer (mmap cold pages). Subsequent queries are faster.

---

## Voice Commands

| Say this | Charlie does |
|----------|-------------|
| "Remember my favorite color is blue" | Saves: favorite color → blue |
| "My sister's name is Priya" | Saves: sister's name → Priya |
| "What's my favorite color?" | Queries DB + answers in character |
| "List everything" | Lists all stored facts |
| "Clear all memory" | Wipes the fact database |

---

## License
MIT — do whatever you want with it, soldier.  
