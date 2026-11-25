#!/bin/sh
# Ключи устройства и очередь платежей сервис хранит рядом с кодом.
# Переносим их в том /data, чтобы пересборка контейнера не убивала сессию кассира.
set -e
mkdir -p /data/logs
for f in keypair.json device.json ecdh-keypair.json tracked-payments.json webhook-retries.json; do
  ln -sf "/data/$f" "/app/$f"
done
rm -rf /app/logs
ln -s /data/logs /app/logs

printf '[{"url":"%s","events":["payment.success","payment.failed","payment.expired","payment.lost"],"secret":"%s"}]\n' \
  "$WEBHOOK_URL" "$WEBHOOK_SECRET" > /app/webhooks.json

exec node server.js
