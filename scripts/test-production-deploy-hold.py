#!/usr/bin/env python3
"""Static fail-closed tests for the Odisseu production workflow hold."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github/workflows/deploy.yml").read_text()
FORBIDDEN_IPS = ("35.238.24.225", "35.192.113.146", "34.68.189.66", "129.148.52.187")


def odisseu_deploy_job():
    match = re.search(r"^  deploy-odisseu:\n(.*?)(?=^  deploy-pokemon:)", WORKFLOW, re.M | re.S)
    if not match:
        raise AssertionError("deploy-odisseu job missing")
    condition = re.search(r"^    if: >-\n((?:^      .*\n)+)", match.group(1), re.M)
    if not condition:
        raise AssertionError("deploy-odisseu job condition missing")
    return "\n".join(line.strip() for line in condition.group(1).splitlines())


def allowed_by_workflow(hold, validated, event, operation, authorization="", service="node",
                        published=True, changed=True):
    # Push never deploys. Absent GitHub variables are empty and therefore block.
    if event != "workflow_dispatch" or not changed or operation == "check":
        return False
    if hold != "false" or validated != "true" or authorization != "autorizar-producao-a6":
        return False
    if service not in ("node", "jvm"):
        return False
    if operation == "rollback":
        return True
    return operation == "deploy" and published


class ProductionDeployHoldTests(unittest.TestCase):
    def test_job_is_manual_and_fail_closed(self):
        condition = odisseu_deploy_job()
        self.assertIn("vars.ODISSEU_PRODUCTION_DEPLOY_HOLD == 'false'", condition)
        self.assertIn("vars.ODISSEU_A6_DELIVERY_VALIDATED == 'true'", condition)
        self.assertIn("inputs.production_authorization == 'autorizar-producao-a6'", condition)
        self.assertIn("inputs.a6_service == matrix.service", condition)
        self.assertIn("github.event_name == 'workflow_dispatch'", condition)
        self.assertNotIn("github.event_name == 'push'", condition)
        self.assertNotIn("AUTO_DEPLOY_ENABLED", condition)
        self.assertIn("matrix:\n        service: [node, jvm]", WORKFLOW)

    def test_push_and_missing_gates_block_node_and_jvm(self):
        for service in ("node", "jvm"):
            self.assertFalse(allowed_by_workflow(
                "false", "true", "push", "deploy", "autorizar-producao-a6", service))
            for hold in (None, "", "true", "TRUE"):
                self.assertFalse(allowed_by_workflow(
                    hold, "true", "workflow_dispatch", "deploy", "autorizar-producao-a6", service))
            self.assertFalse(allowed_by_workflow(
                "false", None, "workflow_dispatch", "deploy", "autorizar-producao-a6", service))
            self.assertFalse(allowed_by_workflow(
                "false", "true", "workflow_dispatch", "deploy", "", service))
            self.assertFalse(allowed_by_workflow(
                "false", "true", "workflow_dispatch", "deploy", "autorizar-producao-a6", "none"))

    def test_explicit_manual_authorization_preserves_publish_and_rollback(self):
        self.assertTrue(allowed_by_workflow(
            "false", "true", "workflow_dispatch", "deploy", "autorizar-producao-a6", "node"))
        self.assertFalse(allowed_by_workflow(
            "false", "true", "workflow_dispatch", "deploy", "autorizar-producao-a6", "node",
            published=False))
        self.assertTrue(allowed_by_workflow(
            "false", "true", "workflow_dispatch", "rollback", "autorizar-producao-a6", "jvm",
            published=False))
        condition = odisseu_deploy_job()
        self.assertIn("needs.publish.result == 'success'", condition)
        self.assertIn("inputs.operation == 'rollback'", condition)
        self.assertEqual(condition.count("ODISSEU_PRODUCTION_DEPLOY_HOLD"), 1)

    def test_check_is_not_a_deploy_and_live_apply_stays_disabled(self):
        self.assertFalse(allowed_by_workflow(
            "false", "true", "workflow_dispatch", "check", "autorizar-producao-a6", "node"))
        deliver = (ROOT / "scripts/ci-a6-deliver.sh").read_text(encoding="utf-8")
        self.assertNotIn("ssh ", deliver)
        self.assertIn("apply na VM continua desligado", deliver)

    def test_no_fixed_operational_address_or_reusable_workflow(self):
        workflows = list((ROOT / ".github/workflows").glob("*.yml"))
        text = "\n".join(item.read_text(encoding="utf-8") for item in workflows)
        self.assertNotIn("workflow_call:", text)
        for ip in FORBIDDEN_IPS:
            self.assertNotIn(ip, text)
        self.assertNotIn("run: bash scripts/ci-ssh-deploy.sh", text.split("deploy-pokemon:")[0])
        self.assertEqual(text.count("run: bash scripts/ci-ssh-deploy.sh"), 1)
        self.assertEqual(text.count("bash scripts/resolve-gce-instance.sh"), 2)
        pokemon = re.search(r"^  deploy-pokemon:\n(.*?)(?=^  check-odisseu:)", text, re.M | re.S)
        check = re.search(r"^  check-odisseu:\n(.*)$", text, re.M | re.S)
        self.assertIsNotNone(pokemon)
        self.assertIsNotNone(check)
        self.assertNotIn("ODISSEU_PRODUCTION_DEPLOY_HOLD", pokemon.group(1))
        self.assertIn("DEPLOY_OPERATION: check", check.group(1))
        self.assertIn("github.event_name == 'workflow_dispatch'", pokemon.group(1))
        self.assertNotIn("SSH_HOST: ${{ vars.POKEMON_HOST ||", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
