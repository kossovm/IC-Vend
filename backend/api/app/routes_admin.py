"""Админ-API. Каждый запрос несёт заголовок X-Admin-Token. Удобно дёргать через /docs."""
import hmac
import secrets

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import kaspi as k
from . import orders as svc
from .config import settings
from .db import get_db, utcnow
from .models import CELLS_PER_MACHINE, Cell, KaspiSession, Machine, Order


def require_admin(x_admin_token: str = Header(...)) -> None:
    if not settings.admin_token or not hmac.compare_digest(settings.admin_token, x_admin_token):
        raise HTTPException(401, "Неверный токен администратора")


router = APIRouter(prefix="/api/admin", tags=["admin"], dependencies=[Depends(require_admin)])


# ── Kaspi: вход кассира ──


class LoginStart(BaseModel):
    phone: str = Field(min_length=10, max_length=16, description="Номер кассира Kaspi Pay")


class LoginVerify(BaseModel):
    process_id: str
    otp: str = Field(min_length=4, max_length=8)


def _kaspi_call(fn, *args):
    try:
        return fn(*args)
    except k.KaspiError as e:
        raise HTTPException(502, str(e)) from e


@router.post("/kaspi/login/start")
def kaspi_login_start(body: LoginStart, kaspi: k.KaspiClient = Depends(k.get_kaspi)):
    """Шаг 1: Kaspi отправит SMS на номер кассира."""
    return _kaspi_call(kaspi.auth_start, body.phone)


@router.post("/kaspi/login/verify")
def kaspi_login_verify(
    body: LoginVerify, db: Session = Depends(get_db), kaspi: k.KaspiClient = Depends(k.get_kaspi)
):
    """Шаг 2: код из SMS. Сессия сохраняется и дальше используется всеми автоматами."""
    r = _kaspi_call(kaspi.auth_verify, body.process_id, body.otp)
    row = db.get(KaspiSession, 1) or KaspiSession(id=1, token_sn="", vtoken_secret="")
    row.token_sn = r["tokenSN"]
    row.vtoken_secret = r["vtokenSecret"]
    row.profile_id = str(r["profileId"]) if r.get("profileId") is not None else None
    row.org_name = r.get("orgName")
    row.phone = r.get("phone")
    row.updated_at = utcnow()
    db.merge(row)
    db.commit()
    return {"ok": True, "org_name": row.org_name, "phone": row.phone}


@router.get("/kaspi/status")
def kaspi_status(db: Session = Depends(get_db), kaspi: k.KaspiClient = Depends(k.get_kaspi)):
    """Жива ли сессия кассира (делает реальный запрос в Kaspi)."""
    row = db.get(KaspiSession, 1)
    if row is None:
        return {"connected": False, "active": False}
    try:
        active = kaspi.session_check(svc.get_creds(db))
    except k.KaspiError as e:
        return {"connected": True, "active": None, "error": str(e), "org_name": row.org_name}
    return {"connected": True, "active": active, "org_name": row.org_name, "since": row.updated_at.isoformat() + "Z"}


# ── автоматы и ячейки ──


class MachineCreate(BaseModel):
    code: str = Field(min_length=1, max_length=64, description="Например, заводской номер ZX20000901")
    name: str = ""


class CellUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    price: int | None = Field(default=None, ge=0, le=1_000_000)
    stock: int | None = Field(default=None, ge=0, le=999)
    image_url: str | None = Field(default=None, max_length=500)
    enabled: bool | None = None


def _machine_view(db: Session, m: Machine, with_token: bool = False) -> dict:
    cells = db.scalars(select(Cell).where(Cell.machine_id == m.id).order_by(Cell.number)).all()
    out = {
        "id": m.id,
        "code": m.code,
        "name": m.name,
        "last_seen_at": m.last_seen_at.isoformat() + "Z" if m.last_seen_at else None,
        "cells": [
            {
                "number": c.number, "title": c.title, "price": c.price, "stock": c.stock,
                "image_url": c.image_url, "enabled": c.enabled,
            }
            for c in cells
        ],
    }
    if with_token:
        out["token"] = m.token
    return out


@router.post("/machines")
def create_machine(body: MachineCreate, db: Session = Depends(get_db)):
    """Создаёт автомат и 8 пустых ячеек. Токен из ответа вводится на автомате один раз."""
    if db.scalar(select(Machine).where(Machine.code == body.code)):
        raise HTTPException(409, "Автомат с таким кодом уже есть")
    m = Machine(code=body.code, name=body.name, token=secrets.token_urlsafe(32))
    db.add(m)
    db.flush()
    for n in range(1, CELLS_PER_MACHINE + 1):
        db.add(Cell(machine_id=m.id, number=n))
    db.commit()
    return _machine_view(db, m, with_token=True)


@router.get("/machines")
def list_machines(db: Session = Depends(get_db)):
    return [_machine_view(db, m) for m in db.scalars(select(Machine).order_by(Machine.id)).all()]


@router.post("/machines/{machine_id}/rotate-token")
def rotate_token(machine_id: int, db: Session = Depends(get_db)):
    m = db.get(Machine, machine_id)
    if m is None:
        raise HTTPException(404, "Автомат не найден")
    m.token = secrets.token_urlsafe(32)
    db.commit()
    return _machine_view(db, m, with_token=True)


@router.put("/machines/{machine_id}/cells/{number}")
def update_cell(machine_id: int, number: int, body: CellUpdate, db: Session = Depends(get_db)):
    cell = db.scalar(select(Cell).where(Cell.machine_id == machine_id, Cell.number == number))
    if cell is None:
        raise HTTPException(404, "Нет такой ячейки")
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(cell, field, value)
    db.commit()
    return _machine_view(db, db.get(Machine, machine_id))


# ── заказы ──


def _order_admin_view(o: Order) -> dict:
    iso = lambda d: d.isoformat() + "Z" if d else None  # noqa: E731
    return {
        "id": o.id, "machine_id": o.machine_id, "cell": o.cell_number, "title": o.title, "amount": o.amount,
        "status": o.status, "kaspi_op_id": o.kaspi_op_id, "kaspi_status": o.kaspi_status,
        "created_at": iso(o.created_at), "paid_at": iso(o.paid_at), "finished_at": iso(o.finished_at),
        "note": o.note,
    }


@router.get("/orders")
def list_orders(status: str | None = None, limit: int = 100, db: Session = Depends(get_db)):
    q = select(Order).order_by(Order.created_at.desc()).limit(min(max(limit, 1), 500))
    if status:
        q = q.where(Order.status == status)
    return [_order_admin_view(o) for o in db.scalars(q).all()]


@router.post("/orders/{order_id}/recheck")
def recheck(order_id: str, db: Session = Depends(get_db), kaspi: k.KaspiClient = Depends(k.get_kaspi)):
    """Заново спросить Kaspi о платеже (для заказов в needs_review)."""
    o = db.get(Order, order_id)
    if o is None:
        raise HTTPException(404, "Заказ не найден")
    ok = svc.check_now(db, kaspi, o)
    return {"checked": ok, "order": _order_admin_view(o)}


@router.post("/orders/{order_id}/refund")
def refund(order_id: str, db: Session = Depends(get_db), kaspi: k.KaspiClient = Depends(k.get_kaspi)):
    """Вернуть деньги вручную (повтор после refund_failed или решение по needs_review)."""
    o = db.get(Order, order_id)
    if o is None:
        raise HTTPException(404, "Заказ не найден")
    ok = svc.refund_order(db, kaspi, o, "возврат администратором")
    return {"refunded": ok, "order": _order_admin_view(o)}


class Resolve(BaseModel):
    status: str = Field(pattern="^(done|refunded|cancelled)$")
    note: str = ""


@router.post("/orders/{order_id}/resolve")
def resolve(order_id: str, body: Resolve, db: Session = Depends(get_db)):
    """Закрыть спорный заказ после ручной проверки (деньги при этом никуда не двигаются)."""
    o = db.get(Order, order_id)
    if o is None:
        raise HTTPException(404, "Заказ не найден")
    if o.status not in (svc.NEEDS_REVIEW, svc.REFUND_FAILED):
        raise HTTPException(409, "Вручную закрываются только заказы needs_review и refund_failed")
    o.status = body.status
    o.status_changed_at = utcnow()
    o.finished_at = utcnow()
    o.kaspi_closed = True
    o.note = ((o.note or "") + f" | закрыт вручную: {body.note}").strip(" |")
    db.commit()
    return _order_admin_view(o)
