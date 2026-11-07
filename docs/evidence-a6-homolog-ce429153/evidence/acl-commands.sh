# NÃO executar live nesta etapa. Somente na janela autorizada, após snapshot ACL.
# Paths reais observados (RO):
#   SESSION=/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core
#   DATA=/var/lib/docker/volumes/odisseu-a6-cutover-data/_data
#
# Pré-req: instalar pacote acl (binários getfacl/setfacl) se ausentes; libacl.so.1 já presente.
#   sudo apt-get install -y acl
#
# 1) Snapshot ACL atual (artefato protegido):
#   sudo getfacl -pR "$SESSION" > "/home/caiohclavico/odisseu-recovery/acl-snapshots/session-$(date -u +%Y%m%dT%H%M%SZ).acl"
#   sudo getfacl -pR "$DATA" > "/home/caiohclavico/odisseu-recovery/acl-snapshots/data-$(date -u +%Y%m%dT%H%M%SZ).acl"
#
# 2) Conceder UID 1000 sem chown:
#   sudo setfacl -m u:1000:rwx "$SESSION"
#   sudo setfacl -R -m u:1000:rwX "$SESSION"
#   sudo setfacl -d -m u:1000:rwx "$SESSION"
#   sudo setfacl -m u:1000:rwx "$DATA"
#   sudo setfacl -m u:1000:rw "$DATA/a6-maintenance.json"
#   # se existir:
#   # sudo setfacl -m u:1000:rw "$DATA/ai-content-history.json"
#   sudo setfacl -d -m u:1000:rwx "$DATA"
#
# 3) Reversão:
#   sudo setfacl --restore=session-SNAP.acl
#   sudo setfacl --restore=data-SNAP.acl
# Alternativa pontual: setfacl -R -x u:1000 + setfacl -k nos diretórios.
