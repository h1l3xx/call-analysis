"""
Scanovich API uploader.

После того как файл скачан из lk.stranzit.ru, он отправляется через
POST /api/v1/calls/bulk-upload в Scanovich backend.
Бэкенд сам разбирает имя файла через PhoneParser, определяет менеджера
и направление звонка, после чего запускает ASR-пайплайн.
"""

import logging
import time
from typing import Optional

import requests

logger = logging.getLogger(__name__)

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
            # accessExpiresAt приходит в миллисекундах
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

    def upload(self, filepath: str, filename: str) -> bool:
        """Отправить файл в Scanovich bulk-upload.

        Возвращает True если хотя бы один файл принят в очередь (queued > 0).
        """
        if not self._ensure_token():
            return False

        result = self._do_upload(filepath, filename)

        if result is None:
            return False

        # HTTP 401 → re-login + retry
        if result == 401:
            logger.info("Scanovich: токен протух, повторная аутентификация…")
            self._token = None
            if not self._login():
                return False
            result = self._do_upload(filepath, filename)
            if result is None or isinstance(result, int):
                return False

        return self._log_result(filename, result)

    def _do_upload(self, filepath: str, filename: str) -> Optional[dict | int]:
        """Выполнить HTTP-запрос. Возвращает dict с ответом, int с кодом ошибки или None."""
        ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else "mp3"
        mime = _MIME_BY_EXT.get(ext, "application/octet-stream")
        try:
            with open(filepath, "rb") as fh:
                resp = requests.post(
                    f"{self.base_url}/api/v1/calls/bulk-upload",
                    headers={"Authorization": f"Bearer {self._token}"},
                    files={"files": (filename, fh, mime)},
                    timeout=120,
                )
            if resp.status_code == 401:
                return 401
            resp.raise_for_status()
            return resp.json()
        except requests.HTTPError as exc:
            logger.error("Scanovich: HTTP-ошибка при загрузке %s — %s", filename, exc)
            return None
        except Exception as exc:
            logger.error("Scanovich: ошибка при загрузке %s — %s", filename, exc)
            return None

    @staticmethod
    def _log_result(filename: str, data: dict) -> bool:
        queued = data.get("queued", 0)
        failed = data.get("failed", 0)
        pre_no_speech = data.get("preNoSpeech", 0)
        logger.info(
            "Scanovich bulk-upload [%s]: queued=%d, failed=%d, noSpeech=%d",
            filename, queued, failed, pre_no_speech,
        )
        for item in data.get("results", []):
            status = item.get("status", "?")
            if status not in ("queued", "no_speech"):
                logger.warning(
                    "  [%s] %s — %s",
                    status, item.get("filename"), item.get("error", ""),
                )
        return queued > 0
