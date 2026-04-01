#!/usr/bin/env bash
# =============================================================================
# Malikov — Deploy Script (GPU Server: RTX 2080Ti / i5-12600 / 32GB RAM)
# Usage: scripts/deploy.sh [command]
#
# Commands:
#   deploy     Full deploy: clone pipeline + patch + build + up (default)
#   up         Build and start all containers
#   down       Stop and remove containers
#   restart    Restart all containers
#   rebuild    Rebuild and restart specific service (e.g. rebuild pipeline)
#   logs       Follow container logs
#   ps         Show container status
#   pull       Clone/update pipeline repo + apply patch only
#   backup     Backup PostgreSQL database
#   restore    Restore PostgreSQL from backup file
#   status     Health check all services
#   setup      First-time server setup check
#   init       Full first-time server provisioning (Docker, NVIDIA, etc.)
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[deploy]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[deploy]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[deploy]${NC} $*"; }
log_error() { echo -e "${RED}[deploy]${NC} $*"; }
log_step()  { echo -e "${CYAN}[deploy]${NC} ── $* ──"; }

# ── Load environment ─────────────────────────────────────────────────────────

ENV_FILE="$ROOT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ROOT_DIR/.env.production.example" ]]; then
    cp "$ROOT_DIR/.env.production.example" "$ENV_FILE"
  else
    cp "$ROOT_DIR/.env.example" "$ENV_FILE"
  fi
  log_warn "Created .env from template. Fill in secrets before deploying!"
  log_warn "Edit: $ENV_FILE"
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

VLLM_PROFILE_ENV="${VLLM_PROFILE_ENV:-}"
if [[ -n "$VLLM_PROFILE_ENV" && -f "$VLLM_PROFILE_ENV" ]]; then
  set -a
  source "$VLLM_PROFILE_ENV"
  set +a
fi

# ── Validate required secrets in production ──────────────────────────────────

validate_secrets() {
  local has_errors=false

  if [[ "${ENVIRONMENT:-development}" == "production" ]]; then
    if [[ "${POSTGRES_PASSWORD:-}" == *"CHANGE_ME"* || -z "${POSTGRES_PASSWORD:-}" ]]; then
      log_error "POSTGRES_PASSWORD is not set or still has placeholder"
      has_errors=true
    fi
    if [[ "${DB_APP_PASSWORD:-}" == *"CHANGE_ME"* || -z "${DB_APP_PASSWORD:-}" ]]; then
      log_error "DB_APP_PASSWORD is not set or still has placeholder"
      has_errors=true
    fi
    if [[ "${JWT_SECRET:-}" == *"CHANGE_ME"* || "${JWT_SECRET:-}" == *"change_me"* || -z "${JWT_SECRET:-}" ]]; then
      log_error "JWT_SECRET is not set or still has placeholder"
      has_errors=true
    fi
    if [[ ${#JWT_SECRET} -lt 32 ]]; then
      log_error "JWT_SECRET must be at least 32 characters"
      has_errors=true
    fi
    if [[ "${PIPELINE_API_KEY:-}" == *"CHANGE_ME"* || -z "${PIPELINE_API_KEY:-}" ]]; then
      log_error "PIPELINE_API_KEY is not set or still has placeholder"
      has_errors=true
    fi
    if [[ -z "${OPENROUTER_API_KEY:-}" && -z "${OPENAI_API_KEY:-}" ]]; then
      log_warn "No LLM API key set (OPENROUTER_API_KEY / OPENAI_API_KEY). LLM evaluation will fail."
    fi
    if [[ -z "${HF_TOKEN:-}" || "${HF_TOKEN:-}" == *"YOUR_HF"* ]]; then
      log_warn "HF_TOKEN not set. Speaker diarization requires a HuggingFace token."
      log_warn "Get one at: https://huggingface.co/settings/tokens"
    fi
    if [[ -z "${DOMAIN:-}" || "${DOMAIN:-}" == "localhost" || "${DOMAIN:-}" == *"YOUR_NAME"* ]]; then
      log_warn "DOMAIN not set. HTTPS will not work without a real domain."
      log_warn "Get a free one at: https://www.duckdns.org"
    fi

    if [[ "$has_errors" == "true" ]]; then
      log_error "Fix secrets in $ENV_FILE before production deploy."
      exit 1
    fi
  fi
}

# ── Docker Compose configuration ─────────────────────────────────────────────

PIPELINE_BUILD_CONTEXT="${PIPELINE_BUILD_CONTEXT:-./pipeline}"
PIPELINE_GIT_URL="${PIPELINE_GIT_URL:-https://github.com/FUYOH666/Scanovich.ai-audio-call.git}"
PIPELINE_GIT_REF="${PIPELINE_GIT_REF:-main}"
APPLY_PIPELINE_PATCH="${APPLY_PIPELINE_PATCH:-true}"
PIPELINE_PATCH_FILE="${PIPELINE_PATCH_FILE:-$ROOT_DIR/patches/pipeline-custom-v2.patch}"

COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.prod.yml)
if [[ "${ENABLE_VLLM:-false}" == "true" ]]; then
  COMPOSE_FILES+=(-f docker-compose.vllm.yml)
fi

COMPOSE_CMD=(docker compose --env-file "$ENV_FILE")
if [[ -n "$VLLM_PROFILE_ENV" && -f "$VLLM_PROFILE_ENV" ]]; then
  COMPOSE_CMD+=(--env-file "$VLLM_PROFILE_ENV")
fi

dc() {
  "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" "$@"
}

# ── GPU check ────────────────────────────────────────────────────────────────

check_gpu() {
  log_info "Checking GPU availability..."

  if ! command -v nvidia-smi &>/dev/null; then
    log_warn "nvidia-smi not found. NVIDIA drivers may not be installed."
    log_warn "Pipeline will run on CPU (much slower)."
    return 1
  fi

  local gpu_info
  gpu_info=$(nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader 2>/dev/null || true)
  if [[ -z "$gpu_info" ]]; then
    log_warn "No NVIDIA GPU detected."
    return 1
  fi

  log_ok "GPU found: $gpu_info"

  if ! docker info 2>/dev/null | grep -qi "nvidia"; then
    if ! docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi &>/dev/null; then
      log_warn "NVIDIA Container Toolkit not working. Run: scripts/deploy.sh init"
      return 1
    fi
  fi

  log_ok "NVIDIA Container Toolkit is working."
  return 0
}

# ── Pipeline repo management ─────────────────────────────────────────────────

ensure_pipeline_repo() {
  if [[ -f "$PIPELINE_BUILD_CONTEXT/pyproject.toml" ]]; then
    log_ok "Pipeline source already present in $PIPELINE_BUILD_CONTEXT"
    if [[ -d "$PIPELINE_BUILD_CONTEXT/.git" ]]; then
      log_info "Updating pipeline repo..."
      git -C "$PIPELINE_BUILD_CONTEXT" fetch origin "$PIPELINE_GIT_REF" 2>/dev/null || true
      git -C "$PIPELINE_BUILD_CONTEXT" pull --ff-only origin "$PIPELINE_GIT_REF" 2>/dev/null || true
    fi
  else
    log_info "Cloning pipeline repo into $PIPELINE_BUILD_CONTEXT ..."
    if [[ -d "$PIPELINE_BUILD_CONTEXT" ]]; then
      rm -rf "$PIPELINE_BUILD_CONTEXT"
    fi
    mkdir -p "$(dirname "$PIPELINE_BUILD_CONTEXT")"
    git clone --branch "$PIPELINE_GIT_REF" "$PIPELINE_GIT_URL" "$PIPELINE_BUILD_CONTEXT"
  fi

  if [[ ! -f "$ROOT_DIR/deploy/pipeline.prod.yaml" ]]; then
    log_error "Missing deploy/pipeline.prod.yaml (production pipeline config)"
    exit 1
  fi
}

apply_pipeline_patch() {
  if [[ "$APPLY_PIPELINE_PATCH" != "true" ]]; then
    log_info "Skipping pipeline patch (APPLY_PIPELINE_PATCH=$APPLY_PIPELINE_PATCH)"
    return
  fi

  if [[ ! -d "$PIPELINE_BUILD_CONTEXT/.git" ]]; then
    log_info "Pipeline is embedded (no .git) — skipping patch (already applied)."
    return
  fi

  if [[ ! -f "$PIPELINE_PATCH_FILE" ]]; then
    log_warn "Pipeline patch file not found: $PIPELINE_PATCH_FILE"
    log_warn "Continuing without patch."
    return
  fi

  log_info "Applying pipeline patch: $PIPELINE_PATCH_FILE"
  if git -C "$PIPELINE_BUILD_CONTEXT" apply --check "$PIPELINE_PATCH_FILE" >/dev/null 2>&1; then
    git -C "$PIPELINE_BUILD_CONTEXT" apply "$PIPELINE_PATCH_FILE"
    log_ok "Pipeline patch applied."
  elif git -C "$PIPELINE_BUILD_CONTEXT" apply --reverse --check "$PIPELINE_PATCH_FILE" >/dev/null 2>&1; then
    log_ok "Pipeline patch already applied."
  else
    log_error "Cannot apply pipeline patch cleanly."
    log_error "The upstream pipeline changed; update the patch file."
    exit 1
  fi
}

# ── Backup ───────────────────────────────────────────────────────────────────

backup_db() {
  local backup_dir="$ROOT_DIR/backups"
  mkdir -p "$backup_dir"
  local timestamp
  timestamp=$(date +%Y%m%d_%H%M%S)
  local backup_file="$backup_dir/malikov_${timestamp}.sql.gz"

  log_info "Backing up database to $backup_file ..."

  if ! docker compose ps postgres --format '{{.State}}' 2>/dev/null | grep -q running; then
    log_warn "PostgreSQL is not running. Cannot backup."
    return 1
  fi

  docker compose exec -T postgres \
    pg_dump -U malikov -d malikov --no-owner --no-acl \
    | gzip > "$backup_file"

  local size
  size=$(du -h "$backup_file" | cut -f1)
  log_ok "Backup complete: $backup_file ($size)"

  local count
  count=$(ls -1 "$backup_dir"/malikov_*.sql.gz 2>/dev/null | wc -l)
  if [[ $count -gt 30 ]]; then
    ls -1t "$backup_dir"/malikov_*.sql.gz | tail -n +31 | xargs rm -f
    log_info "Cleaned old backups (kept last 30)."
  fi
}

restore_db() {
  local backup_file="${2:-}"
  if [[ -z "$backup_file" ]]; then
    log_error "Usage: scripts/deploy.sh restore <backup_file.sql.gz>"
    local latest
    latest=$(ls -1t "$ROOT_DIR/backups"/malikov_*.sql.gz 2>/dev/null | head -1 || true)
    if [[ -n "$latest" ]]; then
      log_info "Latest backup: $latest"
    fi
    exit 1
  fi

  if [[ ! -f "$backup_file" ]]; then
    log_error "Backup file not found: $backup_file"
    exit 1
  fi

  log_warn "This will OVERWRITE the current database. Continue? (y/N)"
  read -r confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    log_info "Cancelled."
    exit 0
  fi

  log_info "Restoring from $backup_file ..."
  gunzip -c "$backup_file" | docker compose exec -T postgres \
    psql -U malikov -d malikov --quiet
  log_ok "Restore complete."
}

# ── Health check ─────────────────────────────────────────────────────────────

wait_healthy() {
  local service=$1
  local url=$2
  local timeout=${3:-120}
  local elapsed=0

  log_info "Waiting for $service to be healthy..."
  while [[ $elapsed -lt $timeout ]]; do
    if curl -sf "$url" >/dev/null 2>&1; then
      log_ok "$service is healthy (${elapsed}s)"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done

  log_error "$service not healthy after ${timeout}s"
  return 1
}

check_status() {
  log_info "Service status:"
  echo ""
  dc ps
  echo ""

  local all_ok=true

  if curl -sf http://localhost:${APP_PORT:-8080}/health >/dev/null 2>&1; then
    local health
    health=$(curl -sf http://localhost:${APP_PORT:-8080}/health)
    log_ok "Backend:  $health"
  else
    log_error "Backend:  NOT RESPONDING"
    all_ok=false
  fi

  if curl -sf http://localhost:${FRONTEND_PORT:-80}/ >/dev/null 2>&1; then
    log_ok "Frontend: OK (port ${FRONTEND_PORT:-80})"
  else
    log_error "Frontend: NOT RESPONDING"
    all_ok=false
  fi

  if nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader 2>/dev/null; then
    log_ok "GPU status above ^"
  fi

  if [[ "$all_ok" == "true" ]]; then
    echo ""
    log_ok "All services are running."
  else
    echo ""
    log_error "Some services are down. Check logs: scripts/deploy.sh logs"
  fi
}

# ── First-time server init ───────────────────────────────────────────────────

server_init() {
  log_step "First-time server provisioning"
  echo ""

  if [[ "$(id -u)" -ne 0 ]]; then
    log_warn "Run as root for full provisioning: sudo scripts/deploy.sh init"
  fi

  # Docker
  if ! command -v docker &>/dev/null; then
    log_info "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
    if [[ -n "${SUDO_USER:-}" ]]; then
      usermod -aG docker "$SUDO_USER"
      log_ok "Added $SUDO_USER to docker group (re-login required)"
    fi
  else
    log_ok "Docker already installed: $(docker --version)"
  fi

  # NVIDIA drivers
  if ! command -v nvidia-smi &>/dev/null; then
    log_info "Installing NVIDIA drivers..."
    apt-get update
    apt-get install -y ubuntu-drivers-common
    ubuntu-drivers install --gpgpu
    log_warn "REBOOT required after NVIDIA driver install!"
  else
    log_ok "NVIDIA drivers: $(nvidia-smi --query-gpu=driver_version --format=csv,noheader | head -1)"
  fi

  # NVIDIA Container Toolkit
  if ! dpkg -l nvidia-container-toolkit &>/dev/null 2>&1; then
    log_info "Installing NVIDIA Container Toolkit..."
    curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
      | gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
    local distribution
    distribution=$(. /etc/os-release; echo "$ID$VERSION_ID")
    curl -s -L "https://nvidia.github.io/libnvidia-container/$distribution/libnvidia-container.list" \
      | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
      | tee /etc/apt/sources.list.d/nvidia-container-toolkit.list
    apt-get update
    apt-get install -y nvidia-container-toolkit
    nvidia-ctk runtime configure --runtime=docker
    systemctl restart docker
    log_ok "NVIDIA Container Toolkit installed."
  else
    log_ok "NVIDIA Container Toolkit already installed."
  fi

  # Verify GPU in Docker
  if docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi &>/dev/null; then
    log_ok "GPU passthrough to Docker verified."
  else
    log_error "GPU passthrough FAILED. Check NVIDIA drivers and reboot."
  fi

  # Firewall (open only needed ports)
  if command -v ufw &>/dev/null; then
    log_info "Configuring firewall..."
    ufw allow 22/tcp    # SSH
    ufw allow 80/tcp    # Frontend
    ufw allow 443/tcp   # HTTPS (for future reverse proxy)
    ufw --force enable
    log_ok "Firewall configured (SSH + HTTP/HTTPS)."
  fi

  # Swap (safety net for 32GB)
  if [[ ! -f /swapfile ]]; then
    log_info "Creating 4GB swap..."
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    log_ok "4GB swap created."
  else
    log_ok "Swap already configured."
  fi

  echo ""
  log_ok "Server provisioning complete."
  log_info "Next steps:"
  log_info "  1. Reboot if NVIDIA drivers were just installed"
  log_info "  2. Copy .env.production.example to .env and fill secrets"
  log_info "  3. Run: scripts/deploy.sh deploy"
}

# ── Server setup check ──────────────────────────────────────────────────────

setup_check() {
  log_step "Server Setup Check"
  echo ""

  # Docker
  if command -v docker &>/dev/null; then
    log_ok "Docker: $(docker --version)"
  else
    log_error "Docker: NOT INSTALLED — run: scripts/deploy.sh init"
  fi

  # Docker Compose
  if docker compose version &>/dev/null; then
    log_ok "Docker Compose: $(docker compose version --short)"
  else
    log_error "Docker Compose: NOT INSTALLED"
  fi

  # GPU
  check_gpu || true

  # Disk space
  echo ""
  log_info "Disk usage:"
  df -h / | tail -1 | awk '{printf "  Root:  %s used of %s (%s free)\n", $3, $2, $4}'
  if [[ -d /var/lib/docker ]]; then
    local docker_size
    docker_size=$(du -sh /var/lib/docker 2>/dev/null | cut -f1 || echo "N/A")
    log_info "  Docker data: $docker_size"
  fi

  # Memory
  if [[ "$(uname)" == "Linux" ]]; then
    echo ""
    log_info "Memory:"
    free -h | head -2
    local swap
    swap=$(free -h | awk '/^Swap:/{print $2}')
    if [[ "$swap" == "0B" || "$swap" == "0" ]]; then
      log_warn "No swap configured. Recommended: scripts/deploy.sh init"
    fi
  fi

  # .env
  echo ""
  if [[ -f "$ROOT_DIR/.env" ]]; then
    log_ok ".env file exists"
    if grep -q "CHANGE_ME\|<<<" "$ROOT_DIR/.env" 2>/dev/null; then
      log_warn ".env still has placeholders — fill them before deploy!"
    fi
  else
    log_warn ".env file missing — copy from .env.production.example"
  fi

  # Pipeline repo
  if [[ -d "${PIPELINE_BUILD_CONTEXT:-.}/.git" ]]; then
    log_ok "Pipeline repo: exists at $PIPELINE_BUILD_CONTEXT"
  else
    log_warn "Pipeline repo: not cloned yet (will be cloned on deploy)"
  fi

  # Production config
  if [[ -f "$ROOT_DIR/deploy/pipeline.prod.yaml" ]]; then
    log_ok "Pipeline prod config: deploy/pipeline.prod.yaml exists"
  else
    log_warn "Pipeline prod config: deploy/pipeline.prod.yaml MISSING"
  fi

  echo ""
  log_step "Setup check complete"
}

# ── Main deploy ──────────────────────────────────────────────────────────────

deploy_all() {
  validate_secrets

  log_step "Starting full deploy"
  echo ""

  # GPU check (non-fatal)
  check_gpu || log_warn "Proceeding without GPU — pipeline will use CPU."

  # Pipeline
  ensure_pipeline_repo
  apply_pipeline_patch

  # Backup existing database if postgres is running
  if docker compose ps postgres --format '{{.State}}' 2>/dev/null | grep -q running; then
    backup_db || true
  fi

  # Build backend JAR via Docker (host network for Maven/Gradle downloads)
  if [[ ! -f "$ROOT_DIR/build/libs/malikov-backend.jar" ]] || [[ -n "$(find "$ROOT_DIR/src" -newer "$ROOT_DIR/build/libs/malikov-backend.jar" 2>/dev/null | head -1)" ]]; then
    log_info "Building backend JAR (Gradle via Docker) ..."
    docker run --rm --network=host \
      -v "$ROOT_DIR":/build -w /build \
      eclipse-temurin:21-jdk-alpine \
      sh -c "chmod +x gradlew && ./gradlew --no-daemon buildFatJar"
    log_ok "Backend JAR built."
  else
    log_ok "Backend JAR is up to date."
  fi

  # Start
  log_info "Starting full stack ..."
  dc up --build -d

  # Wait for health — services are not exposed on host ports in prod,
  # so we check via docker compose health status instead.
  echo ""
  log_info "Waiting for containers to become healthy..."
  local max_wait=360
  local elapsed=0
  while [[ $elapsed -lt $max_wait ]]; do
    local unhealthy
    unhealthy=$(dc ps --format '{{.Name}} {{.Health}}' 2>/dev/null | grep -v healthy | grep -v "" || true)
    local all_healthy=true
    for svc in malikov_pipeline malikov_app malikov_frontend; do
      local health
      health=$(docker inspect --format='{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "missing")
      if [[ "$health" != "healthy" ]]; then
        all_healthy=false
        break
      fi
    done
    if [[ "$all_healthy" == "true" ]]; then
      break
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    printf "\r  [%3ds / %ds] Waiting for services..." "$elapsed" "$max_wait"
  done
  echo ""
  if [[ $elapsed -ge $max_wait ]]; then
    log_warn "Some services may not be healthy yet. Check: docker compose ps"
  else
    log_ok "All core services healthy."
  fi

  echo ""
  log_ok "=== Deploy complete ==="
  check_status
}

# ── Command routing ──────────────────────────────────────────────────────────

case "${1:-deploy}" in
  deploy)
    deploy_all
    ;;
  pull)
    ensure_pipeline_repo
    apply_pipeline_patch
    ;;
  up)
    validate_secrets
    dc up --build -d
    ;;
  down)
    dc down
    ;;
  restart)
    dc restart
    ;;
  rebuild)
    local_service="${2:-}"
    # Rebuild JAR if source changed (needed for app/all)
    if [[ -z "$local_service" || "$local_service" == "app" ]]; then
      if [[ ! -f "$ROOT_DIR/build/libs/malikov-backend.jar" ]] || [[ -n "$(find "$ROOT_DIR/src" -newer "$ROOT_DIR/build/libs/malikov-backend.jar" 2>/dev/null | head -1)" ]]; then
        log_info "Building backend JAR (Gradle via Docker) ..."
        docker run --rm --network=host \
          -v "$ROOT_DIR":/build -w /build \
          eclipse-temurin:21-jdk-alpine \
          sh -c "chmod +x gradlew && ./gradlew --no-daemon buildFatJar"
        log_ok "Backend JAR built."
      else
        log_ok "Backend JAR is up to date."
      fi
    fi
    if [[ -z "$local_service" ]]; then
      log_info "Rebuilding all services..."
      dc up --build -d
    else
      log_info "Rebuilding $local_service..."
      dc up --build -d "$local_service"
    fi
    ;;
  logs)
    dc logs -f --tail=200 ${2:-}
    ;;
  ps)
    dc ps
    ;;
  backup)
    backup_db
    ;;
  restore)
    restore_db "$@"
    ;;
  status)
    check_status
    ;;
  setup)
    setup_check
    ;;
  init)
    server_init
    ;;
  *)
    cat <<'EOF'
Usage: scripts/deploy.sh [command]

Commands:
  deploy     Full deploy: clone pipeline + patch + build + up (default)
  up         Build and start all containers
  down       Stop and remove containers
  restart    Restart all containers
  rebuild    Rebuild and restart service (e.g. rebuild pipeline)
  logs       Follow container logs (optional: service name)
  ps         Show container status
  pull       Clone/update pipeline repo + apply patch only
  backup     Backup PostgreSQL database
  restore    Restore PostgreSQL from backup file
  status     Health check all services
  setup      Check server prerequisites
  init       Full first-time server provisioning (Docker, NVIDIA, etc.)

Examples:
  scripts/deploy.sh                     # Full deploy
  scripts/deploy.sh init                # First-time server setup (run as root)
  scripts/deploy.sh setup               # Check server prerequisites
  scripts/deploy.sh logs pipeline       # Follow pipeline logs only
  scripts/deploy.sh rebuild pipeline    # Rebuild and restart pipeline
  scripts/deploy.sh backup              # Manual DB backup
  scripts/deploy.sh restore backups/malikov_20260331.sql.gz
EOF
    exit 1
    ;;
esac
