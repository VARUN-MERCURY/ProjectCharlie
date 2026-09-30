#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════
# Project Charlie — Setup Script
# ═══════════════════════════════════════════════════════════
# This script:
#   1. Clones whisper.cpp and llama.cpp to third_party/
#   2. Downloads AI models (~425 MB total)
#   3. Prints instructions for building and deploying
#
# Prerequisites:
#   - git, curl
#   - Android Studio with NDK installed
#   - ~2 GB free disk space
# ═══════════════════════════════════════════════════════════

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIRD_PARTY="$SCRIPT_DIR/third_party"
MODELS_DIR="$SCRIPT_DIR/models"

# Colors
G='\033[0;32m'   # Green
Y='\033[1;33m'   # Yellow
R='\033[0;31m'   # Red
C='\033[0;36m'   # Cyan
B='\033[1m'      # Bold
N='\033[0m'      # Reset

echo ""
echo -e "${G}╔══════════════════════════════════════════════╗${N}"
echo -e "${G}║       PROJECT CHARLIE — SETUP SCRIPT         ║${N}"
echo -e "${G}║   Offline AI Memory Assistant for Android     ║${N}"
echo -e "${G}╚══════════════════════════════════════════════╝${N}"
echo ""

# ─────────────────────────────────────────────────────
# Step 1: Clone C++ Dependencies
# ─────────────────────────────────────────────────────
echo -e "${Y}[1/3]${N} ${B}Cloning C++ dependencies...${N}"
mkdir -p "$THIRD_PARTY"

# llama.cpp (LLM inference engine + ggml)
if [ ! -d "$THIRD_PARTY/llama.cpp" ]; then
    echo -e "  ${C}→${N} Cloning llama.cpp (this may take a minute)..."
    git clone --depth 1 \
        https://github.com/ggml-org/llama.cpp.git \
        "$THIRD_PARTY/llama.cpp"
    echo -e "  ${G}✓${N} llama.cpp cloned"
else
    echo -e "  ${G}✓${N} llama.cpp already exists, skipping"
fi

# whisper.cpp (STT engine)
if [ ! -d "$THIRD_PARTY/whisper.cpp" ]; then
    echo -e "  ${C}→${N} Cloning whisper.cpp..."
    git clone --depth 1 \
        https://github.com/ggml-org/whisper.cpp.git \
        "$THIRD_PARTY/whisper.cpp"
    echo -e "  ${G}✓${N} whisper.cpp cloned"
else
    echo -e "  ${G}✓${N} whisper.cpp already exists, skipping"
fi

echo ""

# ─────────────────────────────────────────────────────
# Step 2: Download AI Models
# ─────────────────────────────────────────────────────
echo -e "${Y}[2/3]${N} ${B}Downloading AI models...${N}"
mkdir -p "$MODELS_DIR"

# Whisper tiny.en — STT model (~75 MB)
WHISPER_MODEL="$MODELS_DIR/ggml-tiny.en.bin"
if [ ! -f "$WHISPER_MODEL" ]; then
    echo -e "  ${C}→${N} Downloading Whisper tiny.en (~75 MB)..."
    curl -L --progress-bar -o "$WHISPER_MODEL" \
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
    echo -e "  ${G}✓${N} Whisper model downloaded"
else
    echo -e "  ${G}✓${N} Whisper model already exists"
fi

# Qwen2.5-0.5B-Instruct Q4_K_M — LLM brain (~350 MB)
LLM_MODEL="$MODELS_DIR/qwen2.5-0.5b-instruct-q4_k_m.gguf"
if [ ! -f "$LLM_MODEL" ]; then
    echo -e "  ${C}→${N} Downloading Qwen2.5-0.5B-Instruct Q4_K_M (~350 MB)..."
    curl -L --progress-bar -o "$LLM_MODEL" \
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    echo -e "  ${G}✓${N} LLM model downloaded"
else
    echo -e "  ${G}✓${N} LLM model already exists"
fi

echo ""

# ─────────────────────────────────────────────────────
# Step 3: Print Next Steps
# ─────────────────────────────────────────────────────
echo -e "${Y}[3/3]${N} ${B}Setup complete! Next steps:${N}"
echo ""
echo -e "  ${B}1. Generate Gradle wrapper${N} (if not present):"
echo -e "     ${C}cd $SCRIPT_DIR${N}"
echo -e "     ${C}gradle wrapper --gradle-version 8.5${N}"
echo ""
echo -e "  ${B}2. Open in Android Studio${N}:"
echo -e "     Open the folder: ${C}$SCRIPT_DIR${N}"
echo -e "     Make sure NDK is installed: SDK Manager → SDK Tools → NDK (Side by side)"
echo ""
echo -e "  ${B}3. Build the app${N}:"
echo -e "     Click ${C}▶ Run${N} in Android Studio (first build takes ~10 min for native code)"
echo ""
echo -e "  ${B}4. Push models to your phone${N} (after installing the app):"
echo ""
echo -e "     ${C}# Connect phone via USB with debugging enabled, then:${N}"
echo -e "     ${C}adb shell mkdir -p /data/data/com.alpha.charlie/files/models/${N}"
echo -e "     ${C}adb push $MODELS_DIR/ggml-tiny.en.bin /data/data/com.alpha.charlie/files/models/${N}"
echo -e "     ${C}adb push $MODELS_DIR/qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.alpha.charlie/files/models/${N}"
echo ""
echo -e "  ${B}5. Launch the app${N} and tap the mic button!"
echo ""
echo -e "${G}═══════════════════════════════════════════════${N}"
echo -e "${G}  All systems go, soldier. Build and deploy.   ${N}"
echo -e "${G}═══════════════════════════════════════════════${N}"
echo ""
