"""Клиент к сервису kaspi-pos-automation (он живёт рядом, в соседнем контейнере)."""
from dataclasses import dataclass
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

from .config import settings
from .db import utcnow

# Статусы QR-платежа у Kaspi (те же группы, что в самом сервисе).
INTERMEDIATE = {"QrTokenCreated", "Wait"}
SUCCESS = {"Processed"}
EXPIRED = {"QrTokenDiscarded", "Expired"}
SESSION_EVICTED_CODE = -101001

KASPI_TZ = ZoneInfo("Asia/Almaty")
DEFAULT_QR_TTL = timedelta(seconds=180)


class KaspiError(Exception):
    """Kaspi или сервис ответили ошибкой, либо недоступны."""


class KaspiSessionError(KaspiError):
    """Нет сессии кассира или Kaspi её отозвал: нужен новый вход по SMS."""


@dataclass(frozen=True)
class Creds:
    token_sn: str
    vtoken_secret: str
    profile_id: str | None


@dataclass(frozen=True)
class QrInfo:
    op_id: str
    payload: str  # строка, которую рисуем как QR
    expires_at: datetime  # UTC


def parse_expire(value: str | None, now: datetime | None = None) -> datetime:
    """ExpireDate у Kaspi приходит без часового пояса: считаем его временем Алматы.
    Если результат выглядит неправдоподобно, берём стандартные 3 минуты."""
    now = now or utcnow()
    fallback = now + DEFAULT_QR_TTL
    if not value:
        return fallback
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return fallback
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=KASPI_TZ)
    utc = dt.astimezone(ZoneInfo("UTC")).replace(tzinfo=None)
    if not (now < utc <= now + timedelta(minutes=15)):
        return fallback
    return utc


class KaspiClient:
    def __init__(self, base_url: str | None = None, timeout: float = 25.0):
        self.http = httpx.Client(base_url=base_url or settings.kaspi_url, timeout=timeout)

    # ── общее ──

    @staticmethod
    def _headers(creds: Creds) -> dict:
        h = {"X-Token-SN": creds.token_sn, "X-Vtoken-Secret": creds.vtoken_secret}
        if creds.profile_id:
            h["X-Profile-Id"] = str(creds.profile_id)
        return h

    def _call(self, method: str, path: str, creds: Creds | None = None, **kwargs) -> dict:
        headers = self._headers(creds) if creds else {}
        try:
            resp = self.http.request(method, path, headers=headers, **kwargs)
        except httpx.HTTPError as e:
            raise KaspiError(f"сервис Kaspi недоступен: {e}") from e
        try:
            body = resp.json()
        except ValueError as e:
            raise KaspiError(f"не JSON в ответе ({resp.status_code})") from e
        if resp.status_code == 401:
            raise KaspiSessionError(str(body.get("error") or "сессия не принята"))
        if isinstance(body, dict) and body.get("StatusCode") == SESSION_EVICTED_CODE:
            raise KaspiSessionError("сессия кассира вытеснена входом с другого устройства")
        if resp.status_code >= 400:
            raise KaspiError(str(body.get("error") if isinstance(body, dict) else body))
        return body

    @staticmethod
    def _data(body: dict) -> dict:
        code = body.get("StatusCode")
        data = body.get("Data")
        if code not in (0, None) or not isinstance(data, dict):
            raise KaspiError(f"Kaspi вернул ошибку: StatusCode={code} {body.get('Message') or ''}".strip())
        return data

    # ── QR ──

    def qr_create(self, creds: Creds, amount: int) -> QrInfo:
        data = self._data(self._call("POST", "/api/qr/create", creds, json={"amount": amount}))
        op_id = data.get("QrOperationId")
        payload = data.get("QrOriginalToken") or data.get("QrToken")
        if not op_id or not payload:
            raise KaspiError("в ответе нет QrOperationId или QR-ссылки")
        return QrInfo(op_id=str(op_id), payload=payload, expires_at=parse_expire(data.get("ExpireDate")))

    def qr_status(self, creds: Creds, op_id: str) -> str:
        body = self._call("GET", "/api/qr/status", creds, params={"qrOperationId": op_id})
        status = self._data(body).get("Status")
        if not status:
            raise KaspiError("в ответе нет статуса")
        return str(status)

    def refund(self, creds: Creds, op_id: str, amount: int) -> dict:
        body = self._call(
            "POST", "/api/refund/create", creds, json={"qrOperationId": int(op_id), "returnAmount": amount}
        )
        code = body.get("StatusCode")
        if code not in (0, None):
            raise KaspiError(f"возврат отклонён: StatusCode={code} {body.get('Message') or ''}".strip())
        return body

    def session_check(self, creds: Creds) -> bool:
        try:
            body = self._call("GET", "/api/session/check", creds)
        except KaspiSessionError:
            return False
        return bool(body.get("active"))

    # ── вход кассира по SMS ──

    def auth_start(self, phone: str) -> dict:
        init = self._call("POST", "/api/auth/init")
        process_id = init.get("processId")
        if not process_id:
            raise KaspiError("Kaspi не начал вход (нет processId)")
        sent = self._call("POST", "/api/auth/send-phone", json={"phoneNumber": phone, "processId": process_id})
        if not sent.get("success"):
            raise KaspiError(f"Kaspi не отправил SMS: {sent.get('view') or sent}")
        return {"process_id": process_id, "desc": sent.get("desc")}

    def auth_verify(self, process_id: str, otp: str) -> dict:
        r = self._call("POST", "/api/auth/verify-otp", json={"otp": otp, "processId": process_id})
        if not r.get("success") or not r.get("tokenSN") or not r.get("vtokenSecret"):
            raise KaspiError("код не принят или вход не завершён")
        return r


_client: KaspiClient | None = None


def get_kaspi() -> KaspiClient:
    global _client
    if _client is None:
        _client = KaspiClient()
    return _client
