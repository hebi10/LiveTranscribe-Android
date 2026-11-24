#!/usr/bin/env python3
"""Read-only Cloud configuration preflight. Never deploy, enable APIs or access secret values."""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REQUIRED_APIS = frozenset({
    "run.googleapis.com", "playintegrity.googleapis.com", "androidpublisher.googleapis.com",
    "secretmanager.googleapis.com", "artifactregistry.googleapis.com",
})
PROJECT_ID = re.compile(r"[a-z][a-z0-9-]{4,28}[a-z0-9]")
SERVICE = re.compile(r"[a-z][a-z0-9-]{0,47}[a-z0-9]")
REGION = re.compile(r"[a-z]+(?:-[a-z]+)+[0-9]+")
RUN_ORIGIN = re.compile(r"https://[a-z0-9-]+(?:\.[a-z0-9-]+)*\.run\.app")
SECRET_ENV = "BILLING_GATEWAY_AUTH_TOKEN"


def gcloud(args):
    # A specific project/region is supplied to each read. Never print provider stdout/stderr.
    env = dict(os.environ, CLOUDSDK_CORE_DISABLE_PROMPTS="1", CLOUDSDK_CORE_LOG_HTTP="false",
               CLOUDSDK_CORE_DISABLE_FILE_LOGGING="true")
    try:
        result = subprocess.run(["gcloud", *args, "--format=json", "--verbosity=error"],
                                stdin=subprocess.DEVNULL, capture_output=True,
                                timeout=40, check=True, env=env)
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def at(value, *path, default=None):
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return default
        value = value[key]
    return value


def equals_if_known(value, expected):
    return None if value is None else value == expected


def report_check(report, key, result):
    report["checks"][key] = "UNKNOWN" if result is None else "PASS" if result else "FAIL"


def service_env(service):
    containers = at(service, "spec", "template", "spec", "containers")
    if not isinstance(containers, list) or len(containers) != 1:
        return None
    entries = at(containers[0], "env", default=[])
    if not isinstance(entries, list):
        return None
    mapped = {}
    for entry in entries:
        name = at(entry, "name")
        if not isinstance(name, str) or name in mapped:
            return None
        mapped[name] = entry
    return mapped


def secret_ref(env):
    if env is None:
        return None
    item = env.get(SECRET_ENV)
    if not isinstance(item, dict) or "value" in item:
        return False
    ref = at(item, "valueFrom", "secretKeyRef")
    name, key = at(ref, "name"), at(ref, "key")
    # Explicit numbered versions keep the two services synchronized.
    if not isinstance(name, str) or not name or not isinstance(key, str) or not re.fullmatch(r"[1-9][0-9]*", key):
        return False
    return (name, key)


def service_ready(service):
    conditions = at(service, "status", "conditions")
    if not isinstance(conditions, list):
        return None
    ready = [c for c in conditions if at(c, "type") == "Ready"]
    if len(ready) != 1:
        return None
    generation = at(service, "metadata", "generation")
    observed = at(service, "status", "observedGeneration")
    if generation is None or observed is None:
        return None
    return at(ready[0], "status") == "True" and str(generation) == str(observed)


def identity(service):
    value = at(service, "spec", "template", "spec", "serviceAccountName")
    return value if isinstance(value, str) and value.endswith(".gserviceaccount.com") else None


def invoker_policy(policy, gateway_identity):
    bindings = at(policy, "bindings")
    if not isinstance(bindings, list) or gateway_identity is None:
        return None
    # Only the gateway's dedicated identity may have direct Run invoker access.
    allowed = "serviceAccount:" + gateway_identity
    invokers = set()
    for item in bindings:
        members = at(item, "members")
        if not isinstance(members, list) or not all(isinstance(m, str) for m in members):
            return None
        if any(m in {"allUsers", "allAuthenticatedUsers"} for m in members):
            return False
        if at(item, "role") == "roles/run.invoker":
            if "condition" in item:
                return False
            invokers.update(members)
    return invokers == {allowed}


def inspect(project, region=None, gateway=None, verifier=None, play_project_number=None, read=gcloud):
    report = {
        "schema": "lt-billing-ops-preflight-v1",
        "package": "com.hebi.livetranslate",
        "readOnly": True, "livePurchaseVerified": False, "productionReady": False,
        "checks": {},
        "manualReview": [
            "Play Console project linkage and Play account licensing",
            "Service account effective IAM (including inherited bindings) and Play app permissions",
            "Secret Manager access grants, Cloud Armor per-caller rate limit and load balancer routing",
            "Deployed image digest/source provenance, Cloud budgets and actual endpoint probes",
            "AdMob/UMP, data safety, published privacy notice and licensed device purchase/refund/restore",
        ],
    }
    def check(key, value):
        report_check(report, key, value)
    p = read(["projects", "describe", project])
    check("project_active", None if not isinstance(p, dict) else
          p.get("projectId") == project and p.get("lifecycleState") == "ACTIVE")
    number = at(p, "projectNumber")
    check("play_integrity_project_number_match", None if number is None or play_project_number is None
          else str(number) == str(play_project_number))
    billing = read(["billing", "projects", "describe", project])
    check("billing_enabled", equals_if_known(at(billing, "billingEnabled"), True))
    enabled = read(["services", "list", "--enabled", "--project", project])
    names = {at(x, "config", "name") for x in enabled} if isinstance(enabled, list) else None
    for api in sorted(REQUIRED_APIS):
        check("api:" + api, None if names is None else api in names)
    if not all((region, gateway, verifier)):
        check("cloud_run_services", None)
        return report
    def run_read(action, name):
        return read(["run", "services", action, name, "--project", project, "--region", region])
    g, v = run_read("describe", gateway), run_read("describe", verifier)
    for name, service in (("gateway", g), ("verifier", v)):
        check(name + "_service_ready", service_ready(service))
        max_scale = at(service, "metadata", "annotations", "run.googleapis.com/maxScale")
        # Service-level limit is used; revision-only limit is insufficient during traffic splits.
        check(name + "_service_max_instances_1", None if max_scale is None else str(max_scale) == "1")
        concurrency = at(service, "spec", "template", "spec", "containerConcurrency")
        check(name + "_concurrency_limit", None if concurrency is None else
              type(concurrency) is int and 1 <= concurrency <= 8)
        check(name + "_dedicated_identity_present", None if service is None else identity(service) is not None)
    gsa, vsa = identity(g), identity(v)
    check("separate_runtime_identities", None if not gsa or not vsa else gsa != vsa)
    annotations = at(v, "metadata", "annotations")
    check("verifier_invoker_iam_enabled", None if not isinstance(annotations, dict) else
          str(annotations.get("run.googleapis.com/invoker-iam-disabled", "false")).lower() == "false")
    check("verifier_direct_invoker_policy", invoker_policy(run_read("get-iam-policy", verifier), gsa))
    gann = at(g, "metadata", "annotations")
    check("gateway_ingress_restricted_to_load_balancer", None if not isinstance(gann, dict) else
          gann.get("run.googleapis.com/ingress-status", gann.get("run.googleapis.com/ingress")) ==
          "internal-and-cloud-load-balancing")
    check("gateway_default_url_disabled", None if not isinstance(gann, dict) else
          str(gann.get("run.googleapis.com/default-url-disabled", "false")).lower() == "true")
    genv, venv = service_env(g), service_env(v)
    gs, vs = secret_ref(genv), secret_ref(venv)
    check("shared_pinned_secret_reference", None if gs is None or vs is None else
          bool(gs and vs and gs == vs))
    origin = at(genv, "BILLING_PRIVATE_VERIFIER_ORIGIN", "value")
    verifier_url = at(v, "status", "url")
    check("gateway_targets_exact_private_origin", None if genv is None or verifier_url is None else
          isinstance(origin, str) and bool(RUN_ORIGIN.fullmatch(origin)) and origin == verifier_url)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Explicit Google Cloud project ID")
    parser.add_argument("--play-project-number", help="Project number observed in this app's Play Console")
    parser.add_argument("--region")
    parser.add_argument("--gateway")
    parser.add_argument("--verifier")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not PROJECT_ID.fullmatch(args.project):
        parser.error("Use an explicit standard project ID")
    if args.play_project_number and not re.fullmatch(r"[1-9][0-9]{0,19}", args.play_project_number):
        parser.error("Invalid project number")
    values = (args.region, args.gateway, args.verifier)
    if any(values) and (not all(values) or not REGION.fullmatch(args.region)
                        or not SERVICE.fullmatch(args.gateway) or not SERVICE.fullmatch(args.verifier)
                        or args.gateway == args.verifier):
        parser.error("Supply one region and two distinct service names together")
    report = inspect(args.project, args.region, args.gateway, args.verifier, args.play_project_number)
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    sys.stdout.write(output)
    # Unknown is not a pass; even a clean metadata audit requires manual operational approval.
    return 0 if all(x == "PASS" for x in report["checks"].values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
