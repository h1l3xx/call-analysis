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

    def upload_batch(self, files: list[tuple[str, str]]) -> dict[str, bool]:
        """Отправить несколько файлов одним запросом — один батч в Scanovich.

        files: список пар (filepath, filename).
        Возвращает dict {filepath: True/False} с результатом для каждого файла.
        """
        if not files:
            return {}
        if not self._ensure_token():
            return {fp: False for fp, _ in files}

        result = self._do_upload_batch(files)

        if result == 401:
            logger.info("Scanovich: токен протух, повторная аутентификация…")
            self._token = None
            if not self._login():
                return {fp: False for fp, _ in files}
            result = self._do_upload_batch(files)

        if result is None or isinstance(result, int):
            return {fp: False for fp, _ in files}

        return self._log_batch_result(files, result)

    def upload(self, filepath: str, filename: str) -> bool:
        """Отправить один файл (обёртка над upload_batch)."""
        results = self.upload_batch([(filepath, filename)])
        return results.get(filepath, False)

    def _do_upload_batch(self, files: list[tuple[str, str]]) -> Optional[dict | int]:
        """Multipart-запрос с несколькими файлами. Возвращает dict, 401 или None."""
        opened = []
        try:
            multipart = []
            for filepath, filename in files:
                ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else "mp3"
                mime = _MIME_BY_EXT.get(ext, "application/octet-stream")
                fh = open(filepath, "rb")  # noqa: WPS515
                opened.append(fh)
                multipart.append(("files", (filename, fh, mime)))

            resp = requests.post(
                f"{self.base_url}/api/v1/calls/bulk-upload",
                headers={"Authorization": f"Bearer {self._token}"},
                files=multipart,
                timeout=120 + 30 * len(files),
            )
            if resp.status_code == 401:
                return 401
            resp.raise_for_status()
            return resp.json()
        except requests.HTTPError as exc:
            logger.error("Scanovich: HTTP-ошибка при загрузке батча — %s", exc)
            return None
        except Exception as exc:
            logger.error("Scanovich: ошибка при загрузке батча — %s", exc)
            return None
        finally:
            for fh in opened:
                fh.close()

    def _do_upload(self, filepath: str, filename: str) -> Optional[dict | int]:
        return self._do_upload_batch([(filepath, filename)])

    @staticmethod
    def _log_batch_result(files: list[tuple[str, str]], data: dict) -> dict[str, bool]:
        """Логировать итог батч-загрузки и вернуть {filepath: успех}."""
        queued = data.get("queued", 0)
        failed = data.get("failed", 0)
        pre_no_speech = data.get("preNoSpeech", 0)
        logger.info(
            "Scanovich bulk-upload (%d файлов): queued=%d, failed=%d, noSpeech=%d",
            len(files), queued, failed, pre_no_speech,
        )

        # Строим маппинг filename → filepath для обратного матчинга
        name_to_path = {fname: fpath for fpath, fname in files}
        result_by_name: dict[str, bool] = {}

        for item in data.get("results", []):
            status = item.get("status", "?")
            fname = item.get("filename", "")
            ok = status in ("queued", "no_speech")
            result_by_name[fname] = ok
            if not ok:
                logger.warning("  [%s] %s — %s", status, fname, item.get("error", ""))

        # Возвращаем результат по filepath
        out: dict[str, bool] = {}
        for fpath, fname in files:
            out[fpath] = result_by_name.get(fname, queued > 0)
        return out
