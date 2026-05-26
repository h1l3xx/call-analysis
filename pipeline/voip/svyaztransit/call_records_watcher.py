#!/usr/bin/env python3
"""
Stranzit Call Records Watcher - Автоматический загрузчик записей звонков
"""

import os
import time
import json
import sqlite3
import logging
import signal
import shutil
import psutil
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Dict, Optional

from stranzit_auth import StranzitAuth
from config import get_config

class CallRecord:
    """Модель записи звонка"""

    def __init__(self, data: dict):
        self.id = str(data.get('Id', ''))
        self.start_time = data.get('StartTime', '')
        self.end_time = data.get('EndTime', '')
        self.service_name = data.get('ServiceName', '')
        self.call_direction = data.get('CallDirection', '')
        self.file_name = data.get('FileName', '')
        self.full_file_name = data.get('FullFileName', '')
        self.call_parties = data.get('CallParties', '')
        self.duration_seconds = data.get('Duration', {}).get('TotalSeconds', 0)
        self.record_count = data.get('RecordCount', 0)
        self.server_ip = data.get('ServerIpAddress', '')
        self.root_folder = data.get('RootFolder', '')

    def get_human_readable_time(self) -> str:
        """Преобразовать timestamp в читаемый формат"""
        try:
            # /Date(1758446374000)/ -> timestamp
            if self.start_time.startswith('/Date('):
                timestamp = int(self.start_time[6:-2]) / 1000
                dt = datetime.fromtimestamp(timestamp)
                return dt.strftime('%Y-%m-%d %H:%M:%S')
        except:
            pass
        return self.start_time

    def get_duration_str(self) -> str:
        """Получить длительность в формате HH:MM:SS"""
        hours = int(self.duration_seconds // 3600)
        minutes = int((self.duration_seconds % 3600) // 60)
        seconds = int(self.duration_seconds % 60)
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}"

class GracefulKiller:
    """Класс для graceful shutdown при получении сигналов SIGINT/SIGTERM"""

    kill_now = False

    def __init__(self):
        signal.signal(signal.SIGINT, self.exit_gracefully)
        signal.signal(signal.SIGTERM, self.exit_gracefully)

    def exit_gracefully(self, *args):
        """Обработчик сигналов завершения"""
        self.kill_now = True

class DatabaseManager:
    """Менеджер базы данных для отслеживания загруженных файлов"""

    def __init__(self, db_path: str = "stranzit_calls.db"):
        self.db_path = db_path
        self.init_db()

    def init_db(self):
        """Инициализация базы данных"""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute('''
                CREATE TABLE IF NOT EXISTS downloaded_records (
                    id TEXT PRIMARY KEY,
                    file_name TEXT,
                    start_time TEXT,
                    call_direction TEXT,
                    duration_seconds INTEGER,
                    downloaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    local_path TEXT,
                    uploaded_at TIMESTAMP,
                    upload_status TEXT DEFAULT 'pending'
                )
            ''')
            # Миграция: добавляем столбцы, если таблица уже существовала без них
            for col_def in (
                "ALTER TABLE downloaded_records ADD COLUMN uploaded_at TIMESTAMP",
                "ALTER TABLE downloaded_records ADD COLUMN upload_status TEXT DEFAULT 'pending'",
            ):
                try:
                    conn.execute(col_def)
                except Exception:
                    pass
            conn.commit()

    def is_record_downloaded(self, local_path: str) -> bool:
        """Проверить, была ли запись уже загружена по локальному пути"""

        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                "SELECT id FROM downloaded_records WHERE local_path = ?",
                (local_path,)
            )
            return cursor.fetchone() is not None

    def mark_record_downloaded(self, record: CallRecord, local_path: str):
        """Отметить запись как загруженную"""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute('''
                INSERT OR REPLACE INTO downloaded_records
                (id, file_name, start_time, call_direction, duration_seconds, local_path)
                VALUES (?, ?, ?, ?, ?, ?)
            ''', (
                record.id,
                record.file_name,
                record.get_human_readable_time(),
                record.call_direction,
                record.duration_seconds,
                local_path
            ))
            conn.commit()

    def mark_record_uploaded(self, local_path: str, success: bool) -> None:
        """Зафиксировать результат загрузки в Scanovich."""
        status = "done" if success else "failed"
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE downloaded_records "
                "SET uploaded_at = CURRENT_TIMESTAMP, upload_status = ? "
                "WHERE local_path = ?",
                (status, local_path),
            )
            conn.commit()

    def get_stats(self) -> dict:
        """Получить статистику загрузок"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute("SELECT COUNT(*) FROM downloaded_records")
            total = cursor.fetchone()[0]

            cursor = conn.execute("SELECT COUNT(*) FROM downloaded_records WHERE downloaded_at >= date('now', '-1 day')")
            today = cursor.fetchone()[0]

            return {
                'total_downloaded': total,
                'downloaded_today': today
            }

class CallRecordsWatcher:
    """Основной класс watcher-загрузчика"""

    def __init__(self):
        # Загружаем конфигурацию
        config = get_config()
        
        # Настройки из конфигурации
        stranzit_config = config.stranzit
        download_config = config.download
        filter_config = config.filters
        db_config = config.database
        log_config = config.logging
        
        self.auth = StranzitAuth(username=stranzit_config.username)
        self.download_dir = download_config.download_dir
        self.db = DatabaseManager(str(db_config.database_path))
        self.check_interval = download_config.check_interval
        self.schedule_times = download_config.schedule_times
        self.filter_hours_back = 0  # Не используется в новой версии, оставлено для совместимости

        # Настройка логирования с pathname:lineno
        log_format = log_config.format
        log_handlers = [logging.StreamHandler()]
        if log_config.log_file:
            log_handlers.append(logging.FileHandler(log_config.log_file))
        else:
            log_handlers.append(logging.FileHandler('watcher.log'))
        
        logging.basicConfig(
            level=getattr(logging, log_config.level),
            format=log_format,
            handlers=log_handlers,
            force=True,  # Перезаписываем существующую конфигурацию
        )
        self.logger = logging.getLogger(__name__)

        # Создание директории для загрузок (уже создается в валидаторе)

        # Инициализация graceful killer для корректного завершения
        self.killer = GracefulKiller()

        # Мониторинг ресурсов
        self.last_cleanup = datetime.now()

        # Сохраняем конфигурацию фильтров для использования
        self.filter_config = filter_config

        # Scanovich API uploader (опционально)
        scanovich_cfg = config.scanovich
        self.uploader = None
        self.delete_after_upload = False
        if scanovich_cfg.enabled:
            from scanovich_uploader import ScanovichUploader
            self.uploader = ScanovichUploader(
                url=scanovich_cfg.url,
                email=scanovich_cfg.email,
                password=scanovich_cfg.password,
            )
            self.delete_after_upload = scanovich_cfg.delete_after_upload
            self.logger.info(
                "Интеграция со Scanovich включена: %s (delete_after_upload=%s)",
                scanovich_cfg.url, self.delete_after_upload,
            )
        else:
            self.logger.info(
                "Интеграция со Scanovich отключена "
                "(задайте SCANOVICH_URL, SCANOVICH_EMAIL, SCANOVICH_PASSWORD для включения)"
            )

    def check_disk_space(self) -> dict:
        """Проверить свободное место на диске"""
        stat = os.statvfs(self.download_dir)
        total_gb = (stat.f_bavail * stat.f_frsize) / (1024**3)
        free_gb = (stat.f_bavail * stat.f_frsize) / (1024**3)
        used_gb = ((stat.f_blocks - stat.f_bavail) * stat.f_frsize) / (1024**3)
        usage_percent = ((stat.f_blocks - stat.f_bavail) / stat.f_blocks) * 100

        return {
            'total_gb': total_gb,
            'free_gb': free_gb,
            'used_gb': used_gb,
            'usage_percent': usage_percent
        }

    def check_memory_usage(self) -> dict:
        """Проверить использование памяти процессом"""
        process = psutil.Process()
        memory_info = process.memory_info()

        return {
            'rss_mb': memory_info.rss / 1024 / 1024,  # Resident Set Size
            'vms_mb': memory_info.vms / 1024 / 1024,  # Virtual Memory Size
            'cpu_percent': process.cpu_percent()
        }

    def cleanup_old_logs(self, days_to_keep: int = 7):
        """Очистить старые логи"""
        log_files = ['watcher.log', 'watcher.log.1', 'watcher.log.2', 'watcher.log.3', 'watcher.log.4']

        for log_file in log_files:
            if os.path.exists(log_file):
                file_age = datetime.now() - datetime.fromtimestamp(os.path.getmtime(log_file))
                if file_age.days > days_to_keep:
                    try:
                        os.remove(log_file)
                        self.logger.info(f"Удален старый лог: {log_file} (возраст: {file_age.days} дней)")
                    except Exception as e:
                        self.logger.warning(f"Не удалось удалить лог {log_file}: {e}")

    def cleanup_old_downloads(self, days_to_keep: int = 30):
        """Очистить старые файлы загрузок"""
        if not os.path.exists(self.download_dir):
            return

        cutoff_date = datetime.now() - timedelta(days=days_to_keep)
        cleaned_count = 0

        for file_path in Path(self.download_dir).glob("*.mp3"):
            try:
                file_date = datetime.fromtimestamp(file_path.stat().st_mtime)
                if file_date < cutoff_date:
                    file_path.unlink()
                    cleaned_count += 1
                    self.logger.info(f"Удален старый файл: {file_path.name} (дата: {file_date.strftime('%Y-%m-%d')})")
            except Exception as e:
                self.logger.warning(f"Не удалось удалить файл {file_path}: {e}")

        if cleaned_count > 0:
            self.logger.info(f"Очищено старых файлов: {cleaned_count}")

    def perform_maintenance(self):
        """Выполнить плановое обслуживание"""
        now = datetime.now()

        # Очищать логи раз в день
        if (now - self.last_cleanup).total_seconds() >= 24 * 3600:
            self.cleanup_old_logs()
            self.cleanup_old_downloads()
            self.last_cleanup = now

            # Логировать состояние системы
            disk_info = self.check_disk_space()
            memory_info = self.check_memory_usage()

            self.logger.info(f"Системный статус - Диск: {disk_info['free_gb']:.1f}GB свободно "
                           f"({disk_info['usage_percent']:.1f}%), Память: {memory_info['rss_mb']:.1f}MB")

    def should_shutdown_gracefully(self) -> bool:
        """Проверить, нужно ли завершить работу"""
        return self.killer.kill_now

    def _resolve_datetime_setting(self, value: str, now: datetime) -> datetime:
        """Преобразовать настройку времени в datetime."""

        if not value:
            return now

        normalized = value.strip().lower()

        if normalized in {'now', 'текущая_дата'}:
            return now
        if normalized in {'today_start', 'сегодня_00:00'}:
            return now.replace(hour=0, minute=0, second=0, microsecond=0)

        try:
            return datetime.strptime(value.strip(), '%d.%m.%Y %H:%M')
        except ValueError:
            self.logger.warning(
                "Некорректное значение времени '%s'. Используем текущее время.",
                value,
            )
            return now

    def _resolve_duration_operator(self, value: str) -> str:
        """Преобразовать оператор длительности в код API."""

        mapping = {
            '>=': '1',
            'gte': '1',
            '⩾': '1',
            '<=': '2',
            'lte': '2',
            '⩽': '2',
            '==': '3',
            '=': '3',
            'eq': '3',
        }

        normalized = value.strip().lower()

        if normalized in mapping:
            return mapping[normalized]
        if normalized in {'0', '1', '2', '3'}:
            return normalized

        self.logger.warning(
            "Некорректный оператор длительности '%s'. Фильтр отключен.", value
        )
        return '0'

    def _resolve_direction(self, value: str) -> str:
        """Преобразовать направление звонка в код API."""

        mapping = {
            'any': '0',
            'любой': '0',
            'all': '0',
            'incoming': '1',
            'входящий': '1',
            'in': '1',
            'outgoing': '2',
            'исходящий': '2',
            'out': '2',
        }

        normalized = value.strip().lower()

        if normalized in mapping:
            return mapping[normalized]
        if normalized in {'0', '1', '2'}:
            return normalized

        self.logger.warning(
            "Некорректное направление звонка '%s'. Используем 'любой'.", value
        )
        return '0'

    def build_filters(self) -> Dict[str, str]:
        """Собрать фильтры запроса из конфигурации."""

        now = datetime.now()
        start_setting = self.filter_config.start
        end_setting = self.filter_config.end

        start_dt = self._resolve_datetime_setting(start_setting, now)
        end_dt = self._resolve_datetime_setting(end_setting, now)

        if start_dt > end_dt:
            self.logger.warning(
                "Дата начала позже даты окончания (%s > %s). Значения будут поменяны местами.",
                start_dt,
                end_dt,
            )
            start_dt, end_dt = end_dt, start_dt

        filters: Dict[str, str] = {
            'start_date': start_dt.strftime('%d.%m.%Y %H:%M'),
            'end_date': end_dt.strftime('%d.%m.%Y %H:%M'),
            'records_per_page': str(self.filter_config.records_per_page),
        }

        # Фильтр по телефону (если есть в ENV для обратной совместимости)
        phone = os.getenv('CALL_FILTER_PHONE', '').strip()
        if phone:
            filters['phone_number'] = phone

        direction_raw = self.filter_config.direction
        if direction_raw:
            filters['direction'] = self._resolve_direction(direction_raw)

        duration_op_raw = self.filter_config.duration_op
        duration_value = self.filter_config.duration
        if duration_op_raw:
            duration_code = self._resolve_duration_operator(duration_op_raw)
            if duration_code != '0':
                filters['duration_op'] = duration_code
                filters['duration'] = duration_value or '00:00:00'

        self.logger.info(
            "Фильтры запроса: %s", {k: v for k, v in filters.items() if k != 'phone_number'}
        )

        if 'phone_number' in filters:
            self.logger.info(
                "Фильтр по номеру включен (первые цифры): %s",
                filters['phone_number'][:6] + '***' if len(filters['phone_number']) > 6 else filters['phone_number'],
            )

        return filters

    def login(self) -> bool:
        """Вход в систему"""
        config = get_config()
        stranzit_config = config.stranzit
        
        username = stranzit_config.username
        password = stranzit_config.password

        if not username or not password:
            self.logger.error("Не установлены credentials")
            return False

        if self.auth.login(username, password):
            self.logger.info("✅ Успешный вход в систему")
            return True
        else:
            self.logger.error("❌ Ошибка входа")
            return False

    def get_recent_records(self, hours_back: int = 24) -> List[CallRecord]:
        """Получить недавние записи звонков"""
        end_time = datetime.now()
        start_time = end_time - timedelta(hours=hours_back)

        filters = {
            'start_date': start_time.strftime('%d.%m.%Y %H:%M'),
            'end_date': end_time.strftime('%d.%m.%Y %H:%M'),
            'records_per_page': str(self.filter_config.records_per_page),
        }

        self.logger.info("Поиск записей за последние %d часов", hours_back)
        return self.get_filtered_records(filters)

    def _fetch_page(self, params: dict, page: int) -> List[CallRecord]:
        """Запросить одну страницу записей у Связьтранзита. Возвращает пустой список при ошибке."""
        from bs4 import BeautifulSoup

        page_params = {**params, 'PageNumber': str(page)}
        try:
            response = self.auth.session.post(
                "https://lk.stranzit.ru/CallRecords/IndexGet",
                data=page_params,
                timeout=30,
            )
        except Exception as exc:
            self.logger.error("Ошибка сети при запросе страницы %d: %s", page, exc)
            return []

        if response.status_code != 200:
            self.logger.error("Ошибка получения страницы %d: HTTP %d", page, response.status_code)
            return []

        soup = BeautifulSoup(response.text, 'html.parser')
        records_input = soup.find('input', {'name': 'callRecords'})
        if not records_input:
            return []

        try:
            records_data = json.loads(records_input.get('value', '[]'))
            return [CallRecord(data) for data in records_data]
        except json.JSONDecodeError as exc:
            self.logger.error("Ошибка парсинга JSON на странице %d: %s", page, exc)
            return []

    def get_filtered_records(self, filters: dict) -> List[CallRecord]:
        """Получить записи звонков с пользовательскими фильтрами (все страницы)."""
        params = {
            'StartDateTimeStr': '14.09.2025 00:00',
            'EndDateTimeStr': '21.09.2025 23:59',
            'PhoneNumberPart': '',
            'CallDirection': '0',  # 0=Любой, 1=Входящий, 2=Исходящий
            'CallDurationExpression': '0',  # 0=не выбрано, 1=>=, 2=<, 3==
            'CallDuration': '00:00:00',
            'RecordsPerPage': '50',
            'ShortCodesJson': '{}',
        }

        if 'start_date' in filters:
            params['StartDateTimeStr'] = filters['start_date']
        if 'end_date' in filters:
            params['EndDateTimeStr'] = filters['end_date']
        if 'phone_number' in filters:
            params['PhoneNumberPart'] = filters['phone_number']
        if 'direction' in filters:
            params['CallDirection'] = str(filters['direction'])
        if 'duration_op' in filters:
            params['CallDurationExpression'] = str(filters['duration_op'])
        if 'duration' in filters:
            params['CallDuration'] = filters['duration']
        if 'records_per_page' in filters:
            params['RecordsPerPage'] = str(filters['records_per_page'])

        per_page = int(params['RecordsPerPage'])

        self.logger.info(
            "Запрос записей: %s — %s, направление=%s, страниц_размер=%d",
            params['StartDateTimeStr'], params['EndDateTimeStr'],
            params['CallDirection'], per_page,
        )

        all_records: List[CallRecord] = []
        page = 1
        while True:
            self.logger.debug("Загрузка страницы %d…", page)
            page_records = self._fetch_page(params, page)

            all_records.extend(page_records)
            self.logger.info("Страница %d: получено %d записей (всего: %d)", page, len(page_records), len(all_records))

            # Если записей меньше, чем размер страницы — это последняя страница
            if len(page_records) < per_page:
                break

            page += 1

        self.logger.info("Итого найдено %d записей за %d стр.", len(all_records), page)
        return all_records

    def generate_readable_filename(self, record: CallRecord) -> str:
        """Создать читаемое имя файла, совместимое с PhoneParser.

        Формат: ДД.ММ.ГГГГ_ЧЧ-ММ-СС_<CallParties>_<Направление>.mp3
        Скобки в CallParties (например «1586 (504750)») обязательно сохраняются —
        они нужны PhoneParser для определения внутреннего номера менеджера.
        """
        try:
            if record.start_time.startswith('/Date('):
                timestamp = int(record.start_time[6:-2]) / 1000
                dt = datetime.fromtimestamp(timestamp)

                date_str = dt.strftime('%d.%m.%Y')
                time_str = dt.strftime('%H-%M-%S')

                parties = record.call_parties or "unknown"
                direction = record.call_direction or "unknown"

                filename = f"{date_str}_{time_str}_{parties}_{direction}.mp3"
                # Заменяем только символы, запрещённые в именах файлов Linux/macOS.
                # Скобки, запятые и пробелы — оставляем: они нужны PhoneParser.
                import re as _re
                filename = _re.sub(r'[/\\:*?"<>|]', '_', filename)
                return filename
            else:
                return record.file_name.replace('.wav', '.mp3')

        except Exception as e:
            self.logger.warning(f"Ошибка генерации имени файла для {record.id}: {e}")
            return record.file_name.replace('.wav', '.mp3')

    def download_record(self, record: CallRecord) -> Optional[str]:
        """Скачать запись звонка"""
        if not record.file_name:
            return None

        # Формируем правильный URL с параметрами (как в HTML)
        params = {
            'StartTime': record.get_human_readable_time(),
            'EndTime': record.get_human_readable_time(),  # Приблизительно
            'ServiceName': record.service_name,
            'CallDirection': record.call_direction,
            'FileName': record.file_name,
            'FullFileName': record.full_file_name,
            'CallParties': record.call_parties,
            'RecordCount': str(record.record_count),
            'ServerIpAddress': record.server_ip,
            'RootFolder': record.root_folder,
            'Duration': record.get_duration_str(),
            'ErrorNumber': '0'
        }

        download_url = f"https://lk.stranzit.ru/CallRecords/DownloadRecord/{record.id}"

        try:
            response = self.auth.session.get(download_url, params=params, timeout=30)

            if response.status_code == 200:
                # Используем читаемое имя файла (как на сайте)
                filename = self.generate_readable_filename(record)
                filepath = os.path.join(self.download_dir, filename)

                with open(filepath, 'wb') as f:
                    f.write(response.content)

                self.logger.info(f"✅ Скачан: {filename} ({len(response.content)} bytes)")
                return filepath
            else:
                self.logger.warning(f"❌ Ошибка скачивания {record.id}: {response.status_code}")
                if response.status_code == 500:
                    self.logger.debug(f"URL: {response.url}")

        except Exception as e:
            self.logger.error(f"❌ Ошибка при скачивании {record.id}: {e}")

        return None

    def process_new_records(self, records: List[CallRecord]) -> int:
        """Обработать новые записи: скачать всё, затем отправить одним батчем в Scanovich."""
        downloaded: List[tuple] = []  # (local_path, filename, record)

        for record in records:
            filename = self.generate_readable_filename(record)
            local_path = os.path.join(self.download_dir, filename)

            if self.db.is_record_downloaded(local_path):
                self.logger.debug("Запись %s уже загружена", record.file_name)
                continue

            self.logger.info(
                "🎵 Новая запись: %s — %s — %s",
                record.file_name, record.call_direction, record.get_duration_str(),
            )

            downloaded_path = self.download_record(record)
            if downloaded_path:
                self.db.mark_record_downloaded(record, downloaded_path)
                downloaded.append((downloaded_path, filename, record))
                time.sleep(1)

        if not downloaded:
            return 0

        # Загрузка в Scanovich одним батчем (если интеграция настроена)
        if self.uploader is not None and downloaded:
            batch_files = [(path, fname) for path, fname, _ in downloaded]
            upload_results = self.uploader.upload_batch(batch_files)

            for local_path, filename, _ in downloaded:
                upload_ok = upload_results.get(local_path, False)
                self.db.mark_record_uploaded(local_path, upload_ok)

                if upload_ok and self.delete_after_upload:
                    try:
                        os.remove(local_path)
                        self.logger.debug("Локальный файл удалён после загрузки: %s", filename)
                    except Exception as exc:
                        self.logger.warning("Не удалось удалить файл %s: %s", filename, exc)

        return len(downloaded)

    def run_once(self, hours_back: Optional[int] = None) -> int:
        """Один цикл проверки и загрузки"""
        self.logger.info("🔍 Запуск цикла проверки...")

        if not self.login():
            return 0

        target_hours = (
            hours_back
            if hours_back is not None
            else (self.filter_hours_back or None)
        )

        if target_hours:
            records = self.get_recent_records(hours_back=target_hours)
        else:
            records = self.get_filtered_records(self.build_filters())

        if not records:
            self.logger.info("Новых записей не найдено")
            return 0

        downloaded = self.process_new_records(records)
        self.logger.info(f"📊 Цикл завершен: загружено {downloaded} файлов")

        return downloaded

    # ── расписание ────────────────────────────────────────────────

    def _parse_schedule(self) -> list[tuple[int, int]]:
        """Разобрать SCHEDULE_TIMES='09:00,18:00' → [(9,0),(18,0)]."""
        raw = self.schedule_times.strip()
        if not raw:
            return []
        result = []
        for part in raw.split(","):
            part = part.strip()
            try:
                h, m = part.split(":")
                result.append((int(h), int(m)))
            except ValueError:
                self.logger.warning("Неверный формат времени в SCHEDULE_TIMES: %s", part)
        return sorted(result)

    def _seconds_until_next(self, schedule: list[tuple[int, int]]) -> int:
        """Сколько секунд до ближайшего запуска по расписанию (локальное время контейнера)."""
        now = datetime.now()
        today_slots = [
            now.replace(hour=h, minute=m, second=0, microsecond=0)
            for h, m in schedule
        ]
        future = [s for s in today_slots if s > now]
        if future:
            return max(1, int((future[0] - now).total_seconds()))
        # Все слоты сегодня прошли — ждём до первого слота завтра
        tomorrow_first = today_slots[0] + timedelta(days=1)
        return max(1, int((tomorrow_first - now).total_seconds()))

    # ── основной цикл ─────────────────────────────────────────────

    def run_continuous(self):
        """Непрерывный режим работы с мониторингом и graceful shutdown."""
        schedule = self._parse_schedule()

        if schedule:
            slots_str = ", ".join(f"{h:02d}:{m:02d}" for h, m in schedule)
            self.logger.info(
                "🚀 Запуск по расписанию: %s (часовой пояс контейнера: %s)",
                slots_str, datetime.now().astimezone().tzname(),
            )
        else:
            self.logger.info(
                "🚀 Запуск непрерывного режима, интервал: %d с", self.check_interval,
            )

        try:
            while not self.should_shutdown_gracefully():
                self.perform_maintenance()

                disk_info = self.check_disk_space()
                if disk_info['free_gb'] < 0.5:
                    self.logger.error("🚨 Критически мало места на диске, жду 5 минут")
                    time.sleep(300)
                    continue
                elif disk_info['free_gb'] < 1.0:
                    self.logger.warning("⚠️ Мало места на диске: %.1f GB", disk_info['free_gb'])

                downloaded = self.run_once(hours_back=self.filter_hours_back or None)

                stats = self.db.get_stats()
                self.logger.info("📈 Всего загружено: %d файлов", stats['total_downloaded'])

                if schedule:
                    wait_sec = self._seconds_until_next(schedule)
                    next_dt = datetime.now() + timedelta(seconds=wait_sec)
                    self.logger.info(
                        "⏰ Следующий запуск: %s (через %d мин)",
                        next_dt.strftime("%d.%m %H:%M"), wait_sec // 60,
                    )
                    time.sleep(wait_sec)
                else:
                    self.logger.info("⏰ Ожидание %d секунд...", self.check_interval)
                    time.sleep(self.check_interval)

        except KeyboardInterrupt:
            self.logger.info("🛑 Остановка watcher (Ctrl+C)")
        except Exception as e:
            self.logger.error("❌ Критическая ошибка: %s", e)
            try:
                self._send_error_notification(str(e))
            except Exception:
                pass

        self.logger.info("👋 Watcher завершен")

    def _send_error_notification(self, error_message: str):
        """Отправить уведомление об ошибке (если настроены webhook/email)"""
        # Проверка webhook
        webhook_url = os.getenv('WEBHOOK_URL')
        if webhook_url:
            try:
                import requests
                payload = {
                    'event': 'watcher_error',
                    'timestamp': datetime.now().isoformat(),
                    'error': error_message
                }
                requests.post(webhook_url, json=payload, timeout=5)
            except Exception as e:
                self.logger.warning(f"Не удалось отправить webhook уведомление: {e}")

        # Проверка email
        smtp_server = os.getenv('SMTP_SERVER')
        if smtp_server:
            try:
                import smtplib
                from email.mime.text import MIMEText

                msg = MIMEText(f"Ошибка в Stranzit Audio Downloader:\n\n{error_message}")
                msg['Subject'] = "🚨 Ошибка в загрузчике звонков"
                msg['From'] = os.getenv('SMTP_USER', 'watcher@localhost')
                msg['To'] = os.getenv('ALERT_EMAIL', os.getenv('SMTP_USER'))

                server = smtplib.SMTP(smtp_server)
                server.login(os.getenv('SMTP_USER'), os.getenv('SMTP_PASS', ''))
                server.sendmail(msg['From'], msg['To'], msg.as_string())
                server.quit()
            except Exception as e:
                self.logger.warning(f"Не удалось отправить email уведомление: {e}")

    def health_check(self) -> dict:
        """
        Проверка состояния системы (health check).
        
        Returns:
            dict: Статус системы с информацией о версиях, подключениях и конфигурации
        """
        import sys
        import sqlite3
        import requests
        
        health = {
            "status": "healthy",
            "timestamp": datetime.now().isoformat(),
            "versions": {
                "python": sys.version.split()[0],
                "requests": requests.__version__,
                "psutil": psutil.__version__,
            },
            "configuration": {
                "download_dir": str(self.download_dir),
                "check_interval": self.check_interval,
                "filter_direction": self.filter_config.direction,
                "filter_start": self.filter_config.start,
                "filter_end": self.filter_config.end,
            },
            "checks": {},
        }
        
        # Проверка базы данных
        try:
            with sqlite3.connect(self.db.db_path) as conn:
                cursor = conn.execute("SELECT COUNT(*) FROM downloaded_records")
                total_records = cursor.fetchone()[0]
            health["checks"]["database"] = {
                "status": "ok",
                "path": str(self.db.db_path),
                "total_records": total_records,
            }
        except Exception as e:
            health["checks"]["database"] = {
                "status": "error",
                "error": str(e),
            }
            health["status"] = "degraded"
        
        # Проверка директории загрузок
        try:
            download_path = Path(self.download_dir)
            if download_path.exists() and download_path.is_dir():
                # Проверяем доступность записи
                test_file = download_path / ".health_check"
                test_file.touch()
                test_file.unlink()
                health["checks"]["download_dir"] = {
                    "status": "ok",
                    "path": str(download_path),
                    "writable": True,
                }
            else:
                health["checks"]["download_dir"] = {
                    "status": "error",
                    "error": "Directory does not exist",
                }
                health["status"] = "degraded"
        except Exception as e:
            health["checks"]["download_dir"] = {
                "status": "error",
                "error": str(e),
            }
            health["status"] = "degraded"
        
        # Проверка дискового пространства
        try:
            disk_info = self.check_disk_space()
            health["checks"]["disk_space"] = {
                "status": "ok" if disk_info["free_gb"] > 1.0 else "warning",
                "free_gb": round(disk_info["free_gb"], 2),
                "usage_percent": round(disk_info["usage_percent"], 1),
            }
            if disk_info["free_gb"] < 1.0:
                health["status"] = "degraded"
        except Exception as e:
            health["checks"]["disk_space"] = {
                "status": "error",
                "error": str(e),
            }
            health["status"] = "degraded"
        
        # Проверка подключения к Stranzit API
        try:
            if self.auth.is_authenticated:
                health["checks"]["stranzit_auth"] = {
                    "status": "ok",
                    "authenticated": True,
                    "base_url": self.auth.BASE_URL,
                }
            else:
                # Пробуем аутентифицироваться
                if self.login():
                    health["checks"]["stranzit_auth"] = {
                        "status": "ok",
                        "authenticated": True,
                        "base_url": self.auth.BASE_URL,
                    }
                else:
                    health["checks"]["stranzit_auth"] = {
                        "status": "error",
                        "authenticated": False,
                        "error": "Authentication failed",
                    }
                    health["status"] = "unhealthy"
        except Exception as e:
            health["checks"]["stranzit_auth"] = {
                "status": "error",
                "error": str(e),
            }
            health["status"] = "unhealthy"
        
        return health


def main():
    """Главная функция"""
    import argparse

    parser = argparse.ArgumentParser(description='Stranzit Call Records Watcher')
    parser.add_argument('--once', action='store_true', help='Запустить один цикл и выйти')
    parser.add_argument('--hours', type=int, default=24, help='Количество часов для поиска (по умолчанию 24)')
    parser.add_argument('--health', action='store_true', help='Выполнить health check и вывести статус системы')

    args = parser.parse_args()

    watcher = CallRecordsWatcher()
    
    # Health check команда
    if args.health:
        try:
            health = watcher.health_check()
            
            print("\n=== Health Check ===")
            print(f"Status: {health['status']}")
            print(f"Timestamp: {health['timestamp']}")
            print("\nVersions:")
            for key, value in health['versions'].items():
                print(f"  {key}: {value}")
            print("\nConfiguration:")
            for key, value in health['configuration'].items():
                print(f"  {key}: {value}")
            print("\nChecks:")
            for check_name, check_result in health['checks'].items():
                status = check_result.get('status', 'unknown')
                print(f"  {check_name}: {status}")
                if 'error' in check_result:
                    print(f"    Error: {check_result['error']}")
                elif check_name == 'database' and 'total_records' in check_result:
                    print(f"    Total records: {check_result['total_records']}")
                elif check_name == 'download_dir' and 'writable' in check_result:
                    print(f"    Writable: {check_result['writable']}")
                elif check_name == 'disk_space' and 'free_gb' in check_result:
                    print(f"    Free: {check_result['free_gb']} GB ({check_result['usage_percent']}% used)")
                elif check_name == 'stranzit_auth' and 'authenticated' in check_result:
                    print(f"    Authenticated: {check_result['authenticated']}")
                    print(f"    Base URL: {check_result.get('base_url', 'N/A')}")
            
            import sys
            sys.exit(0 if health['status'] == 'healthy' else 1)
        except Exception as e:
            print(f"❌ Ошибка при выполнении health check: {e}")
            import sys
            sys.exit(1)

    if args.once:
        # Один цикл
        downloaded = watcher.run_once()
        print(f"Загружено файлов: {downloaded}")
    else:
        # Непрерывный режим
        watcher.run_continuous()

if __name__ == "__main__":
    main()
