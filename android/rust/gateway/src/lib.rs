//! Standalone embedded OpenShark gateway for Android.
//!
//! Loaded by the Kotlin app via `System.loadLibrary("openshark_gateway")`.
//! The gateway serves the /v1 HTTP+SSE API on 127.0.0.1:<port> so the app
//! talks to it exactly like it used to talk to the Termux-hosted server —
//! except now it's inside the APK. No Termux, no external openshark.

mod config;
mod memory;
mod provider;
mod server;
mod tools;

use std::net::TcpListener as StdTcpListener;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{anyhow, Result};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use tokio::sync::watch;

use crate::memory::MemoryStore;
use crate::server::AppState;
use crate::tools::ToolState;

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static SHUTDOWN: Mutex<Option<watch::Sender<bool>>> = Mutex::new(None);
static CURRENT_PORT: Mutex<Option<u16>> = Mutex::new(None);

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("openshark-gw"),
        );
        let _ = tracing_subscriber::fmt()
            .with_env_filter("info")
            .with_writer(std::io::stderr)
            .try_init();
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("openshark-gw")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// Start the gateway server. Idempotent: returns Ok if already running
/// on the requested port. Returns Err if the port is taken by something else.
pub fn start_gateway(config_dir: PathBuf, port: u16) -> Result<u16> {
    let rt = runtime();

    {
        let current = CURRENT_PORT.lock().unwrap();
        if let Some(p) = *current {
            if p == port {
                log::info!("gateway already running on port {port}");
                return Ok(p);
            }
            return Err(anyhow!(
                "gateway already running on different port {p} (requested {port})"
            ));
        }
    }

    // Fail fast if something else owns the port (e.g. a leftover Termux server)
    let listener = StdTcpListener::bind(("127.0.0.1", port))
        .map_err(|e| anyhow!("cannot bind 127.0.0.1:{port}: {e}"))?;
    listener.set_nonblocking(true)?;

    let cfg = config::GatewayConfig::load(&config_dir)?;
    let memory = MemoryStore::open(&config_dir)?;
    let home = config_dir.clone();
    let state = Arc::new(AppState {
        config: std::sync::RwLock::new(cfg),
        config_dir,
        memory,
        tools: ToolState::new(home),
        started: std::time::Instant::now(),
        requests: std::sync::atomic::AtomicU64::new(0),
        model_context: std::sync::RwLock::new(std::collections::HashMap::new()),
    });

    // Background: probe each provider's /models for real context lengths.
    // Kimi's K3 is 1M — the 128k fallback would lie in the UI.
    {
        let probe_state = state.clone();
        rt.spawn(async move {
            let providers: Vec<(String, String, String)> = {
                let cfg = probe_state.config.read().unwrap();
                cfg.providers
                    .iter()
                    .map(|p| (p.name.clone(), p.base_url.clone(), p.api_key.clone()))
                    .collect()
            };
            let client = reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default();
            for (name, base, key) in providers {
                let url = format!("{}/models", base.trim_end_matches('/'));
                let mut req = client.get(&url);
                if !key.is_empty() {
                    req = req.bearer_auth(&key);
                }
                match req.send().await {
                    Ok(resp) => match resp.json::<serde_json::Value>().await {
                        Ok(json) => {
                            let mut found = 0usize;
                            if let Some(arr) = json.get("data").and_then(|d| d.as_array()) {
                                let mut map = probe_state.model_context.write().unwrap();
                                for m in arr {
                                    if let (Some(id), Some(len)) = (
                                        m.get("id").and_then(|v| v.as_str()),
                                        m.get("context_length").and_then(|v| v.as_i64()),
                                    ) {
                                        map.insert(id.to_string(), len);
                                        found += 1;
                                    }
                                }
                            }
                            log::info!("probed {found} context lengths from provider '{name}'");
                        }
                        Err(e) => log::warn!("provider '{name}' /models parse failed: {e}"),
                    },
                    Err(e) => log::warn!("provider '{name}' /models probe failed: {e}"),
                }
            }
        });
    }

    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
    *SHUTDOWN.lock().unwrap() = Some(shutdown_tx);
    *CURRENT_PORT.lock().unwrap() = Some(port);

    rt.spawn(async move {
        let listener = tokio::net::TcpListener::from_std(listener).expect("tokio listener");
        let app = server::build_router(state);
        log::info!("openshark gateway listening on 127.0.0.1:{port}");
        let server = axum::serve(listener, app);
        tokio::select! {
            res = server => {
                if let Err(e) = res {
                    log::error!("gateway server error: {e}");
                }
            }
            _ = shutdown_rx.changed() => {
                log::info!("gateway shutting down");
            }
        }
        *CURRENT_PORT.lock().unwrap() = None;
        *SHUTDOWN.lock().unwrap() = None;
    });

    Ok(port)
}

pub fn stop_gateway() -> bool {
    let tx = SHUTDOWN.lock().unwrap().take();
    if let Some(tx) = tx {
        let _ = tx.send(true);
        true
    } else {
        false
    }
}

pub fn gateway_status() -> String {
    match *CURRENT_PORT.lock().unwrap() {
        Some(port) => format!("{{\"running\":true,\"port\":{port}}}"),
        None => "{\"running\":false}".to_string(),
    }
}

// -- JNI bindings -------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_synthalorian_openshark_service_GatewayNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_dir: JString,
    port: jint,
) -> jint {
    let dir: String = match env.get_string(&config_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("nativeStart: bad config_dir string: {e}");
            return -1;
        }
    };
    match start_gateway(PathBuf::from(dir), port as u16) {
        Ok(p) => p as jint,
        Err(e) => {
            log::error!("nativeStart failed: {e:#}");
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_synthalorian_openshark_service_GatewayNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if stop_gateway() {
        0
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_synthalorian_openshark_service_GatewayNative_nativeStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let status = gateway_status();
    env.new_string(status)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
