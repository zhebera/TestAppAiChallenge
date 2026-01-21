#!/bin/bash

SERVER="http://89.104.74.205:8081"
CONV_ID=""

echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║          Чат с локальной LLM на VPS                       ║"
echo "║          Модель: qwen2.5:0.5b                             ║"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║  Команды:                                                 ║"
echo "║    exit  - выйти из чата                                  ║"
echo "║    new   - начать новый диалог                            ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

while true; do
    # Читаем ввод пользователя
    echo -n "Вы: "
    read -r msg

    # Проверяем команды
    [[ "$msg" == "exit" ]] && echo "До свидания!" && break
    [[ -z "$msg" ]] && continue

    if [[ "$msg" == "new" ]]; then
        CONV_ID=""
        echo "--- Новый диалог начат ---"
        echo ""
        continue
    fi

    # Экранируем спецсимволы в сообщении для JSON
    msg_escaped=$(echo "$msg" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g')

    # Формируем JSON запрос
    if [[ -z "$CONV_ID" ]]; then
        json_data="{\"message\":\"$msg_escaped\"}"
    else
        json_data="{\"message\":\"$msg_escaped\",\"conversation_id\":\"$CONV_ID\"}"
    fi

    # Отправляем запрос
    echo -n "AI: "

    resp=$(curl -s -X POST "$SERVER/api/v1/chat" \
        -H "Content-Type: application/json" \
        -d "$json_data" 2>/dev/null)

    # Проверяем успешность запроса
    if [[ -z "$resp" ]]; then
        echo "Ошибка: сервер недоступен"
        echo ""
        continue
    fi

    # Извлекаем ответ и conversation_id
    answer=$(echo "$resp" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('response', data.get('error', 'Ошибка')))
except:
    print('Ошибка парсинга ответа')
" 2>/dev/null)

    CONV_ID=$(echo "$resp" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('conversation_id', ''))
except:
    pass
" 2>/dev/null)

    echo "$answer"
    echo ""
done
