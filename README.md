# OpenShark Mobile 🦈📱

**Standalone AI coding harness for Android. No cloud required, no SSH tunnels, no compromises.**

Run OpenShark natively on your Android device via Termux, with a native Android chat interface. Full agent autonomy, persistent memory, tool execution — in your pocket, fully offline.

---

## What's Included

| Component | Status | Description |
|-----------|--------|-------------|
| 🦈 HTTP Gateway | ✅ Complete | Axum server with SSE streaming |
| 🤖 Android App | ✅ Complete | Kotlin + Jetpack Compose |
| 🏠 Local LLM | ✅ Ready | Ollama + Gemma 4 integration |
| ☁️ Cloud Proxy | ✅ Ready | Kimi via OpenClaw bridge |
| 📦 APK Build | ✅ Ready | Gradle build scripts |
| 🎨 Synthwave Theme | ✅ Complete | Neon purple/pink/yellow |
| 🛡️ Agent Modes | ✅ Complete | Safe vs Full Send |
| 🧠 Memory Search | ✅ Complete | Semantic + keyword |
| 🎯 Model Switching | ✅ Complete | Local ↔ Cloud |

---

## Architecture

```
┌─────────────────────────────────────────────┐
│  Android App (Kotlin/Jetpack Compose)       │
│  ┌─────────┐  ┌─────────┐  ┌─────────────┐  │
│  │ Chat UI │  │ Settings│  │ Model Picker│  │
│  └────┬────┘  └─────────┘  └─────────────┘  │
│       │ HTTP (localhost:9876)                │
│  ┌────┴─────────────────────────────────┐   │
│  │ OkHttp + SSE Streaming               │   │
│  └─────────────────────────────────────┘   │
└──────┬──────────────────────────────────────┘
       │ localhost (same device)
┌──────▼──────────────────────────────────────┐
│  Termux Environment                         │
│  ┌─────────────────────────────────────┐   │
│  │ OpenShark HTTP Gateway (Rust/Axum)  │   │
│  │ ┌───────┐ ┌───────┐ ┌───────────┐  │   │
│  │ │Router │ │Memory │ │Providers  │  │   │
│  │ └───────┘ └───────┘ └───────────┘  │   │
│  └─────────────────────────────────────┘   │
│  ┌─────────────────────────────────────┐   │
│  │ Ollama (Local LLM Runtime)          │   │
│  │ ┌─────────┐ ┌─────────┐            │   │
│  │ │Gemma 4  │ │Qwen 3.5 │ ...        │   │
│  │ │E2B/E4B  │ │0.8B/4B  │            │   │
│  │ └─────────┘ └─────────┘            │   │
│  └─────────────────────────────────────┘   │
│  ┌─────────────────────────────────────┐   │
│  │ SQLite Database (~/.openshark/)     │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

---

## Quick Start (5 minutes)

### 1. Install Termux

From F-Droid (NOT Play Store): [f-droid.org/packages/com.termux](https://f-droid.org/packages/com.termux/)

### 2. Run Complete Setup

```bash
curl -sL https://raw.githubusercontent.com/synthalorian/openshark/main/mobile/scripts/complete-setup.sh | bash
```

This will:
- ✅ Install Ollama (local LLM runtime)
- ✅ Download Gemma 4 E2B (2B parameters, ~7GB)
- ✅ Configure OpenShark with local provider
- ✅ Create convenient shortcuts

### 3. Start Server

```bash
openshark server --port 9876
```

### 4. Install Android App

Build from source or download APK:
```bash
cd openshark-mobile/android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. Chat Offline! 🎉

Open the app → Settings → verify `http://127.0.0.1:9876` → Start chatting.

**No internet required. Your data never leaves your device.**

---

## Cloud Mode — Kimi via OpenClaw

Want cloud power when you have internet? The Kimi proxy bridges OpenShark to your existing OpenClaw → Kimi connection.

### Architecture

```
OpenShark ──► Kimi Proxy (localhost:9000) ──► OpenClaw Gateway ──► Kimi API
                (reads OpenClaw config)         (your existing        (moonshot.cn)
                                                connection)
```

### Setup

```bash
# Start the proxy (reads your OpenClaw config for auth)
cd openshark-mobile/proxy
bash proxy.sh start

# Or use the alias (after running complete-setup.sh)
kimi-proxy start
```

The proxy auto-discovers your Kimi API key from OpenClaw's config and exposes an OpenAI-compatible endpoint at `http://127.0.0.1:9000/v1`.

### Available Cloud Models

| Model | Context | Use Case |
|-------|---------|----------|
| `kimi-k3` | 1M tokens | Complex reasoning, large codebases |
| `kimi-k2.5` | 256K tokens | General coding, analysis |

### Switching Models in the App

Tap the model name in the top bar → select **kimi-k3** or **kimi-k2.5** for cloud power, or **gemma4:e2b** for offline mode.

---

## API Endpoints

The HTTP gateway exposes these endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/v1/chat` | Main chat endpoint (SSE streaming) |
| GET | `/v1/models` | List available models |
| GET | `/v1/memory` | Search memory |
| POST | `/v1/memory` | Save to memory |
| GET | `/v1/status` | System status |
| POST | `/v1/tools/execute` | Execute a tool |
| GET | `/v1/health` | Health check |

---

## Configuration

Edit `~/.config/openshark/config.toml` in Termux:

```toml
version = "1.1.0"
default_model = "gemma4:e2b"

[providers.ollama]
base_url = "http://127.0.0.1:11434/v1"
api_key = "ollama"

[[providers.ollama.models]]
name = "gemma4:e2b"
context_length = 8192
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

# Add cloud provider via OpenClaw proxy:
[providers.kimi]
base_url = "http://127.0.0.1:9000/v1"
api_key = "local-proxy"

[[providers.kimi.models]]
name = "kimi-k3"
context_length = 1000000
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]
```

---

## Android App Features

### Chat Screen
- 💬 Real-time streaming responses
- 🎨 Message bubbles (user right, assistant left)
- 🔄 Model switcher in top bar
- 🛡️ Agent mode toggle (Safe/Full Send)
- 🧠 Memory search dialog
- 📤 Export chat to clipboard

### Models Screen
- 🏠 Local models (Ollama)
- ☁️ Cloud models (OpenAI, etc.)
- 📊 Context length and cost info
- ⚡ One-tap model switching

### Settings Screen
- 🔌 Server URL configuration
- 🧪 Connection test
- 🎨 Theme selection
- 📊 About page

---

## Local LLM Models

| Model | Size | Speed | Best For |
|-------|------|-------|----------|
| **Gemma 4 E2B** | ~7GB | 6-10 tok/s | General coding, chat |
| **Gemma 4 E4B** | ~13GB | 3-6 tok/s | Better code quality |
| **Qwen 3.5 0.8B** | ~1GB | 10-18 tok/s | Fast responses, simple tasks |

**Recommendation:** Start with Gemma 4 E2B. Upgrade to E4B if you need better quality and have the storage.

---

## Project Structure

```
openshark-mobile/
├── ARCHITECTURE.md          # System design doc
├── INTEGRATION.md           # How to wire into existing OpenShark
├── README.md                # This file
├── src/
│   └── gateway/
│       ├── http.rs          # Axum HTTP server
│       └── mod.rs           # Module exports
├── patches/
│   ├── 0001-add-dependencies.patch
│   ├── 0002-add-http-module.patch
│   └── 0003-add-server-command.patch
├── scripts/
│   ├── complete-setup.sh    # One-command setup
│   ├── setup-local-llm.sh   # Local LLM only
│   └── termux-build.sh      # Build OpenShark in Termux
└── android/                 # Android app
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/src/main/java/com/synthalorian/openshark/
        ├── MainActivity.kt
        ├── data/remote/
        │   ├── OpenSharkApi.kt
        │   └── SseClient.kt
        └── ui/
            ├── screens/
            │   ├── ChatScreen.kt
            │   ├── ModelsScreen.kt
            │   └── SettingsScreen.kt
            ├── theme/
            │   └── Theme.kt
            └── viewmodel/
                └── ChatViewModel.kt
```

---

## Development

### Adding a New Screen

1. Create `ui/screens/YourScreen.kt`
2. Add route to `MainActivity.kt` NavHost
3. Add navigation from relevant screen

### Modifying the API

1. Update `data/remote/OpenSharkApi.kt`
2. Update `ChatViewModel.kt` to use new endpoint
3. Update `http.rs` to handle new route

### Building APK

```bash
cd android
./build-apk.sh
```

---

## Troubleshooting

### "Cannot connect to server"
- Verify server is running: `curl http://127.0.0.1:9876/v1/health`
- Check port matches in app Settings
- Ensure Termux is running in background

### "Model not found"
- Download model: `ollama pull gemma4:e2b`
- Verify: `ollama list`
- Restart OpenShark server after adding models

### Slow responses
- Use smaller model (E2B vs E4B)
- Enable battery optimization whitelist for Termux
- Close other apps to free RAM
- Add swap file if needed: `dd if=/dev/zero of=~/swapfile bs=1M count=2048`

### Build errors in Termux
- `pkg install rust cargo sqlite pkg-config`
- Check disk space: `df -h`
- Try without --release flag first

---

## Performance Tips

1. **Use Gemma 4 E2B** for best speed/quality balance on mobile
2. **Enable swap** if you have <8GB RAM
3. **Whitelist Termux** in battery settings
4. **Use WiFi** for model downloads (can be 7-13GB)
5. **Keep model loaded** with `OLLAMA_KEEP_ALIVE=-1`

---

## Roadmap

- [x] HTTP gateway with SSE streaming
- [x] Android chat UI
- [x] Local LLM integration (Ollama)
- [x] Model picker
- [x] Agent mode toggle
- [x] Memory search
- [x] APK build scripts
- [ ] Tool execution UI (approve/deny)
- [ ] Multi-model comparison
- [ ] 24 synthwave themes
- [ ] Voice input/output
- [ ] F-Droid distribution
- [ ] Auto-start on boot

---

## License

Apache-2.0 — The future of coding belongs to everyone.

Made by synth with synthclaw 🎹🦞
