# vLLM Profiles

This project supports vLLM via `docker-compose.vllm.yml` and env presets in:

- `deploy/vllm/profiles/l4-24gb.env`
- `deploy/vllm/profiles/a100-40gb.env`
- `deploy/vllm/profiles/a100-80gb.env`
- `deploy/vllm/profiles/dual-a100-80gb.env`

## Quick start

1) Enable vLLM in `.env`:

```bash
ENABLE_VLLM=true
VLLM_PROFILE_ENV=deploy/vllm/profiles/l4-24gb.env
```

2) Deploy:

```bash
./scripts/deploy.sh deploy
```

## Tuning notes

- `VLLM_GPU_MEMORY_UTILIZATION`: start from `0.90-0.93`, lower if OOM.
- `VLLM_MAX_MODEL_LEN`: reduce first when memory is tight.
- `VLLM_MAX_NUM_SEQS`: controls concurrency; higher = more throughput, more VRAM.
- `VLLM_TENSOR_PARALLEL_SIZE`: set to number of GPUs for multi-GPU servers.
- `PIPELINE_VLLM_TIMEOUT` and `PIPELINE_QUALITY_TIMEOUT`: increase for long calls.

## Recommended defaults

- **24GB GPU (L4/A10):** `Qwen2.5-14B-AWQ`, context `8k`, low concurrency.
- **40GB GPU (A100 40G):** `Qwen2.5-32B-AWQ`, context `12k`, medium concurrency.
- **80GB GPU (A100/H100):** `Qwen2.5-72B-AWQ`, context `16k`, higher concurrency.
- **2x80GB:** `Qwen2.5-72B-AWQ`, tensor parallel `2`, context `24k`.

If your infrastructure uses different models, keep the same profile shape and change only:
- `VLLM_MODEL`
- `VLLM_DTYPE`
- `VLLM_EXTRA_ARGS`
