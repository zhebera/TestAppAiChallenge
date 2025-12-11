# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Run tests
./gradlew test

# Clean build
./gradlew clean build
```

## Environment Setup

Set the `ANTHROPIC_API_KEY` environment variable before running. If not set, the app will prompt for manual input.

## Architecture Overview

This is a Kotlin console chat application that communicates with the Anthropic Claude API. It follows clean architecture with three layers:

### Layer Structure

- **presentation/** - Console I/O (`ConsoleInput`)
- **domain/** - Business logic
  - `models/` - Core data classes (`LlmMessage`, `LlmAnswer`, `ChatRole`)
  - `usecase/` - Use cases (`SendMessageUseCase`)
- **data/** - API and persistence
  - `network/` - `LlmClient` interface for LLM providers
  - `api/` - Provider implementations (`AnthropicClient`)
  - `repository/` - `ChatRepository` orchestrates multiple LLM clients
  - `dto/` - Request/response DTOs

### Key Patterns

- **Multi-LLM Support**: `LlmClient` interface allows multiple LLM providers. `ChatRepository` can query multiple clients in parallel using coroutines.
- **Structured JSON Responses**: The app expects LLM responses in a specific JSON format with `phase`, `message`, and `document` fields. Responses are parsed and displayed accordingly.
- **System Prompts**: Located in `utils/SystemPrompts.kt`. Different personas/modes (logic solver, tech writer, pirate, etc.) are available via `/changePrompt` command.

### Console Commands

- `exit` - Quit the application
- `/changePrompt` - Switch between system prompt personas
- `/temperature <0.0-1.0>` - Adjust response temperature

## Tech Stack

- Kotlin 2.2 with coroutines
- Ktor Client (CIO engine) for HTTP
- kotlinx.serialization for JSON
- Gradle Kotlin DSL
