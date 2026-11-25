# IC Vending — сервер

Три контейнера:

| Контейнер | Что делает |
|---|---|
| `api` | FastAPI: каталог, заказы, выдача, возвраты, админ-API |
| `kaspi` | [kaspi-pos-automation](https://github.com/tapter-dev/kaspi-pos-automation): ходит в Kaspi Pay от имени кассира |
| `db` | PostgreSQL |

Один кассир Kaspi обслуживает все автоматы: на каждый заказ создаётся свой QR со своим номером операции.

## Запуск

```sh
cd backend
cp .env.example .env        # заполнить четыре значения: openssl rand -hex 32
docker compose up -d --build
```

Сервер слушает порт `8080` (меняется через `API_PORT`). Проверка: `http://<сервер>:8080/health`.
Все ручки с описанием и кнопкой «Try it out»: `http://<сервер>:8080/docs`.

## Первая настройка (через /docs, заголовок `X-Admin-Token` = `ADMIN_TOKEN` из .env)

1. **Подключить кассира.** `POST /api/admin/kaspi/login/start` с номером кассира → придёт SMS →
   `POST /api/admin/kaspi/login/verify` с `process_id` и кодом. Проверка: `GET /api/admin/kaspi/status`.
2. **Создать автомат.** `POST /api/admin/machines` → в ответе `token`. Он вводится на автомате один раз.
3. **Заполнить ячейки.** `PUT /api/admin/machines/{id}/cells/{1..8}`: название, цена в тенге, остаток, `enabled: true`.

## Правила эксплуатации

- **Кассир выделенный.** Вход в приложение Kaspi Pay с этого номера выбивает сессию сервера,
  оплата на всех автоматах остановится до нового входа по SMS.
- **Том `kaspi` не удалять.** В нём ключи устройства; без них нужен новый вход по SMS.
- **Контейнер `kaspi` наружу не публиковать.** У него нет своей авторизации, а в логи он пишет токены сессии.
- Если вход кассира падает с «Обновите приложение», заполни `KASPI_APP_VERSION` и `KASPI_APP_BUILD` в `.env`
  версией актуального Kaspi Pay для iOS и перезапусти `kaspi`.

## Как идёт заказ

```
автомат: POST /api/machine/orders {cell}          → сервер создаёт QR, статус awaiting_payment
автомат: GET  /api/machine/orders/{id} (опрос)    → ждёт stage = "dispense"
Kaspi → сервис → вебхук /api/kaspi/webhook        → статус paid
автомат: POST .../dispense-start {token}          → allowed: true выдаётся на заказ один раз
автомат крутит мотор
автомат: POST .../dispense-result {token, result} → ok: done и остаток −1
                                                     motor_error: автовозврат
                                                     no_reply/unknown: needs_review
```

Статусы заказа:

| Статус | Что значит |
|---|---|
| `awaiting_payment` | QR на экране |
| `paid` | оплачен, ждёт выдачи |
| `dispensing` | автомат получил разрешение на выдачу |
| `done` | товар выдан |
| `cancelled`, `expired`, `payment_failed` | не оплачен |
| `refund_pending`, `refunded` | деньги возвращаются / возвращены |
| `refund_failed` | возврат не прошёл: повторить `POST /api/admin/orders/{id}/refund` |
| `needs_review` | оплата или выдача не подтверждены: проверить вручную |

Страховки:

- Вебхук может потеряться, поэтому сервер каждые 10 секунд сам спрашивает Kaspi о неоплаченных заказах.
- Событиям «истёк» и «отклонён» из вебхука сервер не верит на слово и перепроверяет статус.
- Если клиент оплатил после отмены или истечения заказа, деньги возвращаются автоматически.
- Заказ, оплату которого не удалось проверить (сессия кассира умерла), уходит в `needs_review`, а не в «не оплачен».

## Тесты

```sh
cd backend/api
pip install -r requirements.txt pytest
python -m pytest -q
```

Тесты идут на SQLite с поддельным Kaspi, Docker для них не нужен.
