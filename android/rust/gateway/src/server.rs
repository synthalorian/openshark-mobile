//! Axum HTTP+SSE server implementing the /v1 contract the Kotlin app expects.

use std::convert::Infallible;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::Instant;

use axum::{
    extract::{Query, State},
    response::sse::{Event, KeepAlive, Sse},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};

use crate::config::GatewayConfig;
use crate::memory::MemoryStore;
use crate::provider::{self, ChatMessage};
use crate::tools::{self, ToolState};

pub struct AppState {
    pub config: RwLock<GatewayConfig>,
    pub config_dir: PathBuf,
    pub memory: MemoryStore,
    pub tools: ToolState,
    pub started: Instant,
    pub requests: AtomicU64,
}

// -- Request/response types (must match the Kotlin data classes) ------------

#[derive(Debug, Deserialize)]
pub struct ChatRequestBody {
    pub message: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default = "default_stream")]
    pub stream: bool,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub system_prompt: Option<String>,
}

fn default_stream() -> bool {
    true
}

#[derive(Debug, Serialize)]
pub struct ChatResponseChunk {
    pub chunk: String,
    pub done: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ToolCall {
    pub name: String,
    pub args: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ModelInfo {
    pub name: String,
    pub provider: String,
    pub context_length: i64,
    pub cost_per_1k_input: f64,
    pub cost_per_1k_output: f64,
    pub capabilities: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct StatusResponse {
    pub version: String,
    pub default_model: String,
    pub models_available: usize,
    pub memory_enabled: bool,
    pub uptime_seconds: u64,
}

#[derive(Debug, Deserialize)]
pub struct MemoryQuery {
    pub query: Option<String>,
    #[serde(default = "default_limit")]
    pub limit: usize,
    #[serde(default)]
    pub semantic: bool,
}

fn default_limit() -> usize {
    10
}

#[derive(Debug, Deserialize)]
pub struct MemorySaveRequest {
    pub content: String,
    #[serde(default)]
    pub session_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ToolExecuteRequest {
    pub name: String,
    pub args: String,
}

#[derive(Debug, Serialize)]
pub struct ToolExecuteResponse {
    pub success: bool,
    pub result: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

// -- Handlers ----------------------------------------------------------------

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
    }))
}

async fn status(State(state): State<Arc<AppState>>) -> Json<StatusResponse> {
    let cfg = state.config.read().unwrap();
    Json(StatusResponse {
        version: env!("CARGO_PKG_VERSION").to_string(),
        default_model: cfg.default_model.clone(),
        models_available: cfg.providers.iter().map(|p| p.models.len()).sum(),
        memory_enabled: true,
        uptime_seconds: state.started.elapsed().as_secs(),
    })
}

async fn list_models(State(state): State<Arc<AppState>>) -> Json<Vec<ModelInfo>> {
    let cfg = state.config.read().unwrap();
    let mut out = Vec::new();
    for p in &cfg.providers {
        for m in &p.models {
            out.push(ModelInfo {
                name: m.clone(),
                provider: p.name.clone(),
                context_length: 128_000,
                cost_per_1k_input: 0.0,
                cost_per_1k_output: 0.0,
                capabilities: vec!["chat".to_string()],
            });
        }
    }
    Json(out)
}

fn chunk_event(chunk: ChatResponseChunk) -> Result<Event, Infallible> {
    Ok(Event::default().json_data(chunk).unwrap())
}

async fn chat(State(state): State<Arc<AppState>>, Json(body): Json<ChatRequestBody>) -> Sse<
    impl futures::Stream<Item = Result<Event, Infallible>>,
> {
    state.requests.fetch_add(1, Ordering::Relaxed);
    let session_id = body
        .session_id
        .clone()
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());

    // Persist the user message
    let _ = state.memory.save(&session_id, "user", &body.message);

    // Assemble message history: recent session context + system prompt + new msg
    let mut messages: Vec<ChatMessage> = Vec::new();
    if let Some(sys) = &body.system_prompt {
        messages.push(ChatMessage {
            role: "system".into(),
            content: sys.clone(),
        });
    }
    if let Ok(recent) = state.memory.recent(&session_id, 20) {
        for m in recent {
            // Only chat roles may be replayed to the provider
            // (memory notes etc. must not leak into the transcript)
            if m.role != "user" && m.role != "assistant" {
                continue;
            }
            messages.push(ChatMessage {
                role: m.role,
                content: m.content,
            });
        }
    }
    if messages.last().map(|m| m.content.as_str()) != Some(body.message.as_str()) {
        messages.push(ChatMessage {
            role: "user".into(),
            content: body.message.clone(),
        });
    }

    let state2 = state.clone();
    let stream = async_stream::stream! {
        let (model, provider) = {
            let cfg = state2.config.read().unwrap();
            let model = cfg.effective_model(&body.model);
            let provider = cfg.provider_for_model(&model).cloned();
            (model, provider)
        };

        let Some(provider) = provider else {
            yield chunk_event(ChatResponseChunk {
                chunk: String::new(),
                done: true,
                tool_calls: None,
                error: Some(
                    "no providers configured — add one in Settings → Providers".to_string(),
                ),
            });
            return;
        };

        let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(64);
        let provider2 = provider.clone();
        let model2 = model.clone();
        let handle = tokio::spawn(async move {
            provider::stream_chat(&provider2, &model2, &messages, move |delta| {
                let _ = tx.blocking_send(delta);
            })
            .await
        });

        while let Some(delta) = rx.recv().await {
            yield chunk_event(ChatResponseChunk {
                chunk: delta,
                done: false,
                tool_calls: None,
                error: None,
            });
        }

        match handle.await {
            Ok(Ok(full)) => {
                let _ = state2.memory.save(&session_id, "assistant", &full);
                yield chunk_event(ChatResponseChunk {
                    chunk: String::new(),
                    done: true,
                    tool_calls: None,
                    error: None,
                });
            }
            Ok(Err(e)) => {
                yield chunk_event(ChatResponseChunk {
                    chunk: String::new(),
                    done: true,
                    tool_calls: None,
                    error: Some(format!("{e:#}")),
                });
            }
            Err(e) => {
                yield chunk_event(ChatResponseChunk {
                    chunk: String::new(),
                    done: true,
                    tool_calls: None,
                    error: Some(format!("provider task failed: {e}")),
                });
            }
        }
    };

    Sse::new(stream).keep_alive(KeepAlive::default())
}

async fn search_memory(
    State(state): State<Arc<AppState>>,
    Query(q): Query<MemoryQuery>,
) -> Json<serde_json::Value> {
    let query = q.query.unwrap_or_default();
    match state.memory.search(&query, q.limit) {
        Ok(msgs) => Json(serde_json::to_value(msgs).unwrap()),
        Err(e) => Json(serde_json::json!({ "error": e.to_string() })),
    }
}

async fn save_memory(
    State(state): State<Arc<AppState>>,
    Json(body): Json<MemorySaveRequest>,
) -> Json<serde_json::Value> {
    let session = body.session_id.unwrap_or_else(|| "default".into());
    match state.memory.save(&session, "note", &body.content) {
        Ok(id) => Json(serde_json::json!({ "id": id, "saved": true })),
        Err(e) => Json(serde_json::json!({ "error": e.to_string(), "saved": false })),
    }
}

async fn execute_tool(
    State(state): State<Arc<AppState>>,
    Json(body): Json<ToolExecuteRequest>,
) -> Json<ToolExecuteResponse> {
    let (success, result, error) = tools::execute(&state.tools, &body.name, &body.args).await;
    Json(ToolExecuteResponse {
        success,
        result,
        error,
    })
}

/// Reload config.toml from disk (Settings screen writes, then calls this).
async fn reload_config(State(state): State<Arc<AppState>>) -> Json<serde_json::Value> {
    match GatewayConfig::load(&state.config_dir) {
        Ok(cfg) => {
            let providers = cfg.providers.len();
            *state.config.write().unwrap() = cfg;
            Json(serde_json::json!({ "reloaded": true, "providers": providers }))
        }
        Err(e) => Json(serde_json::json!({ "reloaded": false, "error": format!("{e:#}") })),
    }
}

pub fn build_router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/status", get(status))
        .route("/v1/models", get(list_models))
        .route("/v1/chat", post(chat))
        .route("/v1/memory", get(search_memory).post(save_memory))
        .route("/v1/tools/execute", post(execute_tool))
        .route("/v1/config/reload", post(reload_config))
        .with_state(state)
}
