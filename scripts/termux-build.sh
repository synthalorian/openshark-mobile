#!/bin/bash
# OpenShark Mobile — Termux Build Script
# Builds OpenShark for Android/Termux and sets up the HTTP server

set -e

echo "🦈 OpenShark Mobile — Termux Builder"
echo "===================================="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in Termux
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo -e "${YELLOW}Warning: Not running in Termux. This script is designed for Termux.${NC}"
    echo "Continuing anyway..."
fi

# Dependencies check
echo -e "\n${GREEN}Checking dependencies...${NC}"

MISSING_DEPS=()

if ! command -v rustc &> /dev/null; then
    MISSING_DEPS+=("rust")
fi

if ! command -v cargo &> /dev/null; then
    MISSING_DEPS+=("cargo")
fi

if ! command -v sqlite3 &> /dev/null; then
    MISSING_DEPS+=("sqlite")
fi

if ! command -v pkg-config &> /dev/null; then
    MISSING_DEPS+=("pkg-config")
fi

if [ ${#MISSING_DEPS[@]} -ne 0 ]; then
    echo -e "${YELLOW}Missing dependencies: ${MISSING_DEPS[*]}${NC}"
    echo "Installing..."
    pkg install -y "${MISSING_DEPS[@]}"
fi

echo -e "${GREEN}All dependencies satisfied.${NC}"

# Find OpenShark source
OPENSOURCE_DIR=""

if [ -d "$HOME/openshark" ]; then
    OPENSOURCE_DIR="$HOME/openshark"
elif [ -d "$HOME/git/openshark" ]; then
    OPENSOURCE_DIR="$HOME/git/openshark"
elif [ -d "/sdcard/Download/openshark" ]; then
    OPENSOURCE_DIR="/sdcard/Download/openshark"
else
    echo -e "${YELLOW}OpenShark source not found. Cloning from GitHub...${NC}"
    git clone https://github.com/synthalorian/openshark.git "$HOME/openshark"
    OPENSOURCE_DIR="$HOME/openshark"
fi

echo -e "${GREEN}Using OpenShark at: $OPENSOURCE_DIR${NC}"

# Copy our HTTP gateway into the source
echo -e "\n${GREEN}Integrating HTTP gateway...${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/src/gateway/http.rs" ]; then
    cp "$SCRIPT_DIR/src/gateway/http.rs" "$OPENSOURCE_DIR/src/gateway/http.rs"
    echo "✓ Copied http.rs"
fi

# Update gateway/mod.rs to include http module
MOD_FILE="$OPENSOURCE_DIR/src/gateway/mod.rs"
if [ -f "$MOD_FILE" ] && ! grep -q "pub mod http;" "$MOD_FILE"; then
    echo "pub mod http;" >> "$MOD_FILE"
    echo "✓ Added http module to gateway/mod.rs"
fi

# Update main.rs to add 'server' subcommand
MAIN_FILE="$OPENSOURCE_DIR/src/main.rs"
if [ -f "$MAIN_FILE" ]; then
    # Check if server command already exists
    if ! grep -q "server" "$MAIN_FILE" | grep -q "subcommand"; then
        echo -e "${YELLOW}Note: You may need to manually add the 'server' subcommand to main.rs${NC}"
        echo "Add this to your CLI matches:"
        echo '        Some(("server", sub_matches)) => {'
        echo '            let port = sub_matches.get_one::<u16>("port").copied().unwrap_or(9876);'
        echo '            openshark::gateway::http::start_server(config, port).await?;'
        echo '        }'
    fi
fi

# Update Cargo.toml for Axum
CARGO_FILE="$OPENSOURCE_DIR/Cargo.toml"
if [ -f "$CARGO_FILE" ]; then
    # Check if axum is already a dependency
    if ! grep -q "^axum" "$CARGO_FILE"; then
        echo -e "${YELLOW}Note: Add these to your Cargo.toml dependencies:${NC}"
        echo '[dependencies]'
        echo 'axum = "0.7"'
        echo 'tower = "0.5"'
        echo 'async-stream = "0.3"'
        echo 'futures = "0.3"'
    fi
fi

# Build
echo -e "\n${GREEN}Building OpenShark for Termux...${NC}"
cd "$OPENSOURCE_DIR"

# Set build config for Termux
export CARGO_BUILD_TARGET="aarch64-linux-android"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,/data/data/com.termux/files/usr/lib"

cargo build --release 2>&1 | tee /tmp/openshark-build.log

if [ $? -eq 0 ]; then
    echo -e "${GREEN}Build successful!${NC}"
    
    # Create symlink in PATH
    mkdir -p "$HOME/.local/bin"
    ln -sf "$OPENSOURCE_DIR/target/release/openshark" "$HOME/.local/bin/openshark"
    
    echo -e "${GREEN}OpenShark installed to: $HOME/.local/bin/openshark${NC}"
else
    echo -e "${RED}Build failed. Check /tmp/openshark-build.log${NC}"
    exit 1
fi

# Create config directory
mkdir -p "$HOME/.config/openshark"

# Create default config if not exists
if [ ! -f "$HOME/.config/openshark/config.toml" ]; then
    cat > "$HOME/.config/openshark/config.toml" << 'EOF'
version = "1.1.0"
default_model = "gpt-4o"
auto_route = true
cost_limit_usd = 10.0

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

[providers.openai]
base_url = "https://api.openai.com/v1"
api_key = "${OPENAI_API_KEY}"

[[providers.openai.models]]
name = "gpt-4o"
context_length = 128000
cost_per_1k_input = 0.005
cost_per_1k_output = 0.015
capabilities = ["code", "chat", "analysis"]
EOF
    echo -e "${GREEN}Created default config at ~/.config/openshark/config.toml${NC}"
fi

# Create start script
cat > "$HOME/.local/bin/openshark-server" << 'EOF'
#!/bin/bash
# OpenShark HTTP Server wrapper

PORT="${1:-9876}"
CONFIG="${HOME}/.config/openshark/config.toml"

echo "🦈 Starting OpenShark HTTP server on port $PORT..."
export RUST_LOG=info
exec openshark server --port "$PORT" --config "$CONFIG"
EOF
chmod +x "$HOME/.local/bin/openshark-server"

echo -e "${GREEN}Created start script: openshark-server${NC}"

# Setup termux-services if available
if [ -d "$PREFIX/var/service" ]; then
    echo -e "\n${GREEN}Setting up Termux service...${NC}"
    mkdir -p "$PREFIX/var/service/openshark"
    cat > "$PREFIX/var/service/openshark/run" << EOF
#!/bin/bash
exec openshark server --port 9876
EOF
    chmod +x "$PREFIX/var/service/openshark/run"
    echo "✓ Service configured. Start with: sv up openshark"
fi

echo -e "\n${GREEN}====================================${NC}"
echo -e "${GREEN}OpenShark Mobile setup complete!${NC}"
echo -e "${GREEN}====================================${NC}"
echo ""
echo "Usage:"
echo "  openshark-server        # Start HTTP server on port 9876"
echo "  openshark-server 8080   # Start on custom port"
echo ""
echo "Then open the OpenShark Android app and connect to:"
echo "  http://127.0.0.1:9876"
echo ""
echo "Add to ~/.bashrc for auto-start:"
echo '  openshark-server &'
