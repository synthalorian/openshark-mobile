//! SQLite-backed conversation memory.

use anyhow::Result;
use rusqlite::{params, Connection};
use serde::Serialize;
use std::path::Path;
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize)]
pub struct MemoryMessage {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub created_at: String,
    pub tokens_used: Option<i64>,
}

pub struct MemoryStore {
    conn: Mutex<Connection>,
}

impl MemoryStore {
    pub fn open(config_dir: &Path) -> Result<Self> {
        std::fs::create_dir_all(config_dir)?;
        let db_path = config_dir.join("memory.db");
        let conn = Connection::open(db_path)?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                tokens_used INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
            CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at);",
        )?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn save(&self, session_id: &str, role: &str, content: &str) -> Result<String> {
        let id = uuid::Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO messages (id, session_id, role, content, created_at, tokens_used)
             VALUES (?1, ?2, ?3, ?4, ?5, NULL)",
            params![id, session_id, role, content, now],
        )?;
        Ok(id)
    }

    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<MemoryMessage>> {
        let conn = self.conn.lock().unwrap();
        let pattern = format!("%{}%", query.replace('%', ""));
        let mut stmt = conn.prepare(
            "SELECT id, session_id, role, content, created_at, tokens_used
             FROM messages
             WHERE content LIKE ?1
             ORDER BY created_at DESC
             LIMIT ?2",
        )?;
        let rows = stmt.query_map(params![pattern, limit as i64], |row| {
            Ok(MemoryMessage {
                id: row.get(0)?,
                session_id: row.get(1)?,
                role: row.get(2)?,
                content: row.get(3)?,
                created_at: row.get(4)?,
                tokens_used: row.get(5)?,
            })
        })?;
        Ok(rows.filter_map(|r| r.ok()).collect())
    }

    pub fn recent(&self, session_id: &str, limit: usize) -> Result<Vec<MemoryMessage>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, session_id, role, content, created_at, tokens_used
             FROM messages WHERE session_id = ?1
             ORDER BY created_at DESC LIMIT ?2",
        )?;
        let rows = stmt.query_map(params![session_id, limit as i64], |row| {
            Ok(MemoryMessage {
                id: row.get(0)?,
                session_id: row.get(1)?,
                role: row.get(2)?,
                content: row.get(3)?,
                created_at: row.get(4)?,
                tokens_used: row.get(5)?,
            })
        })?;
        let mut msgs: Vec<_> = rows.filter_map(|r| r.ok()).collect();
        msgs.reverse(); // chronological
        Ok(msgs)
    }
}
