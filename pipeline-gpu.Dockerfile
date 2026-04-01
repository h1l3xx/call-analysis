# GPU-enabled Dockerfile for AI audio-call pipeline.
# NVIDIA CUDA 12.4 runtime for Whisper large-v3 + pyannote.audio diarization.

# Stage 1: Build dependencies
FROM python:3.12-slim AS builder

RUN apt-get update && apt-get install -y \
    build-essential \
    libffi-dev \
    libssl-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.cargo/bin:/root/.local/bin:$PATH"

WORKDIR /build

COPY pyproject.toml uv.lock uv.toml ./
COPY README.md ./

ENV UV_HTTP_TIMEOUT=300
RUN uv sync --frozen --no-dev

# Stage 2: GPU Runtime with CUDA 12.4
FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04

LABEL maintainer="MalikovAI"
LABEL description="Call Analytics — Whisper large-v3 (GPU) + pyannote diarization + quality analysis"

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    python3.12 python3.12-venv python3.12-dev \
    libgomp1 libsndfile1 ffmpeg wget curl \
    software-properties-common \
    && add-apt-repository ppa:deadsnakes/ppa || true \
    && apt-get update \
    && apt-get install -y python3.12 python3.12-venv python3.12-dev \
    && ln -sf /usr/bin/python3.12 /usr/bin/python3 \
    && ln -sf /usr/bin/python3 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash asruser
USER asruser
WORKDIR /home/asruser/app

COPY --from=builder --chown=asruser:asruser /build/.venv /home/asruser/app/.venv

COPY --chown=asruser:asruser src/ ./src/
COPY --chown=asruser:asruser main.py ./
COPY --chown=asruser:asruser pyproject.toml ./
COPY --chown=asruser:asruser uv.toml ./
COPY --chown=asruser:asruser templates/ ./templates/
COPY --chown=asruser:asruser config.example.yaml ./config.yaml
COPY --chown=asruser:asruser branches.yaml* ./

RUN mkdir -p logs input output metadata archive analytics quality_analysis quarantine

ENV PATH="/home/asruser/app/.venv/bin:$PATH"
ENV PYTHONPATH="/home/asruser/app"
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1
ENV LD_LIBRARY_PATH="/usr/local/cuda/lib64:${LD_LIBRARY_PATH}"
ENV NVIDIA_VISIBLE_DEVICES=all
ENV NVIDIA_DRIVER_CAPABILITIES=compute,utility

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=5 \
    CMD wget -qO- http://127.0.0.1:${WEB__PORT:-8001}/healthz || exit 1

CMD ["python", "main.py", "web", "--host", "0.0.0.0", "--port", "8001", "--allow-insecure-public-bind"]
