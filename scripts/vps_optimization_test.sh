#!/bin/bash

# VPS LLM Optimization Test Script
# Сравнение производительности с разными параметрами

SERVER="http://89.104.74.205:8081"
ENDPOINT="/api/v1/chat"

echo "=============================================="
echo "VPS LLM Optimization Tests"
echo "Model: qwen2.5:0.5b"
echo "=============================================="

# Test question for consistency
TEST_QUESTION="Объясни что такое рекурсия в программировании в 2-3 предложениях"

echo ""
echo "=== TEST 1: Baseline (default parameters) ==="
echo "Temperature: default, Max tokens: default"
time curl -s -X POST "${SERVER}${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"${TEST_QUESTION}\"}" | jq -r '.response, "---", "Tokens: \(.output_tokens), Time: \(.processing_time_ms)ms"'

echo ""
echo "=== TEST 2: Low temperature (0.1) - more deterministic ==="
time curl -s -X POST "${SERVER}${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"${TEST_QUESTION}\", \"temperature\": 0.1, \"max_tokens\": 150}" | jq -r '.response, "---", "Tokens: \(.output_tokens), Time: \(.processing_time_ms)ms"'

echo ""
echo "=== TEST 3: With system prompt (optimized for concise answers) ==="
SYSTEM_PROMPT="Ты - краткий технический ассистент. Отвечай только по существу, без вступлений и лишних слов. Максимум 3 предложения."
time curl -s -X POST "${SERVER}${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"${TEST_QUESTION}\", \"temperature\": 0.3, \"max_tokens\": 100, \"system_prompt\": \"${SYSTEM_PROMPT}\"}" | jq -r '.response, "---", "Tokens: \(.output_tokens), Time: \(.processing_time_ms)ms"'

echo ""
echo "=== TEST 4: Higher temperature (0.8) - more creative ==="
time curl -s -X POST "${SERVER}${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"${TEST_QUESTION}\", \"temperature\": 0.8, \"max_tokens\": 200}" | jq -r '.response, "---", "Tokens: \(.output_tokens), Time: \(.processing_time_ms)ms"'

echo ""
echo "=============================================="
echo "Test Complete"
echo "=============================================="
