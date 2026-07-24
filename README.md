# OpenCode Go Agent v10 — Server Studio

Новый Android-проект с нуля. Package: `com.qandil.opencodego`. Минимальная версия: Android 8.0 (API 26).

## Что реализовано в исходниках

- нативный Android-интерфейс без WebView-оболочки и без Termux;
- изолированные проекты во внутренней памяти приложения;
- создание Static/PHP/Node проектов;
- безопасный импорт и экспорт ZIP;
- встроенный редактор файлов;
- реальный HTTP localhost-сервер на `ServerSocket`;
- отдельный сервер и порт для каждого проекта;
- foreground service и журнал запросов;
- WebView Preview Center;
- Runtime Pack Manager для ARM64 PHP/Node/Python бинарников;
- CGI-запуск `index.php`, когда PHP runtime pack установлен;
- настоящий SQLite Database Center;
- создание базы, app-level пользователь и случайный пароль;
- SQL Console, схема, SELECT/DDL/DML и транзакции;
- Provider Hub с OpenCode Go, OpenAI, Claude, Gemini, OpenRouter, Groq, Cerebras, DeepSeek, Mistral, xAI, Kimi, Together, Fireworks, NVIDIA NIM, GitHub Models, Ollama и LM Studio;
- собственный OpenAI-compatible, Anthropic и Gemini HTTP-клиент;
- автономный Agent Engine с tool calling;
- инструменты ИИ: список/чтение/запись файлов, запуск и диагностика сервера, схема и SQL базы;
- отдельные разрешения ИИ на чтение/запись файлов, сервер, чтение и изменение БД.

## Важное техническое ограничение

Android APK не может законно и надёжно «магически» содержать универсальные Linux-серверы от обычного x86-хостинга. PHP, Node и Python должны быть собраны под Android Bionic/ARM64 и поставляться runtime packs. Интерфейс импорта и запуска таких packs реализован. В этот исходный архив проприетарные/непроверенные бинарники не включены. Статический localhost и SQLite работают без дополнительных пакетов.

## Сборка

Откройте проект в актуальном Android Studio либо запустите GitHub Actions workflow `build-android.yml`. Workflow устанавливает Android SDK 37, Gradle 9.5 и собирает debug APK.
