//! Built-in tools exposed over /v1/tools/execute.
//!
//! v1: "shell" — run a command in the device shell with a persistent
//! working directory (cd/pwd are handled internally). This is what the
//! app's Shell screen talks to in "gateway" mode.

use anyhow::Result as AnyhowResult;
#[allow(unused_imports)]
use AnyhowResult as _;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

pub struct ToolState {
    pub shell_cwd: Mutex<PathBuf>,
}

impl ToolState {
    pub fn new(home: PathBuf) -> Self {
        Self {
            shell_cwd: Mutex::new(home),
        }
    }
}

const SHELL: &str = "/system/bin/sh";
const OUTPUT_CAP: usize = 64 * 1024;
const TIMEOUT: Duration = Duration::from_secs(60);

fn truncate(s: &mut String) {
    if s.len() > OUTPUT_CAP {
        let cut = s.len() - OUTPUT_CAP;
        s.drain(..cut);
        s.insert_str(0, "…[output truncated]\n");
    }
}

pub async fn execute(state: &ToolState, name: &str, args: &str) -> (bool, String, Option<String>) {
    match name {
        "shell" | "terminal" | "exec" => shell_exec(state, args).await,
        _ => (
            false,
            String::new(),
            Some(format!("unknown tool: {name}. available: shell")),
        ),
    }
}

async fn shell_exec(state: &ToolState, cmd: &str) -> (bool, String, Option<String>) {
    let trimmed = cmd.trim();

    // Builtins that touch persistent state
    if trimmed == "pwd" {
        let cwd = state.shell_cwd.lock().unwrap().display().to_string();
        return (true, cwd, None);
    }
    if trimmed == "cd" || trimmed.starts_with("cd ") || trimmed.starts_with("cd\t") {
        let target = trimmed[2..].trim();
        let mut guard = state.shell_cwd.lock().unwrap();
        let new_dir = if target.is_empty() || target == "~" {
            guard.clone() // stay; no better home notion here
        } else {
            let p = PathBuf::from(target);
            if p.is_absolute() {
                p
            } else {
                guard.join(p)
            }
        };
        if new_dir.is_dir() {
            *guard = new_dir.canonicalize().unwrap_or(new_dir);
            return (true, guard.display().to_string(), None);
        }
        return (
            false,
            String::new(),
            Some(format!("cd: no such directory: {target}")),
        );
    }

    let cwd = state.shell_cwd.lock().unwrap().clone();
    let child = tokio::process::Command::new(SHELL)
        .arg("-c")
        .arg(cmd)
        .current_dir(&cwd)
        .env("PATH", "/system/bin:/system/xbin:/product/bin:/vendor/bin")
        .env("HOME", cwd.display().to_string())
        .output();

    match tokio::time::timeout(TIMEOUT, child).await {
        Ok(Ok(out)) => {
            let mut result = String::from_utf8_lossy(&out.stdout).to_string();
            let stderr = String::from_utf8_lossy(&out.stderr);
            if !stderr.is_empty() {
                if !result.is_empty() {
                    result.push('\n');
                }
                result.push_str(&stderr);
            }
            truncate(&mut result);
            let code = out.status.code().unwrap_or(-1);
            if out.status.success() {
                (true, result, None)
            } else {
                (false, result, Some(format!("exit code {code}")))
            }
        }
        Ok(Err(e)) => (
            false,
            String::new(),
            Some(format!("failed to spawn {SHELL}: {e}")),
        ),
        Err(_) => (
            false,
            String::new(),
            Some(format!("command timed out after {}s", TIMEOUT.as_secs())),
        ),
    }
}
