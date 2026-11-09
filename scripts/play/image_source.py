#!/usr/bin/env python3
"""Validate the exact reviewed main source after the app's Play release job has finished."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import source

REPO = "hebi10/LiveTranscribe-Android"
INTERNAL_WORKFLOW = ".github/workflows/play-internal.yml"


def resolve_image_source(env, event):
    if env.get("GITHUB_REPOSITORY") != REPO:
        raise source.SourceError("Image sync is restricted to its own repository")
    trigger = env.get("GITHUB_EVENT_NAME")
    if trigger == "workflow_dispatch":
        if env.get("GITHUB_REF") != "refs/heads/main":
            raise source.SourceError("Manual image sync requires main")
        sha = env.get("GITHUB_SHA", "")
    elif trigger == "workflow_run":
        run = event.get("workflow_run", {})
        if (run.get("name") != "Play internal release" or
                run.get("path") != INTERNAL_WORKFLOW or
                run.get("event") != "workflow_run" or
                run.get("status") != "completed" or
                run.get("conclusion") not in ("success", "failure") or
                run.get("head_branch") != "main" or
                run.get("head_repository", {}).get("full_name") != REPO):
            raise source.SourceError("Image sync requires a completed trusted internal-release workflow")
        sha = run.get("head_sha", "")
    else:
        raise source.SourceError("Unsupported image sync trigger")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise source.SourceError("Invalid main source SHA")
    return sha


def main():
    try:
        env = os.environ
        event = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        sha = resolve_image_source(env, event)
        current = source.gh(f"repos/{REPO}/git/ref/heads/main")["object"]["sha"]
        source.require_current_main(sha, current)
        runs = source.gh(
            f"repos/{REPO}/actions/workflows/build.yml/runs?head_sha={sha}&branch=main&per_page=100"
        )["workflow_runs"]
        verified = [run for run in runs
                    if run.get("conclusion") == "success"
                    and run.get("event") in ("push", "workflow_dispatch")]
        if not verified:
            raise source.SourceError("Exact main Android CI success is required")
        build = max(verified, key=lambda item: item["id"])
        jobs = source.gh(f"repos/{REPO}/actions/runs/{build['id']}/jobs?per_page=100")["jobs"]
        source.verify_ci(sha, build, jobs)
        subject = subprocess.run(["git", "show", "-s", "--format=%s", sha],
                                 capture_output=True, check=False, text=True)
        if subject.returncode:
            raise source.SourceError("Unable to verify the image sync commit")
        publish = (trigger_marker(subject.stdout) if env["GITHUB_EVENT_NAME"] == "workflow_run"
                   else env.get("REQUEST_MODE") == "apply")
        with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"sha={sha}\n")
            output.write("publish=" + ("true" if publish else "false") + "\n")
        print("Verified exact main CI and trusted internal workflow completion; image apply=" + str(publish))
        return 0
    except (source.SourceError, KeyError, OSError, ValueError) as error:
        print("Image source verification failed: " + str(error), file=sys.stderr)
        return 1


def trigger_marker(subject):
    return "[sync-play-images]" in subject.splitlines()[0]


if __name__ == "__main__":
    raise SystemExit(main())
