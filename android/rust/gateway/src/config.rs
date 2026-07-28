//! Configuration for the standalone embedded gateway.
//!
//! Lives at <config_dir>/config.toml. Written/edited by the Android app
//! (Settings → Providers). Format:
//!
//! ```toml
//! default_model = "kimi-k3"
//!
//! [[providers]]
//! name = "my-server"
//! base_url = "http://100.64.1.2:8080"   # OpenAI-compatible /v1 endpoint
//! api_key = "sk-..."
//! models = ["kimi-k3", "qwen3"]
//! ```

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub name: String,
    pub base_url: String,
    #[serde(default)]
    pub api_key: String,
    #[serde(default)]
    pub models: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayConfig {
    #[serde(default)]
    pub default_model: String,
    #[serde(default)]
    pub providers: Vec<ProviderConfig>,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            default_model: String::new(),
            providers: Vec::new(),
        }
    }
}

impl GatewayConfig {
    pub fn load(config_dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(config_dir)?;
        let path = config_path(config_dir);
        if path.exists() {
            let content = std::fs::read_to_string(&path)
                .with_context(|| format!("reading {}", path.display()))?;
            let cfg: GatewayConfig =
                toml::from_str(&content).with_context(|| "parsing config.toml")?;
            Ok(cfg)
        } else {
            let cfg = GatewayConfig::default();
            let _ = std::fs::write(&path, toml::to_string_pretty(&cfg)?);
            Ok(cfg)
        }
    }

    /// Find the provider that owns `model`. Falls back to the first provider
    /// for unlisted model names (lets users type ad-hoc model ids).
    pub fn provider_for_model(&self, model: &str) -> Option<&ProviderConfig> {
        self.providers
            .iter()
            .find(|p| p.models.iter().any(|m| m == model))
            .or_else(|| self.providers.first())
    }

    pub fn effective_model<'a>(&'a self, requested: &'a Option<String>) -> String {
        requested
            .clone()
            .filter(|m| !m.is_empty())
            .unwrap_or_else(|| self.default_model.clone())
    }
}

pub fn config_path(config_dir: &Path) -> PathBuf {
    config_dir.join("config.toml")
}
