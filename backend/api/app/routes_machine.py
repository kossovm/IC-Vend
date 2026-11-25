"""API для автомата. Каждый запрос несёт заголовок X-Machine-Token."""
import hmac
from datetime import timedelta

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import orders as svc
from .db import get_db, utcnow
from .kaspi import KaspiClient, get_kaspi
from .models import Cell, KaspiSession, Machine, Order

router = APIRouter(prefix="/api/machine", tags=["machine"])


def current_machine(x_machine_token: str = Header(...), db: Session = Depends(get_db)) -> Machine:
    machine = db.scalar(select(Machine).where(Machine.token == x_machine_token))
    if machine is None or not hmac.compare_digest(machine.token, x_machine_token):
        raise HTTPException(401, "Неизвестный автомат")
    now = utcnow()
    if machine.last_seen_at is None or now - machine.last_seen_at > timedelta(seconds=30):
        machine.last_seen_at = now
        db.commit()
    return machine


def _order(db: Session, machine: Machine, order_id: str) -> Order:
    order = db.get(Order, order_id)
    if order is None or order.machine_id != machine.id:
        raise HTTPException(404, "Заказ не найден")
    return order


def _cell_view(c: Cell) -> dict:
    return {
        "number": c.number,
        "title": c.title,
        "price": c.price,
        "stock": c.stock,
        "image_url": c.image_url,
        "available": bool(c.enabled and c.price > 0 and c.stock > 0),
    }


class CreateOrder(BaseModel):
    cell: int = Field(ge=1, le=8)


class DispenseStart(BaseModel):
    token: str = Field(min_length=8, max_length=64)


class DispenseResult(BaseModel):
    token: str = Field(min_length=8, max_length=64)
    result: str = Field(pattern="^(ok|motor_error|no_reply|unknown)$")


class CellRefill(BaseModel):
    stock: int | None = Field(default=None, ge=0, le=999)
    enabled: bool | None = None


@router.get("/state")
def state(machine: Machine = Depends(current_machine), db: Session = Depends(get_db)):
    """Всё, что нужно для витрины: ячейки, готовность оплаты, незавершённый заказ."""
    cells = db.scalars(select(Cell).where(Cell.machine_id == machine.id).order_by(Cell.number)).all()
    active = db.scalar(
        select(Order)
        .where(
            Order.machine_id == machine.id,
            Order.status.in_([svc.AWAITING, svc.PAID, svc.DISPENSING, svc.REFUND_PENDING]),
        )
        .order_by(Order.created_at.desc())
    )
    return {
        "machine": {"code": machine.code, "name": machine.name},
        "kaspi_ready": db.get(KaspiSession, 1) is not None,
        "cells": [_cell_view(c) for c in cells],
        "active_order": svc.order_view(active) if active else None,
    }


@router.post("/orders")
def create_order(
    body: CreateOrder,
    machine: Machine = Depends(current_machine),
    db: Session = Depends(get_db),
    kaspi: KaspiClient = Depends(get_kaspi),
):
    return svc.order_view(svc.create_order(db, kaspi, machine, body.cell))


@router.get("/orders/{order_id}")
def get_order(order_id: str, machine: Machine = Depends(current_machine), db: Session = Depends(get_db)):
    return svc.order_view(_order(db, machine, order_id))


@router.post("/orders/{order_id}/cancel")
def cancel(order_id: str, machine: Machine = Depends(current_machine), db: Session = Depends(get_db)):
    order = _order(db, machine, order_id)
    svc.cancel_order(db, order)
    return svc.order_view(order)


@router.post("/orders/{order_id}/dispense-start")
def dispense_start(
    order_id: str, body: DispenseStart, machine: Machine = Depends(current_machine), db: Session = Depends(get_db)
):
    """Автомат спрашивает разрешение крутить мотор. «allowed: true» выдаётся на заказ один раз."""
    order = _order(db, machine, order_id)
    allowed = svc.dispense_start(db, order, body.token)
    return {"allowed": allowed, "order": svc.order_view(order)}


@router.post("/orders/{order_id}/dispense-result")
def dispense_result(
    order_id: str,
    body: DispenseResult,
    machine: Machine = Depends(current_machine),
    db: Session = Depends(get_db),
    kaspi: KaspiClient = Depends(get_kaspi),
):
    order = _order(db, machine, order_id)
    svc.dispense_result(db, kaspi, order, body.token, body.result)
    return svc.order_view(order)


@router.put("/cells/{number}")
def refill(
    number: int, body: CellRefill, machine: Machine = Depends(current_machine), db: Session = Depends(get_db)
):
    """Пополнение с экрана автомата (локальная админка по PIN)."""
    cell = db.scalar(select(Cell).where(Cell.machine_id == machine.id, Cell.number == number))
    if cell is None:
        raise HTTPException(404, "Нет такой ячейки")
    if body.stock is not None:
        cell.stock = body.stock
    if body.enabled is not None:
        cell.enabled = body.enabled
    db.commit()
    return _cell_view(cell)
