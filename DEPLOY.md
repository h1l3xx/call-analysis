# Деплой Malikov на выделенный сервер

## Конфигурация сервера

| Компонент   | Спецификация                |
|-------------|-----------------------------|
| CPU         | Intel Core i5-12600, 6 ядер |
| GPU         | RTX 2080Ti, 11 GB GDDR6     |
| RAM         | 32 GB DDR4                   |
| Диски       | 2 × 1 TB NVMe SSD           |
| Сеть        | 1 Гбит/с, публичный IPv4    |

## Распределение ресурсов

| Сервис     | RAM      | GPU VRAM     |
|------------|----------|--------------|
| Pipeline   | 14 GB    | ~5.5 GB      |
| PostgreSQL | 2 GB     | —            |
| Backend    | 2 GB     | —            |
| Redis      | 768 MB   | —            |
| Frontend   | 256 MB   | —            |
| ОС + swap  | ~8 GB    | —            |
| **Итого**  | **~27 GB** | **~5.5 GB** |

Pipeline GPU: Whisper large-v3 float16 (~3 GB) + pyannote diarization (~1.5 GB) + overhead (~1 GB).

---

## 1. Подготовка сервера (один раз)

```bash
# Установить Ubuntu 22.04 LTS через автоустановку ОС

# Подключиться по SSH
ssh root@YOUR_SERVER_IP

# Склонировать проект
git clone <YOUR_REPO_URL> /opt/malikov
cd /opt/malikov

# Автоматическая установка Docker, NVIDIA drivers, NVIDIA Container Toolkit
sudo scripts/deploy.sh init

# Перезагрузить (если установились NVIDIA драйверы)
sudo reboot
```

## 2. Настройка окружения

```bash
cd /opt/malikov

# Скопировать шаблон и заполнить
cp .env.production.example .env
nano .env
```

**Обязательно заполнить:**

| Переменная         | Описание                                            |
|--------------------|-----------------------------------------------------|
| `POSTGRES_PASSWORD` | Пароль PostgreSQL (сгенерировать)                  |
| `DB_APP_PASSWORD`   | Пароль приложения для PostgreSQL                   |
| `JWT_SECRET`        | `openssl rand -base64 48`                          |
| `PIPELINE_API_KEY`  | `openssl rand -hex 32`                             |
| `HF_TOKEN`          | HuggingFace токен (huggingface.co/settings/tokens) |
| `OPENROUTER_API_KEY`| API ключ OpenRouter                                |

**HuggingFace:** перед деплоем принять лицензию модели:
- https://huggingface.co/pyannote/speaker-diarization-3.1
- https://huggingface.co/pyannote/segmentation-3.0

## 3. Проверка готовности

```bash
scripts/deploy.sh setup
```

Должно показать: Docker, Docker Compose, GPU, NVIDIA Container Toolkit — всё OK.

## 4. Деплой

```bash
scripts/deploy.sh deploy
```

Скрипт выполнит:
1. Клонирование pipeline repo + применение патча
2. Сборку backend (Gradle shadowJar)
3. Сборку всех Docker образов (pipeline с GPU, backend, frontend)
4. Запуск всех контейнеров
5. Ожидание health checks

**Первый запуск:** Pipeline загрузит модели (~3-5 GB): Whisper large-v3 + pyannote. Это занимает 5-10 минут.

## 5. Проверка

```bash
# Статус контейнеров
scripts/deploy.sh status

# Логи (все)
scripts/deploy.sh logs

# Логи конкретного сервиса
scripts/deploy.sh logs pipeline
scripts/deploy.sh logs app

# GPU использование
nvidia-smi
```

Сервисы после деплоя:
- **Frontend:** http://YOUR_SERVER_IP (порт 80)
- **Backend API:** http://YOUR_SERVER_IP:8080

> ⚠️ **Всегда используйте `scripts/deploy.sh`**, а не голый `docker compose up`.
> `docker-compose.yml` сам по себе — это dev-конфигурация (CPU pipeline,
> монтирует `pipeline/config.yaml` напрямую). Продовые настройки (GPU-образ
> pipeline, `deploy/pipeline.prod.yaml`, лимиты памяти) лежат в
> `docker-compose.prod.yml` и подключаются только через `-f` (как делает
> `deploy.sh`) либо через `COMPOSE_FILE` в `.env` (уже настроено в
> `.env.production.example`). Если всё же нужно вызвать `docker compose`
> вручную — запускайте его из корня репозитория (`/opt/malikov`), а не из
> `scripts/`, иначе легко случайно подхватить не тот compose-файл.

---

## Управление

```bash
# Остановить
scripts/deploy.sh down

# Перезапустить
scripts/deploy.sh restart

# Пересобрать конкретный сервис
scripts/deploy.sh rebuild pipeline

# Бэкап базы
scripts/deploy.sh backup

# Восстановление из бэкапа
scripts/deploy.sh restore backups/malikov_20260331_120000.sql.gz
```

## Обновление

```bash
cd /opt/malikov
git pull
scripts/deploy.sh deploy
```

## Ожидаемая производительность

| Операция        | Время на один звонок (5 мин) |
|-----------------|------------------------------|
| Whisper large-v3 (GPU, float16) | ~30-60 сек         |
| Pyannote diarization (GPU)      | ~15-20 сек         |
| OpenRouter LLM оценка           | ~10-30 сек         |
| **Итого**       | **~1-2 мин**                 |

При 150 звонках/день: ~2.5-5 часов обработки (2 concurrent tasks).

## Troubleshooting

**Pipeline не стартует (GPU):**
```bash
# Проверить GPU
nvidia-smi
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi

# Если не работает — переустановить NVIDIA Container Toolkit
sudo scripts/deploy.sh init
```

**Модели не загружаются:**
```bash
# Проверить HF_TOKEN
scripts/deploy.sh logs pipeline | grep -i "hugging\|token\|auth"

# Убедиться что лицензия принята на HuggingFace
```

**Нехватка VRAM:**
```bash
# Мониторинг
watch nvidia-smi

# Если не хватает — уменьшить beam_size в config.prod.yaml
```
