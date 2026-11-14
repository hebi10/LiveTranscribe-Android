#!/usr/bin/env python3
"""Summarize manually annotated five-minute subtitle usability sessions.

No audio, video, speech, or subtitle text is collected by this tool.
The report is not evidence that any Android device was tested.
"""

import argparse
import csv
import json
import math
import re
import statistics
from pathlib import Path

CLIP_MS = 300_000
CAPTURE_TAIL_MS = 30_000
COLUMNS = (
    "cue_id", "source_start_ms", "source_end_ms", "outcome",
    "first_visible_ms", "settled_ms", "hidden_ms", "display_chars",
    "max_lines", "revisions", "duplicates"
)
OUTCOMES = frozenset({"MATCHED", "MISSING", "AMBIGUOUS"})
REVIEW_START_LAG_MS = 3000
REVIEW_READABLE_CPS = 12.0
REVIEW_MAX_LINES = 2


def create_template(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(COLUMNS)


def parse_int(row: dict, key: str, line: int, required: bool = True):
    value = (row.get(key) or "").strip()
    if not value:
        if required:
            raise ValueError(f"row {line}: missing {key}")
        return None
    if not re.fullmatch(r"\d+", value):
        raise ValueError(f"row {line}: {key} must be a nonnegative integer")
    return int(value)


def parse_rows(source: Path) -> list[dict]:
    with source.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if tuple(reader.fieldnames or ()) != COLUMNS:
            raise ValueError("Unrecognized CSV columns; use READ-UX-00 template")
        seen = set()
        parsed = []
        for line, row in enumerate(reader, start=2):
            if None in row or not re.fullmatch(r"C\d{3,}", (row["cue_id"] or "").strip()):
                raise ValueError(f"row {line}: invalid cue_id or extra columns")
            cue_id = row["cue_id"].strip()
            if cue_id in seen:
                raise ValueError(f"row {line}: duplicate cue_id")
            seen.add(cue_id)
            start = parse_int(row, "source_start_ms", line)
            end = parse_int(row, "source_end_ms", line)
            if end <= start or end > CLIP_MS:
                raise ValueError(f"row {line}: source times must be within the five-minute clip")
            outcome = (row["outcome"] or "").strip()
            if outcome not in OUTCOMES:
                raise ValueError(f"row {line}: invalid outcome")
            visual = {
                name: parse_int(row, name, line, required=False)
                for name in ("first_visible_ms", "settled_ms", "hidden_ms", "display_chars",
                             "max_lines", "revisions", "duplicates")
            }
            if outcome == "MATCHED":
                for name in ("first_visible_ms", "display_chars", "max_lines", "revisions", "duplicates"):
                    if visual[name] is None:
                        raise ValueError(f"row {line}: MATCHED requires {name}")
                first = visual["first_visible_ms"]
                hidden = visual["hidden_ms"]
                settled = visual["settled_ms"]
                if first < start or first > CLIP_MS + CAPTURE_TAIL_MS:
                    raise ValueError(f"row {line}: first subtitle timestamp is outside capture range")
                if visual["display_chars"] < 1 or visual["max_lines"] < 1:
                    raise ValueError(f"row {line}: visible text must have positive length and lines")
                if visual["revisions"] > 0 and settled is None:
                    raise ValueError(f"row {line}: revised caption needs settled_ms")
                if settled is not None and (settled < first or settled > CLIP_MS + CAPTURE_TAIL_MS):
                    raise ValueError(f"row {line}: invalid settled_ms")
                if visual["revisions"] == 0 and settled is not None and settled != first:
                    raise ValueError(f"row {line}: unchanged caption must settle at first appearance")
                if hidden is not None and (hidden <= first or hidden > CLIP_MS + CAPTURE_TAIL_MS or
                                           (settled is not None and hidden < settled)):
                    raise ValueError(f"row {line}: invalid hidden_ms")
            elif any(value is not None for value in visual.values()):
                raise ValueError(f"row {line}: MISSING/AMBIGUOUS must not invent subtitle data")
            parsed.append({"cue_id": cue_id, "source_start_ms": start,
                           "source_end_ms": end, "outcome": outcome, **visual})
        return parsed


def metric(values: list) -> dict:
    if not values:
        return {"count": 0, "median": None, "p95": None, "max": None}
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * .95) - 1)
    return {"count": len(ordered), "median": round(statistics.median(ordered), 2),
            "p95": round(ordered[index], 2), "max": round(ordered[-1], 2)}


def summarize(rows: list[dict], *, session_id: str, source_sha: str, layout: str) -> dict:
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", session_id):
        raise ValueError("session-id must be a non-identifying identifier (letters, numbers, _ or -)")
    if not re.fullmatch(r"[a-f0-9]{40}", source_sha):
        raise ValueError("source-sha must be an exact 40-character commit SHA")
    if layout not in ("popup", "split"):
        raise ValueError("layout must be popup or split")
    matched = [r for r in rows if r["outcome"] == "MATCHED"]
    missing = [r for r in rows if r["outcome"] == "MISSING"]
    ambiguous = [r for r in rows if r["outcome"] == "AMBIGUOUS"]
    hold = [r["hidden_ms"] - r["first_visible_ms"] for r in matched if r["hidden_ms"] is not None]
    cps = [r["display_chars"] * 1000.0 / (r["hidden_ms"] - r["first_visible_ms"])
           for r in matched if r["hidden_ms"] is not None]
    settle = [(r["settled_ms"] - r["first_visible_ms"]) for r in matched if r["revisions"] > 0]
    observed = len(matched) + len(missing)
    return {
        "schema": "read-ux-00-v1", "session_id": session_id,
        "source_sha": source_sha, "layout": layout,
        "source_clip_ms": CLIP_MS, "capture_tail_ms": CAPTURE_TAIL_MS,
        "status": "ANNOTATED_UNVERIFIED" if rows else "NOT_RUN",
        "source": "operator-supplied synchronized video annotation",
        "counts": {"annotated_cues": len(rows), "matched": len(matched), "missing": len(missing),
                   "ambiguous": len(ambiguous), "matched_without_hide_timestamp": len(matched) - len(hold),
                   "revised_cues": sum(r["revisions"] > 0 for r in matched),
                   "total_revisions": sum(r["revisions"] for r in matched),
                   "total_duplicates": sum(r["duplicates"] for r in matched)},
        "missing_rate_excluding_ambiguous": round(len(missing) / observed, 4) if observed else None,
        "lag_source_start_to_first_visible_ms": metric([r["first_visible_ms"] - r["source_start_ms"] for r in matched]),
        "lag_source_end_to_first_visible_ms": metric([r["first_visible_ms"] - r["source_end_ms"] for r in matched]),
        "caption_visible_duration_ms": metric(hold),
        "display_chars_per_visible_second": metric(cps),
        "time_until_last_rewrite_ms": metric(settle),
        "review_flags": {
            "start_lag_above_3000ms": sum(r["first_visible_ms"] - r["source_start_ms"] > REVIEW_START_LAG_MS for r in matched),
            "more_than_two_lines": sum(r["max_lines"] > REVIEW_MAX_LINES for r in matched),
            "display_above_12_cps": sum(v > REVIEW_READABLE_CPS for v in cps),
        },
        "release_approved": False,
        "actual_physical_device_proven": False,
        "limitation": "Only summarizes manually annotated timings, not native ASR inference or certified screen visibility. No raw voice, text or recording is stored. Verify source video and timing manually."
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_subparsers(dest="action", required=True)
    template = action.add_parser("template")
    template.add_argument("--output", required=True, type=Path)
    analyze = action.add_parser("analyze")
    analyze.add_argument("--input", required=True, type=Path)
    analyze.add_argument("--output", required=True, type=Path)
    analyze.add_argument("--session-id", required=True)
    analyze.add_argument("--source-sha", required=True)
    analyze.add_argument("--layout", choices=("popup", "split"), required=True)
    args = parser.parse_args()
    try:
        if args.action == "template":
            create_template(args.output)
            print("Created blank timing annotation template; no measurement performed")
        else:
            if args.output.exists():
                raise FileExistsError("Report path already exists; do not overwrite previous evidence")
            report = summarize(parse_rows(args.input), session_id=args.session_id,
                               source_sha=args.source_sha, layout=args.layout)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as stream:
                json.dump(report, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
            print(json.dumps({"status": report["status"], "matched": report["counts"]["matched"],
                              "missing": report["counts"]["missing"],
                              "human_review_required": True, "release_approved": False}, ensure_ascii=False))
    except (ValueError, OSError) as error:
        parser.exit(2, f"READ-UX-00 stopped: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
