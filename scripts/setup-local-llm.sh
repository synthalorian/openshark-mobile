#!/bin/bash
# OpenShark Mobile + Local LLM Setup for Termux
# Sets up Ollama with Gemma 4 and configures OpenShark to use it

set -e

echo "🦈 OpenShark Mobile + Local LLM Setup"
echo "======================================"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
OLLAMA_PORT=11434
OPENSHARK_PORT=9876
DEFAULT_MODEL="gemma4:e2b"

# Check Termux
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo -e "${RED}Error: This script must run in Termux${NC}"
    echo "Install Termux from F-Droid: https://f-droid.org/packages/com.termux/"
    exit 1
fi

echo -e "${BLUE}Step 1/6: Updating packages...${NC}"
pkg update -y && pkg upgrade -y

echo -e "${BLUE}Step 2/6: Installing dependencies...${NC}"
pkg install -y ollama curl git jq

# Start Ollama if not running
if ! pgrep -x ollama > /dev/null; then
    echo -e "${BLUE}Step 3/6: Starting Ollama server...${NC}"
    
    # Create Ollama startup script
    mkdir -p ~/.termux/boot
    cat > ~/.termux/boot/ollama-boot.sh << EOF
#!/data/data/com.termux/files/usr/bin/sh
if pgrep -x ollama > /dev/null; then
    echo "Ollama already running"
else
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:${OLLAMA_PORT}
    ollama serve > ~/.ollama/server.log 2>&1 &
fi
EOF
    chmod +x ~/.termux/boot/ollama-boot.sh
    
    # Start now
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:${OLLAMA_PORT}
    ollama serve > ~/.ollama/server.log 2>&1 &
    
    # Wait for Ollama to be ready
    echo -n "Waiting for Ollama to start"
    for i in {1..30}; do
        if curl -s http://127.0.0.1:${OLLAMA_PORT}/api/tags > /dev/null 2>&1; then
            echo -e " ${GREEN}✓ Ready${NC}"
            break
        fi
        sleep 1
        echo -n "."
    done
else
    echo -e "${GREEN}Ollama already running ✓${NC}"
fi

# Pull Gemma 4 model
echo -e "${BLUE}Step 4/6: Downloading Gemma 4 (E2B - 2B parameters)...${NC}"
echo -e "${YELLOW}This may take 10-30 minutes depending on your connection.${NC}"
ollama pull ${DEFAULT_MODEL}

echo -e "${GREEN}✓ Model downloaded${NC}"

# Test the model
echo -e "${BLUE}Step 5/6: Testing local LLM...${NC}"
curl -s http://127.0.0.1:${OLLAMA_PORT}/api/generate -d '{
    "model": "'${DEFAULT_MODEL}'",
    "prompt": "Say 'OpenShark local LLM is ready'",
    "stream": false
}' | jq -r '.response' 2>/dev/null || echo "Test completed"

# Configure OpenShark for local LLM
echo -e "${BLUE}Step 6/6: Configuring OpenShark...${NC}"

mkdir -p ~/.config/openshark

# Create config with local Ollama provider
cat > ~/.config/openshark/config.toml << EOF
version = "1.1.0"
default_model = "${DEFAULT_MODEL}"
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

# Local Ollama Provider (Gemma 4)
[providers.ollama]
base_url = "http://127.0.0.1:${OLLAMA_PORT}/v1"
api_key = "ollama"
kind = "openai-compatible"

[[providers.ollama.models]]
name = "${DEFAULT_MODEL}"
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

# Add other Ollama models as needed
# [[providers.ollama.models]]
# name = "qwen3.5:0.8b"
# context_length = 32768
# cost_per_1k_input = 0.0
# cost_per_1k_output = 0.0
# capabilities = ["code", "chat"]

# Cloud providers (optional - for when you have internet)
# [providers.openai]
# base_url = "https://api.openai.com/v1"
# api_key = "\${OPENAI_API_KEY}"
# 
# [[providers.openai.models]]
# name = "gpt-4o"
# context_length = 128000
# cost_per_1k_input = 0.005
# cost_per_1k_output = 0.015
# capabilities = ["code", "chat", "analysis"]
EOF

echo -e "${GREEN}✓ OpenShark config created${NC}"

# Create convenient aliases
cat >> ~/.bashrc << 'EOF'

# OpenShark Mobile Aliases
alias os-serve='openshark server --port 9876'
alias os-chat='openshark chat'
alias os-status='curl -s http://127.0.0.1:9876/v1/health | jq'
alias ollama-log='tail -f ~/.ollama/server.log'
EOF

echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN}Setup Complete! 🦈${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo "Local LLM: Gemma 4 E2B (2B parameters)"
echo "Ollama API: http://127.0.0.1:${OLLAMA_PORT}"
echo "OpenShark API: http://127.0.0.1:${OPENSHARK_PORT}"
echo ""
echo "Quick Start:"
echo "  1. Start OpenShark server: openshark server --port ${OPENSHARK_PORT}"
echo "  2. Open the Android app (install APK)"
echo "  3. Chat with your local AI - no internet needed!"
echo ""
echo "Useful commands:"
echo "  ollama list              # List downloaded models"
echo "  ollama pull gemma4:e4b   # Download larger model (4B)"
echo "  os-serve                 # Start OpenShark server"
echo "  os-status                # Check OpenShark health"
echo ""
echo -e "${YELLOW}Note: Keep Termux running in background for Ollama to stay alive.${NC}"
echo -e "${YELLOW}Add Termux to battery optimization whitelist in Android settings.${NC}"
