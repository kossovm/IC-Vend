# IC Vending

Автомат на 8 ячеек с оплатой через Kaspi QR.

- `backend/` — сервер: заказы, оплата, возвраты, управление автоматами. См. [backend/README.md](backend/README.md).
- `android/` — приложение автомата (Kotlin + Jetpack Compose), работает в режиме киоска.
- `docs/vend_protocol.py` — протокол платы привода пружин и эталонный кодировщик кадров.
