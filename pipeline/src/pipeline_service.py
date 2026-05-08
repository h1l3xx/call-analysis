"""
Shared single-file analysis pipeline for CLI, API, and future UI flows.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from tempfile import NamedTemporaryFile

from src.asr import ASREngine
from src.audio_preprocessor import AudioPreprocessor
from src.config_validation import AppConfig  # noqa: TC001
from src.speaker_analytics import Turn, SpeakerMetrics
from src.utils import GPUMonitor
from src.vllm_postprocessor import VLLMPostprocessor

logger = logging.getLogger(__name__)


@dataclass
class AnalysisResult:
    """Structured result of a single-file analysis."""

    source_name: str
    raw_transcription: str
    cleaned_text: str
    classification: dict | None
    asr_metrics: dict
    output_path: Path | None = None
    metadata_path: Path | None = None
    quality_result: dict | None = None
    quality_path: Path | None = None
    speaker_turns: list[dict] | None = None
    speaker_metrics: dict | None = None

    @property
    def result_id(self) -> str:
        return Path(self.source_name).stem

    def to_api_dict(self) -> dict:
        """Serialize result for JSON responses."""
        return {
            "result_id": self.result_id,
            "filename": self.source_name,
            "raw_transcription": self.raw_transcription,
            "cleaned_text": self.cleaned_text,
            "classification": self.classification,
            "asr_metrics": self.asr_metrics,
            "speaker_turns": self.speaker_turns,
            "speaker_metrics": self.speaker_metrics,
            "quality": self.quality_result,
            "artifacts": {
                "transcript_path": str(self.output_path) if self.output_path else None,
                "metadata_path": str(self.metadata_path) if self.metadata_path else None,
                "quality_path": str(self.quality_path) if self.quality_path else None,
            },
        }


class CallAnalysisPipeline:
    """Reusable pipeline for processing one audio file."""

    def __init__(self, config: AppConfig):
        self.config = config
        self.gpu_monitor = self._build_gpu_monitor()
        self.audio_preprocessor = AudioPreprocessor(config.asr)
        self.asr_engine = ASREngine(config.asr, self.gpu_monitor)
        self.vllm_postprocessor = VLLMPostprocessor(config.vllm)
        self.quality_analyzer = self._build_quality_analyzer()
        self.diarization_engine = self._build_diarization_engine()

    def _build_gpu_monitor(self) -> GPUMonitor | None:
        if self.config.asr.device != "cuda":
            return None
        try:
            return GPUMonitor(gpu_index=0)
        except Exception as exc:
            logger.warning("GPU monitor init failed (will skip): %s", exc)
            return None

    def _build_diarization_engine(self):
        if not self.config.diarization.enabled:
            return None
        try:
            from src.diarization import DiarizationEngine
            return DiarizationEngine(self.config.diarization)
        except Exception as exc:
            logger.warning("Diarization init failed (will skip): %s", exc)
            return None

    def _build_quality_analyzer(self):
        if not self.config.quality_analysis.enabled:
            return None

        from src.quality_analyzer import QualityAnalyzer

        return QualityAnalyzer(self.config.quality_analysis, self.config.vllm)

    def analyze_file(
        self,
        file_path: str | Path,
        display_name: str | None = None,
        persist: bool = False,
        analyze_quality: bool | None = None,
        custom_criteria: list[dict] | None = None,
    ) -> AnalysisResult:
        """
        Run the analysis pipeline for a single audio file.

        Args:
            file_path: Path to the audio file.
            display_name: Original filename for logs and saved metadata.
            persist: Save transcript/metadata artifacts to configured paths.
            analyze_quality: Override quality-analysis execution.
            custom_criteria: Optional list of criteria dicts from the backend.
                             When provided, these override the file-based script templates.
        """
        file_path_obj = Path(file_path)
        source_name = display_name or file_path_obj.name
        self._validate_audio_file(file_path_obj, source_name)

        logger.info("Shared pipeline start: %s", source_name)
        preprocessed_audio: str | None = None

        try:
            try:
                preprocessed_audio = self.audio_preprocessor.preprocess(str(file_path_obj))
                audio_duration = self.audio_preprocessor.get_audio_duration(str(file_path_obj))
            except ValueError as exc:
                if "CORRUPTED_AUDIO" in str(exc):
                    raise ValueError(f"Битый аудиофайл: {source_name}") from exc
                raise

            use_diarization = self.diarization_engine is not None
            raw_transcription, asr_metrics, word_segments = self.asr_engine.transcribe(
                preprocessed_audio, audio_duration,
                word_timestamps=use_diarization,
            )

            if not raw_transcription or not raw_transcription.strip():
                raise ValueError(f"NO_SPEECH: Тишина / нет речи в записи {source_name}")

            speaker_turns_data: list[dict] | None = None
            speaker_metrics_data: dict | None = None
            if use_diarization and word_segments:
                try:
                    from src.speaker_analytics import (
                        align_words_to_speakers,
                        compute_metrics,
                        identify_roles,
                    )
                    diar_segments = self.diarization_engine.diarize(str(file_path_obj))
                    turns = align_words_to_speakers(word_segments, diar_segments)
                    turns = identify_roles(turns, source_name)
                    metrics = compute_metrics(
                        turns, audio_duration or 0.0, diar_segments,
                    )
                    speaker_turns_data = [t.to_dict() for t in turns]
                    speaker_metrics_data = metrics.to_dict()
                except Exception as exc:
                    logger.warning("Diarization failed for %s: %s", source_name, exc)

            cleaned_text, classification = self.vllm_postprocessor.process(
                raw_transcription, source_name
            )

            output_path = None
            metadata_path = None
            if persist:
                output_path, metadata_path = self._save_results(
                    source_name,
                    cleaned_text,
                    classification,
                    asr_metrics,
                )

            quality_result = None
            quality_path = None
            should_analyze_quality = (
                analyze_quality
                if analyze_quality is not None
                else self.config.quality_analysis.enabled
            )
            if should_analyze_quality and self.quality_analyzer:
                quality_result, quality_path = self._run_quality_analysis(
                    source_name=source_name,
                    text=cleaned_text,
                    classification=classification,
                    metrics=asr_metrics,
                    output_path=output_path,
                    metadata_path=metadata_path,
                    custom_criteria=custom_criteria,
                )

            logger.info("Shared pipeline complete: %s", source_name)
            return AnalysisResult(
                source_name=source_name,
                raw_transcription=raw_transcription,
                cleaned_text=cleaned_text,
                classification=classification,
                asr_metrics=asr_metrics,
                output_path=output_path,
                metadata_path=metadata_path,
                quality_result=quality_result,
                quality_path=Path(quality_path) if quality_path else None,
                speaker_turns=speaker_turns_data,
                speaker_metrics=speaker_metrics_data,
            )
        finally:
            if preprocessed_audio:
                Path(preprocessed_audio).unlink(missing_ok=True)

    def _validate_audio_file(self, file_path: Path, source_name: str) -> None:
        if not file_path.exists():
            raise FileNotFoundError(f"Аудиофайл не найден: {file_path}")

        extension = file_path.suffix.lower()
        if extension not in self.config.security.allowed_extensions:
            raise ValueError(
                f"Неподдерживаемое расширение файла {extension} для {source_name}"
            )

        file_size_mb = file_path.stat().st_size / (1024 * 1024)
        if file_size_mb > self.config.security.max_file_size_mb:
            raise ValueError(
                f"Файл слишком большой: {file_size_mb:.2f} MB "
                f"(лимит: {self.config.security.max_file_size_mb} MB)"
            )

    def _save_results(
        self,
        source_name: str,
        text: str,
        classification: dict | None,
        metrics: dict,
    ) -> tuple[Path, Path | None]:
        base_name = Path(source_name).stem

        output_path = Path(self.config.paths.output) / f"{base_name}.txt"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(text, encoding="utf-8")

        metadata_path = None
        metadata = {
            "filename": source_name,
            "processed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "classification": classification,
            "asr_metrics": metrics,
        }
        metadata_path = Path(self.config.paths.metadata) / f"{base_name}.json"
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        return output_path, metadata_path

    def _run_quality_analysis(
        self,
        *,
        source_name: str,
        text: str,
        classification: dict | None,
        metrics: dict,
        output_path: Path | None,
        metadata_path: Path | None,
        custom_criteria: list[dict] | None = None,
    ) -> tuple[dict, str]:
        if output_path is None:
            output_path, metadata_path = self._write_temporary_artifacts(
                source_name,
                text,
                classification,
                metrics,
            )
            cleanup_temp = True
        else:
            cleanup_temp = False

        try:
            quality_result = self.quality_analyzer.analyze_call(
                str(output_path),
                str(metadata_path) if metadata_path and metadata_path.exists() else None,
                custom_criteria=custom_criteria,
            )
            quality_path = self.quality_analyzer.save_analysis(
                quality_result, Path(source_name).stem
            )
            return quality_result, quality_path
        finally:
            if cleanup_temp:
                output_path.unlink(missing_ok=True)
                if metadata_path:
                    metadata_path.unlink(missing_ok=True)

    def _write_temporary_artifacts(
        self,
        source_name: str,
        text: str,
        classification: dict | None,
        metrics: dict,
    ) -> tuple[Path, Path | None]:
        with NamedTemporaryFile(
            mode="w",
            suffix=f"_{Path(source_name).stem}.txt",
            delete=False,
            encoding="utf-8",
        ) as transcript_file:
            transcript_file.write(text)
            transcript_path = Path(transcript_file.name)

        metadata_path = None
        if classification is not None:
            with NamedTemporaryFile(
                mode="w",
                suffix=f"_{Path(source_name).stem}.json",
                delete=False,
                encoding="utf-8",
            ) as metadata_file:
                metadata = {
                    "filename": source_name,
                    "processed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "classification": classification,
                    "asr_metrics": metrics,
                }
                metadata_file.write(json.dumps(metadata, ensure_ascii=False, indent=2))
                metadata_path = Path(metadata_file.name)

        return transcript_path, metadata_path
