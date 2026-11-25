"""Проверяем, что клиент шлёт сервису именно то, что описано в его docs/API.md."""
import json

import httpx
import pytest

from app import kaspi as k

CREDS = k.Creds(token_sn="SN", vtoken_secret="SECRET", profile_id="42")


def make(handler):
    c = k.KaspiClient(base_url="http://kaspi.test")
    c.http = httpx.Client(transport=httpx.MockTransport(handler), base_url="http://kaspi.test")
    return c


def test_qr_create_request_and_response():
    seen = {}

    def handler(req: httpx.Request):
        seen["path"], seen["headers"], seen["body"] = req.url.path, req.headers, json.loads(req.content)
        return httpx.Response(200, json={"StatusCode": 0, "Data": {
            "QrOperationId": 789012, "QrToken": "https://pay.kaspi.kz/pay/abc",
            "QrOriginalToken": "https://qr.kaspi.kz/abc", "ExpireDate": "bad", "Amount": 500}})

    qr = make(handler).qr_create(CREDS, 500)
    assert seen["path"] == "/api/qr/create" and seen["body"] == {"amount": 500}
    assert seen["headers"]["x-token-sn"] == "SN" and seen["headers"]["x-vtoken-secret"] == "SECRET"
    assert seen["headers"]["x-profile-id"] == "42"
    assert qr.op_id == "789012" and qr.payload == "https://qr.kaspi.kz/abc"


def test_qr_status_and_refund():
    def handler(req: httpx.Request):
        if req.url.path == "/api/qr/status":
            assert req.url.params["qrOperationId"] == "789012"
            return httpx.Response(200, json={"StatusCode": 0, "Data": {"Status": "Wait"}})
        assert req.url.path == "/api/refund/create"
        assert json.loads(req.content) == {"qrOperationId": 789012, "returnAmount": 500}
        return httpx.Response(200, json={"StatusCode": 0, "Data": {}})

    c = make(handler)
    assert c.qr_status(CREDS, "789012") == "Wait"
    c.refund(CREDS, "789012", 500)


def test_errors():
    evicted = make(lambda r: httpx.Response(200, json={"StatusCode": -101001}))
    with pytest.raises(k.KaspiSessionError):
        evicted.qr_status(CREDS, "1")
    unauthorized = make(lambda r: httpx.Response(401, json={"error": "Invalid or expired vtokenSecret."}))
    with pytest.raises(k.KaspiSessionError):
        unauthorized.qr_create(CREDS, 100)
    rejected = make(lambda r: httpx.Response(200, json={"StatusCode": -5, "Message": "нельзя"}))
    with pytest.raises(k.KaspiError):
        rejected.refund(CREDS, "1", 100)

    def down(req):
        raise httpx.ConnectError("refused")

    with pytest.raises(k.KaspiError):
        make(down).qr_status(CREDS, "1")
