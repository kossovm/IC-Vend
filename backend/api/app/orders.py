"""Логика заказа: создание QR, оплата, выдача, возврат, сверка с Kaspi.

Путь заказа:
    awaiting_payment → paid → dispensing → done
    awaiting_payment → expired | payment_failed | cancelled
    paid/dispensing  → refund_pending → refunded | refund_failed   (ошибка мотора)
    cancelled/expired + поздняя оплата → refund_pending → refunded
    needs_review — нужна ручная проверка (статус оплаты или выдачи неизвестен)
"""
import logging
import uuid
from datetime import timedelta

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from . import kaspi as k
from .db import utcnow
from .models import Cell, KaspiSession, Machine, Order

log = logging.getLogger("icvend.orders")

AWAITING = "awaiting_payment"
PAID = "paid"
DISPENSING = "dispensing"
DONE = "done"
CANCELLED = "cancelled"
EXPIRED = "expired"
PAYMENT_FAILED = "payment_failed"
REFUND_PENDING = "refund_pending"
REFUNDED = "refunded"
REFUND_FAILED = "refund_failed"
NEEDS_REVIEW = "needs_review"

# Что автомат показывает на экране для каждого статуса.
STAGE = {
    AWAITING: "pay",
    PAID: "dispense",
    DISPENSING: "dispensing",
    DONE: "done",
    CANCELLED: "closed",
    EXPIRED: "closed",
    PAYMENT_FAILED: "closed",
    REFUND_PENDING: "refunding",
    REFUNDED: "refunded",
    REFUND_FAILED: "problem",
    NEEDS_REVIEW: "problem",
}

CHECK_INTERVAL = timedelta(seconds=10)  # как часто сами спрашиваем Kaspi (вебхук быстрее)
CONFIRM_GRACE = timedelta(seconds=70)  # после срока QR клиент ещё может подтверждать оплату
HARD_CUTOFF = timedelta(minutes=15)  # дольше за заказом не следим
DISPENSE_TIMEOUT = timedelta(minutes=5)
REFUND_STUCK = timedelta(minutes=3)


class OrderError(Exception):
    def __init__(self, http_status: int, message: str):
        super().__init__(message)
        self.http_status = http_status
        self.message = message


# ── вспомогательное ──


def get_creds(db: Session) -> k.Creds:
    s = db.get(KaspiSession, 1)
    if s is None:
        raise k.KaspiSessionError("кассир Kaspi не подключён")
    return k.Creds(token_sn=s.token_sn, vtoken_secret=s.vtoken_secret, profile_id=s.profile_id)


def _transition(db: Session, order: Order, from_statuses: list[str], to_status: str, **values) -> bool:
    """Меняет статус, только если заказ сейчас в одном из from_statuses.
    Так два одновременных запроса не проведут заказ дважды."""
    res = db.execute(
        update(Order)
        .where(Order.id == order.id, Order.status.in_(from_statuses))
        .values(status=to_status, status_changed_at=utcnow(), **values)
    )
    db.commit()
    db.refresh(order)
    return res.rowcount == 1


def _set(db: Session, order: Order, **values) -> None:
    db.execute(update(Order).where(Order.id == order.id).values(**values))
    db.commit()
    db.refresh(order)


def order_view(o: Order) -> dict:
    now = utcnow()
    left = int((o.qr_expires_at - now).total_seconds()) if o.qr_expires_at else 0
    return {
        "id": o.id,
        "status": o.status,
        "stage": STAGE.get(o.status, "problem"),
        "cell": o.cell_number,
        "title": o.title,
        "amount": o.amount,
        "qr": o.qr_payload if o.status == AWAITING else None,
        "seconds_left": max(left, 0),
        "scanned": o.kaspi_status == "Wait",  # клиент отсканировал и подтверждает в приложении
    }


# ── создание ──


def create_order(db: Session, kaspi: k.KaspiClient, machine: Machine, cell_number: int) -> Order:
    cell = db.scalar(select(Cell).where(Cell.machine_id == machine.id, Cell.number == cell_number))
    if cell is None or not cell.enabled or cell.price <= 0:
        raise OrderError(409, "Ячейка недоступна")
    if cell.stock <= 0:
        raise OrderError(409, "Товар закончился")

    busy = db.scalar(
        select(Order).where(Order.machine_id == machine.id, Order.status.in_([PAID, DISPENSING, REFUND_PENDING]))
    )
    if busy is not None:
        raise OrderError(409, "Автомат занят предыдущим заказом")

    # Покупатель вернулся в меню и выбрал другое: прошлый неоплаченный заказ закрываем.
    for old in db.scalars(select(Order).where(Order.machine_id == machine.id, Order.status == AWAITING)).all():
        cancel_order(db, old)

    try:
        qr = kaspi.qr_create(get_creds(db), cell.price)
    except k.KaspiSessionError as e:
        raise OrderError(503, "Оплата временно недоступна") from e
    except k.KaspiError as e:
        log.warning("qr_create failed: %s", e)
        raise OrderError(502, "Не удалось создать QR, попробуйте ещё раз") from e

    now = utcnow()
    order = Order(
        id=uuid.uuid4().hex,
        machine_id=machine.id,
        cell_number=cell.number,
        title=cell.title,
        amount=cell.price,
        status=AWAITING,
        status_changed_at=now,
        kaspi_op_id=qr.op_id,
        kaspi_status="QrTokenCreated",
        qr_payload=qr.payload,
        qr_expires_at=qr.expires_at,
        last_check_at=now,
        created_at=now,
    )
    db.add(order)
    db.commit()
    return order


def cancel_order(db: Session, order: Order) -> bool:
    # kaspi_closed не трогаем: сверка продолжит следить и вернёт деньги, если оплата всё же пройдёт.
    return _transition(db, order, [AWAITING], CANCELLED, finished_at=utcnow())


# ── оплата ──


def apply_kaspi_status(db: Session, kaspi: k.KaspiClient, order: Order, kstatus: str) -> None:
    """Применяет к заказу статус, полученный от Kaspi (из вебхука или из нашей проверки)."""
    now = utcnow()
    if kstatus in k.INTERMEDIATE:
        _set(db, order, kaspi_status=kstatus)
        return

    _set(db, order, kaspi_status=kstatus, kaspi_closed=True)

    if kstatus in k.SUCCESS:
        if _transition(db, order, [AWAITING], PAID, paid_at=now):
            return
        if order.status in (CANCELLED, EXPIRED, PAYMENT_FAILED):
            _set(db, order, paid_at=order.paid_at or now)
            refund_order(db, kaspi, order, "оплата пришла после закрытия заказа")
        return

    if kstatus in k.EXPIRED:
        _transition(db, order, [AWAITING], EXPIRED, finished_at=now)
    else:
        _transition(db, order, [AWAITING], PAYMENT_FAILED, finished_at=now, note=f"Kaspi: {kstatus}")


def check_now(db: Session, kaspi: k.KaspiClient, order: Order) -> bool:
    """Спрашивает статус у Kaspi и применяет его. False, если спросить не удалось."""
    now = utcnow()
    if not order.kaspi_op_id:
        return False
    try:
        kstatus = kaspi.qr_status(get_creds(db), order.kaspi_op_id)
    except k.KaspiError as e:
        log.warning("qr_status failed for %s: %s", order.id, e)
        _set(db, order, last_check_at=now)
        return False
    _set(db, order, last_check_at=now, last_check_ok_at=now)
    apply_kaspi_status(db, kaspi, order, kstatus)
    return True


# ── выдача ──


def dispense_start(db: Session, order: Order, token: str) -> bool:
    """Разрешение на выдачу даётся один раз. Повтор с тем же token (ответ потерялся в сети) тоже получает «да»."""
    if order.status == DISPENSING and order.dispense_token == token:
        return True
    return _transition(db, order, [PAID], DISPENSING, dispense_token=token, dispense_started_at=utcnow())


def dispense_result(db: Session, kaspi: k.KaspiClient, order: Order, token: str, result: str) -> None:
    if not order.dispense_token or order.dispense_token != token:
        raise OrderError(409, "Выдача по этому заказу не начиналась")
    if result == "ok":
        if _transition(db, order, [DISPENSING, NEEDS_REVIEW], DONE, finished_at=utcnow()):
            db.execute(
                update(Cell)
                .where(Cell.machine_id == order.machine_id, Cell.number == order.cell_number, Cell.stock > 0)
                .values(stock=Cell.stock - 1)
            )
            db.commit()
    elif result == "motor_error":
        refund_order(db, kaspi, order, "ошибка мотора")
    else:
        # Плата не ответила или автомат перезагрузился посреди выдачи: выпал ли товар, неизвестно.
        _transition(db, order, [DISPENSING], NEEDS_REVIEW, note=f"результат выдачи неизвестен ({result})")


# ── возврат ──

REFUNDABLE = [PAID, DISPENSING, CANCELLED, EXPIRED, PAYMENT_FAILED, REFUND_FAILED, NEEDS_REVIEW]


def refund_order(db: Session, kaspi: k.KaspiClient, order: Order, reason: str) -> bool:
    if order.paid_at is None or not order.kaspi_op_id:
        raise OrderError(409, "По заказу не подтверждена оплата, возвращать нечего")
    if not _transition(db, order, REFUNDABLE, REFUND_PENDING, note=reason):
        return False
    try:
        kaspi.refund(get_creds(db), order.kaspi_op_id, order.amount)
    except k.KaspiError as e:
        log.error("refund failed for %s: %s", order.id, e)
        _transition(db, order, [REFUND_PENDING], REFUND_FAILED, note=f"{reason}; возврат не прошёл: {e}"[:500])
        return False
    _transition(db, order, [REFUND_PENDING], REFUNDED, finished_at=utcnow())
    return True


# ── сверка (крутится в фоне) ──


def reconcile_once(db: Session, kaspi: k.KaspiClient) -> None:
    now = utcnow()

    open_orders = db.scalars(
        select(Order).where(Order.kaspi_closed.is_(False), Order.kaspi_op_id.is_not(None))
    ).all()
    for o in open_orders:
        try:
            _reconcile_payment(db, kaspi, o, now)
        except Exception:  # одна сбойная запись не должна останавливать остальные
            log.exception("reconcile failed for %s", o.id)
            db.rollback()

    for o in db.scalars(
        select(Order).where(Order.status == DISPENSING, Order.dispense_started_at < now - DISPENSE_TIMEOUT)
    ).all():
        _transition(db, o, [DISPENSING], NEEDS_REVIEW, note="автомат не сообщил результат выдачи")

    for o in db.scalars(
        select(Order).where(Order.status == REFUND_PENDING, Order.status_changed_at < now - REFUND_STUCK)
    ).all():
        _transition(db, o, [REFUND_PENDING], REFUND_FAILED, note="возврат завис, проверьте в Kaspi Pay вручную")


def _reconcile_payment(db: Session, kaspi: k.KaspiClient, o: Order, now) -> None:
    if now - o.created_at > HARD_CUTOFF:
        verified = bool(o.last_check_ok_at and o.qr_expires_at and o.last_check_ok_at > o.qr_expires_at)
        _set(db, o, kaspi_closed=True)
        if verified:
            _transition(db, o, [AWAITING], EXPIRED, finished_at=now)
        else:
            _transition(
                db, o, [AWAITING, EXPIRED, CANCELLED], NEEDS_REVIEW,
                note="не удалось проверить оплату у Kaspi после срока QR",
            )
        return

    if o.last_check_at and now - o.last_check_at < CHECK_INTERVAL:
        return
    if not check_now(db, kaspi, o):
        return

    past_deadline = bool(o.qr_expires_at and now > o.qr_expires_at + CONFIRM_GRACE)
    if o.status == AWAITING and past_deadline:
        # Kaspi ещё не дал финальный статус, но время вышло: автомат отпускаем,
        # а следить продолжаем (kaspi_closed остаётся False).
        _transition(db, o, [AWAITING], EXPIRED, finished_at=now)
