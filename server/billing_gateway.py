#!/usr/bin/env python3
"""Public purchase gateway. Verify Play Integrity before invoking the private verifier."""
from collections import deque
from hashlib import sha256
import hmac
import json
import os
import re
from http.server import ThreadingHTTPServer
from threading import BoundedSemaphore, Lock
from time import monotonic, time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from billing_verify import (
    GATEWAY_HEADER, Handler as BaseHandler, MAX_BODY, METADATA, PACKAGE, PRODUCT,
    TOKEN_PATTERN, read_bounded_json,
)

INTEGRITY_HEADER = "X-LiveTranscribe-Integrity"
INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity"
DECODE_URL = f"https://playintegrity.googleapis.com/v1/{PACKAGE}:decodeIntegrityToken"
METADATA_IDENTITY = METADATA.rsplit("/", 1)[0] + "/identity"
MAX_ATTESTATION = 16_384
MAX_AGE_MS = 120_000
FUTURE_SKEW_MS = 10_000
REPLAY_SECONDS = 150
REQUESTS_PER_MINUTE = 60
CLOUD_RUN_ORIGIN = re.compile(r"https://[a-z0-9-]+(?:\.[a-z0-9-]+)*\.run\.app")
ATTESTATION = re.compile(r"[!-~]{32,16384}")
SEEN = deque()
FINGERPRINTS = set()
ATTEMPTS = deque()
LOCK = Lock()
SLOTS = BoundedSemaphore(8)


class Denied(Exception):
    pass


class Unavailable(Exception):
    pass


class RateLimited(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# Never follow a redirect carrying purchase or service-account credentials.
OPENER = build_opener(ProxyHandler({}), NoRedirect())


def request_hash(method: str, body: bytes) -> str:
    if method not in ("GET", "POST"):
        raise ValueError("Unsupported method")
    return sha256(f"livetranscribe-billing-v1\n{method}\n/verify\n".encode() + body).hexdigest()


def configuration() -> tuple[str, str]:
    origin = os.getenv("BILLING_PRIVATE_VERIFIER_ORIGIN", "")
    proof = os.getenv("BILLING_GATEWAY_AUTH_TOKEN", "")
    if not CLOUD_RUN_ORIGIN.fullmatch(origin) or not re.fullmatch(r"[!-~]{32,512}", proof):
        raise Unavailable("Missing protected verifier configuration")
    return origin, proof


def reserve_attestation(token: str) -> None:
    """Bound decode quota and reject same-instance replays without retaining raw tokens."""
    fingerprint = sha256(token.encode("ascii")).digest()
    with LOCK:
        now = monotonic()
        while SEEN and SEEN[0][0] <= now - REPLAY_SECONDS:
            _, old = SEEN.popleft()
            FINGERPRINTS.remove(old)
        while ATTEMPTS and ATTEMPTS[0] <= now - 60:
            ATTEMPTS.popleft()
        if fingerprint in FINGERPRINTS:
            raise Denied("Repeated integrity proof")
        if len(ATTEMPTS) >= REQUESTS_PER_MINUTE:
            raise RateLimited
        ATTEMPTS.append(now)
        SEEN.append((now, fingerprint))
        FINGERPRINTS.add(fingerprint)


def valid_verdict(payload: dict, expected_hash: str, now_ms: int) -> bool:
    """Check the actual decoded verdict, not an app-supplied verdict or status."""
    if not isinstance(payload, dict):
        return False
    details = payload.get("requestDetails")
    app = payload.get("appIntegrity")
    account = payload.get("accountDetails")
    device = payload.get("deviceIntegrity")
    if not all(isinstance(x, dict) for x in (details, app, account, device)):
        return False
    timestamp = details.get("timestampMillis")
    if isinstance(timestamp, str) and re.fullmatch(r"[0-9]{1,16}", timestamp):
        timestamp = int(timestamp)
    if type(timestamp) is not int or not (-FUTURE_SKEW_MS <= now_ms - timestamp <= MAX_AGE_MS):
        return False
    supplied_hash = details.get("requestHash")
    if (details.get("requestPackageName") != PACKAGE
            or not isinstance(supplied_hash, str)
            or not re.fullmatch(r"[a-f0-9]{64}", supplied_hash)
            or not hmac.compare_digest(supplied_hash, expected_hash)):
        return False
    labels = device.get("deviceRecognitionVerdict")
    return (app.get("appRecognitionVerdict") == "PLAY_RECOGNIZED"
            and app.get("packageName") == PACKAGE
            and account.get("appLicensingVerdict") == "LICENSED"
            and isinstance(labels, list) and "MEETS_DEVICE_INTEGRITY" in labels)


def decode_token(token: str) -> dict:
    credentials = Request(METADATA + "?" + urlencode({"scopes": INTEGRITY_SCOPE}),
                          headers={"Metadata-Flavor": "Google"})
    try:
        with OPENER.open(credentials, timeout=4) as response:
            data = read_bounded_json(response, 4096)
        access = data.get("access_token")
        if not isinstance(access, str) or not access:
            raise Unavailable("Integrity credentials unavailable")
        request = Request(DECODE_URL, data=json.dumps({"integrity_token": token}).encode("utf-8"),
                          headers={"Authorization": "Bearer " + access,
                                   "Content-Type": "application/json"}, method="POST")
        with OPENER.open(request, timeout=8) as response:
            decoded = read_bounded_json(response, 64_000)
        payload = decoded.get("tokenPayloadExternal")
        if not isinstance(payload, dict):
            raise Denied("Missing integrity verdict")
        return payload
    except HTTPError as exc:
        # Invalid credentials/quota/network failure never grants access.
        if exc.code == 429:
            raise RateLimited from None
        raise Unavailable("Integrity API unavailable") from None
    except (OSError, ValueError, KeyError) as exc:
        raise Unavailable("Integrity API unavailable") from exc


def validate_body(method: str, body: bytes) -> dict | None:
    if method == "GET":
        if body:
            raise ValueError("Unexpected body")
        return None
    if method != "POST" or not 0 < len(body) <= MAX_BODY:
        raise ValueError("Invalid body size")
    def unique_pairs(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError("Duplicate JSON field")
            obj[key] = value
        return obj
    payload = json.loads(body.decode("utf-8"), object_pairs_hook=unique_pairs)
    if (not isinstance(payload, dict) or set(payload) != {"productId", "purchaseToken"}
            or payload["productId"] != PRODUCT
            or not isinstance(payload["purchaseToken"], str)
            or not TOKEN_PATTERN.fullmatch(payload["purchaseToken"])):
        raise ValueError("Invalid purchase request")
    return payload


def forward_verified(method: str, payload: dict | None, origin: str, proof: str) -> dict:
    """Construct a new IAM-authenticated request; never forward client headers."""
    identity = Request(METADATA_IDENTITY + "?" + urlencode({"audience": origin}),
                       headers={"Metadata-Flavor": "Google"})
    try:
        with OPENER.open(identity, timeout=4) as response:
            raw = response.read(16_385)
        if not raw or len(raw) > 16_384:
            raise Unavailable("Invalid service identity")
        token = raw.decode("ascii").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token):
            raise Unavailable("Invalid service identity")
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(origin + "/verify", data=data, method=method, headers={
            "Authorization": "Bearer " + token, GATEWAY_HEADER: proof,
            "Content-Type": "application/json", "Cache-Control": "no-store",
        })
        with OPENER.open(request, timeout=20) as response:
            result = read_bounded_json(response, 2048)
        field = "ready" if method == "GET" else "active"
        if (result.get("productId") != PRODUCT or type(result.get(field)) is not bool
                or (field == "ready" and result[field] is not True)):
            raise Unavailable("Invalid verifier response")
        return {"productId": PRODUCT, field: result[field]}
    except HTTPError as exc:
        if exc.code == 429:
            raise RateLimited from None
        raise Unavailable("Private verifier unavailable") from None
    except (OSError, ValueError, KeyError) as exc:
        raise Unavailable("Private verifier unavailable") from exc


def protected_request(method: str, body: bytes, token: str | None) -> dict:
    if not isinstance(token, str) or not ATTESTATION.fullmatch(token):
        raise Denied("Integrity proof required")
    payload = validate_body(method, body)
    origin, proof = configuration()
    if not SLOTS.acquire(blocking=False):
        raise RateLimited
    try:
        reserve_attestation(token)
        verdict = decode_token(token)
        if not valid_verdict(verdict, request_hash(method, body), int(time() * 1000)):
            raise Denied("Integrity verification failed")
        return forward_verified(method, payload, origin, proof)
    finally:
        SLOTS.release()


class Handler(BaseHandler):
    server_version = "BillingGateway"

    def dispatch(self, method: str):
        if self.path == "/healthz" and method == "GET":
            self.respond(200, {"ok": True})
            return
        if self.path != "/verify":
            self.respond(404, {"error": "not_found"})
            return
        tokens = self.headers.get_all(INTEGRITY_HEADER, [])
        # Internal proof and identity headers are never accepted from the public caller.
        if (len(tokens) != 1 or self.headers.get_all(GATEWAY_HEADER)
                or self.headers.get_all("X-Serverless-Authorization")):
            self.respond(403, {"error": "forbidden"})
            return
        try:
            sizes = self.headers.get_all("Content-Length", [])
            size = int(sizes[0]) if len(sizes) == 1 else (0 if not sizes else -1)
            if self.headers.get("Transfer-Encoding") or size < 0 or size > MAX_BODY:
                raise ValueError("Invalid body size")
            if method == "GET":
                if size != 0:
                    raise ValueError("Unexpected body")
                body = b""
            else:
                if not 0 < size <= MAX_BODY or self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                    raise ValueError("Invalid content type or length")
                body = self.rfile.read(size)
                if len(body) != size:
                    raise ValueError("Incomplete body")
            reply = protected_request(method, body, tokens[0])
        except (ValueError, UnicodeError, TimeoutError):
            self.respond(400, {"error": "bad_request"})
            return
        except Denied:
            self.respond(403, {"error": "integrity_required"})
            return
        except RateLimited:
            self.respond(429, {"error": "verification_rate_limited"})
            return
        except Unavailable:
            self.respond(503, {"error": "verification_unavailable"})
            return
        self.respond(200, reply)

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")


class QuietServer(ThreadingHTTPServer):
    # Do not print exception traces containing request data.
    def handle_error(self, request, client_address):
        return


if __name__ == "__main__":
    # Start fail-closed when the server-side configuration is missing.
    configuration()
    QuietServer(("0.0.0.0", int(os.getenv("PORT", "8080"))), Handler).serve_forever()
