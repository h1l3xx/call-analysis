"""
Scanovich API uploader.

После того как файл скачан из lk.stranzit.ru, он отправляется через
POST /api/v1/calls/bulk-upload в Scanovich backend.
Бэкенд сам разбирает имя файла через PhoneParser, определяет менеджера
и направление звонка, после чего запускает ASR-пайплайн.

Большие батчи автоматически разбиваются на чанки по CHUNK_SIZE файлов
и отправляются последовательно в рамках одного batchId.
"""

import logging
import time
from typing import Optional

import requests

logger = logging.getLogger(__name__)

CHUNK_SIZE = 200  # файлов за один HTTP-запрос (совпадает с лимитом фронтенда)

_MIME_BY_EXT = {
    "mp3": "audio/mpeg",
    "wav": "audio/wav",
    "m4a": "audio/mp4",
    "ogg": "audio/ogg",
    "flac": "audio/flac",
    "webm": "audio/webm",
    "opus": "audio/ogg; codecs=opus",
}


class ScanovichUploader:
    """Аутентификация и загрузка файлов в Scanovich backend.

    Токен кэшируется и автоматически обновляется за 60 секунд до истечения.
    При 401 выполняется один автоматический re-login.
    """

    def __init__(self, url: str, email: str, password: str) -> None:
        self.base_url = url.rstrip("/")
        self.email = email
        self.password = password
        self._token: Optional[str] = None
        self._token_expires_at: float = 0.0

    # ──────────────────────────── auth ────────────────────────────

    def _login(self) -> bool:
        try:
            resp = requests.post(
                f"{self.base_url}/api/v1/auth/login",
                json={"email": self.email, "password": self.password},
                timeout=15,
            )
            resp.raise_for_status()
            data = resp.json()
            self._token = data["accessToken"]
            self._token_expires_at = data["accessExpiresAt"] / 1000.0
            logger.info("Scanovich: аутентификация успешна")
            return True
        except Exception as exc:
            logger.error("Scanovich: ошибка аутентификации — %s", exc)
            self._token = None
            return False

    def _ensure_token(self) -> bool:
        if self._token is None or time.time() >= self._token_expires_at - 60:
            return self._login()
        return True

    # ──────────────────────────── upload ────────────────────────────

    def upload_batch(self, files: list[tuple[str, str]]) -> dict[str, bool]:
        """Отправить файлы в Scanovich, автоматически разбивая на чанки.

        files: список пар (filepath, filename).
        Возвращает dict {filepath: True/False} с результатом для каждого файла.
        Все чанки объединяются в один batch на стороне бэкенда (один batchId).
        """
        if not files:
            return {}
        if not self._ensure_token():
            return {fp: False for fp, _ in files}

        chunks = [files[i:i + CHUNK_SIZE] for i in range(0, len(files), CHUNK_SIZE)]
        total_chunks = len(chunks)
        batch_id: Optional[str] = None
        all_results: dict[str, bool] = {}

        logger.info(
            "Scanovich: загрузка %d файлов, %d чанк(ов) по %d",
            len(files), total_chunks, CHUNK_SIZE,
        )

        for idx, chunk in enumerate(chunks):
            is_last = (idx == total_chunks - 1)
            chunk_num = idx + 1

            result = self._do_upload_chunk(chunk, batch_id=batch_id, final=is_last)

            if result == 401:
                logger.info("Scanovich: токен протух, повторная аутентификация…")
                self._token = None
                if not self._login():
                    for fp, _ in chunk:
                        all_results[fp] = False
                    continue
                result = self._do_upload_chunk(chunk, batch_id=batch_id, final=is_last)

            if result is None or isinstance(result, int):
                logger.error("Scanovich: чанк %d/%d не загружен", chunk_num, total_chunks)
                for fp, _ in chunk:
                    all_results[fp] = False
                continue

            if batch_id is None:
                batch_id = result.get("batchId")

            chunk_results = self._log_chunk_result(chunk, result, chunk_num, total_chunks)
            all_results.update(chunk_results)

        return all_results

    def upload(self, filepath: str, filename: str) -> bool:
        """Отправить один файл (обёртка над upload_batch)."""
        results = self.upload_batch([(filepath, filename)])
        return results.get(filepath, False)

    def _do_upload_chunk(
        self,
        files: list[tuple[str, str]],
        batch_id: Optional[str],
        final: bool,
    ) -> Optional[dict | int]:
        """Multipart-запрос одного чанка. Возвращает dict, 401 или None при ошибке."""
        opened = []
        try:
            multipart = []
            for filepath, filename in files:
                ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else "mp3"
                mime = _MIME_BY_EXT.get(ext, "application/octet-stream")
                fh = open(filepath, "rb")  # noqa: WPS515
                opened.append(fh)
                multipart.append(("files", (filename, fh, mime)))

            params: dict[str, str] = {"final": "true" if final else "false"}
            if batch_id:
                params["batchId"] = batch_id

            resp = requests.post(
                f"{self.base_url}/api/v1/calls/bulk-upload",
                headers={"Authorization": f"Bearer {self._token}"},
                files=multipart,
                params=params,
                timeout=120 + 30 * len(files),
            )
            if resp.status_code == 401:
                return 401
            resp.raise_for_status()
            return resp.json()
        except requests.HTTPError as exc:
            logger.error("Scanovich: HTTP-ошибка при загрузке чанка — %s", exc)
            return None
        except Exception as exc:
            logger.error("Scanovich: ошибка при загрузке чанка — %s", exc)
            return None
        finally:
            for fh in opened:
                fh.close()

    @staticmethod
    def _log_chunk_result(
        files: list[tuple[str, str]],
        data: dict,
        chunk_num: int,
        total_chunks: int,
    ) -> dict[str, bool]:
        """Логировать итог одного чанка и вернуть {filepath: успех}."""
        queued = data.get("queued", 0)
        failed = data.get("failed", 0)
        items = data.get("items", [])
        no_speech = sum(1 for i in items if i.get("status") == "no_speech")
        skipped = sum(1 for i in items if i.get("status") == "skipped")

        logger.info(
            "Scanovich чанк %d/%d (%d файлов): queued=%d, skipped=%d, noSpeech=%d, failed=%d",
            chunk_num, total_chunks, len(files), queued, skipped, no_speech, failed,
        )

        name_to_path = {fname: fpath for fpath, fname in files}
        result_by_name: dict[str, bool] = {}

        for item in items:
            status = item.get("status", "?")
            fname = item.get("filename", "")
            ok = status in ("queued", "no_speech", "skipped")
            result_by_name[fname] = ok
            if not ok:
                logger.warning("  [%s] %s — %s", status, fname, item.get("error", ""))

        out: dict[str, bool] = {}
        for fpath, fname in files:
            out[fpath] = result_by_name.get(fname, queued > 0)
        return out
