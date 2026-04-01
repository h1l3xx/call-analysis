"""
Speaker analytics: alignment of Whisper words with diarization segments,
role identification, and metric computation.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, asdict

logger = logging.getLogger(__name__)


@dataclass
class Turn:
    speaker: str
    text: str
    start: float
    end: float

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class SpeakerMetrics:
    manager_talk_ratio: float | None = None
    client_talk_ratio: float | None = None
    silence_ratio: float | None = None
    interruptions_count: int = 0
    avg_pause_seconds: float | None = None
    manager_wpm: float | None = None
    client_wpm: float | None = None
    longest_monologue_sec: float | None = None

    def to_dict(self) -> dict:
        return asdict(self)


def align_words_to_speakers(
    word_segments: list[dict],
    diarization_segments: list,
) -> list[Turn]:
    """Assign each Whisper word to the diarization speaker whose segment overlaps most."""
    if not word_segments or not diarization_segments:
        return []

    def find_speaker(word_start: float, word_end: float) -> str | None:
        best_overlap = 0.0
        best_speaker = None
        for seg in diarization_segments:
            overlap_start = max(word_start, seg.start)
            overlap_end = min(word_end, seg.end)
            overlap = max(0.0, overlap_end - overlap_start)
            if overlap > best_overlap:
                best_overlap = overlap
                best_speaker = seg.speaker
        return best_speaker

    labeled_words = []
    for w in word_segments:
        speaker = find_speaker(w["start"], w["end"])
        if speaker is None:
            speaker = labeled_words[-1]["speaker"] if labeled_words else "SPEAKER_00"
        labeled_words.append({
            "word": w["word"],
            "start": w["start"],
            "end": w["end"],
            "speaker": speaker,
        })

    turns: list[Turn] = []
    for lw in labeled_words:
        if turns and turns[-1].speaker == lw["speaker"]:
            turns[-1].text += lw["word"]
            turns[-1].end = lw["end"]
        else:
            turns.append(Turn(
                speaker=lw["speaker"],
                text=lw["word"],
                start=lw["start"],
                end=lw["end"],
            ))

    for t in turns:
        t.text = t.text.strip()
        t.start = round(t.start, 3)
        t.end = round(t.end, 3)

    return [t for t in turns if t.text]


def identify_roles(turns: list[Turn], filename: str = "") -> list[Turn]:
    """Rename abstract SPEAKER_XX labels to 'manager' / 'client'.

    Heuristic for internal calls (both numbers are internal = both managers):
      If filename indicates internal, use 'speaker_1' / 'speaker_2'.
    For external calls:
      Incoming (Входящий): first speaker is client, second is manager.
      Outgoing (Исходящий): first speaker is manager, second is client.
      Default: first speaker = manager.
    """
    if not turns:
        return turns

    unique_speakers = list(dict.fromkeys(t.speaker for t in turns))

    fn_lower = filename.lower()
    is_incoming = "входящ" in fn_lower
    is_outgoing = "исходящ" in fn_lower

    has_external_number = bool(re.search(r"(?<!\d)\d{10,11}(?!\d)", filename))

    if len(unique_speakers) <= 1:
        mapping = {unique_speakers[0]: "manager"}
    elif has_external_number:
        first_speaker = unique_speakers[0]
        second_speaker = unique_speakers[1]
        if is_incoming:
            mapping = {first_speaker: "client", second_speaker: "manager"}
        else:
            mapping = {first_speaker: "manager", second_speaker: "client"}
        for s in unique_speakers[2:]:
            mapping[s] = "unknown"
    else:
        mapping = {}
        for i, s in enumerate(unique_speakers):
            mapping[s] = f"speaker_{i + 1}"

    for t in turns:
        t.speaker = mapping.get(t.speaker, t.speaker)

    return turns


def compute_metrics(
    turns: list[Turn],
    total_duration: float,
    diarization_segments: list | None = None,
) -> SpeakerMetrics:
    """Compute speaker-level metrics from dialogue turns."""
    if not turns or total_duration <= 0:
        return SpeakerMetrics()

    talk_time: dict[str, float] = {}
    word_count: dict[str, int] = {}
    longest_mono: dict[str, float] = {}

    for t in turns:
        dur = max(0.0, t.end - t.start)
        talk_time[t.speaker] = talk_time.get(t.speaker, 0.0) + dur
        wc = len(t.text.split())
        word_count[t.speaker] = word_count.get(t.speaker, 0) + wc
        if dur > longest_mono.get(t.speaker, 0.0):
            longest_mono[t.speaker] = dur

    total_talk = sum(talk_time.values())
    silence = max(0.0, total_duration - total_talk)

    def ratio(speaker: str) -> float | None:
        if speaker not in talk_time or total_duration <= 0:
            return None
        return round(talk_time[speaker] / total_duration, 3)

    def wpm(speaker: str) -> float | None:
        if speaker not in talk_time or talk_time[speaker] < 1.0:
            return None
        minutes = talk_time[speaker] / 60.0
        return round(word_count.get(speaker, 0) / minutes, 1)

    pauses = []
    for i in range(1, len(turns)):
        gap = turns[i].start - turns[i - 1].end
        if gap > 0.1:
            pauses.append(gap)

    interruptions = 0
    if diarization_segments and len(diarization_segments) >= 2:
        sorted_segs = sorted(diarization_segments, key=lambda s: s.start)
        for i in range(1, len(sorted_segs)):
            if (sorted_segs[i].start < sorted_segs[i - 1].end
                    and sorted_segs[i].speaker != sorted_segs[i - 1].speaker):
                interruptions += 1

    mgr_key = "manager" if "manager" in talk_time else next(iter(talk_time), None)
    cli_key = "client" if "client" in talk_time else None
    if cli_key is None:
        candidates = [k for k in talk_time if k != mgr_key]
        cli_key = candidates[0] if candidates else None

    all_monologues = list(longest_mono.values())

    return SpeakerMetrics(
        manager_talk_ratio=ratio(mgr_key) if mgr_key else None,
        client_talk_ratio=ratio(cli_key) if cli_key else None,
        silence_ratio=round(silence / total_duration, 3) if total_duration > 0 else None,
        interruptions_count=interruptions,
        avg_pause_seconds=round(sum(pauses) / len(pauses), 2) if pauses else None,
        manager_wpm=wpm(mgr_key) if mgr_key else None,
        client_wpm=wpm(cli_key) if cli_key else None,
        longest_monologue_sec=round(max(all_monologues), 2) if all_monologues else None,
    )
