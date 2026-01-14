#!/bin/bash
# Запуск службы поддержки без Gradle progress bar

cd "$(dirname "$0")"

# Собираем если нужно
./gradlew classes --console=plain -q 2>/dev/null

# Запускаем напрямую через java
java --enable-native-access=ALL-UNNAMED \
     -cp "build/classes/kotlin/main:$(./gradlew -q printClasspath 2>/dev/null || echo '')" \
     org.example.support.SupportConsoleAppKt 2>/dev/null

# Если не получилось, пробуем через Gradle
if [ $? -ne 0 ]; then
    ./gradlew runSupportChat --console=plain -q
fi
