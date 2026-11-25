"""Таблицы: автоматы, ячейки, заказы, сессия кассира Kaspi."""
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base, utcnow

CELLS_PER_MACHINE = 8


class Machine(Base):
    __tablename__ = "machines"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(200), default="")
    token: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Cell(Base):
    __tablename__ = "cells"
    __table_args__ = (UniqueConstraint("machine_id", "number"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    machine_id: Mapped[int] = mapped_column(ForeignKey("machines.id"), index=True)
    number: Mapped[int] = mapped_column(Integer)  # номер наклейки 1..8
    title: Mapped[str] = mapped_column(String(200), default="")
    price: Mapped[int] = mapped_column(Integer, default=0)  # тенге
    stock: Mapped[int] = mapped_column(Integer, default=0)
    image_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)


class Order(Base):
    __tablename__ = "orders"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    machine_id: Mapped[int] = mapped_column(ForeignKey("machines.id"), index=True)
    cell_number: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(200), default="")
    amount: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(32), index=True)
    status_changed_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)

    # Kaspi
    kaspi_op_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    kaspi_status: Mapped[str | None] = mapped_column(String(64), nullable=True)
    kaspi_closed: Mapped[bool] = mapped_column(Boolean, default=False)  # Kaspi дал финальный статус
    qr_payload: Mapped[str | None] = mapped_column(Text, nullable=True)
    qr_expires_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_check_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_check_ok_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    # Выдача
    dispense_token: Mapped[str | None] = mapped_column(String(64), nullable=True)
    dispense_started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    paid_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


class KaspiSession(Base):
    """Одна строка (id=1): сессия кассира, общая для всех автоматов."""

    __tablename__ = "kaspi_session"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    token_sn: Mapped[str] = mapped_column(Text)
    vtoken_secret: Mapped[str] = mapped_column(Text)
    profile_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    org_name: Mapped[str | None] = mapped_column(String(300), nullable=True)
    phone: Mapped[str | None] = mapped_column(String(32), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
