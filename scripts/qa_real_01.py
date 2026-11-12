#!/usr/bin/env python3
"""Generate and audit non-identifying LiveTranscribe device QA records.

This only validates researcher-supplied records. It never controls Android,
labels an emulator run as physical evidence, or approves a Play release.
"""
import argparse
import csv
import json
from pathlib import Path

BASE_IDS = tuple(f"DEV-{i:02d}" for i in range(1, 16))
CORRECTION_IDS = tuple(f"CORR-{i:02d}" for i in range(1, 9))
REQUIRED_IDS = BASE_IDS + CORRECTION_IDS
REPEAT_IDS = frozenset({"DEV-09", "DEV-15", "CORR-03", "CORR-08"})
STATUSES = frozenset({"NOT RUN", "PASS", "FAIL", "BLOCKED", "CI ONLY"})
COLUMNS = (
    "test_id", "device_model", "android_api", "abi", "app_commit", "attempt",
    "input_mode", "source_language", "expected_result", "actual_result",
    "status", "elapsed_ms", "recognition_latency_ms", "translation_latency_ms",
    "process_pss_kb", "cpu_percent", "battery_temperature_c", "missing_count",
    "duplicate_count", "crash_or_anr", "evidence_reference", "notes",
)


def write_template(destination: Path) -> None:
    if destination.exists():
        raise ValueError("Template destination exists; never overwrite recorded evidence")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=COLUMNS, lineterminator="\n")
        writer.writeheader()
        for scenario_id in REQUIRED_IDS:
            writer.writerow({"test_id": scenario_id, "status": "NOT RUN"})


def audit(rows: list[dict[str, str]]) -> dict:
    counts = {name: 0 for name in STATUSES}
    errors = []
    per_id: dict[str, list[dict[str, str]]] = {name: [] for name in REQUIRED_IDS}
    for line, row in enumerate(rows, 2):
        scenario_id = row.get("test_id", "").strip()
        status = row.get("status", "").strip()
        if scenario_id not in per_id:
            errors.append(f"line {line}: unrecognized scenario ID")
            continue
        if status not in STATUSES:
            errors.append(f"line {line}: unrecognized status")
            continue
        counts[status] += 1
        per_id[scenario_id].append(row)
        if status == "PASS":
            if not row.get("evidence_reference", "").strip():
                errors.append(f"line {line}: PASS requires an independent evidence reference")
            if not row.get("app_commit", "").strip() or not row.get("abi", "").strip():
                errors.append(f"line {line}: PASS requires app commit and device ABI")
            attempt = row.get("attempt", "").strip()
            if not attempt.isdecimal() or int(attempt) < 1:
                errors.append(f"line {line}: PASS requires a positive attempt number")

    missing = [scenario for scenario, records in per_id.items() if not records]
    completed = []
    for scenario, records in per_id.items():
        passing = [r for r in records if r.get("status", "").strip() == "PASS"]
        attempt_numbers = {int(r["attempt"]) for r in passing if r.get("attempt", "").strip().isdecimal()}
        expected_repeats = 3 if scenario in REPEAT_IDS else 1
        if (len(attempt_numbers) >= expected_repeats and
            all(r.get("status") != "FAIL" for r in records)):
            completed.append(scenario)

    pending = [scenario for scenario in REQUIRED_IDS if scenario not in completed]
    has_failures = any(r.get("status") == "FAIL" for records in per_id.values() for r in records)
    eligible_for_human_review = not errors and not pending and not has_failures
    return {
        "project": "LiveTranscribe-Android",
        "testCases": len(REQUIRED_IDS),
        "recordsProvided": len(rows),
        "statusCounts": {key: counts[key] for key in sorted(counts)},
        "completedScenarioIds": completed,
        "pendingScenarioIds": pending,
        "missingScenarioIds": missing,
        "validationErrors": errors,
        "humanReviewCandidate": eligible_for_human_review,
        "releaseApproval": "never_automatic_manual_review_required",
        "notice": "CSV assertions and filenames do not establish device provenance or inference correctness.",
    }


def read_results(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as file:
        reader = csv.DictReader(file)
        if reader.fieldnames is None or tuple(reader.fieldnames) != COLUMNS:
            raise ValueError("Unexpected CSV fields; use the QA-REAL-01 template")
        return list(reader)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="operation", required=True)
    start = subcommands.add_parser("init", help="Create an empty NOT RUN CSV only")
    start.add_argument("--output", required=True, type=Path)
    report = subcommands.add_parser("audit", help="Audit the user's CSV without changing it")
    report.add_argument("--results", required=True, type=Path)
    report.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.operation == "init":
            write_template(args.output)
            print(json.dumps({"status": "template_created_NOT_RUN", "path": str(args.output)},
                             ensure_ascii=False))
        else:
            result = audit(read_results(args.results))
            if args.report:
                if args.report.exists():
                    raise ValueError("Existing report will not be overwritten")
                args.report.parent.mkdir(parents=True, exist_ok=True)
                args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
            print(json.dumps(result, ensure_ascii=False, indent=2))
            if result["validationErrors"]:
                return 2
    except (ValueError, OSError, csv.Error) as error:
        parser.exit(2, f"QA-REAL-01: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
