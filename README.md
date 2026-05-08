# Scanovich Backend

Платформа анализа качества телефонных звонков. Загружайте аудиозаписи — система автоматически транскрибирует их, определяет говорящих и оценивает звонок по настраиваемым скриптам.

## Стек

| Слой | Технологии |
|---|---|
| Backend | Kotlin, Ktor, Exposed, PostgreSQL, Redis |
| Pipeline | Python 3.12, Whisper large-v3, pyannote, FastAPI |
| Frontend | Vue 3, Vite |
| Инфра | Docker Compose, Flyway, Prometheus, Grafana |

## Как это работает

```
Аудиофайл → Kotlin API → Python Pipeline → Whisper (ASR) + pyannote (диаризация)
                                         ↓
                              Kotlin → LLM (OpenRouter/OpenAI) → Оценка по скрипту → БД
```

1. Файл загружается через веб-интерфейс или API (поддерживаются mp3, wav, m4a, ogg, flac, webm, opus)
2. Python-пайплайн транскрибирует аудио через Whisper и разделяет речь по спикерам
3. Kotlin-бэкенд оценивает транскрипцию через LLM по критериям настроенного скрипта
4. Результаты доступны в интерфейсе: транскрипция, баллы, сильные/слабые стороны, задачи

## Быстрый старт (dev)

```bash
cp .env.example .env
# заполни OPENROUTER_API_KEY или OPENAI_API_KEY в .env
docker compose up -d
```

Фронт: http://localhost:3000 · API: http://localhost:8080 · Pipeline: http://localhost:8001

## Продакшн (GPU)

```bash
cp .env.example .env
# заполни все секреты и DOMAIN в .env
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Требует NVIDIA Container Toolkit и GPU с ≥11 GB VRAM (протестировано на RTX 2080Ti).

## Структура

```
src/                  — Kotlin/Ktor backend
pipeline/             — Python ASR/LLM pipeline
  src/                — pipeline_service, asr, diarization, quality_analyzer
  voip/               — интеграции с VoIP (Ростелеком, Связьтранзит)
frontend/             — Vue 3 SPA
deploy/               — конфиги прода (Caddy, Grafana, Prometheus, pipeline.prod.yaml)
src/main/resources/db/migration/ — Flyway миграции (V1–V35)
```

## Мультитенантность

Каждый клиент получает изолированную PostgreSQL-схему (`tenant_<slug>`). Скрипты оценки, критерии, политики звонков и prompt-шаблоны настраиваются независимо для каждого тенанта.
