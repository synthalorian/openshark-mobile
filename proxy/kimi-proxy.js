#!/usr/bin/env node
/**
 * Kimi-OpenShark Proxy
 * 
 * Bridges OpenShark (OpenAI-compatible client) to Kimi/Moonshot API
 * using credentials from OpenClaw's config.
 * 
 * Run alongside OpenClaw gateway. OpenShark connects to localhost:9000
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROXY_PORT = process.env.KIMI_PROXY_PORT || 9000;
const KIMI_BASE_URL = 'https://api.moonshot.cn';

// ── Find OpenClaw Config ──────────────────────────────────────
function findOpenClawConfig() {
    const possiblePaths = [
        path.join(os.homedir(), '.config', 'openclaw', 'config.yaml'),
        path.join(os.homedir(), '.config', 'openclaw', 'config.yml'),
        path.join(os.homedir(), '.config', 'openclaw', 'config.json'),
        path.join(os.homedir(), '.openclaw', 'config.yaml'),
        path.join(os.homedir(), '.openclaw', 'config.yml'),
        path.join(os.homedir(), '.openclaw', 'config.json'),
        '/usr/lib/node_modules/openclaw/config.yaml',
    ];

    for (const p of possiblePaths) {
        if (fs.existsSync(p)) {
            return p;
        }
    }
    return null;
}

function loadConfig() {
    const configPath = findOpenClawConfig();
    if (!configPath) {
        console.error('OpenClaw config not found. Checked:');
        console.error('   ~/.config/openclaw/config.{yaml,yml,json}');
        console.error('   ~/.openclaw/config.{yaml,yml,json}');
        return {};
    }

    const content = fs.readFileSync(configPath, 'utf8');
    let config = {};
    
    if (configPath.endsWith('.json')) {
        config = JSON.parse(content);
    } else {
        // Naive YAML parser
        const lines = content.split('\n');
        let currentSection = null;
        for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed || trimmed.startsWith('#')) continue;
            
            const match = trimmed.match(/^(\w+):\s*(.*)$/);
            if (match) {
                const [, key, value] = match;
                if (value) {
                    config[key] = value.replace(/^["']|["']$/g, '');
                } else {
                    currentSection = key;
                    config[currentSection] = {};
                }
            }
        }
    }

    return config;
}

// ── Extract Kimi API Key ──────────────────────────────────────
function getKimiApiKey(config) {
    const keys = [
        config.providers?.kimi?.apiKey,
        config.providers?.moonshot?.apiKey,
        config.providers?.kimi?.api_key,
        config.providers?.moonshot?.api_key,
        config.kimi?.apiKey,
        config.moonshot?.apiKey,
        config.apiKey,
        process.env.KIMI_API_KEY,
        process.env.OPENCLAW_KIMI_KEY,
    ];

    for (const key of keys) {
        if (key) return key;
    }

    const envPaths = [
        path.join(os.homedir(), '.config', 'openclaw', '.env'),
        path.join(os.homedir(), '.openclaw', '.env'),
    ];
    for (const p of envPaths) {
        if (fs.existsSync(p)) {
            const env = fs.readFileSync(p, 'utf8');
            const match = env.match(/KIMI_API_KEY[=:]\s*(.+)/);
            if (match) return match[1].trim();
        }
    }

    return null;
}

// ── Proxy Server ──────────────────────────────────────────────
function createProxyServer(apiKey) {
    const server = http.createServer((req, res) => {
        // CORS headers
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

        if (req.method === 'OPTIONS') {
            res.writeHead(200);
            res.end();
            return;
        }

        console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);

        // Health check
        if (req.url === '/v1/health' || req.url === '/health') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'ok', proxy: 'kimi', service: 'openclaw-bridge' }));
            return;
        }

        // Models list
        if (req.url === '/v1/models' && req.method === 'GET') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                object: 'list',
                data: [
                    { id: 'kimi-k3', object: 'model', context_length: 1000000 },
                    { id: 'kimi-k2.5', object: 'model', context_length: 256000 },
                    { id: 'kimi-k2', object: 'model', context_length: 128000 },
                    { id: 'kimi-k1.5', object: 'model', context_length: 128000 },
                ]
            }));
            return;
        }

        // Chat completions (main endpoint)
        if (req.url === '/v1/chat/completions' && req.method === 'POST') {
            let body = '';
            req.on('data', chunk => body += chunk);
            req.on('end', () => {
                try {
                    const requestData = JSON.parse(body);
                    forwardToKimi(req, res, requestData, apiKey);
                } catch (e) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Invalid JSON' }));
                }
            });
            return;
        }

        // Unknown endpoint
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Not found' }));
    });

    return server;
}

// ── Forward to Kimi API ───────────────────────────────────────
function forwardToKimi(req, res, requestData, apiKey) {
    const isStreaming = requestData.stream !== false;

    const kimiRequest = {
        model: requestData.model || 'kimi-k3',
        messages: requestData.messages || [],
        temperature: requestData.temperature ?? 0.7,
        max_tokens: requestData.max_tokens,
        stream: isStreaming,
    };

    const requestBody = JSON.stringify(kimiRequest);

    const options = {
        hostname: 'api.moonshot.cn',
        port: 443,
        path: '/v1/chat/completions',
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${apiKey}`,
            'Content-Length': Buffer.byteLength(requestBody),
        },
    };

    const proxyReq = https.request(options, (proxyRes) => {
        const headers = { ...proxyRes.headers };
        delete headers['content-length'];
        
        res.writeHead(proxyRes.statusCode, headers);

        proxyRes.on('data', (chunk) => {
            res.write(chunk);
        });

        proxyRes.on('end', () => {
            res.end();
            console.log(`[${new Date().toISOString()}] Response complete`);
        });
    });

    proxyReq.on('error', (err) => {
        console.error('Proxy error:', err.message);
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Kimi API unreachable', details: err.message }));
    });

    proxyReq.write(requestBody);
    proxyReq.end();
}

// ── Main ──────────────────────────────────────────────────────
function main() {
    console.log('Kimi-OpenShark Proxy');
    console.log('========================');
    console.log('');

    const configPath = findOpenClawConfig();
    if (configPath) {
        console.log(`Found OpenClaw config: ${configPath}`);
    }

    const apiKey = getKimiApiKey(loadConfig());
    if (!apiKey) {
        console.error('Kimi API key not found.');
        console.error('   Set KIMI_API_KEY environment variable, or');
        console.error('   ensure OpenClaw config has provider.kimi.apiKey');
        process.exit(1);
    }

    console.log('Kimi API key loaded');
    console.log('');

    const server = createProxyServer(apiKey);
    server.listen(PROXY_PORT, '127.0.0.1', () => {
        console.log(`Proxy running on http://127.0.0.1:${PROXY_PORT}`);
        console.log('');
        console.log('OpenShark config:');
        console.log(`  base_url = "http://127.0.0.1:${PROXY_PORT}/v1"`);
        console.log('  api_key = "not-needed-for-local"');
        console.log('');
        console.log('Press Ctrl+C to stop');
    });

    server.on('error', (err) => {
        console.error('Server error:', err.message);
        process.exit(1);
    });
}

main();
