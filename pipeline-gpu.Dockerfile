# GPU-enabled single-stage Dockerfile for AI audio-call pipeline.
# NVIDIA CUDA 12.4 + Python 3.12 (deadsnakes) + uv package manager.

FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04

LABEL maintainer="MalikovAI"
LABEL description="Call Analytics — Whisper large-v3 (GPU) + pyannote diarization + quality analysis"

ENV DEBIAN_FRONTEND=noninteractive

# Install Python 3.12 from deadsnakes PPA + system deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    software-properties-common curl wget gpg-agent \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv python3.12-dev \
    libgomp1 libsndfile1 ffmpeg build-essential libffi-dev libssl-dev \
    && ln -sf /usr/bin/python3.12 /usr/bin/python3 \
    && ln -sf /usr/bin/python3 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/*

# Install uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:$PATH"

# Create app user
RUN useradd --create-home --shell /bin/bash asruser

WORKDIR /home/asruser/app

# Copy dependency files first (better layer caching)
COPY --chown=asruser:asruser pyproject.toml uv.lock uv.toml README.md ./

# Install dependencies as root (uv needs write access), then fix ownership
ENV UV_HTTP_TIMEOUT=300
RUN uv sync --frozen --no-dev --python python3.12 \
    && chown -R asruser:asruser /home/asruser/app/.venv

# Copy app code
COPY --chown=asruser:asruser src/ ./src/
COPY --chown=asruser:asruser main.py ./
COPY --chown=asruser:asruser templates/ ./templates/
COPY --chown=asruser:asruser config.example.yaml ./config.yaml
COPY --chown=asruser:asruser branches.yaml* ./

USER asruser

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
