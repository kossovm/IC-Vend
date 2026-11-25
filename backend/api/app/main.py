"""IC Vending: сервер автоматов."""
import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import models  # noqa: F401  (регистрирует таблицы)
from .config import settings
from .db import Base, SessionLocal, engine
from .kaspi import get_kaspi
from .orders import OrderError, reconcile_once
from .routes_admin import router as admin_router
from .routes_kaspi import router as kaspi_router
from .routes_machine import router as machine_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("icvend")

RECONCILE_EVERY = 3.0  # секунд


def _reconciler(stop: threading.Event) -> None:
    while not stop.wait(RECONCILE_EVERY):
        try:
            with SessionLocal() as db:
                reconcile_once(db, get_kaspi())
        except Exception:
            log.exception("reconciler tick failed")


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    stop = threading.Event()
    if settings.run_reconciler:
        threading.Thread(target=_reconciler, args=(stop,), daemon=True, name="reconciler").start()
    yield
    stop.set()


app = FastAPI(title="IC Vending", lifespan=lifespan)


@app.exception_handler(OrderError)
async def order_error_handler(request: Request, exc: OrderError):
    return JSONResponse(status_code=exc.http_status, content={"detail": exc.message})


@app.get("/health")
def health():
    return {"status": "ok"}


app.include_router(machine_router)
app.include_router(kaspi_router)
app.include_router(admin_router)
