import hashlib
import hmac
import json
from datetime import timedelta

from app import kaspi as k
from app import orders as svc
from app.db import SessionLocal, utcnow
from app.models import Cell, Order

from .conftest import ADMIN


def webhook(client, op_id, event="payment.success", status="Processed", secret="hook-secret"):
    body = json.dumps({"event": event, "paymentId": op_id, "type": "qr", "status": status}).encode()
    sig = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return client.post(
        "/api/kaspi/webhook", content=body, headers={"X-Webhook-Signature": sig, "Content-Type": "application/json"}
    )


def op_id_of(order_id):
    with SessionLocal() as db:
        return db.get(Order, order_id).kaspi_op_id


def stock_of(machine_id, number):
    with SessionLocal() as db:
        return db.query(Cell).filter_by(machine_id=machine_id, number=number).one().stock


def age_order(order_id, **delta):
    """Сдвигает заказ в прошлое, чтобы проверить таймауты."""
    d = timedelta(**delta)
    with SessionLocal() as db:
        o = db.get(Order, order_id)
        o.created_at -= d
        o.qr_expires_at -= d
        o.last_check_at = (o.last_check_at - d) if o.last_check_at else None
        o.last_check_ok_at = (o.last_check_ok_at - d) if o.last_check_ok_at else None
        if o.dispense_started_at:
            o.dispense_started_at -= d
        o.status_changed_at -= d
        db.commit()


def reconcile(fake):
    with SessionLocal() as db:
        svc.reconcile_once(db, fake)


def new_order(client, machine, cell=3):
    r = client.post("/api/machine/orders", json={"cell": cell}, headers=machine["headers"])
    assert r.status_code == 200, r.text
    return r.json()


def get(client, machine, oid):
    return client.get(f"/api/machine/orders/{oid}", headers=machine["headers"]).json()


# ── доступ ──


def test_auth_required(client, machine):
    assert client.get("/api/machine/state").status_code == 422
    assert client.get("/api/machine/state", headers={"X-Machine-Token": "nope"}).status_code == 401
    assert client.get("/api/admin/machines", headers={"X-Admin-Token": "nope"}).status_code == 401


def test_state_lists_eight_cells(client, machine):
    s = client.get("/api/machine/state", headers=machine["headers"]).json()
    assert [c["number"] for c in s["cells"]] == list(range(1, 9))
    assert s["kaspi_ready"] is True
    assert [c["number"] for c in s["cells"] if c["available"]] == [3]


# ── обычная покупка ──


def test_happy_path(client, machine, fake):
    o = new_order(client, machine)
    assert o["stage"] == "pay" and o["qr"].startswith("https://qr.kaspi.kz/") and o["amount"] == 500

    assert webhook(client, op_id_of(o["id"])).status_code == 200
    assert get(client, machine, o["id"])["stage"] == "dispense"

    r = client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=machine["headers"])
    assert r.json()["allowed"] is True

    r = client.post(
        f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "ok"}, headers=machine["headers"]
    )
    assert r.json()["status"] == "done"
    assert stock_of(machine["id"], 3) == 1
    assert fake.refunds == []


def test_dispense_allowed_only_once(client, machine, fake):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    url = f"/api/machine/orders/{o['id']}/dispense-start"
    assert client.post(url, json={"token": "attempt-0001"}, headers=machine["headers"]).json()["allowed"] is True
    # тот же запрос повторно (ответ потерялся) — снова «да», мотор ещё не крутился
    assert client.post(url, json={"token": "attempt-0001"}, headers=machine["headers"]).json()["allowed"] is True
    # другой токен — отказ
    assert client.post(url, json={"token": "attempt-0002"}, headers=machine["headers"]).json()["allowed"] is False
    # повторный вебхук об оплате ничего не ломает
    webhook(client, op_id_of(o["id"]))
    assert get(client, machine, o["id"])["status"] == "dispensing"


def test_duplicate_ok_result_decrements_once(client, machine):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    h = machine["headers"]
    client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=h)
    for _ in range(2):
        client.post(f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "ok"}, headers=h)
    assert stock_of(machine["id"], 3) == 1


def test_cannot_dispense_unpaid(client, machine):
    o = new_order(client, machine)
    r = client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=machine["headers"])
    assert r.json()["allowed"] is False


# ── ошибки выдачи ──


def test_motor_error_refunds(client, machine, fake):
    o = new_order(client, machine)
    op = op_id_of(o["id"])
    webhook(client, op)
    h = machine["headers"]
    client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=h)
    r = client.post(f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "motor_error"}, headers=h)
    assert r.json()["status"] == "refunded"
    assert fake.refunds == [(op, 500)]
    assert stock_of(machine["id"], 3) == 2


def test_refund_failure_is_flagged_and_can_be_retried(client, machine, fake):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    h = machine["headers"]
    client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=h)
    fake.refund_error = k.KaspiError("нет связи")
    r = client.post(f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "motor_error"}, headers=h)
    assert r.json()["status"] == "refund_failed" and r.json()["stage"] == "problem"
    fake.refund_error = None
    r = client.post(f"/api/admin/orders/{o['id']}/refund", headers=ADMIN)
    assert r.json()["refunded"] is True and len(fake.refunds) == 1


def test_no_reply_goes_to_review_without_refund(client, machine, fake):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    h = machine["headers"]
    client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=h)
    r = client.post(f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "no_reply"}, headers=h)
    assert r.json()["status"] == "needs_review"
    assert fake.refunds == []


def test_dispense_timeout_goes_to_review(client, machine, fake):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    client.post(f"/api/machine/orders/{o['id']}/dispense-start", json={"token": "attempt-0001"}, headers=machine["headers"])
    age_order(o["id"], minutes=6)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "needs_review"
    # автомат вернулся после перезагрузки и сообщил, что товар выдан
    r = client.post(
        f"/api/machine/orders/{o['id']}/dispense-result", json={"token": "attempt-0001", "result": "ok"}, headers=machine["headers"]
    )
    assert r.json()["status"] == "done" and stock_of(machine["id"], 3) == 1


# ── оплата: отмена, срок, поздняя оплата ──


def test_cancel_then_late_payment_is_refunded(client, machine, fake):
    o = new_order(client, machine)
    op = op_id_of(o["id"])
    assert client.post(f"/api/machine/orders/{o['id']}/cancel", headers=machine["headers"]).json()["status"] == "cancelled"
    webhook(client, op)  # клиент всё-таки оплатил
    assert get(client, machine, o["id"])["status"] == "refunded"
    assert fake.refunds == [(op, 500)]


def test_new_order_cancels_previous_unpaid(client, machine, fake):
    first = new_order(client, machine)
    second = new_order(client, machine)
    assert get(client, machine, first["id"])["status"] == "cancelled"
    assert get(client, machine, second["id"])["status"] == "awaiting_payment"


def test_reconciler_catches_payment_without_webhook(client, machine, fake):
    o = new_order(client, machine)
    fake.statuses[op_id_of(o["id"])] = "Processed"
    reconcile(fake)  # сразу после создания не дёргаем Kaspi
    assert fake.status_calls == 0
    age_order(o["id"], seconds=11)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "paid"


def test_scanned_flag(client, machine, fake):
    o = new_order(client, machine)
    fake.statuses[op_id_of(o["id"])] = "Wait"
    age_order(o["id"], seconds=11)
    reconcile(fake)
    v = get(client, machine, o["id"])
    assert v["status"] == "awaiting_payment" and v["scanned"] is True


def test_expires_locally_then_late_payment_refunded(client, machine, fake):
    o = new_order(client, machine)
    op = op_id_of(o["id"])
    age_order(o["id"], seconds=180 + 71)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "expired"
    fake.statuses[op] = "Processed"
    age_order(o["id"], seconds=11)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "refunded"
    assert fake.refunds == [(op, 500)]


def test_kaspi_final_expiry(client, machine, fake):
    o = new_order(client, machine)
    fake.statuses[op_id_of(o["id"])] = "QrTokenDiscarded"
    age_order(o["id"], seconds=11)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "expired"
    calls = fake.status_calls
    age_order(o["id"], seconds=60)
    reconcile(fake)
    assert fake.status_calls == calls  # финальный статус получен, больше не спрашиваем


def test_unverifiable_payment_goes_to_review(client, machine, fake):
    o = new_order(client, machine)
    fake.status_error = k.KaspiSessionError("сессия вытеснена")
    age_order(o["id"], seconds=260)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "awaiting_payment"  # проверить не смогли, ждём
    age_order(o["id"], minutes=15)
    reconcile(fake)
    assert get(client, machine, o["id"])["status"] == "needs_review"
    assert fake.refunds == []


# ── вебхук ──


def test_webhook_rejects_bad_signature(client, machine, fake):
    o = new_order(client, machine)
    assert webhook(client, op_id_of(o["id"]), secret="wrong").status_code == 401
    assert get(client, machine, o["id"])["status"] == "awaiting_payment"


def test_webhook_expired_event_is_rechecked_not_trusted(client, machine, fake):
    o = new_order(client, machine)
    op = op_id_of(o["id"])
    fake.statuses[op] = "Processed"  # сервис считает, что срок вышел, а Kaspi платёж провёл
    webhook(client, op, event="payment.expired", status="Expired")
    assert get(client, machine, o["id"])["status"] == "paid"


def test_webhook_session_expired_does_not_fail_order(client, machine, fake):
    o = new_order(client, machine)
    fake.status_error = k.KaspiSessionError("нет сессии")
    webhook(client, op_id_of(o["id"]), event="payment.failed", status="SessionExpired")
    assert get(client, machine, o["id"])["status"] == "awaiting_payment"


# ── витрина и остатки ──


def test_out_of_stock_and_disabled(client, machine, fake):
    r = client.post("/api/machine/orders", json={"cell": 1}, headers=machine["headers"])
    assert r.status_code == 409
    client.put("/api/machine/cells/3", json={"stock": 0}, headers=machine["headers"])
    r = client.post("/api/machine/orders", json={"cell": 3}, headers=machine["headers"])
    assert r.status_code == 409 and "закончился" in r.json()["detail"]


def test_no_kaspi_session_blocks_orders(client, machine, fake):
    from app.models import KaspiSession

    with SessionLocal() as db:
        db.delete(db.get(KaspiSession, 1))
        db.commit()
    assert client.get("/api/machine/state", headers=machine["headers"]).json()["kaspi_ready"] is False
    fake.qr_create = lambda creds, amount: (_ for _ in ()).throw(AssertionError("не должны звать Kaspi"))
    r = client.post("/api/machine/orders", json={"cell": 3}, headers=machine["headers"])
    assert r.status_code == 503


def test_busy_machine_refuses_second_order(client, machine, fake):
    o = new_order(client, machine)
    webhook(client, op_id_of(o["id"]))
    r = client.post("/api/machine/orders", json={"cell": 3}, headers=machine["headers"])
    assert r.status_code == 409


def test_parse_expire():
    now = utcnow()
    almaty = (now + timedelta(hours=5, minutes=3)).isoformat(timespec="seconds")
    got = k.parse_expire(almaty, now)
    assert abs((got - (now + timedelta(minutes=3))).total_seconds()) < 2
    assert k.parse_expire("мусор", now) == now + k.DEFAULT_QR_TTL
    assert k.parse_expire((now - timedelta(hours=3)).isoformat(), now) == now + k.DEFAULT_QR_TTL
