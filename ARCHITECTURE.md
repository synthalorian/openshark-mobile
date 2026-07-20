# OpenShark Mobile — Standalone Android Architecture

## Goal
Run OpenShark natively on Android via Termux, with a native Android chat app talking to it over local HTTP. No SSH, no remote infrastructure, fully offline-capable.

## Architecture

```
┌─────────────────────────────────────────────┐
│  Android App (Kotlin/Jetpack Compose)       │
│  ┌─────────┐  ┌─────────┐  ┌─────────────┐  │
│  │ Chat UI │  │ Settings│  │ Model Picker│  │
│  └────┬────┘  └─────────┘  └─────────────┘  │
│       │                                     │
│       ▼ HTTP (localhost:9876)               │
│  ┌─────────────────────────────────────┐   │
│  │ HTTP Client (OkHttp + SSE)          │   │
│  └─────────────────────────────────────┘   │
└──────┬──────────────────────────────────────┘
       │ localhost
┌──────▼──────────────────────────────────────┐
│  Termux Environment                         │
│  ┌─────────────────────────────────────┐   │
│  │ OpenShark HTTP Gateway (Axum)       │   │
│  │ ┌───────┐ ┌───────┐ ┌───────────┐  │   │
│  │ │Router │ │Memory │ │Providers  │  │   │
│  │ └───────┘ └───────┘ └───────────┘  │   │
│  └─────────────────────────────────────┘   │
│  ┌─────────────────────────────────────┐   │
│  │ SQLite Database (~/.openshark/)     │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

## Why This Approach

1. **Termux is the runtime** — Already has Rust, can build/run native binaries
2. **Localhost bridge** — No network config, no auth, no pairing. Just works.
3. **HTTP + SSE** — Simple protocol, streaming support, easy to debug with curl
4. **Offline first** — Local LLM support (llama.cpp, ollama in Termux) means zero internet

## Components

### 1. OpenShark HTTP Gateway (`src/gateway/http.rs`)

New module that starts an Axum server when `openshark server` is run:

```rust
// Endpoints:
POST   /v1/chat          → Chat with OpenShark (SSE streaming)
GET    /v1/models        → List available models
POST   /v1/agent         → Run autonomous agent task
GET    /v1/memory        → Search memory
POST   /v1/memory        → Save to memory
GET    /v1/status        → System status
POST   /v1/tools/execute → Execute a tool directly
GET    /v1/skills        → List loaded skills
```

Request format:
```json
POST /v1/chat
{
  "message": "Refactor the auth module",
  "model": "gpt-4o",
  "stream": true,
  "session_id": "optional-persist-key"
}
```

Response (SSE):
```
event: message
data: {"chunk": "I'll", "done": false}

event: message
data: {"chunk": " analyze", "done": false}

event: message
data: {"chunk": " the auth module...", "done": true, "tool_calls": []}
```

### 2. Android App (`openshark-android/`)

Kotlin + Jetpack Compose, minimal dependencies:

```kotlin
// Core modules:
- data/remote/      → OpenSharkApi interface (Retrofit + OkHttp SSE)
- data/local/       → SettingsStore (DataStore)
- ui/screens/       → ChatScreen, SettingsScreen
- ui/components/    → MessageBubble, InputBar, ModelChip
- service/          → Background connection manager
```

Screens:
- **Chat** — Message bubbles, markdown rendering, code blocks with copy
- **Settings** — Server URL (default: http://localhost:9876), API key, theme
- **Models** — Dropdown to switch models, shows context length + cost
- **Tools** — Toggle which tools are available, view tool output

### 3. Termux Integration

Build script and service wrapper:

```bash
# Install in Termux:
pkg install rust sqlite

# Build OpenShark for Termux:
cd openshark
cargo build --release --features http-gateway

# Run server:
openshark server --port 9876

# Or as background service:
termux-services package
openshark-server &
```

Auto-start on boot via Termux:Boot app.

## File Structure

```
openshark/
├── src/
│   ├── gateway/
│   │   ├── mod.rs           ← Add http module
│   │   ├── http.rs          ← NEW: Axum HTTP server
│   │   ├── events.rs        ← GatewayEvent (existing)
│   │   ├── unified_router.rs ← Existing
│   │   └── ...
│   ├── main.rs              ← Add `server` subcommand
│   └── ...
├── openshark-android/       ← NEW: Android app
│   ├── app/
│   │   ├── src/main/java/...
│   │   └── build.gradle.kts
│   └── gradle/
└── scripts/
    └── termux-build.sh      ← NEW: Build for Termux
```

## Implementation Phases

### Phase 1: HTTP Gateway (Server)
- [ ] Create `src/gateway/http.rs` with Axum routes
- [ ] Integrate with existing `UnifiedRouter` / `MessageRouter`
- [ ] Add `openshark server` CLI command
- [ ] SSE streaming for real-time responses
- [ ] Session persistence via query param or header

### Phase 2: Android Client
- [ ] Scaffold Kotlin project with Compose
- [ ] HTTP client with OkHttp + SSE parser
- [ ] Chat UI with message bubbles
- [ ] Settings screen for connection config
- [ ] Model picker dropdown

### Phase 3: Termux Polish
- [ ] `termux-build.sh` cross-compile script
- [ ] Service wrapper for background running
- [ ] Auto-start integration
- [ ] Package as Termux package (optional)

### Phase 4: Advanced Features
- [ ] Tool execution UI (view output, approve/deny)
- [ ] Memory search screen
- [ ] Multi-model comparison view
- [ ] Agent mode toggle (safe vs full-send)
- [ ] Local LLM integration (ollama/llama.cpp in Termux)

## Open Questions

1. **Port binding** — Termux might need `termux-api` for port access. Check if 9876 is free.
2. **Background execution** — Android kills background services. Need foreground service + notification.
3. **Local LLM** — What's the plan? Ollama in Termux? llama.cpp? This affects provider config.
4. **Auth** — Localhost = no auth needed initially. But if user opens port to LAN, add API key.

## Next Steps

1. I'll create the HTTP gateway module (`src/gateway/http.rs`)
2. Add `server` subcommand to `main.rs`
3. Scaffold the Android project structure
4. Write the Termux build script

Ready to start coding? Which phase do you want first?
