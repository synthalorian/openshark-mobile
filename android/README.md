# OpenShark Mobile — Android App

Native Android chat client for OpenShark AI coding harness. Connects to your local OpenShark server running in Termux.

## Features

- 💬 **Real-time Chat** — SSE streaming for instant responses
- 🧠 **Memory Search** — Search your conversation history
- 🎯 **Model Switching** — Switch between local and cloud models on the fly
- 🛡️ **Agent Modes** — Safe (ask before tools) or Full Send (autonomous)
- 🏠 **Local LLM Support** — Works offline with Ollama/Gemma 4
- ☁️ **Cloud Fallback** — Use OpenAI, Anthropic when needed
- 🎨 **Synthwave84 Theme** — Neon purple, pink, and yellow aesthetic
- 📴 **Fully Offline** — No internet required for local models

## Screenshots

| Chat | Model Picker | Settings |
|------|-------------|----------|
| (screenshot) | (screenshot) | (screenshot) |

## Quick Start

### Prerequisites

- Android Studio or Android SDK
- JDK 17+
- Android device with Termux installed (F-Droid version)

### Build APK

```bash
cd openshark-mobile/android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy APK to phone and install manually.

## Setup Local LLM (Termux)

1. Install Termux from F-Droid
2. Run the setup script:
```bash
curl -sL https://raw.githubusercontent.com/synthalorian/openshark/main/mobile/scripts/setup-local-llm.sh | bash
```
3. Start OpenShark server:
```bash
openshark server --port 9876
```
4. Open the Android app and start chatting!

## Architecture

```
Android App (Kotlin/Compose)
    └── HTTP Client (OkHttp + SSE)
            └── localhost:9876
                    └── OpenShark Server (Rust/Axum)
                            └── Ollama (Local LLM)
                                    └── Gemma 4 / Qwen / etc.
```

## Configuration

The app connects to `http://127.0.0.1:9876` by default. Change in Settings if your server runs on a different port.

### Supported Models

**Local (Ollama):**
- `gemma4:e2b` — 2B params, ~7GB, recommended for mobile
- `gemma4:e4b` — 4B params, ~13GB, better quality
- `qwen3.5:0.8b` — 0.8B params, ~1GB, fastest

**Cloud (requires API key):**
- `gpt-4o` — OpenAI
- `claude-3-5-sonnet` — Anthropic
- `kimi-k3` — Moonshot AI

## Development

### Project Structure

```
app/src/main/java/com/synthalorian/openshark/
├── MainActivity.kt          # Entry point with navigation
├── OpenSharkApplication.kt  # App initialization
├── data/
│   └── remote/
│       ├── OpenSharkApi.kt  # REST API interface
│       └── SseClient.kt     # SSE streaming client
└── ui/
    ├── screens/
    │   ├── ChatScreen.kt     # Main chat interface
    │   ├── ModelsScreen.kt   # Model selection
    │   └── SettingsScreen.kt # Configuration
    ├── theme/
    │   └── Theme.kt          # Synthwave84 colors
    └── viewmodel/
        └── ChatViewModel.kt  # State management
```

### Adding a Feature

1. Update `OpenSharkApi.kt` with new endpoint
2. Add UI component in `screens/`
3. Update `ChatViewModel.kt` with business logic
4. Add navigation route in `MainActivity.kt`

## Troubleshooting

### "Cannot connect to server"
- Verify OpenShark server is running: `curl http://127.0.0.1:9876/v1/health`
- Check that Termux is running in background
- Verify port matches in Settings

### "Model not found"
- Ensure model is downloaded: `ollama list`
- Pull model: `ollama pull gemma4:e2b`
- Restart OpenShark server after adding models

### Slow responses
- Use smaller model (e2b vs e4b)
- Enable battery optimization whitelist for Termux
- Close other apps to free RAM

## License

Apache-2.0 — Made by synth with synthclaw 🎹🦞
