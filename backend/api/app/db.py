"""Подключение к базе."""
from datetime import datetime, timezone

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from sqlalchemy.pool import StaticPool

from .config import settings


def utcnow() -> datetime:
    """Время в UTC без tzinfo: так оно одинаково хранится в Postgres и SQLite."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


_kwargs = {}
if settings.database_url.startswith("sqlite"):
    _kwargs["connect_args"] = {"check_same_thread": False}
    if settings.database_url in ("sqlite://", "sqlite:///:memory:"):
        _kwargs["poolclass"] = StaticPool

engine = create_engine(settings.database_url, **_kwargs)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
