"""Política de escritores Datomic.

A presença de `transact` no código não prova um writer ativo. A quiescência
só fica comprovada quando o armazenamento gravável não é visível para um
processo iniciado depois e esse processo não consegue remontá-lo.
"""

from __future__ import annotations


def _row(path, kind, transact, entry, lock, while_jvm, note):
    return {
        "path": path,
        "kind": kind,
        "transact": transact,
        "entry": entry,
        "credentials": "ODISSEU_BRIDGE_STORAGE / volume montado no processo",
        "maintenanceProtocol": False,
        "canRunWhileJvmActive": while_jvm,
        "usesWriterLock": lock,
        "note": note,
    }


INVENTORY = (
    _row("apps/odisseu/src/odisseu/smoke/a6_cold_backup.clj", "manutencao", True,
         "-main", True, True, "Smoke isolado de cópia fria. O lock é do próprio processo, não da bridge."),
    _row("apps/odisseu/src/odisseu/smoke/a6_drain_cold_rehearsal.clj", "manutencao", True,
         "-main", False, True, "Ensaio isolado de drain e cópia. Não passa pela bridge."),
    _row("apps/odisseu/src/odisseu/smoke/a6_species_hp_schema035.clj", "migracao", True,
         "-main", False, True, "Aplica schema 035 só em árvore descartável."),
    _row("apps/odisseu/src/odisseu/smoke/bag_flags_apply.clj", "manutencao", True,
         "-main", False, True, "Aplica manifesto de flags no storage indicado pelo ambiente."),
    _row("apps/odisseu/src/odisseu/smoke/datomic_local.clj", "legado", True,
         "-main", False, True, "Smoke M1 de desenvolvimento, storage local."),
    _row("apps/odisseu/src/odisseu/smoke/maintenance_quiescence.clj", "manutencao", True,
         "-main", False, True, "Smoke HTTP do gate em Datomic temporário."),
    _row("modules/pokemon/application/pokemon/migration/import.clj", "migracao", True,
         "biblioteca", False, True, "Importa IR para um storage que o chamador escolher."),
    _row("scripts/a6-gym-hp-repair-rehearsal.clj", "recuperacao", True,
         "-main", False, True, "Reparo de HP. O comentário admite volume montado no host."),
    _row("scripts/a64_apply_028_schema_only.clj", "migracao", True,
         "-main", False, True, "Schema aditivo 028 no storage do ambiente."),
    _row("scripts/a6_032_thematic_rehearsal.clj", "migracao", True,
         "-main", False, True, "Ensaio da migration 032 em cópia isolada."),
    _row("scripts/a6_033_trade_public_rehearsal.clj", "migracao", True,
         "funções", False, True, "Ensaio da migration 033 em cópia isolada."),
    _row("scripts/a6_fase7_schema_rehearsal.clj", "migracao", True,
         "-main", False, True, "Schema 029 e 030 em cópia isolada."),
    _row("scripts/a6_flat9_ambiguous_audit.clj", "leitura", False,
         "script", False, True, "A palavra transact aparece só na proibição. Não grava."),
    _row("scripts/a6_flat9_ambiguous_reconcile_apply.clj", "recuperacao", True,
         "script", False, True, "Aplica manifesto em Datomic isolado."),
    _row("scripts/a6_flat9_conversion_rehearsal.clj", "leitura", False,
         "script", False, True, "Simula a conversão. O texto diz que não há transact."),
    _row("apps/odisseu/src/odisseu/cutover/rehearsal.clj", "migracao", False,
         "biblioteca", False, True, "Ensaio offline. Abre cliente sem transact neste arquivo."),
    _row("apps/odisseu/src/odisseu/smoke/a6_species_hp_ro_audit.clj", "leitura", False,
         "-main", False, True, "Agregado de HP sem escrita."),
    _row("apps/odisseu/src/odisseu/smoke/a6_species_hp_ro_export.clj", "leitura", False,
         "-main", False, True, "Exporta fixtures sem escrita."),
    _row("modules/pokemon/application/pokemon/migration/receipt_a433.clj", "leitura", False,
         "-main", False, True, "Auditoria da migration 019."),
    _row("scripts/a64_force_expire_open.clj", "recuperacao", False,
         "-main", False, True, "Lista batalhas abertas. A escrita é opcional e não está neste arquivo."),
    _row("scripts/a64_idle_026_probe.clj", "leitura", False,
         "-main", False, True, "Probe e ensaio em storage descartável."),
    _row("scripts/a6_fase1281_pilot_festival_rehearsal.clj", "legado", False,
         "-main", False, True, "Ensaio de flag simulada, sem volume live."),
    _row("scripts/a6_fase7_integrity_manifest.clj", "leitura", False,
         "-main", False, True, "Contagens e hashes, sem escrita."),
    _row("scripts/a6_legacy_datomic_ro.clj", "leitura", False,
         "-main", False, True, "Inventário do volume legado em cópia local."),
)


def prove_quiescence(collection, state=None):
    """Só uma Collection lida por collect_from_runner pode ser verdadeira.
    Mapa, flag mountsObserved e quiescenceProven declarados não provam nada.
    Produção nunca é autorizada por este resultado."""
    from a6_mount_policy import LIVE_VOLUME, Collection, assess

    state = state or {}
    if state.get("environment") == "production":
        return {"quiescenceProven": False, "reason": "production_not_authorized", "phase": "draining"}
    if isinstance(collection, dict) or not isinstance(collection, Collection):
        return {"quiescenceProven": False, "reason": "declared_evidence", "phase": "draining"}
    if collection.source != "docker-inspect-subprocess":
        return {"quiescenceProven": False, "reason": "declared_evidence", "phase": "draining"}
    if collection.volume == LIVE_VOLUME or "cutover" in collection.volume:
        return {"quiescenceProven": False, "reason": "live_volume_not_authorized", "phase": "draining"}
    if collection.active_node_ops != 0 or collection.active_jvm_writes != 0:
        return {"quiescenceProven": False, "reason": "writes_in_flight", "phase": "draining"}
    if collection.node_phase not in ("draining", "quiescent", "frozen"):
        return {"quiescenceProven": False, "reason": "node_not_draining", "phase": "draining"}
    if collection.jvm_phase not in ("quiescent", "frozen"):
        return {"quiescenceProven": False, "reason": "jvm_not_quiescent", "phase": "draining"}
    for row in INVENTORY:
        if row["transact"] and row["path"] in collection.processes:
            return {"quiescenceProven": False, "reason": "external_datomic_client", "phase": "draining"}
    result = assess(collection.documents, collection.volume, collection.authorized)
    if not result["exclusive"]:
        return {"quiescenceProven": False, "reason": result["reason"], "phase": "draining"}
    return {"quiescenceProven": True, "reason": None, "phase": "quiescent"}


def inventory_report():
    transact = [row for row in INVENTORY if row["transact"]]
    return {
        "clients": len(INVENTORY),
        "transactPaths": len(transact),
        "quiescenceProven": False,
        "rows": list(INVENTORY),
    }
