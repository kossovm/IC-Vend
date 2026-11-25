import os

os.environ["DATABASE_URL"] = "sqlite://"
os.environ["DISABLE_RECONCILER"] = "1"
os.environ["ADMIN_TOKEN"] = "admin-secret"
os.environ["KASPI_WEBHOOK_SECRET"] = "hook-secret"

from datetime import timedelta  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import kaspi as k  # noqa: E402
from app.db import Base, SessionLocal, engine, utcnow  # noqa: E402
from app.main import app  # noqa: E402
from app.models import KaspiSession  # noqa: E402


class FakeKaspi:
    """Поддельный Kaspi: статусы платежей задаёт тест."""

    def __init__(self):
        self.next_id = 1000
        self.statuses: dict[str, str] = {}
        self.refunds: list[tuple[str, int]] = []
        self.refund_error: Exception | None = None
        self.status_error: Exception | None = None
        self.status_calls = 0

    def qr_create(self, creds, amount):
        self.next_id += 1
        op = str(self.next_id)
        self.statuses[op] = "QrTokenCreated"
        return k.QrInfo(op_id=op, payload=f"https://qr.kaspi.kz/{op}", expires_at=utcnow() + timedelta(seconds=180))

    def qr_status(self, creds, op_id):
        self.status_calls += 1
        if self.status_error:
            raise self.status_error
        return self.statuses[op_id]

    def refund(self, creds, op_id, amount):
        if self.refund_error:
            raise self.refund_error
        self.refunds.append((op_id, amount))
        return {"StatusCode": 0}

    def session_check(self, creds):
        return True


@pytest.fixture()
def fake():
    return FakeKaspi()


@pytest.fixture()
def client(fake):
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        db.add(KaspiSession(id=1, token_sn="sn", vtoken_secret="secret", profile_id="1"))
        db.commit()
    app.dependency_overrides[k.get_kaspi] = lambda: fake
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


ADMIN = {"X-Admin-Token": "admin-secret"}


@pytest.fixture()
def machine(client):
    """Автомат с товаром в ячейке 3: 500 ₸, 2 штуки."""
    m = client.post("/api/admin/machines", json={"code": "ZX1", "name": "Тест"}, headers=ADMIN).json()
    r = client.put(
        f"/api/admin/machines/{m['id']}/cells/3",
        json={"title": "Вода", "price": 500, "stock": 2, "enabled": True},
        headers=ADMIN,
    )
    assert r.status_code == 200
    return {"id": m["id"], "headers": {"X-Machine-Token": m["token"]}}
