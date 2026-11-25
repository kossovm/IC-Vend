"""Вебхук от сервиса kaspi-pos-automation."""
import hashlib
import hmac
import json
import logging

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import orders as svc
from .config import settings
from .db import get_db
from .kaspi import KaspiClient, get_kaspi
from .models import Order

log = logging.getLogger("icvend.webhook")
router = APIRouter(prefix="/api/kaspi", tags=["kaspi"])


def signature_ok(body: bytes, header: str | None) -> bool:
    if not settings.kaspi_webhook_secret or not header:
        return False
    expected = "sha256=" + hmac.new(settings.kaspi_webhook_secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header)


@router.post("/webhook")
async def webhook(request: Request, db: Session = Depends(get_db), kaspi: KaspiClient = Depends(get_kaspi)):
    raw = await request.body()
    if not signature_ok(raw, request.headers.get("x-webhook-signature")):
        raise HTTPException(401, "Неверная подпись")
    try:
        payload = json.loads(raw)
    except ValueError as e:
        raise HTTPException(400, "Не JSON") from e

    order = db.scalar(select(Order).where(Order.kaspi_op_id == str(payload.get("paymentId"))))
    if order is None:
        return {"ok": True, "ignored": "unknown payment"}

    if payload.get("event") == "payment.success" and payload.get("status") == "Processed":
        svc.apply_kaspi_status(db, kaspi, order, "Processed")
    else:
        # «Истёк», «отклонён» и «потерян» сервис иногда выставляет сам, не спросив Kaspi.
        # Поэтому таким событиям не верим на слово, а перепроверяем статус.
        log.info("webhook %s/%s for %s: rechecking", payload.get("event"), payload.get("status"), order.id)
        svc.check_now(db, kaspi, order)
    return {"ok": True}
