#!/usr/bin/env python3
"""Helper root real + auditorias Docker reais; VM/WhatsApp simulados.

Todos os arquivos vêm de Datomic criado pelo teste, sob /root. O ensaio
não recebe tokens, sessão WhatsApp, socket remoto ou volumes de produção.
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import a6_post_v3_backup as post
import a6_production_backup as production
import a6_root_backup as admin
from a6_mount_policy import Collection
from a6_production_contract import VM, CURRENT
from a6_window_journal import load

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("post_v3_runner_tests", ROOT / "scripts/test-a6-post-v3-runner.py")
unit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(unit)


@unittest.skipUnless(os.geteuid() == 0 and os.environ.get("A6_POST_V3_TEST_IMAGE"),
                     "ensaio root/Docker no runner isolado de build")
class AuthenticatedRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="post-v3-lab-", dir="/root")
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        self.image = os.environ["A6_POST_V3_TEST_IMAGE"]
        self.rollback = os.environ["A6_POST_V3_ROLLBACK_IMAGE"]
        self.sha = os.environ["A6_POST_V3_TEST_SHA"]
        self.rollback_sha = os.environ["A6_POST_V3_ROLLBACK_SHA"]
        self.original_ci = {k: os.environ.pop(k, None) for k in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME")}
        self.code = self.root / "code"
        self.code.mkdir(mode=0o755)
        shutil.copyfile(ROOT / "scripts/a6_post_v3_restore_audit.clj", self.code / post.CODE_FILES[0])
        shutil.copyfile(ROOT / "modules/pokemon/infrastructure/pokemon/infrastructure/datomic/xp_reconciliation.clj",
                        self.code / post.CODE_FILES[1])
        for item in self.code.iterdir():
            item.chmod(0o644)
        seeded = self.root / "seed"
        seeded.mkdir(mode=0o700)
        program = """
          (require 'pokemon.tests.xp-reconciliation-cas-a6-test
                   'pokemon.infrastructure.datomic.xp-reconciliation
                   'pokemon.infrastructure.http.json 'datomic.client.api
                   'pokemon.domain.growth)
          (let [c (#'pokemon.tests.xp-reconciliation-cas-a6-test/seeded)
                id "abb8955f-7ebe-4084-a634-fd74cfa9bf62"
                p (pokemon.infrastructure.datomic.xp-reconciliation/preview c id id)
                save (ffirst (datomic.client.api/q '[:find ?e :where [?e :save/key]] (datomic.client.api/db c)))]
            (pokemon.infrastructure.datomic.xp-reconciliation/reconcile! c id id (:snapshot-sha256 p))
            (datomic.client.api/transact c
              {:tx-data [[:db/add [:pokemon/id-pokemon id] :pokemon/level 7]
                         [:db/add [:pokemon/id-pokemon id] :pokemon/xp 3]
                         [:db/add [:pokemon/id-pokemon id] :pokemon/xp-desde-nivel 3]
                         [:db/add [:pokemon/id-pokemon id] :pokemon/xp-total
                           (pokemon.domain.growth/progressive-synthetic-total 7 3)]
                         [:db/add [:pokemon/id-pokemon id] :pokemon/xp-progress-version "progressive-xp-v3"]
                         [:db/add save :save/coins 42]
                         {:egg/id "fixture-egg" :egg/incubator-id "fixture-incubator"}
                         {:inventory-item/key "fixture-item" :inventory-item/save save
                          :inventory-item/item-id "pokeball" :inventory-item/qty 3}]})
            (println "AUDIT_JSON"
              (pokemon.infrastructure.http.json/write-str
                (pokemon.infrastructure.datomic.xp-reconciliation/audit-post-v3 c id id)))
            (shutdown-agents))
        """
        result = subprocess.run(["docker", "run", "--rm", "--pull=never", "--network=none",
            "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "-v", str(seeded) + ":/seed", "-e", "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/seed",
            "--entrypoint=clojure", self.image, "-M", "-e", program],
            check=True, text=True, capture_output=True, timeout=180)
        self.audit = json.loads(next(line.removeprefix("AUDIT_JSON ") for line in result.stdout.splitlines()
                                    if line.startswith("AUDIT_JSON ")))
        self.assertEqual(self.audit["status"], "PASS")
        systems = [p for p in seeded.iterdir() if p.name.startswith("a6-reconcile-") and p.is_dir()]
        self.assertEqual(len(systems), 1)
        self.source = self.root / "source"
        shutil.copytree(systems[0], self.source)
        self.backups = self.root / "backups"
        self.backups.mkdir(mode=0o700)
        self.plans = self.root / "plans"
        self.plans.mkdir(mode=0o700)
        self.plan = dict(unit.PLAN, imageSha=self.sha, jvmDigest=self.image,
                         rollbackSha=self.rollback_sha, rollbackJvmDigest=self.rollback,
                         system=systems[0].name, database="a6-recon")
        # Node é simulado; a validação independente de revisão/ID usa imagens
        # imutáveis reais de JVM para ambos os slots no ensaio administrativo.
        self.plan["nodeDigest"] = self.image
        self.plan["rollbackNodeDigest"] = self.rollback
        self.plan["auditCodeSha256"] = post.digest({
            p.name: __import__("hashlib").sha256(p.read_bytes()).hexdigest() for p in self.code.iterdir()})
        self.plan_file = self.plans / "post-v3-test.json"
        self.plan_file.write_bytes(post.canonical(self.plan))
        self.plan_file.chmod(0o600)
        self.world = unit.World()
        self.world.audit = copy.deepcopy(self.audit)
        self.world.revision = self.sha
        self.world.jvm_id = self.image
        self.world.node_id = self.image
        self.world.containers["odisseu-a6-jvm"]["image"] = self.rollback
        self.world.containers["odisseu-a6-node"]["image"] = self.rollback
        self.original_tags = self.world._tags
        self.world._tags = lambda: {
            "odisseu-jvm:a6-%s-amd64" % self.sha[:12]: self.image,
            "odisseu-node:a6-%s" % self.sha[:12]: self.image}
        self.patches = [
            patch.object(post, "PLAN_ROOT", self.plans), patch.object(post, "CODE_ROOT", self.code),
            patch.object(admin, "BACKUP_ROOT", self.backups),
            patch.object(admin, "DATOMIC_SOURCE", str(self.source)),
            patch.object(production, "DATOMIC_SOURCE", str(self.source)),
            patch.object(admin, "_collect", self.collect),
            patch.object(admin, "_run", self.administrative_observation)]
        for p in self.patches:
            p.start()
        self.helper_calls = []

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.temp.cleanup()
        for k, v in self.original_ci.items():
            if v is not None:
                os.environ[k] = v

    def collect(self):
        docs = self.world.documents()
        for doc in docs:
            if doc["Name"] == "/odisseu-a6-jvm":
                doc["CapEff"] = "0"
        collection = Collection("docker-inspect-subprocess", tuple(docs),
            self.world.processes, self.world.jvm_phase, self.world.node_phase,
            self.world.writes, self.world.ops, admin.DATOMIC_VOLUME, "odisseu-a6-jvm")
        return collection, dict(VM)

    def administrative_observation(self, args):
        result = self.world.runner(args)
        if args[:2] == ["docker", "exec"] and "curl -fsS" in args[-1]:
            result.stdout = result.stdout.rsplit("\n", 1)[0]
        return result

    def helper(self, destination, action):
        self.helper_calls.append(action)
        if action == "preflight":
            return post.preflight("post-v3-test")
        return getattr(post, action.replace("-", "_"))(admin, "post-v3-test")

    def backup(self):
        self.world.node_phase = self.world.jvm_phase = "quiescent"
        self.world.containers["odisseu-a6-jvm"]["running"] = False
        return post.backup(admin, "post-v3-test")

    def test_authenticated_t0_to_official_cutover_and_resume(self):
        from a6_operational_window import execute_window, MemoryHold, CONFIRM
        env = dict(unit.lab.ENV, A6_V3_PROTOCOL="POST_V3_UPGRADE",
                   A6_LIVE_BACKUP="autorizar-backup-producao-a6")
        with patch("a6_post_v3_upgrade.post_v3", self.helper):
            report = execute_window(self.world.runner, MemoryHold(), lambda _: CONFIRM,
                self.sha, self.image, self.image, env,
                "/var/backups/odisseu-a6/post-v3-test/data", None,
                window_id="post-v3-test", state=str(self.root / "journal"),
                materialize=unit.lab.materialize)
        self.assertTrue(report["deployComplete"], report["blocked"])
        evidence = load(self.root / "journal")["postV3"]["proof"]
        self.assertEqual(evidence["audit"], self.audit)
        self.assertTrue(evidence["rollbackV3Aware"])
        self.assertEqual(evidence, post.status(admin, "post-v3-test"))
        self.assertFalse(report["datomicRestored"])
        print(json.dumps({"test": "authenticated-post-v3-window", "result": "PASS",
            "imageSha": self.sha, "jvmImageId": self.image, "rollbackImageId": self.rollback,
            "manifestSha256": evidence["manifestSha256"], "proofSha256": evidence["proofSha256"],
            "audit": self.audit}, sort_keys=True))

    def test_proof_tampering_stale_window_and_source_drift_fail_closed(self):
        self.backup()
        proof = post.audit(admin, "post-v3-test")
        target = self.backups / "post-v3-test/post-v3-proof.json"
        original = target.read_bytes()
        for key, value in (("windowId", "other"), ("manifestSha256", "0" * 64),
                           ("imageSha", "0" * 40), ("restoreManifestSha256", "0" * 64)):
            body = {k: v for k, v in proof.items() if k != "proofSha256"}
            body[key] = value
            target.write_bytes(post.canonical({**body, "proofSha256": post.digest(body)}))
            with self.assertRaises(post.ProductionBackupError):
                post.status(admin, "post-v3-test")
            target.write_bytes(original)
        restored = self.backups / "post-v3-test/restore-isolated"
        first = next(p for p in restored.rglob("*") if p.is_file())
        before = first.read_bytes()
        first.write_bytes(before + b"tampered")
        with self.assertRaises(post.ProductionBackupError):
            post.status(admin, "post-v3-test")
        first.write_bytes(before)
        (self.source / "extra").write_bytes(b"legitimate-progress")
        with self.assertRaisesRegex(post.ProductionBackupError, "source_drift"):
            self.backup()
        self.assertEqual(proof, post.status(admin, "post-v3-test"))

    def test_incomplete_restore_and_interrupted_audit_never_count_as_evidence(self):
        self.backup()
        restored = self.backups / "post-v3-test/restore-isolated"
        first = next(p for p in restored.rglob("*") if p.is_file())
        first.unlink()
        with self.assertRaises(post.ProductionBackupError):
            post.audit(admin, "post-v3-test")
        self.assertFalse((restored.parent / "post-v3-proof.json").exists())

    def test_operator_record_and_image_code_cannot_forge_authority(self):
        self.plan_file.chmod(0o666)
        with self.assertRaisesRegex(post.ProductionBackupError, "permissions"):
            post.preflight("post-v3-test")
        self.plan_file.chmod(0o600)
        code = self.code / "xp_reconciliation.clj"
        code.write_text(code.read_text() + "\n; adulterado\n")
        with self.assertRaisesRegex(post.ProductionBackupError, "code_mismatch"):
            post.preflight("post-v3-test")


if __name__ == "__main__":
    unittest.main(verbosity=2)
