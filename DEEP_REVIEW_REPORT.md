## Детальный отчет по `scanovich-backend`

Дата: 2026-03-26

### 1) Карта системы (map-core)

- **Стек:** Kotlin 2.0, Ktor 2.3.12 (Netty), Exposed 0.52, Flyway 10.15, PostgreSQL 16, Redis 7, HikariCP.
- **Точка входа:** `com.scanovich.ApplicationKt` (`main()` → `embeddedServer(Netty)` → `Application.module()`).
- **Bootstrap-цепочка:** `AppConfig.load()` → `ServiceRegistry(config)` → `configureDatabase()` (Hikari+Flyway+Exposed connect) → `configurePlugins()` (JSON/CORS/JWT/StatusPages) → `configureRouting()`.
- **DI:** ручной `ServiceRegistry` создает репозитории/сервисы/pipeline-client и передает в routing.
- **Runtime зависимости:** DB (Postgres), Redis (в коде пока не используется), внешний Python pipeline по HTTP.

Ключевые пакеты:

- `config`: загрузка env-конфига, подключение БД, Ktor-плагины
- `routing`: HTTP API, auth-guard’ы≠
- `auth`: login/refresh/logout, JWT
- `db`: Exposed-таблицы + репозитории (public и tenant)
- `service`: бизнес-слой
- `pipeline`: async-оркестрация + HTTP клиент + writer результата в БД
- `dto`: запросы/ответы, пагинация

### 2) Ключевые бизнес-потоки (trace-flows)

Auth:

- `POST /api/v1/auth/login` → `AuthService.login()` → `UserRepository.findByEmail()` → bcrypt verify → `JwtService.generateTokenPair()` → `UserRepository.saveRefreshToken()`.
- `POST /api/v1/auth/refresh` → `AuthService.refresh()` → `findRefreshToken()` → revoke + rotate → новая пара токенов.
- `POST /api/v1/auth/logout(-all)` → revoke token / revoke all tokens.

Tenant-scoped API (под JWT):

- `GET /api/v1/managers` → `ManagerService` → `ManagerRepository` (join tenant.managers + tenant.departments + public.users).
- `GET/POST/PUT /api/v1/scripts` → `ScriptService` → `ScriptRepository` (tenant.scripts + tenant.criteria).
- Calls:
  - `POST /api/v1/calls` (без аудио) → `CallService.create()` → `CallRepository.create()` → статус `queued`.
  - `POST /api/v1/calls/upload` → multipart upload во временный файл → `CallService.createWithAudio()` → `CallRepository.create()` → `PipelineService.submitAsync()`.
  - `GET /api/v1/calls` / `GET /api/v1/calls/{id}` → `CallRepository.list/findById`.
  - `GET /api/v1/calls/{id}/result` → `CallRepository.findResult()` → агрегирует tenant.calls + tenant.transcriptions/speaker_metrics/quality_scores/error_events.

Async pipeline:

- `PipelineService.submitAsync()` запускает корутину → `markProcessing()` → HTTP `PipelineClient.analyze()` (до 300s) → `PipelineResultWriter.saveResult()` → обновление статуса звонка `done` / `transcribed_only` или `failed`.

### 3) Данные, multi-tenant, миграции (audit-data-tenancy)

Модель tenancy:

- **Public schema**: `tenants`, `users`, `refresh_tokens`, `plans`, `tenant_subscriptions`.
- **Tenant schema**: `departments`, `managers`, `scripts`, `criteria`, `calls`, `transcriptions`, `speaker_metrics`, `quality_scores`, `error_events`, `usage_log`.
- Tenant-схема выбирается через `principal.schema` (claim `schema` в JWT), и далее таблицы адресуются как `"$schema.table"`.

Критичный риск (исправлено):

- `V6__tenant_timestamps_to_bigint.sql` мигрировал существующие tenant-схемы на `bigint`, но **функция `public.create_tenant_schema()` (V2) продолжала создавать `timestamptz` колонки**.
- Это приводило бы к поломке при онбординге нового клиента после V6 (Exposed ожидает `Long`/`bigint`).
- Добавлены миграции:
  - `src/main/resources/db/migration/V7__fix_updated_at_to_bigint.sql` — исправляет `public.set_updated_at()` под `bigint`.
  - `src/main/resources/db/migration/V8__update_create_tenant_schema_bigint.sql` — обновляет `create_tenant_schema()` так, чтобы новые tenant-таблицы создавались сразу с `bigint` timestamps.

Доп. замечания по согласованности:

- В миграции `V1` для `public.refresh_tokens.ip_address` тип `INET`, а в Exposed модель — `text("ip_address")`. PostgreSQL обычно кастует текст в INET, но это потенциальное место для runtime-ошибок при нестандартных значениях.

### 4) Security и эксплуатационные риски (audit-security-ops)

High:

- **CORS:** включено `anyHost()` вместе с `allowCredentials = true` в `configureCors()` — это опасная конфигурация для production (куки/Authorization + любой origin).
- **JWT secret defaults:** есть дефолтные значения в `.env/.env.example` и `docker-compose.yml` (и fallback в `AppConfig`), что повышает риск запуска с предсказуемым секретом.

Medium:

- **Rate limiting не включен:** dependency есть (`ktor-server-rate-limit`), но плагин не установлен; login/refresh без ограничений → риск brute force.
- **CallLogging не включен:** dependency есть, но `CallLogging` не установлен; расследование инцидентов сложнее.
- **JWT schema claim:** `schema` берется из токена и напрямую используется как квалификатор таблиц; при компрометации секрета JWT атакующий сможет переключать schema (это ожидаемо, но важно понимать модель доверия).
- **Temp file cleanup:** в `POST /calls/upload` временный файл мог оставаться на диске при ошибках до передачи в pipeline. Исправлено в `src/main/kotlin/com/scanovich/routing/CallRoutes.kt` (best-effort cleanup в `finally`, если pipeline не “взял” файл).

Ops:

- `init-db.sql` создает роль `scanovich_app` с паролем `scanovich_dev` (нормально для dev, не для prod).
- `logback.xml`: `com.scanovich` в `DEBUG` — в production лучше снизить, чтобы уменьшить риск утечки контекста в логах.
- Нет CI workflow / README / тестов в `src/test`.

### 5) Roadmap (final-report)

Срочно (0-1 день):

- Задать production-safe CORS (конкретные origins) и запретить `anyHost()` при `ENVIRONMENT=production`.
- Убрать/запретить дефолтные JWT secrets (валидация при старте: “секрет слишком короткий/значение = change_me” → fail-fast).
- Включить базовый rate limit на `/api/v1/auth/login` и `/refresh`.

Быстрые победы (1-2 дня):

- Добавить `CallLogging` + корреляционный request id (и прокинуть в pipeline логи).
- Минимальные интеграционные тесты (Testcontainers Postgres):
  - миграции поднимаются (Flyway)
  - login/refresh работает
  - create tenant → создаются таблицы в новой schema
  - `calls/upload` создает call и пишет `processing/failed/done` статусы

Среднесрочно (1-2 недели):

- Пересмотреть модель доверия к `schema` claim: вычислять schema по `tenant_id` через БД или кэш, не доверяя полностью данным из JWT.
- Ввести policy по секретам/конфигам (vault/secret manager), отдельные env для dev/stage/prod.
- Добавить метрики/healthchecks для зависимостей (DB/Redis/Pipeline) и алерты.

---

### 6) Интеграция с Python AI Pipeline (Scanovich.ai-audio-call)

Контракт интеграции:
- **`POST /analyze`** — Kotlin `PipelineClient` отправляет аудиофайл через streaming multipart (`InputProvider`), Python возвращает `PipelineAnalyzeResponse` (transcription + classification + ASR metrics + quality scores).
- **`GET /healthz`** — health-check используется в `GET /health` и в `GET /api/v1/pipeline/health`.
- **`GET /analyses`** / **`GET /analyses/{id}`** — проксируются через `GET /api/v1/pipeline/analyses[/{resultId}]`.

Реализованные изменения:
- `docker-compose.yml` — Python pipeline как service `pipeline` с GPU-reservations и healthcheck; backend зависит от `pipeline:service_healthy`.
- `PipelineClient.kt` — streaming upload (InputProvider, без readBytes); proxy-методы `listAnalyses()`/`getAnalysis()`.
- `PipelineService.kt` — передает `scriptId` в `processCall()`/`submitAsync()` для корректной записи в `quality_scores`.
- `PipelineResultWriter.kt` — записывает `scriptId` в `quality_scores`; вызывает `public.bill_call_minutes()` и пишет `usage_log` после обработки.
- `PipelineRoutes.kt` — proxy-маршруты для pipeline-side analyses (доступ TEAM_LEAD/CLIENT_ADMIN).
- `PipelineDtos.kt` — DTO для `/analyses` list/detail ответов Python.

Оставшиеся задачи (pipeline-side):
- **Передача tenant-критериев в pipeline**: Python `POST /analyze` принимает только файл; для оценки по динамическим tenant-скриптам нужно расширить Python API (принимать criteria JSON как form field).
- **Speaker diarization**: Python pipeline отдает только ASR-метрики (duration, language, RTF); полные speaker metrics (talk ratio, WPM, interruptions) будут доступны после обновления Python pipeline.
- **Callback-модель**: сейчас синхронный HTTP-вызов (до 300с); для масштабирования — перейти на async callback / webhook.
