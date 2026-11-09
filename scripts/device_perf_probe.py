#!/usr/bin/env python3
"""Read-only Android performance sampler. Never collects logs, audio or transcript text."""
import argparse
import csv
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_PACKAGE = "com.hebi.livetranslate.debug"
COLUMNS = ("elapsed_s", "pid", "pss_kb", "cpu_percent", "battery_temp_c", "battery_level_percent")


def parse_pid(text):
    values = re.findall(r"\b\d+\b", text)
    return int(values[0]) if len(values) == 1 else None


def parse_meminfo(text):
    match = re.search(r"^\s*TOTAL PSS:\s*([\d,]+)", text, re.MULTILINE | re.IGNORECASE)
    if match:
        return int(match.group(1).replace(",", ""))
    match = re.search(r"^\s*TOTAL\s+([\d,]+)\s", text, re.MULTILINE)
    return int(match.group(1).replace(",", "")) if match else None


def parse_cpuinfo(text, pid):
    # dumpsys cpuinfo percentages are sampled over the OS-defined window, not instantaneous.
    if pid is None:
        return None
    match = re.search(r"^\s*([\d.]+)%\s+" + re.escape(str(pid)) + r"/[^:\s]+:", text, re.MULTILINE)
    return float(match.group(1)) if match else None


def parse_battery(text):
    temp = re.search(r"^\s*temperature:\s*(-?\d+)", text, re.MULTILINE)
    level = re.search(r"^\s*level:\s*(\d+)", text, re.MULTILINE)
    return ((int(temp.group(1)) / 10) if temp else None,
            int(level.group(1)) if level else None)


def adb(args, serial=None, timeout=15):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    completed = subprocess.run(cmd + args, capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout, check=False)
    if completed.returncode != 0:
        raise RuntimeError("ADB command failed. Check USB debugging, device authorization and app state.")
    return completed.stdout


def sample(package, serial=None):
    pid = parse_pid(adb(["shell", "pidof", package], serial)) if running(package, serial) else None
    if pid is None:
        return {"pid": None, "pss_kb": None, "cpu_percent": None,
                "battery_temp_c": None, "battery_level_percent": None}
    mem = adb(["shell", "dumpsys", "meminfo", str(pid)], serial)
    cpu = adb(["shell", "dumpsys", "cpuinfo"], serial)
    battery = adb(["shell", "dumpsys", "battery"], serial)
    temperature, level = parse_battery(battery)
    return {"pid": pid, "pss_kb": parse_meminfo(mem),
            "cpu_percent": parse_cpuinfo(cpu, pid),
            "battery_temp_c": temperature, "battery_level_percent": level}


def running(package, serial):
    try:
        return bool(adb(["shell", "pidof", package], serial).strip())
    except RuntimeError:
        return False


def run(package, serial, seconds, interval, out):
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_.]+", package):
        raise ValueError("Invalid Android application ID")
    if out.exists():
        raise FileExistsError("Output already exists; choose a new path to avoid overwriting measurements")
    out.parent.mkdir(parents=True, exist_ok=True)
    start_utc = datetime.now(timezone.utc).isoformat()
    start = time.monotonic()
    rows = 0
    with out.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS)
        writer.writeheader()
        try:
            while True:
                elapsed = time.monotonic() - start
                if elapsed > seconds and rows:
                    break
                result = sample(package, serial)
                writer.writerow({"elapsed_s": round(elapsed, 2), **result})
                handle.flush()
                rows += 1
                remaining = seconds - (time.monotonic() - start)
                if remaining <= 0:
                    break
                time.sleep(min(interval, remaining))
        except KeyboardInterrupt:
            pass
    summary = {"app_package": package, "started_utc": start_utc,
               "sample_interval_s": interval, "requested_duration_s": seconds, "samples": rows,
               "measurement_notes": [
                   "CPU percent is dumpsys cpuinfo process utilization over its reporting window.",
                   "PSS is process memory, not system-wide RAM.",
                   "Battery temperature is not CPU/GPU temperature and is not attributable to the app.",
                   "No audio, captions, device identifier, or raw dumpsys output is stored."
               ]}
    meta = out.with_suffix(".json")
    with meta.open("x", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"Samples: {rows}; CSV: {out}; metadata: {meta}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--serial", default=None, help="ADB device selection; not saved in results")
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument("--interval", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.duration < 1 or args.interval < 1 or args.interval > args.duration:
        parser.error("duration and interval must be positive; interval cannot exceed duration")
    try:
        run(args.package, args.serial, args.duration, args.interval, args.output)
    except (FileExistsError, RuntimeError, subprocess.TimeoutExpired, OSError, ValueError) as error:
        parser.exit(1, f"Measurement stopped: {error}\n")


if __name__ == "__main__":
    main()
