# Role & Objective
You are a Senior Mobile AI Architect specializing in hyper-optimized, on-device machine learning for Android. I need your help researching the best native tech stack and models to build "Project Charlie"—a 100% offline, voice-activated AI memory assistant. 

# Target Hardware Constraints (CRITICAL)
I am building this specifically for my daily driver: a **Samsung Galaxy M15 5G**.
* **OS:** Android
* **Total RAM:** 6 GB (Assume the OS and background apps consume ~3-4 GB).
* **App RAM Budget:** 1.5 GB to 2.0 GB absolute maximum.
* **Storage:** 128 GB.
* **Rule:** Zero cloud API calls. No internet required for the core loop.

# Developer Profile
Assume I am a capable developer who has already built web and mobile apps (like productivity tools and ed-tech platforms). You do not need to explain basic programming concepts to me. Focus entirely on the native Android ecosystem, C++ integrations (JNI/NDK), and local AI model orchestration.

# The Core Feature Flow
1.  **Passive Wake Word:** Runs in the background with minimal battery drain, listening for a custom phrase like "Charlie comin".
2.  **Speech-to-Text (STT):** Records my command and transcribes it locally.
3.  **The Brain (LLM) & Memory:** Parses the text to figure out if I am telling it a fact to remember (save to a local DB) or asking it a question (query the DB and generate an answer). 
4.  **Persona:** The AI must respond in the character of a gritty "Army Cop".
5.  **Text-to-Speech (TTS):** Speaks the LLM's text response back to me using an offline voice model.

# The Research Tasks
Please provide a comprehensive research report addressing the following, keeping the 2GB RAM budget as your absolute deciding factor:

1.  **Framework Feasibility:** Given the heavy use of C++ bindings (whisper.cpp, llama.cpp), should I use React Native (Bare Workflow) or stick strictly to Native Android (Kotlin + NDK)? Give me the pros and cons regarding RAM overhead and background service capabilities.
2.  **Model Selection (The 2GB Challenge):** * What is the absolute best, most lightweight STT model? (e.g., Whisper.cpp tiny vs. Vosk).
    * What is the smartest quantized LLM under 1 billion parameters that can fit in memory *alongside* the STT and TTS engines? (e.g., Qwen2.5-0.5B, Llama-3.2-1B).
3.  **TTS & Voice Persona:** I need a deep, "Army Cop" style voice without calling an API. Can Android's native offline TTS handle this via pitch shifting, or do I need to allocate RAM for something like Piper TTS?
4.  **Thermal & Memory Orchestration:** How do I architecture the app so it doesn't crash? (e.g., Should I dynamically load/unload the LLM from RAM only *after* the STT is finished transcribing?)
5.  **Database:** What is the fastest local database (e.g., Room, SQLite, ObjectBox) for extracting and querying simple text facts ("Favorite ice cream is cotton candy") to inject into the LLM's prompt?

Please deliver your findings with clear, data-driven reasoning. Let me know what the biggest technical bottleneck will be on this specific Samsung device.