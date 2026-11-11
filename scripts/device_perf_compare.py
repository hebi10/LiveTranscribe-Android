#!/usr/bin/env python3
"""Compare two local device telemetry runs without uploading private captures."""
import argparse
import csv
import json
import statistics
from pathlib import Path

COLUMNS = ("elapsed_s", "pss_kb", "cpu_percent", "battery_temp_c", "battery_level_percent")


def read_run(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not set(COLUMNS).issubset(reader.fieldnames):
            raise ValueError("Missing expected measurement columns")
        rows = []
        for row in reader:
            parsed = {}
            for key in COLUMNS:
                raw = row[key].strip()
                try:
                    parsed[key] = float(raw) if raw else None
                except ValueError as exc:
                    raise ValueError("Invalid numeric measurement") from exc
            rows.append(parsed)
    if not rows:
        raise ValueError("Empty measurement run")
    times = [r["elapsed_s"] for r in rows]
    if any(t is None or t < 0 for t in times) or times != sorted(times):
        raise ValueError("Invalid or unordered timestamps")
    return rows


def metric(values):
    clean = sorted(v for v in values if v is not None)
    if not clean:
        return {"count": 0, "median": None, "p95": None, "max": None}
    p95 = clean[max(0, (95 * len(clean) + 99) // 100 - 1)]
    return {"count": len(clean), "median": round(statistics.median(clean), 3),
            "p95": round(p95, 3), "max": round(clean[-1], 3)}


def summarize(rows):
    pss = [r["pss_kb"] for r in rows if r["pss_kb"] is not None]
    temps = [r["battery_temp_c"] for r in rows if r["battery_temp_c"] is not None]
    duration = rows[-1]["elapsed_s"] - rows[0]["elapsed_s"]
    return {
        "duration_s": round(duration, 3),
        "samples": len(rows),
        "cpu_percent": metric([r["cpu_percent"] for r in rows]),
        "pss_kb": metric([r["pss_kb"] for r in rows]),
        "pss_change_kb": round(pss[-1] - pss[0], 3) if len(pss) >= 2 else None,
        "battery_temp_c": metric([r["battery_temp_c"] for r in rows]),
        "battery_temp_change_c": round(temps[-1] - temps[0], 3) if len(temps) >= 2 else None,
        "note": "CPU is an OS reporting-window estimate; battery temperature is device-wide, not app-only.",
    }


def compare(base, candidate):
    b, c = summarize(base), summarize(candidate)
    deltas = {}
    for key in ("cpu_percent", "pss_kb", "battery_temp_c"):
        old, new = b[key]["median"], c[key]["median"]
        deltas[key + "_median_delta"] = round(new - old, 3) if old is not None and new is not None else None
    deltas["pss_change_delta_kb"] = (
        round(c["pss_change_kb"] - b["pss_change_kb"], 3)
        if c["pss_change_kb"] is not None and b["pss_change_kb"] is not None else None
    )
    comparable = b["duration_s"] > 0 and c["duration_s"] > 0 and (
        min(b["duration_s"], c["duration_s"]) / max(b["duration_s"], c["duration_s"]) >= .9
    ) and all(b[k]["count"] >= 3 and c[k]["count"] >= 3 for k in ("cpu_percent", "pss_kb"))
    return {"baseline": b, "candidate": c, "deltas": deltas,
            "comparable_duration_and_samples": comparable,
            "performance_improvement_verified": False,
            "limitation": "No performance claim without matched devices, equivalent input, repeated trials and caption latency measurements."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output path already exists")
    try:
        data = compare(read_run(args.baseline), read_run(args.candidate))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError) as exc:
        parser.exit(1, "Analysis stopped: " + str(exc) + "\n")


if __name__ == "__main__":
    main()
