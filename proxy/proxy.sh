#!/bin/bash
# Start Kimi-OpenShark Proxy
# Runs alongside OpenClaw gateway to bridge OpenShark → Kimi API

set -e

PROXY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIDFILE="/tmp/kimi-proxy.pid"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

case "${1:-}" in
    start)
        if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
            echo -e "${GREEN}✓ Proxy already running (PID: $(cat "$PIDFILE"))${NC}"
            exit 0
        fi

        echo -e "${BLUE}Starting Kimi-OpenShark Proxy...${NC}"
        
        # Check Node.js
        if ! command -v node &> /dev/null; then
            echo "Installing Node.js..."
            pkg install -y nodejs || apt-get install -y nodejs
        fi

        # Start proxy in background
        nohup node "$PROXY_DIR/kimi-proxy.js" > /tmp/kimi-proxy.log 2>&1 &
        echo $! > "$PIDFILE"
        
        sleep 2
        
        if kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
            echo -e "${GREEN}✓ Proxy started on http://127.0.0.1:9000${NC}"
            echo "  Log: /tmp/kimi-proxy.log"
            echo ""
            echo "Add to OpenShark config:"
            echo '  [providers.kimi]'
            echo '  base_url = "http://127.0.0.1:9000/v1"'
            echo '  api_key = "local-proxy"'
        else
            echo -e "${RED}✗ Failed to start proxy${NC}"
            cat /tmp/kimi-proxy.log
            exit 1
        fi
        ;;

    stop)
        if [ -f "$PIDFILE" ]; then
            PID=$(cat "$PIDFILE")
            if kill -0 "$PID" 2>/dev/null; then
                kill "$PID"
                rm -f "$PIDFILE"
                echo -e "${GREEN}✓ Proxy stopped${NC}"
            else
                echo "Proxy not running"
                rm -f "$PIDFILE"
            fi
        else
            echo "Proxy not running"
        fi
        ;;

    status)
        if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
            echo -e "${GREEN}✓ Proxy running (PID: $(cat "$PIDFILE"))${NC}"
            echo "  URL: http://127.0.0.1:9000/v1"
            tail -5 /tmp/kimi-proxy.log
        else
            echo -e "${RED}✗ Proxy not running${NC}"
        fi
        ;;

    logs)
        tail -f /tmp/kimi-proxy.log
        ;;

    *)
        echo "Usage: $0 {start|stop|status|logs}"
        echo ""
        echo "Commands:"
        echo "  start   - Start the proxy"
        echo "  stop    - Stop the proxy"
        echo "  status  - Check proxy status"
        echo "  logs    - View proxy logs"
        exit 1
        ;;
esac
