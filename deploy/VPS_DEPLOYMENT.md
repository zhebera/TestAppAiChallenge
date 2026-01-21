# Развёртывание Local LLM Chat Server на VPS

## Обзор

Этот документ описывает процесс развёртывания REST API сервера для чата с локальной LLM (Ollama) на удалённом VPS.

## Требования к VPS

- **ОС**: Ubuntu 20.04+ / Debian 11+ / CentOS 8+
- **RAM**: минимум 8GB (рекомендуется 16GB для моделей 7B)
- **CPU**: 4+ ядер (рекомендуется 8+ для быстрой генерации)
- **Диск**: 20GB+ свободного места
- **Java**: JDK 21+

## Быстрый старт

### 1. Подключение к VPS

```bash
ssh user@your-vps-ip
```

### 2. Установка зависимостей

#### Ubuntu/Debian:
```bash
# Обновление пакетов
sudo apt update && sudo apt upgrade -y

# Установка Java 21
sudo apt install -y openjdk-21-jdk git curl

# Установка Ollama
curl -fsSL https://ollama.com/install.sh | sh
```

#### CentOS/RHEL:
```bash
# Установка Java 21
sudo dnf install -y java-21-openjdk java-21-openjdk-devel git curl

# Установка Ollama
curl -fsSL https://ollama.com/install.sh | sh
```

### 3. Запуск Ollama и загрузка модели

```bash
# Запуск Ollama в фоновом режиме
ollama serve &

# Загрузка модели (qwen2.5:7b по умолчанию)
ollama pull qwen2.5:7b

# Проверка работы
ollama list
```

### 4. Клонирование и сборка проекта

```bash
# Клонирование репозитория
git clone https://github.com/YOUR_USERNAME/TestAppAiChallenge.git
cd TestAppAiChallenge

# Сборка проекта
chmod +x gradlew
./gradlew build -x test
```

### 5. Запуск сервера

#### Вариант A: Ручной запуск

```bash
# Запуск с параметрами по умолчанию
./gradlew runLocalLlmServer

# Или с кастомными параметрами
LOCAL_LLM_PORT=8081 \
OLLAMA_HOST=http://localhost:11434 \
OLLAMA_MODEL=qwen2.5:7b \
./gradlew runLocalLlmServer
```

#### Вариант B: Systemd сервис (рекомендуется)

```bash
# Автоматическое развёртывание
chmod +x deploy/deploy-vps.sh
./deploy/deploy-vps.sh
```

Или вручную создайте сервис:

```bash
sudo nano /etc/systemd/system/local-llm-server.service
```

```ini
[Unit]
Description=Local LLM Chat Server
After=network.target

[Service]
Type=simple
User=your-username
WorkingDirectory=/path/to/TestAppAiChallenge
Environment="LOCAL_LLM_PORT=8081"
Environment="OLLAMA_HOST=http://localhost:11434"
Environment="OLLAMA_MODEL=qwen2.5:7b"
ExecStart=/path/to/TestAppAiChallenge/gradlew runLocalLlmServer --no-daemon
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable local-llm-server
sudo systemctl start local-llm-server
```

### 6. Настройка firewall

```bash
# UFW (Ubuntu)
sudo ufw allow 8081/tcp
sudo ufw reload

# firewalld (CentOS)
sudo firewall-cmd --permanent --add-port=8081/tcp
sudo firewall-cmd --reload
```

## API Endpoints

После запуска сервер доступен на `http://YOUR_VPS_IP:8081`

### Health Check

```bash
curl http://YOUR_VPS_IP:8081/api/v1/health
```

Ответ:
```json
{
  "status": "ok",
  "version": "1.0.0",
  "ollama_available": true,
  "default_model": "qwen2.5:7b",
  "available_models": ["qwen2.5:7b", "llama3.2:3b"]
}
```

### Список моделей

```bash
curl http://YOUR_VPS_IP:8081/api/v1/models
```

### Отправка сообщения (sync)

```bash
curl -X POST http://YOUR_VPS_IP:8081/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Объясни, что такое машинное обучение",
    "system_prompt": "Ты полезный ассистент. Отвечай кратко и понятно.",
    "temperature": 0.7
  }'
```

Ответ:
```json
{
  "response": "Машинное обучение — это область искусственного интеллекта...",
  "model": "qwen2.5:7b",
  "conversation_id": "550e8400-e29b-41d4-a716-446655440000",
  "input_tokens": 45,
  "output_tokens": 120,
  "processing_time_ms": 3500
}
```

### Продолжение диалога

```bash
curl -X POST http://YOUR_VPS_IP:8081/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Приведи пример",
    "conversation_id": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

### Стриминг ответа (SSE)

```bash
curl -X POST http://YOUR_VPS_IP:8081/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Напиши короткое стихотворение"
  }'
```

Ответ (Server-Sent Events):
```
data: {"type":"delta","content":"Весна"}
data: {"type":"delta","content":" пришла"}
data: {"type":"delta","content":" в наш"}
data: {"type":"delta","content":" дом..."}
data: {"type":"done","model":"qwen2.5:7b","input_tokens":15,"output_tokens":45}
```

### Использование другой модели

```bash
curl -X POST http://YOUR_VPS_IP:8081/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Hello!",
    "model": "llama3.2:3b"
  }'
```

## Управление сервисом

```bash
# Статус
sudo systemctl status local-llm-server

# Логи (следить)
sudo journalctl -u local-llm-server -f

# Логи (последние 100 строк)
sudo journalctl -u local-llm-server -n 100

# Перезапуск
sudo systemctl restart local-llm-server

# Остановка
sudo systemctl stop local-llm-server
```

## Доступные модели Ollama

Для загрузки дополнительных моделей:

```bash
# Компактные модели (4-8GB RAM)
ollama pull llama3.2:3b      # Meta Llama 3.2 3B
ollama pull phi3:3.8b        # Microsoft Phi-3 Mini
ollama pull gemma2:2b        # Google Gemma 2 2B

# Средние модели (8-16GB RAM)
ollama pull qwen2.5:7b       # Alibaba Qwen 2.5 7B
ollama pull llama3.1:8b      # Meta Llama 3.1 8B
ollama pull mistral:7b       # Mistral 7B

# Большие модели (16GB+ RAM)
ollama pull qwen2.5:14b      # Alibaba Qwen 2.5 14B
ollama pull llama3.1:70b     # Meta Llama 3.1 70B (требует ~40GB RAM)
```

## Troubleshooting

### Ollama не запускается

```bash
# Проверить, запущен ли процесс
ps aux | grep ollama

# Перезапустить
pkill ollama
ollama serve &

# Проверить логи
journalctl -u ollama -n 50
```

### Ошибка "Connection refused"

```bash
# Проверить, слушает ли Ollama
curl http://localhost:11434/api/tags

# Если нет, запустить
ollama serve &
```

### Медленная генерация

1. Проверьте загрузку CPU: `htop`
2. Используйте более компактную модель: `qwen2.5:3b` вместо `qwen2.5:7b`
3. Увеличьте количество ядер CPU

### Недостаточно памяти

```bash
# Проверить свободную память
free -h

# Использовать квантованную модель
ollama pull qwen2.5:7b-q4_K_M
```

## Безопасность (Production)

Для production-окружения рекомендуется:

1. **Nginx reverse proxy** с SSL:
```bash
sudo apt install nginx certbot python3-certbot-nginx
```

2. **Rate limiting** в Nginx:
```nginx
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

server {
    listen 443 ssl;
    server_name your-domain.com;

    location /api/ {
        limit_req zone=api burst=20 nodelay;
        proxy_pass http://127.0.0.1:8081;
    }
}
```

3. **API ключи** (добавить в код при необходимости)

4. **Мониторинг**: Prometheus + Grafana

## Пример клиента (Python)

```python
import requests

def chat(message, conversation_id=None):
    response = requests.post(
        "http://YOUR_VPS_IP:8081/api/v1/chat",
        json={
            "message": message,
            "conversation_id": conversation_id,
            "temperature": 0.7
        }
    )
    return response.json()

# Использование
result = chat("Привет! Как дела?")
print(result["response"])

# Продолжение диалога
result2 = chat("Расскажи о себе", result["conversation_id"])
print(result2["response"])
```

## Пример клиента (JavaScript)

```javascript
async function chat(message, conversationId = null) {
    const response = await fetch("http://YOUR_VPS_IP:8081/api/v1/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            message,
            conversation_id: conversationId,
            temperature: 0.7
        })
    });
    return response.json();
}

// Стриминг
async function chatStream(message, onChunk) {
    const response = await fetch("http://YOUR_VPS_IP:8081/api/v1/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message })
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const lines = decoder.decode(value).split("\n");
        for (const line of lines) {
            if (line.startsWith("data: ")) {
                const data = JSON.parse(line.slice(6));
                onChunk(data);
            }
        }
    }
}

// Использование стриминга
chatStream("Расскажи сказку", (chunk) => {
    if (chunk.type === "delta") {
        process.stdout.write(chunk.content);
    }
});
```
