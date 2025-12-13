# LLM Chat Console

Консольное приложение для общения с Claude API (Anthropic) с поддержкой персистентной памяти и автоматического сжатия истории.

## Возможности

- Общение с Claude через консоль
- Персистентная память (SQLite) — история сохраняется между запусками
- Автоматическое сжатие диалога для экономии токенов
- Несколько режимов (персон) ассистента
- Streaming ответов

## Как работает память

1. Все сообщения сохраняются в SQLite (`~/.llm-chat/chat_memory.db`)
2. Каждые 2 сообщения (вопрос + ответ) автоматически сжимаются в краткое summary
3. В запрос отправляется только summary + текущий вопрос — это экономит токены
4. Хранится до 10 последних сообщений; старые удаляются вместе с их summary

## Установка

```bash
# Установить переменные окружения
export ANTHROPIC_API_KEY="your-key"
export OPENROUTER_API_KEY="your-key"  # опционально, для сжатия истории

# Собрать и запустить
./gradlew run
```

## Команды

| Команда | Описание |
|---------|----------|
| `exit` | Выход из программы |
| `/new`, `/clear` | Начать новый диалог |
| `/stats` | Статистика истории |
| `/changePrompt` | Сменить персону ассистента |
| `/temperature <0.0-1.0>` | Изменить temperature |
| `/maxTokens <число>` | Изменить лимит токенов |
| `/memory show` | Показать последние сообщения |
| `/memory search <текст>` | Поиск в истории |
| `/memory clear` | Очистить всю память |

## Технологии

- Kotlin 2.2 + Coroutines
- Ktor Client (HTTP)
- Exposed (SQLite ORM)
- kotlinx.serialization

## Структура проекта

```
src/main/kotlin/
├── Main.kt                 # Точка входа
├── data/
│   ├── network/            # API клиенты (Anthropic, OpenRouter)
│   ├── persistence/        # SQLite (Exposed)
│   └── repository/         # Репозитории
├── domain/
│   ├── models/             # Модели данных
│   └── usecase/            # Use cases
├── presentation/           # Консольный ввод
└── utils/                  # System prompts
```
