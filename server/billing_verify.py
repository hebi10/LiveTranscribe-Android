#!/usr/bin/env python3
"""Cloud Run purchase-verification endpoint. Deploy separately with a scoped service account."""
import datetime as dt
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

PACKAGE = "com.hebi.livetranslate"
PRODUCT = "remove_ads"
GOOGLE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
METADATA = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
MAX_BODY = 2048
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9._:/-]{10,1500}$")
ACTIVE_STATES = frozenset({
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
})


class VerificationUnavailable(Exception):
    pass


def is_active(subscription: dict, now: dt.datetime | None = None) -> bool:
    if not isinstance(subscription, dict) or subscription.get("subscriptionState") not in ACTIVE_STATES:
        return False
    now = now or dt.datetime.now(dt.timezone.utc)
    for item in subscription.get("lineItems", []):
        if not isinstance(item, dict) or item.get("productId") != PRODUCT:
            continue
        expires = item.get("expiryTime")
        if not isinstance(expires, str):
            continue
        try:
            until = dt.datetime.fromisoformat(expires.replace("Z", "+00:00"))
        except ValueError:
            continue
        if until.tzinfo is not None and until > now:
            return True
    return False


def access_token() -> str:
    request = Request(METADATA, headers={"Metadata-Flavor": "Google"})
    try:
        with urlopen(request, timeout=4) as response:
            data = json.loads(response.read(4096))
    except (OSError, ValueError, KeyError) as exc:
        raise VerificationUnavailable("Google service credentials unavailable") from exc
    token = data.get("access_token")
    if not isinstance(token, str) or not token:
        raise VerificationUnavailable("Google credentials invalid")
    return token


def fetch_subscription(token: str) -> dict:
    safe = quote(token, safe="")
    path = f"{GOOGLE}{PACKAGE}/purchases/subscriptionsv2/tokens/{safe}"
    request = Request(path, headers={"Authorization": "Bearer " + access_token(),
                                     "Accept": "application/json"})
    try:
        with urlopen(request, timeout=8) as response:
            data = json.loads(response.read(200_000))
        if not isinstance(data, dict):
            raise VerificationUnavailable("Invalid Google response")
        return data
    except HTTPError as exc:
        if exc.code in (404, 410):
            return {}
        raise VerificationUnavailable("Play purchase status unavailable") from None
    except (OSError, ValueError) as exc:
        raise VerificationUnavailable("Play verification unavailable") from exc


def verification_result(payload: dict) -> dict:
    if not isinstance(payload, dict) or payload.get("productId") != PRODUCT:
        raise ValueError("Unsupported product")
    token = payload.get("purchaseToken")
    if not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token):
        raise ValueError("Invalid purchase proof")
    return {"productId": PRODUCT, "active": is_active(fetch_subscription(token))}


class Handler(BaseHTTPRequestHandler):
    server_version = "PurchaseVerifier"
    sys_version = ""

    def log_message(self, format_string: str, *args: object) -> None:
        # Never print receipt bodies, tokens, raw Play responses, or client identifiers.
        return

    def respond(self, status: int, payload: dict):
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self.respond(200, {"ok": True})
        else:
            self.respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/verify":
            self.respond(404, {"error": "not_found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            size = -1
        if size <= 0 or size > MAX_BODY or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            self.respond(400, {"error": "bad_request"})
            return
        try:
            payload = json.loads(self.rfile.read(size))
            reply = verification_result(payload)
        except (UnicodeError, ValueError, KeyError):
            self.respond(400, {"error": "bad_request"})
            return
        except VerificationUnavailable:
            self.respond(503, {"error": "verification_unavailable"})
            return
        self.respond(200, reply)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "8080"))), Handler).serve_forever()
