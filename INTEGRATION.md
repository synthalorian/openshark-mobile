# OpenShark Mobile Integration Guide

This guide shows exactly how to integrate the HTTP gateway into your existing OpenShark codebase.

## Step 1: Update Cargo.toml

Add these dependencies (they're lightweight):

```toml
[dependencies]
# Add to existing [dependencies] section:
async-stream = "0.3"
tower = "0.5"

# axum is already present as optional under web-api feature.
# We can use it without the feature flag since it's already defined.
```

## Step 2: Update src/gateway/mod.rs

Add the http module export:

```rust
pub mod http;
```

## Step 3: Add Server Command to src/main.rs

Add a new command variant to the `Commands` enum (around line 120, after the existing commands):

```rust
    /// Start HTTP server for mobile app connectivity
    Server {
        /// Port to bind on (default: 9876)
        #[arg(short, long, default_value_t = 9876)]
        port: u16,
        /// Bind address (default: 127.0.0.1)
        #[arg(short, long, default_value = "127.0.0.1")]
        bind: String,
    },
```

Then add the handler in the main match block (around line 900, before the `Ok(())` at the end):

```rust
        Some(Commands::Server { port, bind }) => {
            info!("Starting OpenShark HTTP server on {}:{}", bind, port);
            let addr = format!("{}:{}", bind, port);
            if let Err(e) = crate::gateway::http::start_server(config, port).await {
                eprintln!("❌ HTTP server error: {}", e);
                std::process::exit(1);
            }
        }
```

## Step 4: Copy the HTTP Gateway Module

Copy `src/gateway/http.rs` from this mobile project into your OpenShark repo at `src/gateway/http.rs`.

## Step 5: Build and Test

```bash
cargo build --release
# Test the server:
./target/release/openshark server --port 9876
# In another terminal:
curl http://127.0.0.1:9876/v1/health
curl -X POST http://127.0.0.1:9876/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello", "stream": false}'
```

## Step 6: Termux Build (Android)

Run the provided `scripts/termux-build.sh` in Termux after cloning your OpenShark repo.

## Architecture Notes

- The HTTP gateway uses the existing `MessageRouter` for consistency with Discord/Telegram
- SSE streaming allows real-time responses without WebSocket complexity
- Binding to `127.0.0.1` by default means only local connections (safe for mobile)
- No auth needed for localhost — the Android app and server are on the same device
