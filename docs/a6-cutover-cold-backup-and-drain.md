# A6 cold backup, isolated restore and maintenance drain

**State at 2026-11-09:** disposable physical rehearsal PASS; production cold backup NOT RUN; RC #66 cutover NO-GO. This branch now contains an Announcer suspension gate and backup runbook updates. No live service, volume, flag, schema or image was changed during this work.

## What the live A6 layout uses

The active Compose project is the cutover runtime under `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z`, not the older `odisseu-core-*` Compose project in this checkout.

| Volume | Consumer/writer | Purpose |
| --- | --- | --- |
| `odisseu-a6-cutover-datomic` | `odisseu-a6-jvm` | Datomic data |
| `odisseu-a6-cutover-journal` | `odisseu-a6-jvm` | Datomic journal |
| `odisseu-a6-cutover-maint` | `odisseu-a6-jvm` | JVM maintenance state |
| `odisseu-a6-cutover-data` | `odisseu-a6-node` | Durable Node maintenance marker, app history and idempotency data |

Node also mounts WhatsApp LocalAuth from `/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core` into `/app/.wwebjs_auth_a6_cutover`. It is not part of the four named-volume archives and must remain untouched. The live JVM also has a protected `/run/odisseu-control` bind mount. Datomic is written by the JVM; Node requests mutations through the bridge. The live inventory found no second active container mounting Datomic, no A6 cron/systemd writer, and no non-application process with an open path into the volume. This inventory does not protect against a privileged operator or a new external process started during the window; exclusive operator attestation and disabled deployment automation remain required.

Do not use the stale `docker-compose.odisseu-core.yml` or old `odisseu-core-*` volume scripts for this runtime.

## Implemented controls

`scripts/a6_cutover_backup.py` is a fail-closed host-side helper. It only accepts the exact four A6 volume names for live backup. It requires both expected containers to exist and be stopped with restart policy `no`; rejects any running/restart-armed container mounting the volumes, privileged or Docker-socket container, bind mount under a volume root, or host process whose cwd/fd/map enters a volume. It rechecks for writers before and after each volume archive. It refuses non-native AMD64 backup hosts.

The copy runs through the approved AMD64 JVM image as a read-only, network-isolated tar helper, preserving numeric ownership, mode, ACLs, xattrs, SELinux labels, sparse files and links. It inventories each source tree before and after copying and refuses publication if content or relevant metadata changes. A private, unique staging directory contains an `INCOMPLETE` marker until all archives and checksums verify. The manifest is written atomically and the completed directory is published by same-filesystem rename. Existing destinations are never replaced. Failed/interrupted stages are retained for explicit inspection; no old backup is automatically deleted.

The manifest records UTC time, source volume names, per-volume archive SHA-256 and logical tree fingerprint, host architecture, container state/image IDs and OCI labels, helper image identity, backup tool SHA-256, and the sanitized maintenance/GitHub/health context. The context validator rejects secret-like field names and requires exclusive operator attestation, hold true, CI `disabled_manually`, zero active/queued runs, healthy Node/JVM, WhatsApp READY, pre-maintenance gates open and drained Node/JVM gates quiescent with zero counts and durable Node state.

`restore-isolated` verifies the manifest and archive checksums, creates only fresh `odisseu-a6-restore-*` volumes and compares restored tree fingerprints with the manifest. It refuses existing restore volumes and never targets production names. On error, partial restore volumes are retained rather than silently erased. `apps/odisseu/src/odisseu/smoke/a6_restore_aggregate.clj` reads the isolated Datomic copy and reports aggregate schema/save/Pokémon/inventory/coin/ledger/receipt/gym/release statistics without entity identifiers or player values.

## Rehearsal and tests

The following disposable checks passed on native Linux AMD64:

- `python3 -m unittest scripts/test_a6_cutover_backup.py -v`: **9/9 PASS**, covering manifest/hash, corruption detection, no-overwrite, interrupted copy, required gate context, private destination, source mutation during copy, symlink metadata and unsafe archive member rejection.
- `python3 -m py_compile scripts/a6_cutover_backup.py scripts/test_a6_cutover_backup.py`: PASS.
- Existing JVM cold-backup smoke: **12 scenarios PASS**.
- JVM maintenance/quiescence smoke: PASS against disposable Datomic/HTTP, with no external transport.
- Node drain smoke: **4 scenarios PASS**, including closed admission, draining, persistent quiescent marker, fail-closed malformed marker, timeout and explicit resume.
- `scripts/a6-cutover-disposable-jvm-rehearsal.sh r3-20261109`: **PASS**. It created new rehearsal volumes, started the candidate JVM with `--network none`, entered maintenance, checked the shutdown hook, made and verified a four-volume cold backup, restored to new isolated volumes, restarted only the isolated JVM in quiescent state and performed aggregate read-only Datomic inspection. The fixture was intentionally empty for player data; this verifies the procedure and physical archive/restore path, not semantic correctness of live player records.

No production volume was mounted by the rehearsal. No WhatsApp session, live Datomic, save, migration, or gameplay state was read or changed by the disposable run.

## Controlled window procedure

This sequence is approved for a future cold backup only after the independent gates below are green. It does **not** authorize or perform migration 036, image update, cutover or announcement.

### Persistent Release Announcer suspension

The RC adds a persistent control file at `/app/data/release-announcer-control.json`, on the existing Node data volume. The Announcer requires a valid versioned state file; missing, malformed, unsafe-permission or unreadable state is **closed**. The initial state is created explicitly as `open`, and maintenance changes it atomically to `suspended`. A cross-process `publication-inflight` lock serializes publication admission with suspension. Suspension writes the durable closed state first and waits for an already admitted publication to finish; timeout leaves the state suspended and must abort the maintenance window. The gate is checked at WhatsApp READY, inside the serialized runtime, and again immediately before the transport's send-start/send boundary. A process restart cannot clear the persisted state.

Commands run inside the candidate Node container as its application UID, against the persistent data mount; they emit only state and reason codes:

```sh
node /app/scripts/lib/release-announcer-control.cjs status /app/data/release-announcer-control.json
node /app/scripts/lib/release-announcer-control.cjs suspend /app/data/release-announcer-control.json cold-backup 60000
node /app/scripts/lib/release-announcer-control.cjs status /app/data/release-announcer-control.json
```

Before the first start of a candidate that contains this gate, initialize the file explicitly as **suspended** while mounting only the Node data volume into a network-isolated one-off container, using the same image digest and UID 1000. This creates the fail-closed state atomically without any temporary open window. During maintenance, require status `suspended` and `publicationInFlight=false`; if initialization/status fails, the command times out, the lock is malformed/stale, or state cannot be read, abort before starting the candidate or stopping JVM. Keep the file suspended through Node stop/start, Chromium reconnect and WhatsApp READY. The READY callback returns before reading the intent, checking receipts, reconciling buffered ACKs or invoking the transport while suspended.

```sh
node /app/scripts/lib/release-announcer-control.cjs initialize-suspended /app/data/release-announcer-control.json cold-backup
node /app/scripts/lib/release-announcer-control.cjs status /app/data/release-announcer-control.json
```

The `transport_uncertain` receipt is **not** a retry authorization. The candidate performs a read-only canonical receipt check after ACK-buffer reconciliation and returns `uncertain-manual-review`; the JVM store also does not claim an already send-started uncertain record. No receipt, intent or idempotency key is edited by suspend/resume. Only real ACK correlation can complete an uncertain row; manual confirmation remains a separate audited operation. A stale publication lock after an unexpected process death is not auto-deleted: leave suspension in place, inspect the exact process and canonical Datomic receipt, then perform an explicitly reviewed lock recovery. Do not force resume or retry to clear it.

After restoration of the original services, verify Announcer state is still suspended, `publicationInFlight=false`, Node/JVM health and WhatsApp READY. Read the canonical receipt for any previously uncertain key; preserve its historical state. Resume only after the maintenance window is complete and the operator intends normal Announcer operation:

```sh
node /app/scripts/lib/release-announcer-control.cjs resume /app/data/release-announcer-control.json maintenance-complete
node /app/scripts/lib/release-announcer-control.cjs status /app/data/release-announcer-control.json
```

Resume refuses an active publication lock. The open state restores the prior `RELEASE_ANNOUNCER_ENABLED` behavior; it does not alter the enable flag, intent, allowlist or receipts. If the old intent resolves to an uncertain receipt, it remains blocked from automatic replay after resume. If the receipt is pending or otherwise eligible, it may publish on a later WhatsApp READY event, so an operator who does not intend a release must not resume until the canonical read-only eligibility check is complete.

1. Confirm VM identity/AMD64, actual active Compose project, live image IDs/labels, volume mounts, disk/inodes and backup destination. Confirm current Node/JVM health, WhatsApp READY and the actual maintenance status from both services. Confirm deploy hold true, CI disabled, no active/queued runs, and obtain a fresh exclusive-operator attestation. Do not disclose env values or tokens.
2. Record the exact current image IDs, restart policies, service configuration, feature flags, and WhatsApp readiness. Verify a tested `docker start` path for the exact stopped containers and the tested maintenance recovery procedure. Do not remove/recreate containers.
3. Close Node admission with the existing authenticated maintenance operation. Confirm its durable marker and drain result (`quiescent`, zero admitted operations); do not infer quiescence from health or a one-time count alone. Then enter JVM maintenance through the protected break-glass control, confirm quiescent and zero in-flight bridge operations, and verify schedulers/writers are contained. Any timeout or ambiguous operation aborts before stopping either service.
4. Stop Node and JVM in the tested order using bounded graceful stops, preserving their stopped containers and restart policy `no`. Confirm shutdown hooks, exited state, and no unexpected restart. The backup helper independently checks that no container/process can write or hold open paths beneath any source volume.
5. Check protected destination ownership/mode, same-filesystem staging, available bytes/inodes, retention capacity and reserve. Require enough space for the measured archive + staging/restore peak plus at least 5 GiB free after completion; do not infer peak from unique Docker layer size. Use a unique destination only.
6. Create a sanitized context JSON with the schema expected by `validate_context` (`exclusive_operator_confirmed`, `github`, `preflight`, `drain`, `flags`; no secret-named fields or values). The helper's live invocation is `sudo python3 scripts/a6_cutover_backup.py backup <new-destination> --context-json <protected-context.json> --helper-image sha256:c60eeb31f0e66948f92e8ff5e03b04c055c381b3cf10c59ed5698f175ef59c20`. The helper itself performs the stopped-writer, source-inventory, metadata and space checks. Do not copy live volume files with a generic `tar` command.
7. Run `verify <backup-dir>`, then `restore-isolated <backup-dir> --volume-prefix odisseu-a6-restore-<unique-id> --helper-image <approved-amd64-helper>`. Start only an isolated JVM attached to the restored volumes, with `--network none`, unique names, no WhatsApp, production credentials absent, and automatic jobs disabled. Confirm JVM healthy, maintenance remains quiescent, databases/schema are readable, and run the aggregate-only restore inspection. Do not transact, migrate or run recovery/backfill on the copy.
8. If backup and isolated audit pass, restart the original containers with their exact prior config and volumes, JVM first and Node second. Keep admission closed until JVM/bridge health, Datomic availability, Node health, WhatsApp READY and maintenance markers are verified. Reopen using the existing explicit resume operations only after both services agree. Compare flags and image IDs to the pre-window record. Do not enable CI/CD or remove the deploy hold.

### Abort and recovery

- Before stopping services: abort and leave production untouched on any failed gate.
- Drain timeout, pending/ambiguous operation, failed shutdown hook, unexpected process/container, disk reserve shortfall, source fingerprint change, failed checksum or incomplete restore: do not publish or trust the backup. Retain incomplete stage/restore artifacts for operator review; never overwrite an older backup.
- If failure occurs after stop: do not restore an older snapshot over the live volumes. Start the original stopped containers on their original volumes only if their state is intact and restart is compatible; keep mutation admission closed until health, bridge, Datomic and WhatsApp readiness are verified. If any state is ambiguous, leave the services stopped and escalate with the exact stage and evidence.
- A successful backup does not itself authorize schema 036, image deployment, rollback to a different application, or announcement.

## Production result and remaining gate

Read-only production check on 2026-11-09 confirmed host `odisseu-core` (x86_64), active Node image `sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7` and JVM image `sha256:5cfca31b18c4cbf652280a01df39deaa2744cf0a6c93cff2cdb292d24aaa94f2`; both containers were running/healthy with restart count 0. The passive bounded Node healthcheck returned `status=ok`, `whatsapp=READY`, `chromium=true`. Earlier read-only inventory confirmed the four mounts and approximately 35.3 MB Datomic, empty journal/maintenance directories, and about 20 KB durable Node data. GitHub was previously verified as hold `true`, CI/CD `disabled_manually`, with zero active and queued runs; this must be repeated immediately before any future window.

### Code and verification in `fix/a6-announcer-maintenance-suspension`

The active Node's three Announcer modules were hashed read-only and exactly matched the RC base commit `fdf6c5932e2cd30fbef9682a589888ace712c615`; the new `release-announcer-control.cjs` was absent from the live image. This establishes source compatibility for this patch, not production availability. Live Node reported `v22.23.4`; the local isolated test runtime was Node `v22.23.3`.

The branch adds a persistent, atomic, permission-checked state file on `/app/data`, a cross-process publication lock, explicit status/suspend/resume commands, a READY early gate, runtime serialization, and a final control check at the WhatsApp send boundary. Missing/corrupt/unreadable state, a held lock or timeout never opens the gate. A stale lock is not auto-cleared. Uncertain receipts are read-only checked after buffered ACK reconciliation and never treated as permission to retry. No intent or receipt mutation was added to maintenance operations.

Branch verification (same runtime/tool versions for RC base and candidate: Node `v22.23.3`, npm `10.9.9`):

- Announcer/control/bootstrap/transport tests are now part of `npm test` through `npm run test:release-announcer`: **30/30 PASS**, all transports synthetic; no WhatsApp calls. Added explicit coverage for first initialization suspended, an orphan publication lock and a suspension racing a claimed send. The orphan is preserved, blocks publication and prevents resume; no auto-cleanup exists.
- Cold-backup helper tests: **9/9 PASS**; Python syntax check PASS.
- `npm run build`: PASS; existing ClojureScript inference/deprecation warnings remain.
- `npm test` on both RC base and candidate reproduced the same **236 tests / 1,407 assertions: 1 failure and 3 errors**, all in unchanged `pokemon_http_test.cljs` cases (mission text response type and Professor “Cartões” text). The candidate additionally runs and passes the 30 release tests before the ClojureScript suite. No Pokémon source/test was changed by this branch.
- `npm run test:http` on both RC base and candidate reproduced **284/285 PASS**. The sole failure is `pokemon-service/test/playable-save-activation.test.cjs` with `Missing expected rejection` at the same assertion (line 277; test begins at line 237). It is unchanged by this PR.
- `git diff --check`: PASS.

Full suite failures remain visible and were not edited or suppressed. Required GitHub checks/review must be inspected on the PR; this report does not claim a green merge gate.

**No live cold backup was created.** The live Node's Announcer configuration was observed enabled. The RC control described above is a code change and is not present in the active production image. No attempt was made to inspect or alter the real announcement intent/receipt, disable the feature, or send a message. Production containers and volumes were left running and unchanged.

**Verdict: NO-GO for a live cold backup until the suspension implementation is reviewed/merged, deployed through its own authorized Node-only operation, and verified on the active image.** Passing disposable tests is not proof of production protection. Then repeat the live preflight/hold checks, initialize and verify the durable control state, and perform only the cold backup procedure. RC #66 remains uncut over; schema 036 and candidate images remain unapplied.
