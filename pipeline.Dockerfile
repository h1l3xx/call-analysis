# Patched Dockerfile for AI audio-call pipeline (CPU mode).
# Fixes: hatchling build requires README.md during `uv sync`.
# Original Dockerfile hardcodes port 8080; we override CMD for port 8001.

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
# hatchling needs README.md to build the project metadata
COPY README.md ./

ENV UV_HTTP_TIMEOUT=300
RUN uv sync --frozen --no-dev

# Stage 2: Runtime
FROM python:3.12-slim

LABEL maintainer="MalikovAI"
LABEL description="Call Analytics — transcription and quality analysis"

RUN apt-get update && apt-get install -y \
    libgomp1 wget \
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

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:${WEB__PORT:-8001}/healthz || exit 1

CMD ["python", "main.py", "web", "--host", "0.0.0.0", "--port", "8001", "--allow-insecure-public-bind"]
