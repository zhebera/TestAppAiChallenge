#!/bin/bash

# ============================================================
# Deploy Local LLM Chat Server to VPS
# ============================================================
# This script helps deploy the Local LLM Chat Server
# to a VPS with Ollama installed.
# ============================================================

set -e

# Configuration
APP_NAME="local-llm-server"
APP_PORT="${LOCAL_LLM_PORT:-8081}"
OLLAMA_HOST="${OLLAMA_HOST:-http://localhost:11434}"
OLLAMA_MODEL="${OLLAMA_MODEL:-qwen2.5:7b}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║         Local LLM Chat Server - VPS Deployment                ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════════╝${NC}"

# Check if running as root
if [ "$EUID" -eq 0 ]; then
    echo -e "${YELLOW}Warning: Running as root. Consider using a non-root user.${NC}"
fi

# Check Java
echo -e "\n${GREEN}[1/5]${NC} Checking Java installation..."
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
    echo -e "  ✓ Java found: $JAVA_VERSION"
else
    echo -e "${RED}  ✗ Java not found. Installing...${NC}"
    if command -v apt-get &> /dev/null; then
        sudo apt-get update && sudo apt-get install -y openjdk-21-jdk
    elif command -v yum &> /dev/null; then
        sudo yum install -y java-21-openjdk
    elif command -v dnf &> /dev/null; then
        sudo dnf install -y java-21-openjdk
    else
        echo -e "${RED}Cannot install Java automatically. Please install JDK 21+ manually.${NC}"
        exit 1
    fi
fi

# Check Ollama
echo -e "\n${GREEN}[2/5]${NC} Checking Ollama installation..."
if command -v ollama &> /dev/null; then
    echo -e "  ✓ Ollama found"

    # Check if Ollama service is running
    if curl -s "$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
        echo -e "  ✓ Ollama service is running"
    else
        echo -e "${YELLOW}  ! Ollama is installed but not running. Starting...${NC}"
        ollama serve &
        sleep 3
    fi
else
    echo -e "${YELLOW}  ! Ollama not found. Installing...${NC}"
    curl -fsSL https://ollama.com/install.sh | sh

    # Start Ollama service
    echo -e "  Starting Ollama service..."
    ollama serve &
    sleep 3
fi

# Pull default model
echo -e "\n${GREEN}[3/5]${NC} Checking model: $OLLAMA_MODEL..."
if ollama list | grep -q "$OLLAMA_MODEL"; then
    echo -e "  ✓ Model $OLLAMA_MODEL is available"
else
    echo -e "  Pulling model $OLLAMA_MODEL..."
    ollama pull "$OLLAMA_MODEL"
fi

# Build the application
echo -e "\n${GREEN}[4/5]${NC} Building the application..."
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew build -x test --no-daemon
    echo -e "  ✓ Build successful"
else
    echo -e "${RED}  ✗ gradlew not found. Run this script from the project root.${NC}"
    exit 1
fi

# Create systemd service file
echo -e "\n${GREEN}[5/5]${NC} Setting up systemd service..."
SERVICE_FILE="/etc/systemd/system/$APP_NAME.service"
PROJECT_DIR=$(pwd)

sudo tee $SERVICE_FILE > /dev/null <<EOF
[Unit]
Description=Local LLM Chat Server
After=network.target ollama.service

[Service]
Type=simple
User=$USER
WorkingDirectory=$PROJECT_DIR
Environment="LOCAL_LLM_PORT=$APP_PORT"
Environment="OLLAMA_HOST=$OLLAMA_HOST"
Environment="OLLAMA_MODEL=$OLLAMA_MODEL"
ExecStart=$PROJECT_DIR/gradlew runLocalLlmServer --no-daemon
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd
sudo systemctl daemon-reload
sudo systemctl enable $APP_NAME
sudo systemctl start $APP_NAME

echo -e "\n${GREEN}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                    Deployment Complete!                       ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════════╝${NC}"
echo -e ""
echo -e "Server is running at: ${GREEN}http://0.0.0.0:$APP_PORT${NC}"
echo -e ""
echo -e "Useful commands:"
echo -e "  ${YELLOW}sudo systemctl status $APP_NAME${NC}  - Check service status"
echo -e "  ${YELLOW}sudo systemctl restart $APP_NAME${NC} - Restart service"
echo -e "  ${YELLOW}sudo journalctl -u $APP_NAME -f${NC}  - View logs"
echo -e ""
echo -e "Test API:"
echo -e "  ${YELLOW}curl http://localhost:$APP_PORT/api/v1/health${NC}"
echo -e "  ${YELLOW}curl -X POST http://localhost:$APP_PORT/api/v1/chat \\
    -H 'Content-Type: application/json' \\
    -d '{\"message\": \"Hello!\"}' ${NC}"
