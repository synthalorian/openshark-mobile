#!/usr/bin/env node
/**
 * Kimi Proxy for OpenShark
 * 
 * Bridges OpenShark (OpenAI-compatible) ↔ OpenClaw's Kimi connection
 * Runs alongside OpenClaw gateway, exposes localhost OpenAI API endpoint
 * 
 * Usage:
 *   node kimi-proxy.js [--port 9000]
 * 
 * Then in OpenShark config.toml:
 *   [providers.kimi]
 *   base_url = "http://127.0.0.1:9000/v1"
 *   api_key = "dummy"
 */

const http = require('http');
const { URL } = require('url');

// ── Config ──────────────────────────────────────────────────────
const PROXY_PORT = parseInt(process.argv.find((_, i, a) => a[i-1] === '--port') || process.env.KIMI_PROXY_PORT || '9000', 10);
const KIMI_BASE_URL = process.env.KIMI_API_URL || 'https://api.moonshot.cn/v1';
const KIMI_API_KEY = process.env.KIMI_API_KEY || '';

// Try to load from OpenClaw config if available
function loadOpenClawConfig() {
    const fs = require('fs');
    const path = require('path');
    const os = require('os');
    
    const configPaths = [
        path.join(os.homedir(), '.config/openclaw/config.json'),
        path.join(os.homedir(), '.config/openclaw/config.toml'),
        path.join(os.homedir(), '.openclaw/config.json'),
        path.join(os.homedir(), '.openclaw/config.toml'),
        '/usr/lib/node_modules/openclaw/config.json',
    ];
    
    for (const configPath of configPaths) {
        try {
            const content = fs.readFileSync(configPath, 'utf8');
            // Simple TOML/JSON parse for provider config
            if (configPath.endsWith('.toml')) {
                // Extract kimi/moonshot provider from TOML
                const kimiMatch = content.match(/\[providers\.(kimi|moonshot)[^\]]*\]([^\[]*)/i);
                if (kimiMatch) {
                    const section = kimiMatch[2];
                    const apiKeyMatch = section.match(/api_key\s*=\s*"([^"]+)"/);
                    const baseUrlMatch = section.match(/base_url\s*=\s*"([^"]+)"/);
                    return {
                        apiKey: apiKeyMatch ? apiKeyMatch[1] : KIMI_API_KEY,
                        baseUrl: baseUrlMatch ? baseUrlMatch[1] : KIMI_BASE_URL,
                    };
                }
            } else {
                const config = JSON.parse(content);
                const providers = config.providers || {};
                const kimi = providers.kimi || providers.moonshot || {};
                if (kimi.api_key || kimi.base_url) {
                    return {
                        apiKey: kimi.api_key || KIMI_API_KEY,
                        baseUrl: kimi.base_url || KIMI_BASE_URL,
                    };
                }
            }
        } catch (e) {
            // Continue to next path
        }
    }
    
    return { apiKey: KIMI_API_KEY, baseUrl: KIMI_BASE_URL };
}

const config = loadOpenClawConfig();
console.log(`[Kimi Proxy] Loaded config: baseUrl=${config.baseUrl}, apiKey=${config.apiKey ? '***' : 'NOT SET'}`);

if (!config.apiKey) {
    console.error('[Kimi Proxy] WARNING: No Kimi API key found. Set KIMI_API_KEY env var or configure OpenClaw.');
}

// ── OpenAI → Kimi Request Transformer ───────────────────────────
function transformRequest(openaiBody) {
    // Kimi/Moonshot is already OpenAI-compatible, just pass through
    // with any necessary tweaks
    return {
        model: openaiBody.model || 'kimi-k3',
        messages: openaiBody.messages || [],
        stream: openaiBody.stream !== false,
        temperature: openaiBody.temperature ?? 0.7,
        max_tokens: openaiBody.max_tokens || 4096,
        top_p: openaiBody.top_p ?? 1,
        // Kimi-specific params
        ...((openaiBody.tools || openaiBody.tool_choice) ? {
            tools: openaiBody.tools,
            tool_choice: openaiBody.tool_choice,
        } : {}),
    };
}

// ── Proxy Request Handler ───────────────────────────────────────
async function handleChatCompletions(req, res) {
    const chunks = [];
    for await (const chunk of req) {
        chunks.push(chunk);
    }
    const bodyStr = Buffer.concat(chunks).toString('utf8');
    
    let body;
    try {
        body = JSON.parse(bodyStr || '{}');
    } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'Invalid JSON', type: 'invalid_request_error' } }));
        return;
    }
    
    const kimiBody = transformRequest(body);
    
    if (body.stream) {
        // SSE streaming
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'Access-Control-Allow-Origin': '*',
        });
        
        try {
            const response = await fetch(`${config.baseUrl}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${config.apiKey}`,
                    'Accept': 'text/event-stream',
                },
                body: JSON.stringify(kimiBody),
            });
            
            if (!response.ok) {
                const error = await response.text();
                res.write(`data: ${JSON.stringify({ error: { message: error, code: response.status } })}

`);
                res.write('data: [DONE]

');
                res.end();
                return;
            }
            
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                const text = decoder.decode(value, { stream: true });
                res.write(text);
            }
            
            res.write('data: [DONE]

');
            res.end();
        } catch (e) {
            console.error('[Kimi Proxy] Stream error:', e.message);
            res.write(`data: ${JSON.stringify({ error: { message: e.message } })}

`);
            res.write('data: [DONE]

');
            res.end();
        }
    } else {
        // Non-streaming
        try {
            const response = await fetch(`${config.baseUrl}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${config.apiKey}`,
                },
                body: JSON.stringify(kimiBody),
            });
            
            const data = await response.json();
            res.writeHead(response.status, {
                'Content-Type': 'application/json',
                'Access-Control-Allow-Origin': '*',
            });
            res.end(JSON.stringify(data));
        } catch (e) {
            console.error('[Kimi Proxy] Request error:', e.message);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: { message: e.message, type: 'api_error' } }));
        }
    }
}

// ── Models Endpoint ─────────────────────────────────────────────
function handleModels(req, res) {
    const models = {
        object: 'list',
        data: [
            {
                id: 'kimi-k3',
                object: 'model',
                created: 1700000000,
                owned_by: 'moonshot',
                permission: [],
                root: 'kimi-k3',
                parent: null,
            },
            {
                id: 'kimi-k2.5',
                object: 'model',
                created: 1700000000,
                owned_by: 'moonshot',
                permission: [],
                root: 'kimi-k2.5',
                parent: null,
            },
            {
                id: 'kimi-k2',
                object: 'model',
                created: 1700000000,
                owned_by: 'moonshot',
                permission: [],
                root: 'kimi-k2',
                parent: null,
            },
        ],
    };
    
    res.writeHead(200, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
    });
    res.end(JSON.stringify(models));
}

// ── Health Check ────────────────────────────────────────────────
function handleHealth(req, res) {
    res.writeHead(200, { 
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
    });
    res.end(JSON.stringify({ 
        status: 'ok', 
        service: 'kimi-proxy',
        proxy_port: PROXY_PORT,
        upstream: config.baseUrl,
    }));
}

// ── HTTP Server ─────────────────────────────────────────────────
const server = http.createServer((req, res) => {
    // CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    
    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }
    
    const url = new URL(req.url, `http://${req.headers.host}`);
    
    if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
        handleChatCompletions(req, res);
    } else if (url.pathname === '/v1/models' && req.method === 'GET') {
        handleModels(req, res);
    } else if (url.pathname === '/v1/health' && req.method === 'GET') {
        handleHealth(req, res);
    } else {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'Not found', type: 'invalid_request_error' } }));
    }
});

server.listen(PROXY_PORT, '127.0.0.1', () => {
    console.log(`
╔══════════════════════════════════════════════════════════════╗
║              🎹🦞 Kimi Proxy for OpenShark                   ║
╚══════════════════════════════════════════════════════════════╝

Listening: http://127.0.0.1:${PROXY_PORT}
Upstream:  ${config.baseUrl}

OpenShark config:
  [providers.kimi]
  base_url = "http://127.0.0.1:${PROXY_PORT}/v1"
  api_key = "dummy"

Models available:
  • kimi-k3
  • kimi-k2.5
  • kimi-k2

Keep this running alongside OpenClaw.
`);
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('\n[Kimi Proxy] Shutting down...');
    server.close(() => process.exit(0));
});

process.on('SIGTERM', () => {
    console.log('\n[Kimi Proxy] Shutting down...');
    server.close(() => process.exit(0));
});
