# Odisseu A6 — contenção do workflow de produção

Data da auditoria: 2026-11-05 UTC
Status: **BLOCKED para considerar produção protegida**; patch local testado e PR em preparação.

## Base e escopo

O patch foi reaplicado no clone independente `/private/tmp/a6-production-workflow-hold`, branch `codex/odisseu-production-deploy-hold-master`, sobre o `master` confirmado em `9a89e738dc4a5c4742e3c256140ec8b5721ffe7a`. O workspace original não foi alterado. O escopo contém somente o workflow CI/CD, seu teste estático e este relatório. Não há instrumentação Node, Release Announcer ou mudanças em saves.

## Workflow ativo e caminhos de execução

- `master` aponta para `9a89e738dc4a5c4742e3c256140ec8b5721ffe7a`. O workflow ativo é ID `331282459`, `.github/workflows/deploy.yml`, estado `active`; o blob ativo (`229ef5d67a9cc4cd5da6ea0e4c1b7fee0170a6bc`) ainda não contém o hold.
- `deploy-odisseu` usa o destino configurado no workflow (`35.238.24.225`, usuário `zapbot-deploy`) e o environment `production`. Push para `master` também depende de `AUTO_DEPLOY_ENABLED == 'true'`; `workflow_dispatch` admite deploy e rollback sem essa variável. Ambos passam por `scripts/ci-ssh-deploy.sh`.
- `check-odisseu` usa o mesmo destino/credenciais declarados, mas fixa `DEPLOY_OPERATION=check`; é a operação de inspeção prevista no workflow. `deploy-pokemon` é um destino separado e permanece byte-a-byte inalterado pelo patch.
- A auditoria dos arquivos de workflow encontrou apenas `deploy.yml` e `pokemon-storage-ci.yml`; não encontrou `workflow_call` nem outro caller de deploy Odisseu. `pokemon-storage-ci.yml` não usa credenciais de produção.

## Runs e controles administrativos observados

- A consulta read-only da API listou 256 runs do workflow; todos estavam `completed` no momento da consulta. Não foi observado run `queued`, `waiting` ou `in_progress`.
- Nos 30 dias anteriores à consulta, foram observados 54 eventos push/`workflow_dispatch` (51 push, 3 manuais). O último push para master observado foi em 2026-10-15 06:08 UTC.
- Runs antigos ainda podem ser reexecutáveis dentro da janela de 30 dias. O GitHub documenta que reruns reutilizam `GITHUB_SHA` e `GITHUB_REF` do evento original e dependem de permissão de escrita; uma revisão antiga sem o hold não recebe a nova condição. A documentação também descreve que desabilitar o workflow impede reruns enquanto desabilitado: [Reexecutar workflows e jobs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
- O environment `production` expôs somente proteção de branch (`master`), sem reviewer obrigatório nem timer de espera. Isso não foi modificado.
- A identidade GitHub conectada reportou acesso administrativo ao repositório. A integração não permitiu ler valores das repository variables (endpoint retornou 401), e não consultamos secrets. Portanto `ODISSEU_PRODUCTION_DEPLOY_HOLD` e `AUTO_DEPLOY_ENABLED` têm valor **desconhecido**; nenhuma credencial foi exibida.
- Runs estavam concluídos na consulta, mas a janela de rerun, a ausência de aprovação obrigatória e o destino divergente continuam riscos. A consulta é um retrato temporal e deve ser repetida antes de qualquer mudança administrativa.

## Patch local proposto

No job `deploy-odisseu`, antes das condições de push/manual, a expressão exige:

```yaml
vars.ODISSEU_PRODUCTION_DEPLOY_HOLD == 'false'
```

Ausente, vazio, `true` ou qualquer valor diferente de `false` bloqueia o job. O gate cobre deploy e rollback de execuções na revisão protegida. A rota push conserva os gates existentes de `AUTO_DEPLOY_ENABLED` e publicação. O job Pokémon, host, usuário, chaves, secrets e destino não mudam. O job de teste de deploy passa a executar `scripts/test-production-deploy-hold.py`.

O teste estático cobre hold ausente/true/valores não falsos, `false`, push, dispatch de deploy, rollback, check e inventário dos workflows/callers. Resultado local: **6/6 PASS**. Parse YAML e `git diff --check`: **PASS**. Estes testes não executam Actions e não comprovam valores configurados no GitHub.

## Contenção administrativa necessária

O novo `if` não protege reexecuções que usem uma revisão histórica sem o gate. Antes de publicar/aplicar o patch, a sequência a executar por administrador, em autorização separada, é:

1. Desabilitar temporariamente o workflow `CI/CD`.
2. Consultar novamente todos os runs e cancelar execuções pendentes/em andamento; confirmar que não há outra credencial/caminho SSH de deploy fora dos workflows auditados.
3. Publicar e revisar o PR deste patch, sem merge automático.
4. Configurar a repository variable `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` antes de reabilitar o workflow.
5. Reabilitar e confirmar no workflow ativo a condição fail-closed e o valor `true`; verificar que não restaram execuções antigas reexecutáveis que possam contornar a revisão protegida.
6. Manter hold `true` até reconciliar formalmente o destino SSH e reforçar a aprovação de produção. Só alterar para `false` após autorização operacional específica.

Nenhuma dessas ações administrativas foi executada. Também não foi possível comprovar por integração o valor atual da variável, a configuração de secrets ou a inexistência de credenciais fora do repositório.

## Revisão e reversão

Arquivos propostos: `.github/workflows/deploy.yml`, `scripts/test-production-deploy-hold.py` e `docs/odisseu-a6-maintenance-quiescence.md`. Diff revisado contra `master` atual; nenhum destino ou credencial foi alterado. A reversão futura deve ser feita por novo commit aprovado que remova o gate/teste, não por edição manual do workflow ativo. Não houve deploy, alteração de variable/secret/environment, cancelamento de run ou mudança de produção.

PR: [#35](https://github.com/caioclavico/zapbot/pull/35), aberto em rascunho contra `master`, sem merge. A branch remota iniciou com commit `81610128ecb26120385c0d3336a11d9c947fcbbe`, pai `9a89e738dc4a5c4742e3c256140ec8b5721ffe7a` e árvore `0c50540278bff2e5e40ec27f579626c2ac6e4ab9`, idêntica à árvore do commit local `eaced249f0a096d1205e9e7d17c1ae2057d0744a`. O SHA difere por metadados de autoria/commit entre a criação local e a criação via integração GitHub; o conteúdo versionado é o mesmo. Merge permanece sujeito a revisão humana.
Decisão: **BLOCKED** para declarar produção protegida. A revisão ativa não contém o hold, o valor da variável é desconhecido, o workflow continua ativo e os reruns históricos precisam de contenção administrativa independente.
