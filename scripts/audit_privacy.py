#!/usr/bin/env python3
"""Static check for accidental regression to outgoing app networking or telemetry."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
app = root / "app"
manifest = (app / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
build = (app / "build.gradle.kts").read_text(encoding="utf-8")
blocked_dependencies = (
    "firebase", "play-services-ads", "retrofit", "okhttp",
    "billingclient", "work-runtime", "google-services"
)
for item in blocked_dependencies:
    if item in build.lower():
        raise SystemExit(f"Forbidden runtime dependency: {item}")
if 'android:name="android.permission.INTERNET" tools:node="remove"' not in manifest:
    raise SystemExit("The app must explicitly remove merged INTERNET permissions")
if 'android:allowBackup="false"' not in manifest:
    raise SystemExit("App backups must be disabled")
bad_patterns = (
    r'\bjava\.net\.(URL|HttpURLConnection|Socket)\b',
    r'\bokhttp3\.',
    r'\bretrofit2\.',
    r'\bFirebaseApp\b',
    r'\bMobileAds\b',
    r'\bIntent\.ACTION_SEND\b',
    r'\bBuildConfig\.UPDATE_REPO_OWNER\b',
    r'\bHttpLoggingInterceptor\b'
)
for path in (app / "src/main/java").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for pattern in bad_patterns:
        if re.search(pattern, text):
            raise SystemExit(f"Forbidden behavior in {path.relative_to(root)}: {pattern}")
assert (root / "scripts/prepare_language_id.py").exists(), "Language ID model installer missing"
assert (root / "app/src/main/java/com/charles/livecaptionn/speech/OfflineSpokenLanguageIdentifier.kt").exists()
assert (root / "app/src/main/java/com/charles/livecaptionn/translation/TranslationModelSetup.kt").exists()
assert (root / "app/src/main/java/com/charles/livecaptionn/speech/LanguageRecommendationTracker.kt").exists()
print("OK: no app Internet permission, online SDKs, sharing intents, or remote client paths")
