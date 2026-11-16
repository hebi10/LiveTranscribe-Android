#!/usr/bin/env python3
"""Source-level regression audit; does not inspect SDK internals or device traffic."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
app = root / "app"
manifest = (app / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
build = (app / "build.gradle.kts").read_text(encoding="utf-8")
blocked_dependencies = (
    "firebase", "play-services-ads", "retrofit", "okhttp",
    "work-runtime", "google-services", "mlkit", "play-services"
)
for item in blocked_dependencies:
    if item in build.lower():
        raise SystemExit(f"Forbidden runtime dependency: {item}")
android = "{http://schemas.android.com/apk/res/android}"
tools = "{http://schemas.android.com/tools}"
manifest_xml = ET.fromstring(manifest)
# Downloads are explicitly requested and may use Wi-Fi or mobile data.
# Network capability alone cannot prove that payloads stay local; audit the
# only permitted model GET transport separately from all other application code.
for permission in ("INTERNET", "ACCESS_NETWORK_STATE"):
    nodes = [node for node in manifest_xml if node.tag.startswith("uses-permission")
             and node.get(android + "name") == f"android.permission.{permission}"]
    if (len(nodes) != 1 or nodes[0].tag != "uses-permission"
            or nodes[0].get(tools + "node") == "remove"
            or nodes[0].get(android + "maxSdkVersion") is not None):
        raise SystemExit(f"Model downloads require one unrestricted {permission} declaration")
application = manifest_xml.find("application")
if application is None or application.get(android + "allowBackup") != "false":
    raise SystemExit("App backups must be disabled")
if application.get(android + "usesCleartextTraffic") != "false":
    raise SystemExit("Cleartext network traffic must be disabled")
if application.get(android + "fullBackupContent") != "false":
    raise SystemExit("Legacy full backups must be disabled")
if application.get(android + "dataExtractionRules") != "@xml/data_extraction_rules":
    raise SystemExit("App must reference the audited backup/transfer rules")

# root only covers the root domain, not files/, preferences, databases or
# device-protected equivalents. Require each applicable domain explicitly.
backup_domains = {"root", "file", "database", "sharedpref", "external",
                  "device_root", "device_file", "device_database", "device_sharedpref"}
rules = ET.parse(app / "src/main/res/xml/data_extraction_rules.xml").getroot()
for operation in ("cloud-backup", "device-transfer"):
    section = rules.find(operation)
    excluded = set() if section is None else {
        node.get("domain") for node in section.findall("exclude") if node.get("path") == "."
    }
    missing = backup_domains - excluded
    if missing:
        raise SystemExit(f"Missing {operation} backup domains: {', '.join(sorted(missing))}")
    if section.findall("include"):
        raise SystemExit(f"Unexpected {operation} backup includes")

history_store = app / "src/main/java/com/charles/livecaptionn/data/TranscriptHistoryStore.kt"
if "context.noBackupFilesDir" not in history_store.read_text(encoding="utf-8"):
    raise SystemExit("Transcript history must use noBackupFilesDir")
bad_patterns = (
    r'\bokhttp3\.',
    r'\bretrofit2\.',
    r'\bFirebaseApp\b',
    r'\bIntent\.ACTION_SEND\b',
    r'\bBuildConfig\.UPDATE_REPO_OWNER\b',
    r'\bHttpLoggingInterceptor\b'
)
transport = app / "src/main/java/com/charles/livecaptionn/models/ModelDownloadTransport.kt"
monetization_root = app / "src/main/java/com/charles/livecaptionn/monetization"
purchase_transport = monetization_root / "PurchaseVerifier.kt"
if not all(value in build for value in (
    "com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0",
    "com.google.android.ump:user-messaging-platform:4.0.0",
    "com.android.billingclient:billing-ktx:9.1.0",
    "GMA_ADS_ENABLED", "BILLING_VERIFIER_URL",
)):
    raise SystemExit("Expected fail-closed monetization dependencies/config missing")

network_pattern = r'\b(?:java\.net\.(?:URL|HttpURLConnection|Socket)|javax\.net\.ssl\.HttpsURLConnection)\b'
for path in (app / "src/main/java").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if path == transport:
        if not re.search(r'requestMethod\s*=\s*"GET"', text) or not re.search(r'doOutput\s*=\s*false', text):
            raise SystemExit("Model transport must use GET without a request body")
        if re.search(r'\b(?:getOutputStream|outputStream|Socket)\b|doOutput\s*=\s*true|requestMethod\s*=\s*"(?:POST|PUT|PATCH|DELETE)"', text):
            raise SystemExit("Model transport cannot upload a request body")
    elif path == purchase_transport:
        if "HttpsURLConnection" not in text or "BuildConfig.BILLING_VERIFIER_URL" not in text:
            raise SystemExit("Billing verification must use configured HTTPS endpoint")
        if any(s in text for s in ("transcriptLines", "originalText", "translatedText", "RECORD_AUDIO")):
            raise SystemExit("Billing client cannot access speech or translated content")
    elif re.search(network_pattern, text):
        raise SystemExit(f"Forbidden behavior in {path.relative_to(root)}: direct network access")
    if path.is_relative_to(monetization_root):
        if any(s in text for s in ("MediaProjection", "transcriptLines", "translatedText", "originalText", "AudioRecord")):
            raise SystemExit(f"Monetization cannot access captions or audio: {path}")
    elif any(s in text for s in ("com.google.android.libraries.ads.mobile.sdk.",
                                  "com.android.billingclient.api.", "com.google.android.ump.",
                                  "MobileAds.initialize(")):
        raise SystemExit(f"Monetization SDK must stay in isolated package: {path}")
    if "Intent.ACTION_VIEW" in text:
        policy_intent = r'Intent\(Intent\.ACTION_VIEW,\s*Uri\.parse\("https://hebi10\.github\.io/LiveTranscribe-Android/privacy-policy\.html"\)\)'
        if (path.name != "MainScreen.kt" or text.count("Intent.ACTION_VIEW") != 1
                or not re.search(policy_intent, text)):
            raise SystemExit("External browser access is limited to the fixed public privacy policy")
    for pattern in bad_patterns:
        if re.search(pattern, text):
            raise SystemExit(f"Forbidden behavior in {path.relative_to(root)}: {pattern}")
assert (root / "scripts/prepare_language_id.py").exists(), "Language ID model installer missing"
assert (root / "app/src/main/java/com/charles/livecaptionn/speech/OfflineSpokenLanguageIdentifier.kt").exists()
assert (root / "app/src/main/java/com/charles/livecaptionn/translation/TranslationModelSetup.kt").exists()
assert (root / "app/src/main/java/com/charles/livecaptionn/speech/LanguageRecommendationTracker.kt").exists()
print("OK: explicit model GET transport and fixed external privacy link only; known upload/sharing patterns blocked; backup domains excluded")
print("Static source audit only: SDK internals/telemetry, merged manifest and device traffic unverified")

