"""Reserva conservadora de disco; não limpa imagens, containers ou volumes."""
from a6_production_deploy import DeployError

LOG_BYTES = 128 * 1024 * 1024
MIN_MARGIN = 1024 * 1024 * 1024


def budget(free_bytes, datomic_bytes, archive_bytes=0, import_bytes=0, rollback_bytes=0):
    values = (free_bytes, datomic_bytes, archive_bytes, import_bytes, rollback_bytes)
    if any(type(v) is not int or v < 0 for v in values):
        raise DeployError("capacity_measurement_invalid")
    base = archive_bytes + import_bytes + rollback_bytes + 4 * datomic_bytes + LOG_BYTES
    margin = max(MIN_MARGIN, (base + 4) // 5)
    required = base + margin
    result = {"freeBytes": free_bytes, "requiredBytes": required, "marginBytes": margin,
              "datomicCopiesBytes": 4 * datomic_bytes, "logsBytes": LOG_BYTES,
              "archiveBytes": archive_bytes, "importBytes": import_bytes,
              "missingRollbackBytes": rollback_bytes, "sufficient": free_bytes >= required}
    if not result["sufficient"]:
        raise DeployError("insufficient_operational_space")
    return result
