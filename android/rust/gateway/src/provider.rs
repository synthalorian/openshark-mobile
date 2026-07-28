//! OpenAI-compatible provider client with SSE streaming.

use anyhow::{anyhow, Context, Result};
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::config::ProviderConfig;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

/// Stream chat completion deltas from an OpenAI-compatible endpoint.
/// Calls `on_delta` for each text chunk; returns the full assembled text.
pub async fn stream_chat<F>(
    provider: &ProviderConfig,
    model: &str,
    messages: &[ChatMessage],
    mut on_delta: F,
) -> Result<String>
where
    F: FnMut(String),
{
    let base = provider
        .base_url
        .trim_end_matches('/')
        .trim_end_matches("/v1");
    let url = format!("{base}/v1/chat/completions");
    let client = reqwest::Client::new();
    let mut req = client.post(&url).json(&json!({
        "model": model,
        "messages": messages,
        "stream": true,
    }));
    if !provider.api_key.is_empty() {
        req = req.bearer_auth(&provider.api_key);
    }

    let resp = req
        .send()
        .await
        .with_context(|| format!("POST {url}"))?;
    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "provider {} returned {}: {}",
            provider.name,
            status,
            body.chars().take(500).collect::<String>()
        ));
    }

    let mut full = String::new();
    let mut stream = resp.bytes_stream();
    let mut buf = String::new();

    while let Some(chunk) = stream.next().await {
        let bytes = chunk.context("reading stream")?;
        buf.push_str(&String::from_utf8_lossy(&bytes));

        // Process complete SSE lines
        while let Some(pos) = buf.find('\n') {
            let line = buf[..pos].trim_end_matches('\r').to_string();
            buf.drain(..=pos);
            let Some(data) = line.strip_prefix("data:") else {
                continue;
            };
            let data = data.trim();
            if data == "[DONE]" {
                return Ok(full);
            }
            if data.is_empty() {
                continue;
            }
            let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else {
                continue;
            };
            if let Some(delta) = v
                .get("choices")
                .and_then(|c| c.get(0))
                .and_then(|c| c.get("delta"))
                .and_then(|d| d.get("content"))
                .and_then(|c| c.as_str())
            {
                if !delta.is_empty() {
                    full.push_str(delta);
                    on_delta(delta.to_string());
                }
            }
        }
    }

    Ok(full)
}

/// One-shot (non-streaming) chat completion.
pub async fn chat_once(
    provider: &ProviderConfig,
    model: &str,
    messages: &[ChatMessage],
) -> Result<String> {
    let base = provider
        .base_url
        .trim_end_matches('/')
        .trim_end_matches("/v1");
    let url = format!("{base}/v1/chat/completions");
    let client = reqwest::Client::new();
    let mut req = client.post(&url).json(&json!({
        "model": model,
        "messages": messages,
        "stream": false,
    }));
    if !provider.api_key.is_empty() {
        req = req.bearer_auth(&provider.api_key);
    }
    let resp = req.send().await.with_context(|| format!("POST {url}"))?;
    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!(
            "provider {} returned {}: {}",
            provider.name,
            status,
            body.chars().take(500).collect::<String>()
        ));
    }
    let v: serde_json::Value = resp.json().await?;
    v.get("choices")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("message"))
        .and_then(|m| m.get("content"))
        .and_then(|c| c.as_str())
        .map(String::from)
        .ok_or_else(|| anyhow!("provider response had no message content"))
}
