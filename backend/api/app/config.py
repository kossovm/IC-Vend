"""Настройки из переменных окружения."""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str
    kaspi_url: str
    kaspi_webhook_secret: str
    admin_token: str
    run_reconciler: bool


def load() -> Settings:
    return Settings(
        database_url=os.environ.get("DATABASE_URL", "sqlite:///./icvend.db"),
        kaspi_url=os.environ.get("KASPI_URL", "http://kaspi:3000"),
        kaspi_webhook_secret=os.environ.get("KASPI_WEBHOOK_SECRET", ""),
        admin_token=os.environ.get("ADMIN_TOKEN", ""),
        run_reconciler=os.environ.get("DISABLE_RECONCILER", "") != "1",
    )


settings = load()
