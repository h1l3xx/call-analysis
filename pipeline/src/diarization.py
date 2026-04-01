"""
Speaker diarization using pyannote.audio.

Identifies who speaks when in mono audio recordings.
"""

import logging
import os
import time
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)


@dataclass
class DiarizedSegment:
    start: float
    end: float
    speaker: str


class DiarizationEngine:
    """Speaker diarization via pyannote/speaker-diarization-3.1."""

    def __init__(self, config):
        self.config = config
        self.pipeline = None

        hf_token = os.environ.get("HF_TOKEN", "")
        if not hf_token:
            logger.warning(
                "HF_TOKEN not set — diarization will fail for gated models. "
                "Get a free token at https://huggingface.co/settings/tokens "
                "and accept the model license at https://huggingface.co/pyannote/speaker-diarization-3.1"
            )

        self._hf_token = hf_token or None
        self._load_pipeline()
        logger.info("DiarizationEngine initialized: model=%s", config.model)

    def _load_pipeline(self):
        if self.pipeline is not None:
            return

        import torch
        from pyannote.audio import Pipeline

        device_str = self.config.device
        if device_str == "auto":
            device_str = "cuda" if torch.cuda.is_available() else "cpu"

        logger.info("Loading diarization model: %s (device=%s)", self.config.model, device_str)
        start = time.time()

        self.pipeline = Pipeline.from_pretrained(
            self.config.model,
            use_auth_token=self._hf_token,
        )

        device = torch.device(device_str)
        self.pipeline.to(device)

        logger.info("Diarization model loaded in %.2fs", time.time() - start)

    def diarize(self, audio_path: str) -> list[DiarizedSegment]:
        if not Path(audio_path).exists():
            raise FileNotFoundError(f"Audio not found: {audio_path}")

        logger.info("Diarizing: %s", Path(audio_path).name)
        start = time.time()

        params = {
            "min_speakers": self.config.min_speakers,
            "max_speakers": self.config.max_speakers,
        }

        diarization = self.pipeline(audio_path, **params)

        segments = []
        for turn, _, speaker in diarization.itertracks(yield_label=True):
            segments.append(DiarizedSegment(
                start=round(turn.start, 3),
                end=round(turn.end, 3),
                speaker=speaker,
            ))

        elapsed = time.time() - start
        speakers = {s.speaker for s in segments}
        logger.info(
            "Diarization complete: %d segments, %d speakers, %.2fs",
            len(segments), len(speakers), elapsed,
        )

        return segments
