"""
Предобработка аудио: wake-signal, нормализация громкости, multi-format support.
"""

import base64
import json
import logging
import tempfile
from pathlib import Path
from typing import Optional

import librosa
import numpy as np
import soundfile as sf

from src.config_validation import ASRConfig

logger = logging.getLogger(__name__)


class AudioPreprocessor:
    """Предобработка аудиофайлов для улучшения качества транскрипции."""

    def __init__(self, config: ASRConfig):
        """
        Инициализация препроцессора.

        Args:
            config: ASR конфигурация
        """
        self.config = config
        self.preprocessing_config = config.preprocessing
        self.target_sr = self.preprocessing_config.target_sample_rate

        wake_cfg = self.preprocessing_config.wake_signal
        use_wake = wake_cfg["enabled"] and not config.vad_filter
        if wake_cfg["enabled"] and config.vad_filter:
            logger.warning(
                "Wake-signal отключен: несовместим с vad_filter=true "
                "(шум сбивает детектор начала речи)"
            )

        if use_wake:
            self.wake_signal = self._generate_wake_signal(
                samples=wake_cfg["samples"],
                target_rms=wake_cfg["rms"],
            )
        else:
            self.wake_signal = None

        logger.info(
            f"✓ AudioPreprocessor инициализирован: SR={self.target_sr}, "
            f"wake_signal={'enabled' if self.wake_signal is not None else 'disabled'}, "
            f"vad_filter={config.vad_filter}"
        )
        
        # Статистика форматов (для мониторинга источников)
        self.format_stats = {
            "raw_audio": 0,
            "json_wrapped": 0,
            "decoded_files": 0
        }

    def _detect_and_decode_json_audio(self, file_path: Path) -> Path:
        """
        Автоматическое определение и декодирование JSON-wrapped аудио.
        
        Поддерживает форматы:
        - {"data": "base64_encoded_mp3"} (Asterisk/VoIP systems)
        - Сырые MP3/WAV/M4A файлы
        
        Args:
            file_path: Путь к входному файлу
            
        Returns:
            Path: Путь к сырому аудио (оригинальный или декодированный)
            
        Raises:
            ValueError: Если файл — JSON, но аудио из него извлечь невозможно
        """
        with open(file_path, 'rb') as f:
            header = f.read(200)
        
        # Проверка 1: Начинается с { или [ → вероятно JSON-wrapped audio
        if header.strip().startswith((b'{', b'[')):
            logger.info(f"Обнаружен JSON-wrapped audio: {file_path.name}")
            return self._decode_json_audio(file_path)
        
        # Проверка 2: Стандартные аудио-заголовки
        is_mp3 = (header[0:1] == b'\xff' and len(header) > 1 and 
                 (header[1] & 0xE0) == 0xE0)
        
        if (header.startswith(b'ID3') or 
            is_mp3 or
            header.startswith(b'RIFF') or
            header.startswith(b'OggS') or
            header.startswith(b'fLaC') or
            b'ftyp' in header[:20]):
            
            logger.debug(f"Стандартный аудиофайл: {file_path.name}")
            self.format_stats["raw_audio"] += 1
            return file_path
        
        # Неизвестный формат - пытаемся обработать как обычный
        logger.warning(
            f"Неизвестный формат файла: {file_path.name}, "
            f"header: {header[:20].hex()}, size: {file_path.stat().st_size} bytes"
        )
        self.format_stats["raw_audio"] += 1
        return file_path

    def _decode_json_audio(self, file_path: Path) -> Path:
        """Извлечь base64-encoded аудио из JSON-файла. Бросает ValueError при неудаче."""
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except json.JSONDecodeError as e:
            raise ValueError(f"CORRUPTED_AUDIO: Invalid JSON format: {e}")
        
        if not ('data' in data and isinstance(data['data'], str)):
            raise ValueError(
                f"CORRUPTED_AUDIO: JSON missing 'data' field, keys={list(data.keys())}"
            )
        
        b64_audio = data['data']
        if not b64_audio or not b64_audio.strip():
            raise ValueError("CORRUPTED_AUDIO: Empty data field in JSON")
        
        try:
            audio_bytes = base64.b64decode(b64_audio)
        except Exception as e:
            raise ValueError(f"CORRUPTED_AUDIO: Invalid base64: {e}")
        
        if len(audio_bytes) < 100:
            raise ValueError(
                f"CORRUPTED_AUDIO: Decoded audio too small ({len(audio_bytes)} bytes)"
            )
        
        temp_file = tempfile.NamedTemporaryFile(
            suffix=".mp3", delete=False, dir=tempfile.gettempdir()
        )
        temp_file.write(audio_bytes)
        temp_file.close()
        
        logger.info(
            f"JSON декодирован: {len(b64_audio)} chars -> "
            f"{len(audio_bytes)} bytes -> {temp_file.name}"
        )
        self.format_stats["json_wrapped"] += 1
        self.format_stats["decoded_files"] += 1
        return Path(temp_file.name)

    def _generate_wake_signal(self, samples: int, target_rms: float) -> np.ndarray:
        """
        Генерация wake-signal (универсальный громкий сигнал).

        Args:
            samples: Количество сэмплов
            target_rms: Целевой RMS

        Returns:
            np.ndarray: Wake-signal
        """
        # Генерация белого шума
        signal = np.random.uniform(-1, 1, samples).astype(np.float32)

        # Нормализация до целевого RMS
        current_rms = np.sqrt(np.mean(signal**2))
        if current_rms > 0:
            signal = signal * (target_rms / current_rms)

        logger.debug(f"Wake-signal сгенерирован: {samples} samples, RMS={target_rms}")
        return signal

    def _normalize_volume(self, audio: np.ndarray) -> np.ndarray:
        """
        Peak normalization (нормализация по пику).

        Args:
            audio: Входной аудиосигнал

        Returns:
            np.ndarray: Нормализованный сигнал
        """
        max_val = np.abs(audio).max()
        if max_val > 0:
            # Нормализуем до 0.95 от максимума (небольшой headroom)
            audio = audio * (0.95 / max_val)
        return audio

    def preprocess(self, input_path: str) -> str:
        """
        Полная предобработка аудиофайла.

        Args:
            input_path: Путь к входному аудиофайлу

        Returns:
            str: Путь к обработанному временному WAV файлу

        Raises:
            FileNotFoundError: Если файл не найден
            ValueError: Если файл некорректен
        """
        input_path_obj = Path(input_path)
        if not input_path_obj.exists():
            raise FileNotFoundError(f"Аудиофайл не найден: {input_path}")

        logger.info(f"Предобработка аудио: {input_path_obj.name}")

        try:
            # ⭐ НОВОЕ: Автоматическое определение и декодирование формата
            decoded_path = self._detect_and_decode_json_audio(input_path_obj)
            
            # Загрузка аудио с валидацией
            audio, sr = librosa.load(str(decoded_path), sr=self.target_sr, mono=True)
            
            # ⭐ ВАЛИДАЦИЯ: проверка sample rate и данных
            if sr is None or sr == 0:
                raise ValueError(f"Битый аудиофайл: invalid sample rate (sr={sr})")
            
            if audio is None or len(audio) == 0:
                raise ValueError(f"Битый аудиофайл: пустые аудиоданные")
            
            duration = len(audio) / sr
            if duration < 0.1:  # Минимум 0.1 секунда
                raise ValueError(f"Битый аудиофайл: слишком короткий ({duration:.3f}s)")
            
            logger.debug(
                f"Загружено: {len(audio)} samples, SR={sr}, длительность={duration:.2f}s"
            )

            # Wake-signal инъекция (в начало)
            if self.wake_signal is not None:
                audio = np.concatenate([self.wake_signal, audio])
                logger.debug("Wake-signal добавлен в начало")

            # Нормализация громкости
            if self.preprocessing_config.normalize_volume:
                audio = self._normalize_volume(audio)
                logger.debug("Громкость нормализована (peak normalization)")

            # Сохранение во временный WAV
            temp_file = tempfile.NamedTemporaryFile(
                suffix=".wav", delete=False, dir=tempfile.gettempdir()
            )
            sf.write(temp_file.name, audio, sr, subtype="PCM_16")
            logger.info(f"✓ Предобработка завершена: {temp_file.name}")
            
            # Очистка временного декодированного файла (если был)
            if decoded_path != input_path_obj and decoded_path.exists():
                try:
                    decoded_path.unlink()
                    logger.debug(f"Временный декодированный файл удалён: {decoded_path}")
                except Exception:
                    pass

            return temp_file.name

        except ValueError as e:
            logger.error(
                f"Аудио не прошло валидацию {input_path_obj.name}: {e}",
                exc_info=True,
            )
            raise
        except Exception as e:
            detail = str(e) or type(e).__name__
            logger.error(
                f"Ошибка предобработки аудио {input_path_obj.name} "
                f"[{type(e).__name__}]: {detail}",
                exc_info=True,
            )
            raise ValueError(
                f"Не удалось обработать аудио [{type(e).__name__}]: {detail}"
            ) from e

    def get_audio_duration(self, audio_path: str) -> Optional[float]:
        """
        Получить длительность аудиофайла.

        Args:
            audio_path: Путь к аудиофайлу

        Returns:
            float или None: Длительность в секундах
        """
        try:
            audio_path_obj = Path(audio_path)
            
            # ⭐ КРИТИЧНО: Декодируем JSON если нужно (как в preprocess)
            decoded_path = self._detect_and_decode_json_audio(audio_path_obj)
            
            # Получаем информацию о файле
            info = sf.info(str(decoded_path))
            duration = info.duration
            
            # Очистка временного файла
            if decoded_path != audio_path_obj and decoded_path.exists():
                try:
                    decoded_path.unlink()
                except Exception:
                    pass
            
            logger.debug(f"Длительность {audio_path_obj.name}: {duration:.2f}s")
            return duration
            
        except Exception as e:
            logger.warning(
                f"Не удалось получить длительность {audio_path} [{type(e).__name__}]: {e}",
                exc_info=True,
            )
            return None

