#!/bin/bash
# OpenShark Mobile — Complete Setup Script
# This script sets up everything: OpenShark server + Local LLM + Android app

set -e

echo "🦈 OpenShark Mobile — Complete Setup"
echo "====================================="
echo ""
echo "This script will:"
echo "  1. Install Ollama (local LLM runtime)"
echo "  2. Download Gemma 4 (2B params)"
echo "  3. Configure OpenShark for local + cloud"
echo "  4. Start the server"
echo ""
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check Termux
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo "⚠️  Warning: Not running in Termux"
    echo "This setup is designed for Android/Termux"
    echo "Continuing anyway..."
    echo ""
fi

echo -e "${BLUE}Phase 1/4: Installing dependencies...${NC}"
pkg update -y
pkg install -y ollama curl git jq termux-api

echo -e "${BLUE}Phase 2/4: Setting up Ollama...${NC}"

# Create startup script
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/ollama-start.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/sh
if ! pgrep -x ollama > /dev/null; then
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:11434
    ollama serve > ~/.ollama/server.log 2>&1 &
fi
EOF
chmod +x ~/.termux/boot/ollama-start.sh

# Start Ollama
if ! pgrep -x ollama > /dev/null; then
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:11434
    ollama serve > ~/.ollama/server.log 2>&1 &
    
    echo -n "Waiting for Ollama"
    for i in {1..30}; do
        if curl -s http://127.0.0.1:11434/api/tags > /dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
            break
        fi
        sleep 1
        echo -n "."
    done
fi

echo -e "${BLUE}Phase 3/4: Downloading Gemma 4...${NC}"
echo -e "${YELLOW}This will download ~7GB. Ensure you have space and WiFi.${NC}"
echo ""
read -p "Download Gemma 4 E2B (2B params)? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ollama pull gemma4:e2b
    echo -e "${GREEN}✓ Gemma 4 E2B ready${NC}"
fi

echo ""
read -p "Download Gemma 4 E4B (4B params, ~13GB)? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ollama pull gemma4:e4b
    echo -e "${GREEN}✓ Gemma 4 E4B ready${NC}"
fi

echo -e "${BLUE}Phase 4/4: Configuring OpenShark...${NC}"

# Create config
mkdir -p ~/.config/openshark
cat > ~/.config/openshark/config.toml << 'EOF'
version = "1.1.0"
default_model = "gemma4:e2b"
auto_route = false
cost_limit_usd = 0.0

[agent]
name = "openshark-mobile"
display_name = "OpenShark"
role = "coding assistant"
origin = "Termux on Android"
purpose = "Ship code fast"
tagline = "Let's build the future."
tone = "Professional but friendly"
style = "Concise and thorough"
greeting = "Hey! Ready to code?"
farewell = "See you next session!"
emoji = "🦈"
catchphrases = ["Let's do this!", "Ship it!"]
behavioral_rules = [
    "Always verify before claiming success",
    "Show the code, don't just describe it",
]

[memory]
db_path = "~/.config/openshark/memory.db"

# Local Ollama Provider (Primary - Offline)
[providers.ollama]
base_url = "http://127.0.0.1:11434/v1"
api_key = "ollama"
kind = "openai-compatible"

[[providers.ollama.models]]
name = "gemma4:e2b"
context_length = 8192
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

[[providers.ollama.models]]
name = "gemma4:e4b"
context_length = 8192
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

# Cloud providers (uncomment and add API key to use)
# [providers.openai]
# base_url = "https://api.openai.com/v1"
# api_key = "${OPENAI_API_KEY}"
# 
# [[providers.openai.models]]
# name = "gpt-4o"
# context_length = 128000
# cost_per_1k_input = 0.005
# cost_per_1k_output = 0.015
# capabilities = ["code", "chat", "analysis"]
EOF

echo -e "${GREEN}✓ Config created${NC}"

# Create shortcuts
cat >> ~/.bashrc << 'EOF'

# OpenShark Mobile
alias os-start='ollama serve > ~/.ollama/server.log 2>&1 &; sleep 2; openshark server --port 9876'
alias os-server='openshark server --port 9876'
alias os-status='curl -s http://127.0.0.1:9876/v1/health | jq .'
alias os-models='curl -s http://127.0.0.1:9876/v1/models | jq .'
alias ollama-logs='tail -f ~/.ollama/server.log'
EOF

echo ""
echo -e "${GREEN}=====================================${NC}"
echo -e "${GREEN}Setup Complete! 🦈${NC}"
echo -e "${GREEN}=====================================${NC}"
echo ""
echo "Next steps:"
echo ""
echo "1. Start the server:"
echo "   openshark server --port 9876"
echo ""
echo "2. Install the Android APK:"
echo "   adb install openshark-mobile.apk"
echo ""
echo "3. Open the app and chat!"
echo ""
echo "Shortcuts added to ~/.bashrc:"
echo "   os-start    — Start Ollama + OpenShark"
echo "   os-server   — Start OpenShark server only"
echo "   os-status   — Check server health"
echo "   os-models   — List available models"
echo ""
echo -e "${YELLOW}Tip: Add Termux to battery whitelist so Ollama stays running.${NC}"
