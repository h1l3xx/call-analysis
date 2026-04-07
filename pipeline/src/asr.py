"""
ASR модуль на базе Faster-Whisper Large V3.

GPU-оптимизированная транскрипция с метриками производительности.
Поддержка chunked-транскрипции для длинных аудиозаписей (>5 мин).
"""

import logging
import tempfile
import time
from pathlib import Path
from typing import Optional, Tuple

import soundfile as sf
from faster_whisper import WhisperModel

from src.config_validation import ASRConfig
from src.utils import GPUMonitor

logger = logging.getLogger(__name__)

LONG_AUDIO_THRESHOLD_SEC = 180  # 3 min — switch to chunked processing
CHUNK_DURATION_SEC = 240        # 4 min per chunk
CHUNK_OVERLAP_SEC = 15          # 15 sec overlap between chunks


class ASREngine:
    """Движок автоматического распознавания речи (Whisper Large V3)."""

    def __init__(self, config: ASRConfig, gpu_monitor: Optional[GPUMonitor] = None):
        """
        Инициализация ASR движка.

        Args:
            config: ASR конфигурация
            gpu_monitor: Монитор GPU (опционально)

        Raises:
            RuntimeError: Если GPU недоступна при device='cuda'
        """
        self.config = config
        self.gpu_monitor = gpu_monitor
        self.model = None

        # GPU guard: проверка устройства
        if config.device == "cuda":
            if gpu_monitor:
                gpu_monitor.check_device("cuda")
            else:
                import torch

                if not torch.cuda.is_available():
                    raise RuntimeError(
                        "CUDA недоступна! Только GPU-режим поддерживается."
                    )

        # Загрузка модели при инициализации (как в рабочем проекте)
        self._load_model()

        logger.info(f"✓ ASR движок инициализирован с моделью {config.model}")

    def _load_model(self):
        """Загрузить модель Whisper в GPU память (один раз при старте)."""
        if self.model is not None:
            return  # Модель уже загружена

        logger.info(f"Загрузка модели Whisper: {self.config.model}")
        start_time = time.time()

        try:
            # Параметры загрузки модели (точно как в рабочем ASR-time)
            model_kwargs = {
                "model_size_or_path": self.config.model,  # КЛЮЧЕВОЕ: первый параметр!
                "device": self.config.device,
                "compute_type": self.config.compute_type,
                "cpu_threads": self.config.cpu_threads,
            }
            
            # Для GPU указываем индекс устройства
            if self.config.device == "cuda":
                model_kwargs["device_index"] = getattr(self.config, "device_index", 0)
            
            self.model = WhisperModel(**model_kwargs)
            load_time = time.time() - start_time
            logger.info(
                f"✓ Модель загружена за {load_time:.2f}s: "
                f"device={self.config.device}, compute_type={self.config.compute_type}"
            )

            if self.gpu_monitor and self.config.device == "cuda":
                mem_info = self.gpu_monitor.get_memory_info()
                logger.info(
                    f"GPU память после загрузки: {mem_info['used_mb']} MB "
                    f"({mem_info['utilization_percent']}%)"
                )

        except Exception as e:
            logger.error(f"Ошибка загрузки модели Whisper: {e}")
            raise RuntimeError(f"Не удалось загрузить модель: {e}") from e

    def transcribe(
        self,
        audio_path: str,
        audio_duration: Optional[float] = None,
        word_timestamps: bool = False,
    ) -> Tuple[str, dict, list]:
        """
        Транскрибация аудиофайла. Автоматически переключается на
        chunked-режим для файлов длиннее LONG_AUDIO_THRESHOLD_SEC.

        Args:
            audio_path: Путь к аудиофайлу
            audio_duration: Длительность аудио (для расчета RTF)
            word_timestamps: Возвращать пословные таймкоды для диаризации

        Returns:
            Tuple[str, dict, list]: (текст, метрики, word_segments)
              word_segments пуст если word_timestamps=False

        Raises:
            FileNotFoundError: Если файл не найден
            ValueError: Если транскрипция не удалась
        """
        audio_path_obj = Path(audio_path)
        if not audio_path_obj.exists():
            raise FileNotFoundError(f"Аудиофайл не найден: {audio_path}")

        actual_duration = audio_duration
        if actual_duration is None:
            try:
                info = sf.info(audio_path)
                actual_duration = info.duration
            except Exception:
                pass

        if actual_duration and actual_duration > LONG_AUDIO_THRESHOLD_SEC:
            logger.info(
                f"Длинное аудио ({actual_duration:.0f}s > {LONG_AUDIO_THRESHOLD_SEC}s), "
                f"используем chunked-транскрипцию: {audio_path_obj.name}"
            )
            return self._transcribe_chunked(audio_path, actual_duration, word_timestamps)

        return self._transcribe_single(audio_path, audio_duration, word_timestamps)

    def _build_transcribe_params(self, word_timestamps: bool) -> dict:
        """Общие параметры для вызова model.transcribe."""
        params = {
            "beam_size": self.config.beam_size,
            "temperature": self.config.temperature,
            "vad_filter": self.config.vad_filter,
            "language": self.config.language,
            "word_timestamps": word_timestamps,
            "condition_on_previous_text": False,
            "hallucination_silence_threshold": 2.0,
            "no_speech_threshold": 0.95,
            "log_prob_threshold": -1.5,
            "compression_ratio_threshold": 2.8,
        }
        if self.config.vad_filter:
            params["vad_parameters"] = {
                "threshold": 0.3,
                "min_speech_duration_ms": 100,
                "min_silence_duration_ms": 300,
                "speech_pad_ms": 500,
            }
        if self.config.initial_prompt:
            params["initial_prompt"] = self.config.initial_prompt
        return params

    @staticmethod
    def _strip_hallucinations(text: str) -> str:
        """Remove known Whisper hallucination patterns from transcription."""
        import re

        hallucination_patterns = [
            r"^Продолжение следует\.{0,3}\s*",
            r"^и т\.?\s*д\.?\s*",
            r"^Субтитры\s+.*?\n?\s*",
            r"^Редактор субтитров.*?\n?\s*",
            r"^Подписывайтесь на канал.*?\n?\s*",
            r"^Спасибо за (просмотр|подписку|внимание)\.?\s*",
            r"^\.{2,}\s*",
            r"^Благодарю за внимание\.?\s*",
            r"^Музыка\.?\s*",
            r"^♪.*?♪\s*",
        ]

        cleaned = text
        for pattern in hallucination_patterns:
            cleaned = re.sub(pattern, "", cleaned, flags=re.IGNORECASE).strip()

        if cleaned != text:
            logger.info("Stripped hallucination artifacts from transcription start")

        return cleaned

    def _gpu_cleanup(self):
        if self.config.device == "cuda":
            import torch
            import gc
            torch.cuda.empty_cache()
            gc.collect()

    def _transcribe_single(
        self,
        audio_path: str,
        audio_duration: Optional[float] = None,
        word_timestamps: bool = False,
    ) -> Tuple[str, dict, list]:
        """Транскрипция одного файла целиком (для коротких аудио)."""
        audio_path_obj = Path(audio_path)
        logger.info(f"Транскрипция (single): {audio_path_obj.name}")

        gpu_mem_before = None
        if self.gpu_monitor and self.config.device == "cuda":
            gpu_mem_before = self.gpu_monitor.get_memory_info()

        start_time = time.time()

        try:
            transcribe_params = self._build_transcribe_params(word_timestamps)
            segments, info = self.model.transcribe(audio_path, **transcribe_params)

            transcription_text = ""
            segment_count = 0
            word_segments_list: list[dict] = []
            for segment in segments:
                transcription_text += segment.text + " "
                segment_count += 1
                if word_timestamps and hasattr(segment, "words") and segment.words:
                    for w in segment.words:
                        word_segments_list.append({
                            "word": w.word,
                            "start": round(w.start, 3),
                            "end": round(w.end, 3),
                        })

            transcription_text = self._strip_hallucinations(transcription_text.strip())
            elapsed_time = time.time() - start_time
            rtf = elapsed_time / audio_duration if audio_duration else None

            metrics = {
                "elapsed_time": round(elapsed_time, 2),
                "audio_duration": round(audio_duration, 2) if audio_duration else None,
                "rtf": round(rtf, 4) if rtf else None,
                "segment_count": segment_count,
                "language": info.language,
                "language_probability": round(info.language_probability, 4),
            }

            if self.gpu_monitor and self.config.device == "cuda":
                gpu_mem_after = self.gpu_monitor.get_memory_info()
                metrics["gpu_memory_mb"] = gpu_mem_after["used_mb"]
                metrics["gpu_memory_delta_mb"] = (
                    gpu_mem_after["used_mb"] - gpu_mem_before["used_mb"]
                    if gpu_mem_before
                    else 0
                )

            logger.info(
                f"✓ Транскрипция завершена: {len(transcription_text)} символов, "
                f"{elapsed_time:.2f}s"
                + (f", RTF={rtf:.4f}" if rtf else "")
            )

            if not transcription_text:
                logger.warning(f"Пустая транскрипция для {audio_path_obj.name}")

            self._gpu_cleanup()
            return transcription_text, metrics, word_segments_list

        except Exception as e:
            self._gpu_cleanup()
            logger.error(
                f"Ошибка транскрипции {audio_path_obj.name}: {e}", exc_info=True
            )
            raise ValueError(f"Транскрипция не удалась: {e}") from e

    def _transcribe_chunked(
        self,
        audio_path: str,
        audio_duration: float,
        word_timestamps: bool = False,
    ) -> Tuple[str, dict, list]:
        """
        Chunked-транскрипция для длинных аудио.
        Разбивает файл на ~4-мин фрагменты с перекрытием, транскрибирует
        каждый отдельно, затем склеивает результат.
        """
        audio_path_obj = Path(audio_path)
        logger.info(
            f"Chunked транскрипция: {audio_path_obj.name} "
            f"({audio_duration:.0f}s, chunk={CHUNK_DURATION_SEC}s, overlap={CHUNK_OVERLAP_SEC}s)"
        )

        gpu_mem_before = None
        if self.gpu_monitor and self.config.device == "cuda":
            gpu_mem_before = self.gpu_monitor.get_memory_info()

        start_time = time.time()

        try:
            file_info = sf.info(audio_path)
            sr = file_info.samplerate
            total_frames = file_info.frames

            chunk_frames = int(CHUNK_DURATION_SEC * sr)
            overlap_frames = int(CHUNK_OVERLAP_SEC * sr)
            step_frames = chunk_frames - overlap_frames

            chunks: list[tuple[int, int]] = []
            offset = 0
            while offset < total_frames:
                end = min(offset + chunk_frames, total_frames)
                chunks.append((offset, end))
                if end >= total_frames:
                    break
                offset += step_frames

            logger.info(f"Разбито на {len(chunks)} чанков")

            transcribe_params = self._build_transcribe_params(word_timestamps)

            all_text_parts: list[str] = []
            all_word_segments: list[dict] = []
            total_segment_count = 0
            lang = "ru"
            lang_prob = 0.0

            for idx, (frame_start, frame_end) in enumerate(chunks):
                chunk_start_sec = frame_start / sr
                chunk_dur_sec = (frame_end - frame_start) / sr
                logger.info(
                    f"  Чанк {idx + 1}/{len(chunks)}: "
                    f"{chunk_start_sec:.1f}s–{chunk_start_sec + chunk_dur_sec:.1f}s"
                )

                data, _ = sf.read(audio_path, start=frame_start, stop=frame_end, dtype="float32")

                tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
                sf.write(tmp.name, data, sr, subtype="PCM_16")
                tmp.close()

                try:
                    segments, info = self.model.transcribe(tmp.name, **transcribe_params)

                    chunk_text = ""
                    for seg in segments:
                        chunk_text += seg.text + " "
                        total_segment_count += 1
                        if word_timestamps and hasattr(seg, "words") and seg.words:
                            for w in seg.words:
                                all_word_segments.append({
                                    "word": w.word,
                                    "start": round(w.start + chunk_start_sec, 3),
                                    "end": round(w.end + chunk_start_sec, 3),
                                })

                    chunk_text = chunk_text.strip()

                    if idx == 0:
                        lang = info.language
                        lang_prob = info.language_probability

                    if chunk_text:
                        all_text_parts.append(chunk_text)
                        logger.info(f"    → {len(chunk_text)} символов")
                    else:
                        logger.warning(f"    → пустой чанк")

                finally:
                    Path(tmp.name).unlink(missing_ok=True)
                    self._gpu_cleanup()

            merged_text = self._strip_hallucinations(self._merge_overlapping_texts(all_text_parts))

            if word_timestamps and all_word_segments:
                all_word_segments = self._deduplicate_word_segments(all_word_segments)

            elapsed_time = time.time() - start_time
            rtf = elapsed_time / audio_duration

            metrics = {
                "elapsed_time": round(elapsed_time, 2),
                "audio_duration": round(audio_duration, 2),
                "rtf": round(rtf, 4),
                "segment_count": total_segment_count,
                "language": lang,
                "language_probability": round(lang_prob, 4),
                "chunked": True,
                "num_chunks": len(chunks),
            }

            if self.gpu_monitor and self.config.device == "cuda":
                gpu_mem_after = self.gpu_monitor.get_memory_info()
                metrics["gpu_memory_mb"] = gpu_mem_after["used_mb"]
                metrics["gpu_memory_delta_mb"] = (
                    gpu_mem_after["used_mb"] - gpu_mem_before["used_mb"]
                    if gpu_mem_before
                    else 0
                )

            logger.info(
                f"✓ Chunked транскрипция завершена: {len(merged_text)} символов, "
                f"{len(chunks)} чанков, {elapsed_time:.2f}s, RTF={rtf:.4f}"
            )

            if not merged_text:
                logger.warning(f"Пустая транскрипция для {audio_path_obj.name}")

            return merged_text, metrics, all_word_segments

        except Exception as e:
            self._gpu_cleanup()
            logger.error(
                f"Ошибка chunked-транскрипции {audio_path_obj.name}: {e}", exc_info=True
            )
            raise ValueError(f"Транскрипция не удалась: {e}") from e

    @staticmethod
    def _merge_overlapping_texts(parts: list[str]) -> str:
        """
        Склейка текстов чанков с удалением дублей на стыках.
        Ищет наибольшее перекрытие (суффикс предыдущего == префикс следующего)
        по словам, и убирает дублированный фрагмент.
        """
        if not parts:
            return ""
        if len(parts) == 1:
            return parts[0]

        merged = parts[0]
        for i in range(1, len(parts)):
            prev_words = merged.split()
            next_words = parts[i].split()

            best_overlap = 0
            max_check = min(len(prev_words), len(next_words), 20)
            for overlap_len in range(1, max_check + 1):
                if prev_words[-overlap_len:] == next_words[:overlap_len]:
                    best_overlap = overlap_len

            if best_overlap > 0:
                merged = merged + " " + " ".join(next_words[best_overlap:])
                logger.debug(f"Overlap merge: убрано {best_overlap} слов на стыке чанков {i-1}/{i}")
            else:
                merged = merged + " " + parts[i]

        return merged.strip()

    @staticmethod
    def _deduplicate_word_segments(segments: list[dict]) -> list[dict]:
        """Удаление дублированных word-сегментов из зоны перекрытия по таймкодам."""
        if not segments:
            return segments

        segments.sort(key=lambda s: s["start"])
        deduped = [segments[0]]
        for seg in segments[1:]:
            prev = deduped[-1]
            if abs(seg["start"] - prev["start"]) < 0.15 and seg["word"].strip() == prev["word"].strip():
                continue
            deduped.append(seg)
        return deduped

