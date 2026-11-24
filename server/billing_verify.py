#!/usr/bin/env python3
"""Cloud Run purchase-verification endpoint. Deploy separately with a scoped service account."""
from collections import deque
import datetime as dt
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import os
import re
from threading import BoundedSemaphore, Lock
from time import monotonic
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

PACKAGE = "com.hebi.livetranslate"
PRODUCT = "remove_ads"
GOOGLE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
METADATA = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
MAX_BODY = 2048
MAX_GOOGLE_RESPONSE = 200_000
GATEWAY_HEADER = "X-LiveTranscribe-Gateway-Proof"
MAX_IN_FLIGHT = 8
VERIFICATION_WINDOW_SECONDS = 60
VERIFICATIONS_PER_WINDOW = 120
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9._:/-]{10,1500}$")
ACTIVE_STATES = frozenset({
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
})
verification_requests: deque[float] = deque()
verification_lock = Lock()
in_flight = BoundedSemaphore(MAX_IN_FLIGHT)


class VerificationUnavailable(Exception):
    pass


class VerificationRateLimited(Exception):
    pass


def reserve_verification() -> None:
    with verification_lock:
        now = monotonic()
        expires_before = now - VERIFICATION_WINDOW_SECONDS
        while verification_requests and verification_requests[0] <= expires_before:
            verification_requests.popleft()
        if len(verification_requests) >= VERIFICATIONS_PER_WINDOW:
            raise VerificationRateLimited
        verification_requests.append(now)


def gateway_status(expected: str, provided: str | None) -> int:
    """The private verifier is called only by a trusted, app-attesting gateway.
    Never embed the server-to-server proof in the Android client.
    """
    if len(expected) < 32:
        return 503
    if not provided or len(provided) != len(expected):
        return 403
    return 200 if hmac.compare_digest(expected.encode("utf-8"), provided.encode("utf-8")) else 403


def read_bounded_json(response, limit: int) -> dict:
    raw = response.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("Response exceeds configured limit")
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Expected a JSON object")
    return data


def is_active(subscription: dict, now: dt.datetime | None = None) -> bool:
    if not isinstance(subscription, dict) or subscription.get("subscriptionState") not in ACTIVE_STATES:
        return False
    now = now or dt.datetime.now(dt.timezone.utc)
    line_items = subscription.get("lineItems")
    if not isinstance(line_items, list):
        return False
    for item in line_items:
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
    query = urlencode({"scopes": PLAY_SCOPE})
    request = Request(f"{METADATA}?{query}", headers={"Metadata-Flavor": "Google"})
    try:
        with urlopen(request, timeout=4) as response:
            data = read_bounded_json(response, 4096)
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
            data = read_bounded_json(response, MAX_GOOGLE_RESPONSE)
        return data
    except HTTPError as exc:
        if exc.code in (404, 410):
            return {}
        raise VerificationUnavailable("Play purchase status unavailable") from None
    except (OSError, ValueError) as exc:
        raise VerificationUnavailable("Play verification unavailable") from exc


def verification_result(payload: dict) -> dict:
    if (not isinstance(payload, dict) or set(payload) != {"productId", "purchaseToken"}
            or payload.get("productId") != PRODUCT):
        raise ValueError("Unsupported product")
    token = payload.get("purchaseToken")
    if not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token):
        raise ValueError("Invalid purchase proof")
    if not in_flight.acquire(blocking=False):
        raise VerificationRateLimited
    try:
        reserve_verification()
        return {"productId": PRODUCT, "active": is_active(fetch_subscription(token))}
    finally:
        in_flight.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "PurchaseVerifier"
    sys_version = ""

    def setup(self):
        self.request.settimeout(5)
        super().setup()

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
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(data)

    def require_gateway(self) -> bool:
        proof = self.headers.get_all(GATEWAY_HEADER, [])
        auth_status = gateway_status(os.getenv("BILLING_GATEWAY_AUTH_TOKEN", ""),
                                     proof[0] if len(proof) == 1 else None)
        if auth_status != 200:
            self.respond(auth_status, {"error": "verification_unavailable" if auth_status == 503 else "forbidden"})
            return False
        return True

    def do_GET(self):
        if self.path == "/healthz":
            self.respond(200, {"ok": True})
        elif self.path == "/verify":
            if self.require_gateway():
                # No purchase token or Play API request for this configuration check.
                self.respond(200, {"productId": PRODUCT, "ready": True})
        else:
            self.respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/verify":
            self.respond(404, {"error": "not_found"})
            return
        if not self.require_gateway():
            return
        sizes = self.headers.get_all("Content-Length", [])
        try:
            size = int(sizes[0]) if len(sizes) == 1 else -1
        except ValueError:
            size = -1
        if (size <= 0 or size > MAX_BODY or self.headers.get("Transfer-Encoding")
                or self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json"):
            self.respond(400, {"error": "bad_request"})
            return
        try:
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("Incomplete request")
            payload = json.loads(raw)
            reply = verification_result(payload)
        except (UnicodeError, ValueError, KeyError, TimeoutError):
            self.respond(400, {"error": "bad_request"})
            return
        except VerificationRateLimited:
            self.respond(429, {"error": "verification_rate_limited"})
            return
        except VerificationUnavailable:
            self.respond(503, {"error": "verification_unavailable"})
            return
        self.respond(200, reply)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "8080"))), Handler).serve_forever()
