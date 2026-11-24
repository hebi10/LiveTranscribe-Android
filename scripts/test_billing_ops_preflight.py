import copy
import json
import unittest
from unittest.mock import patch

import billing_ops_preflight as p


PROJECT = "example-project"
GATEWAY = "billing-gateway"
VERIFIER = "billing-verifier"
GSA = "gateway@example-project.iam.gserviceaccount.com"
VSA = "verifier@example-project.iam.gserviceaccount.com"
ORIGIN = "https://billing-verifier-example.a.run.app"


def service(identity, gateway=False):
    annotations = {"run.googleapis.com/maxScale": "1", "run.googleapis.com/invoker-iam-disabled": "false"}
    if gateway:
        annotations.update({"run.googleapis.com/ingress-status": "internal-and-cloud-load-balancing",
                            "run.googleapis.com/default-url-disabled": "true"})
    env = [{"name": p.SECRET_ENV, "valueFrom": {"secretKeyRef": {"name": "shared-ref", "key": "1"}}}]
    if gateway:
        env.append({"name": "BILLING_PRIVATE_VERIFIER_ORIGIN", "value": ORIGIN})
    return {"metadata": {"generation": 1, "annotations": annotations},
            "spec": {"template": {"spec": {"serviceAccountName": identity,
                "containerConcurrency": 8, "containers": [{"env": env}]}}},
            "status": {"observedGeneration": 1, "url": ORIGIN,
                       "conditions": [{"type": "Ready", "status": "True"}]}}


class BillingOperationsPreflightTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "projects": {"projectId": PROJECT, "projectNumber": "123456789", "lifecycleState": "ACTIVE"},
            "billing": {"billingEnabled": True, "billingAccountName": "not-exported"},
            "services": [{"config": {"name": api}} for api in p.REQUIRED_APIS],
            GATEWAY: service(GSA, True),
            VERIFIER: service(VSA),
            "policy": {"bindings": [{"role": "roles/run.invoker", "members": ["serviceAccount:" + GSA]}]},
        }
        self.calls = []

    def read(self, args):
        self.calls.append(args)
        if args[:3] == ["run", "services", "get-iam-policy"]:
            return self.data["policy"]
        if args[:3] == ["run", "services", "describe"]:
            return self.data[args[3]]
        return self.data[args[0]]

    def audit(self):
        return p.inspect(PROJECT, "asia-northeast3", GATEWAY, VERIFIER, "123456789", self.read)

    def test_good_metadata_does_not_claim_production_or_purchase_ready(self):
        report = self.audit()
        self.assertTrue(all(x == "PASS" for x in report["checks"].values()))
        self.assertFalse(report["productionReady"])
        self.assertFalse(report["livePurchaseVerified"])
        self.assertNotIn("not-exported", json.dumps(report))
        self.assertNotIn("shared-ref", json.dumps(report))
        self.assertNotIn(GSA, json.dumps(report))
        self.assertTrue(all(args[0] in ("projects", "billing", "services", "run") for args in self.calls))

    def test_missing_permissions_and_predeploy_services_remain_unknown(self):
        self.data["billing"] = None
        report = p.inspect(PROJECT, read=self.read)
        self.assertEqual("UNKNOWN", report["checks"]["billing_enabled"])
        self.assertEqual("UNKNOWN", report["checks"]["cloud_run_services"])
        self.assertFalse(any(args[0] == "run" for args in self.calls))

    def test_public_or_wrong_invoker_and_disabled_iam_rejected(self):
        for member in ("allUsers", "allAuthenticatedUsers", "serviceAccount:" + VSA):
            self.data["policy"]["bindings"][0]["members"] = [member]
            self.assertEqual("FAIL", self.audit()["checks"]["verifier_direct_invoker_policy"])
        self.data[VERIFIER]["metadata"]["annotations"]["run.googleapis.com/invoker-iam-disabled"] = "true"
        self.assertEqual("FAIL", self.audit()["checks"]["verifier_invoker_iam_enabled"])

    def test_plaintext_or_unpinned_secret_and_wrong_origin_rejected(self):
        env = self.data[GATEWAY]["spec"]["template"]["spec"]["containers"][0]["env"]
        env[0] = {"name": p.SECRET_ENV, "value": "never-show-this"}
        env[1]["value"] = "https://other-example.a.run.app"
        report = self.audit()
        self.assertEqual("FAIL", report["checks"]["shared_pinned_secret_reference"])
        self.assertEqual("FAIL", report["checks"]["gateway_targets_exact_private_origin"])
        self.assertNotIn("never-show-this", json.dumps(report))
        env[0] = {"name": p.SECRET_ENV, "valueFrom": {"secretKeyRef": {"name": "shared-ref", "key": "latest"}}}
        self.assertEqual("FAIL", self.audit()["checks"]["shared_pinned_secret_reference"])

    def test_wrong_project_number_and_service_limit(self):
        self.data["projects"]["projectNumber"] = "987654321"
        self.data[GATEWAY]["metadata"]["annotations"]["run.googleapis.com/maxScale"] = "2"
        self.data[GATEWAY]["metadata"]["annotations"]["run.googleapis.com/ingress-status"] = "all"
        checks = self.audit()["checks"]
        self.assertEqual("FAIL", checks["play_integrity_project_number_match"])
        self.assertEqual("FAIL", checks["gateway_service_max_instances_1"])
        self.assertEqual("FAIL", checks["gateway_ingress_restricted_to_load_balancer"])

    def test_provider_failure_returns_unknown_without_output(self):
        with patch.object(p.subprocess, "run", side_effect=OSError("private output")) as run:
            self.assertIsNone(p.gcloud(["projects", "describe", PROJECT]))
            self.assertEqual("1", run.call_args.kwargs["env"]["CLOUDSDK_CORE_DISABLE_PROMPTS"])


if __name__ == "__main__":
    unittest.main()
