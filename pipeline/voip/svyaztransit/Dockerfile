FROM python:3.12-slim

# Install uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# Copy dependency manifest first for layer caching
COPY pyproject.toml ./

# Install dependencies into the system Python (no venv needed in container)
RUN uv pip install --system -r pyproject.toml 2>/dev/null || \
    uv pip install --system \
        "requests==2.32.5" \
        "beautifulsoup4==4.13.5" \
        "pydantic==2.10.3" \
        "pydantic-settings>=2.0.0,<3.0.0" \
        "lxml==6.0.1" \
        "psutil>=5.8.0,<6.0.0"

# Copy source
COPY *.py ./

# SQLite DB and download dir live in a volume
VOLUME ["/data"]

ENV DATABASE_PATH=/data/stranzit_calls.db
ENV DOWNLOAD_DIR=/data/downloads

CMD ["python", "call_records_watcher.py"]
