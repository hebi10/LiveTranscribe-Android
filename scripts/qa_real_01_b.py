#!/usr/bin/env python3
"""Validate operator-supplied, non-identifying Android QA evidence. Never certifies real inference."""

import argparse
import hashlib
import csv
import json
import math
import re
import statistics
from pathlib import Path

from device_perf_compare import read_run, summarize

CONDITIONS = ("correction_off", "correction_on", "focus_on")
ATTEMPTS = (1, 2, 3)
STATUSES = {"NOT RUN", "PASS", "FAIL", "BLOCKED"}
COLUMNS = (
    "session_id", "condition", "attempt", "status", "device_alias", "source_sha",
    "layout", "input_mode", "source_language", "clip_id", "model_ready",
    "first_translation", "correction_behavior", "performance_csv", "caption_json",
    "evidence_reference", "notes",
)


def create_template(path: Path) -> None:
    if path.exists():
        raise FileExistsError("Output already exists; do not replace existing QA records")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=COLUMNS, lineterminator="\n")
        writer.writeheader()
        for condition in CONDITIONS:
            for attempt in ATTEMPTS:
                writer.writerow({"session_id": f"{condition}-{attempt}",
                                 "condition": condition, "attempt": attempt, "status": "NOT RUN"})


def load_manifest(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if tuple(reader.fieldnames or ()) != COLUMNS:
            raise ValueError("Unexpected manifest columns; use the generated template")
        rows = list(reader)
        if any(None in row for row in rows):
            raise ValueError("Unexpected extra manifest cells")
        return rows


def group_stats(values: list[float]) -> dict:
    return {"count": len(values), "median": round(statistics.median(values), 3) if values else None,
            "min": round(min(values), 3) if values else None,
            "max": round(max(values), 3) if values else None}


def verify_row(row: dict[str, str], directory: Path) -> dict:
    name = row["session_id"].strip()
    condition = row["condition"].strip()
    attempt = row["attempt"].strip()
    status = row["status"].strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name):
        raise ValueError("invalid session ID")
    if condition not in CONDITIONS or not attempt.isdecimal() or int(attempt) not in ATTEMPTS:
        raise ValueError("unknown condition or attempt")
    if status not in STATUSES:
        raise ValueError("invalid status")
    if name != f"{condition}-{attempt}":
        raise ValueError("session ID must match the condition and attempt")
    result = {"condition": condition, "attempt": int(attempt), "status": status,
              "session_id": name}
    if status != "PASS":
        return result

    sha = row["source_sha"].strip()
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("PASS requires exact 40-character source commit")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", row["device_alias"].strip()):
        raise ValueError("PASS requires anonymous device alias")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", row["clip_id"].strip()):
        raise ValueError("PASS requires anonymous clip ID")
    if row["layout"].strip() not in ("popup", "split"):
        raise ValueError("PASS requires popup or split layout")
    if row["input_mode"].strip() not in ("microphone", "video"):
        raise ValueError("PASS requires microphone or video input")
    if row["source_language"].strip() not in ("en", "ja"):
        raise ValueError("PASS requires en or ja source language")
    if row["model_ready"].strip() != "YES" or row["first_translation"].strip() != "YES":
        raise ValueError("PASS requires manually verified model and first translation")
    behavior = row["correction_behavior"].strip()
    expected = {
        "correction_off": ("OFF",),
        "correction_on": ("ON_CHANGED", "ON_UNCHANGED"),
        "focus_on": ("FOCUS_SKIPPED",),
    }[condition]
    if behavior not in expected:
        raise ValueError("PASS correction behavior conflicts with condition")
    if not row["evidence_reference"].strip():
        raise ValueError("PASS requires independent, private evidence reference")
    for key in ("performance_csv", "caption_json"):
        if not row[key].strip():
            raise ValueError(f"PASS requires {key}")
    perf = (directory / row["performance_csv"].strip()).resolve()
    caption = (directory / row["caption_json"].strip()).resolve()
    if directory.resolve() not in perf.parents or directory.resolve() not in caption.parents:
        raise ValueError("evidence paths must stay inside the manifest directory")
    raw_measurements = read_run(perf)
    if any(not math.isfinite(value) for sample in raw_measurements
           for value in sample.values() if value is not None):
        raise ValueError("Telemetry values and timestamps must be finite")
    measures = summarize(raw_measurements)
    if not 270 <= measures["duration_s"] <= 330 or measures["samples"] < 30:
        raise ValueError("PASS needs a five-minute telemetry recording (270-330 seconds)")
    if measures["pss_kb"]["count"] < 30 or measures["cpu_percent"]["count"] < 30:
        raise ValueError("PASS needs at least 30 valid CPU and PSS samples")
    with caption.open("r", encoding="utf-8") as stream:
        timed = json.load(stream)
    if (timed.get("schema") != "read-ux-00-v1" or
            timed.get("source_sha") != sha or
            timed.get("session_id") != name or
            timed.get("layout") != row["layout"].strip() or
            timed.get("status") != "ANNOTATED_UNVERIFIED" or
            timed.get("counts", {}).get("annotated_cues", 0) < 1 or
            timed.get("source_clip_ms") != 300000 or
            timed.get("capture_tail_ms") != 30000):
        raise ValueError("caption report source, session, layout or data missing")
    lag = timed.get("lag_source_start_to_first_visible_ms", {})
    if lag.get("count", 0) < 1 or lag.get("median") is None:
        raise ValueError("caption report has no measured on-screen translation")
    rate = timed.get("missing_rate_excluding_ambiguous")
    if rate is None or not isinstance(rate, (int, float)) or not 0 <= rate <= 1:
        raise ValueError("caption report has no valid missing-rate observation")
    result.update({
        "source_sha": sha, "device_alias": row["device_alias"].strip(),
        "layout": row["layout"].strip(), "input_mode": row["input_mode"].strip(),
        "source_language": row["source_language"].strip(), "clip_id": row["clip_id"].strip(),
        "cpu_median_percent": measures["cpu_percent"]["median"],
        "pss_median_kb": measures["pss_kb"]["median"],
        "battery_temp_median_c": measures["battery_temp_c"]["median"],
        "first_caption_lag_median_ms": lag["median"],
        "first_caption_lag_p95_ms": lag["p95"],
        "missing_rate": rate,
        "revisions": timed["counts"].get("total_revisions"),
        "correction_behavior": behavior,
        "_performance_path": str(perf), "_caption_path": str(caption),
        "_performance_digest": hashlib.sha256(perf.read_bytes()).hexdigest(),
        "_caption_digest": hashlib.sha256(caption.read_bytes()).hexdigest(),
        "_evidence_ref": row["evidence_reference"].strip(),
    })
    return result


def evidence_reuse_errors(passing: list[dict]) -> list[str]:
    errors = []
    for field in ("_performance_path", "_caption_path", "_performance_digest", "_caption_digest", "_evidence_ref"):
        values = [row[field] for row in passing]
        if len(set(values)) != len(values):
            errors.append(f"Reused {field} across separate PASS sessions")
    return errors


def analyze(manifest: Path) -> dict:
    rows = load_manifest(manifest)
    keys = set()
    errors = []
    sessions = []
    for line, row in enumerate(rows, start=2):
        try:
            session = verify_row(row, manifest.parent)
            key = (session["condition"], session["attempt"])
            if key in keys:
                raise ValueError("duplicate condition and attempt")
            keys.add(key)
            sessions.append(session)
        except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError, csv.Error) as exc:
            errors.append(f"line {line}: {exc}")
    expected = {(c, n) for c in CONDITIONS for n in ATTEMPTS}
    missing = sorted(expected - keys)
    passing = [s for s in sessions if s["status"] == "PASS"]
    passed_keys = {(s["condition"], s["attempt"]) for s in passing}
    pending = sorted(expected - passed_keys)
    identity = ("source_sha", "device_alias", "layout", "input_mode", "source_language", "clip_id")
    if passing and any(len({s[key] for s in passing}) > 1 for key in identity):
        errors.append("PASS sessions have mismatched SHA, device, input, layout, language or clip")
    if passing:
        errors.extend(evidence_reuse_errors(passing))
    summary = {}
    for cond in CONDITIONS:
        values = [s for s in passing if s["condition"] == cond]
        summary[cond] = {
            "passingAttempts": sorted(s["attempt"] for s in values),
            "cpuMedianPercent": group_stats([s["cpu_median_percent"] for s in values if s["cpu_median_percent"] is not None]),
            "pssMedianKb": group_stats([s["pss_median_kb"] for s in values if s["pss_median_kb"] is not None]),
            "batteryTempMedianC": group_stats([s["battery_temp_median_c"] for s in values if s["battery_temp_median_c"] is not None]),
            "captionLagP95Ms": group_stats([s["first_caption_lag_p95_ms"] for s in values if s["first_caption_lag_p95_ms"] is not None]),
            "missingRate": group_stats([s["missing_rate"] for s in values if s["missing_rate"] is not None]),
            "totalRevisions": sum((s["revisions"] or 0) for s in values),
        }
    ready = (not errors and not missing and len(passing) == len(expected) and
             len(sessions) == len(expected))
    return {"schema": "qa-real-01-b-v1", "source": "operator_supplied_local_measurements",
            "testSessionsRequired": len(expected), "passSessions": len(passing),
            "missingSessions": [f"{a}-{n}" for a, n in missing],
            "pendingSessions": [f"{a}-{n}" for a, n in pending],
            "validationErrors": errors, "metricsByCondition": summary,
            "status": "READY_FOR_INDEPENDENT_MANUAL_REVIEW" if ready else "NOT_RUN_OR_INCOMPLETE",
            "physicalDeviceProven": False, "releaseApproved": False,
            "notice": "Complete local CSV files cannot independently prove physical-device identity, AI inference quality or consent. Manual evidence review is mandatory."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    init = sub.add_parser("init")
    init.add_argument("--output", required=True, type=Path)
    check = sub.add_parser("analyze")
    check.add_argument("--input", required=True, type=Path)
    check.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.action == "init":
            create_template(args.output)
            print("QA-REAL-01-B: 9 NOT RUN sessions created; no Android test performed")
        else:
            if args.output.exists():
                raise FileExistsError("Output already exists; no report overwrite")
            result = analyze(args.input)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as stream:
                json.dump(result, stream, indent=2, ensure_ascii=False)
                stream.write("\n")
            print(json.dumps({"status": result["status"], "passSessions": result["passSessions"],
                              "validationErrors": result["validationErrors"], "releaseApproved": False}, ensure_ascii=False))
            if result["validationErrors"]:
                return 2
    except (ValueError, OSError, csv.Error) as exc:
        parser.exit(2, f"QA-REAL-01-B: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
