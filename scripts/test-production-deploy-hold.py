#!/usr/bin/env python3
"""Static fail-closed tests for the Odisseu production workflow hold."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github/workflows/deploy.yml").read_text()


def odisseu_deploy_job():
    match = re.search(r"^  deploy-odisseu:\n(.*?)(?=^  deploy-pokemon:)", WORKFLOW, re.M | re.S)
    if not match:
        raise AssertionError("deploy-odisseu job missing")
    condition = re.search(r"^    if: >-\n((?:^      .*\n)+)", match.group(1), re.M)
    if not condition:
        raise AssertionError("deploy-odisseu job condition missing")
    return "\n".join(line.strip() for line in condition.group(1).splitlines())


def allowed_by_workflow(hold, event, operation, auto_enabled=True, published=True, changed=True):
    # Model the job condition's relevant gates. GitHub variables are strings;
    # absent is empty and therefore blocks.
    if hold != "false" or not changed or operation == "check":
        return False
    if event == "push":
        return auto_enabled and published and operation is None
    if event == "workflow_dispatch":
        return operation == "rollback" or published
    return False


class ProductionDeployHoldTests(unittest.TestCase):
    def test_job_requires_explicit_false_before_event_branch(self):
        condition = odisseu_deploy_job()
        gate = "vars.ODISSEU_PRODUCTION_DEPLOY_HOLD == 'false'"
        self.assertIn(gate, condition)
        self.assertLess(condition.index(gate), condition.index("github.event_name == 'push'"))
        self.assertLess(condition.index(gate), condition.index("github.event_name == 'workflow_dispatch'"))

    def test_missing_or_true_hold_blocks_all_operation_kinds(self):
        for hold in (None, "", "true", "TRUE", "1"):
            for event, operation in (("push", None), ("workflow_dispatch", "deploy"),
                                     ("workflow_dispatch", "rollback")):
                with self.subTest(hold=hold, event=event, operation=operation):
                    self.assertFalse(allowed_by_workflow(hold, event, operation))

    def test_explicit_false_preserves_push_gate(self):
        self.assertTrue(allowed_by_workflow("false", "push", None))
        self.assertFalse(allowed_by_workflow("false", "push", None, auto_enabled=False))
        self.assertFalse(allowed_by_workflow("false", "push", None, published=False))
        condition = odisseu_deploy_job()
        self.assertIn("vars.AUTO_DEPLOY_ENABLED == 'true'", condition)
        self.assertIn("needs.publish.result == 'success'", condition)

    def test_manual_deploy_and_rollback_share_the_same_hold(self):
        self.assertTrue(allowed_by_workflow("false", "workflow_dispatch", "deploy"))
        self.assertTrue(allowed_by_workflow("false", "workflow_dispatch", "rollback", published=False))
        condition = odisseu_deploy_job()
        self.assertIn("inputs.operation == 'rollback'", condition)
        self.assertIn("github.event_name == 'workflow_dispatch'", condition)
        self.assertEqual(condition.count("ODISSEU_PRODUCTION_DEPLOY_HOLD"), 1)

    def test_rerun_of_guarded_revision_keeps_hold_and_check_is_not_deploy(self):
        # GitHub re-runs a job against the original workflow revision; on this
        # guarded revision the job condition still evaluates the live variable.
        self.assertFalse(allowed_by_workflow("true", "workflow_dispatch", "rollback"))
        self.assertFalse(allowed_by_workflow("", "workflow_dispatch", "deploy"))
        self.assertFalse(allowed_by_workflow("false", "workflow_dispatch", "check"))

    def test_no_reusable_workflow_or_other_odisseu_deploy_caller(self):
        workflows = list((ROOT / ".github/workflows").glob("*.yml"))
        text = "\n".join(item.read_text() for item in workflows)
        self.assertNotIn("workflow_call:", text)
        self.assertEqual(text.count("SSH_HOST: '35.238.24.225'"), 2)  # deploy + read-only check
        self.assertEqual(text.count("run: bash scripts/ci-ssh-deploy.sh"), 3)
        # The two deploy service jobs are separate; the divergent Odisseu target
        # is used only by deploy-odisseu and its read-only check.
        pokemon = re.search(r"^  deploy-pokemon:\n(.*?)(?=^  check-odisseu:)", text, re.M | re.S)
        check = re.search(r"^  check-odisseu:\n(.*)$", text, re.M | re.S)
        self.assertIsNotNone(pokemon)
        self.assertIsNotNone(check)
        self.assertNotIn("ODISSEU_PRODUCTION_DEPLOY_HOLD", pokemon.group(1))
        self.assertIn("DEPLOY_OPERATION: check", check.group(1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
