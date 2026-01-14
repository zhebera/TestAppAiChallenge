#!/bin/bash
# Интерактивный чат с Support API

API_URL="${SUPPORT_API_URL:-http://localhost:8080}"
USER_ID="${1:-}"
TICKET_ID="${2:-}"

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║              Support Chat - LLM Chat Application              ║"
echo "╠═══════════════════════════════════════════════════════════════╣"
echo "║  API: $API_URL"
if [ -n "$USER_ID" ]; then
    echo "║  User ID: $USER_ID"
fi
if [ -n "$TICKET_ID" ]; then
    echo "║  Ticket ID: $TICKET_ID"
fi
echo "║  Введите 'exit' для выхода                                    ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

while true; do
    echo -n "Вы: "
    read -r message

    if [ "$message" = "exit" ] || [ "$message" = "quit" ]; then
        echo "До свидания!"
        break
    fi

    if [ -z "$message" ]; then
        continue
    fi

    # Формируем JSON запрос
    json_body="{\"message\":\"$message\""
    if [ -n "$USER_ID" ]; then
        json_body="$json_body,\"user_id\":\"$USER_ID\""
    fi
    if [ -n "$TICKET_ID" ]; then
        json_body="$json_body,\"ticket_id\":\"$TICKET_ID\""
    fi
    json_body="$json_body}"

    echo ""
    echo "Ассистент:"

    # Отправляем запрос и выводим ответ
    response=$(curl -s -X POST "$API_URL/api/v1/support/chat" \
        -H "Content-Type: application/json" \
        -d "$json_body")

    # Извлекаем текст ответа (используем jq если есть, иначе grep/sed)
    if command -v jq &> /dev/null; then
        echo "$response" | jq -r '.response // .error // "Ошибка получения ответа"'

        # Показываем источники если есть
        sources=$(echo "$response" | jq -r '.sources[]? | "  - [\(.type)] \(.file // .ticket_id // "")"' 2>/dev/null)
        if [ -n "$sources" ]; then
            echo ""
            echo "Источники:"
            echo "$sources"
        fi

        # Показываем рекомендуемые действия если есть
        actions=$(echo "$response" | jq -r '.suggested_actions[]? | "  - \(.action): \(.description)"' 2>/dev/null)
        if [ -n "$actions" ]; then
            echo ""
            echo "Рекомендуемые действия:"
            echo "$actions"
        fi
    else
        # Fallback без jq - просто показываем весь ответ
        echo "$response"
    fi

    echo ""
    echo "─────────────────────────────────────────────────────────────────"
    echo ""
done
