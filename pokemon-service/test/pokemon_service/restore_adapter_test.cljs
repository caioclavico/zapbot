(ns pokemon-service.restore-adapter-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.core :as core]
            [pokemon-service.restauracao :as restore]))
(defn pokemon [id] {"id-pokemon" id "nivel" 1 "hp" 10 "hp-atual" 10 "ataque" 5 "defesa" 5
                   "atq-esp" 5 "def-esp" 5 "veloc" 5 "xp-desde-nivel" 0 "tipos" ["normal"] "golpes" []})
(defn conta [id] {"ativo" 0 "equipe" [(pokemon id)] "pc" [] "colecao-unificada" true})
(defn json [x] (js/JSON.stringify (clj->js x)))
(defn banco [f]
  (let [atoms [storage/client storage/pronto storage/cache storage/confirmados storage/filas-gravacao
               storage/falhas-gravacao storage/gravacoes-pendentes storage/partes-bloqueadas
               storage/geracoes-partes storage/restauracao-bloqueios treinador/contas
               restore/controlador restore/reconciliado? restore/fila-chat]
        originals (mapv deref atoms)
        initial {"p" (conta "old") "other" (conta "other")}
        rows (atom {["treinador" "g"] (json initial)})
        queries (atom [])
        driver #js {:execute
                    (fn [sql args _]
                      (let [a (js->clj args) read? (.startsWith sql "SELECT")
                            insert? (.startsWith sql "INSERT")
                            value-first? (.includes sql "(valor, modulo, particao)")
                            [module part] (if read? a (if (and insert? (not value-first?)) (take 2 a) (take 2 (rest a))))
                            key [module part] old (get @rows key)
                            value (if (and insert? (not value-first?)) (nth a 2) (first a))
                            applied (or read? (if insert? (nil? old) (= old (nth a 3))))]
                        (swap! queries conj sql)
                        (when (and (not read?) applied) (swap! rows assoc key value))
                        (p/resolved #js {:first (fn [] (when old #js {:valor old}))
                                         :rows (if old #js [#js {:valor old}] #js [])
                                         :wasApplied (fn [] applied)})))}]
    (reset! storage/client driver) (reset! storage/pronto true)
    (reset! storage/cache {"treinador" {"g" initial}})
    (reset! storage/confirmados {"treinador" {"g" initial}})
    (doseq [a [storage/filas-gravacao storage/falhas-gravacao storage/partes-bloqueadas
               storage/geracoes-partes storage/restauracao-bloqueios]] (reset! a {}))
    (reset! storage/gravacoes-pendentes 0) (reset! treinador/contas {"g" initial})
    (restore/criar!)
    (-> (p/then (p/resolved nil) (fn [_] (f {:rows rows :queries queries :initial initial})))
        (p/finally (fn [] (doseq [[a v] (map vector atoms originals)] (reset! a v)))))))
(defn concluir [work done] (-> work (p/catch #(is false (str %))) (p/finally done)))

(deftest protocolo-real-sincroniza-atom-e-preserva-outros
  (async done
    (concluir (banco
      (fn [{:keys [rows queries initial]}]
        (let [{:keys [protocol store]} @restore/controlador]
          ;; Enable only this synthetic instance, never the production gate.
          (set! (.-gate protocol) (fn [] true))
          (p/let [preview (.preview protocol "g" "p" (clj->js (conta "new")) 1)
                  result (storage/com-writer-restauracao! "g"
                           #(.confirm protocol "g" "p" (.-token preview)
                                      (fn [_] (p/resolved (clj->js (conta "new"))))))
                  backups (.listBackups store "g" "p")]
            (is (= "completed" (.-status result)))
            (is (= "new" (get-in @treinador/contas ["g" "p" "equipe" 0 "id-pokemon"])))
            (is (= (get initial "other") (get-in @treinador/contas ["g" "other"])))
            (is (empty? @storage/restauracao-bloqueios))
            (is (= 1 (alength backups)))
            (is (every? #(not (.includes % "legacy_")) @queries)))))) done)))

(deftest bloqueio-recusa-snapshot-e-cas-fora-da-fila
  (async done
    (concluir (banco
      (fn [{:keys [initial rows]}]
        (storage/bloquear-restauracao! "g" "op")
        (is (try (storage/exigir-parte-disponivel! "treinador" "g") false (catch :default _ true)))
        (is (try (storage/restauracao-cas-grupo! "g" #js {} #js {}) false (catch :default _ true)))
        (is (try (storage/salvar! "treinador" {"g" {"p" (conta "bad")}}) false (catch :default _ true)))
        (is (= initial (get @treinador/contas "g")))
        (is (= (json initial) (get @rows ["treinador" "g"])))
        (p/resolved nil))) done)))

(deftest startup-nao-ignora-journal-ausente-e-lock-duravel
  (async done
    (concluir (banco
      (fn [{:keys [rows]}]
        (swap! rows assoc ["pokemon-restauracao-controle" "registry"]
               (json {:operations {"op" {:chat "g" :player "p"}}
                      :locks {"id" {:chat "g" :player "p" :operation "op"}} :backups {}}))
        (p/let [_ (restore/reconciliar-inicializacao!)]
          (is (= "op" (get @storage/restauracao-bloqueios "g")))
          (is (try (storage/exigir-restauracao-livre! "g") false (catch :default _ true)))
          (is (nil? (storage/exigir-restauracao-livre! "other-group")))))) done)))

(deftest executor-de-slots-aceita-somente-leituras-e-cas-nos-modulos-aditivos
  (async done
    (concluir (banco
      (fn [{:keys [queries]}]
        (let [select (str "SELECT valor FROM " config/cassandra-keyspace
                          ".estado_particionado WHERE modulo = ? AND particao = ?")]
          (p/let [_ (storage/save-slot-executar! select
                         #js ["pokemon-save-slot-index" "identity-hash"]
                         #js {:consistency "SERIAL"})]
            (is (= select (last @queries)))
            (is (try
                  (storage/save-slot-executar! "DELETE FROM zapbot.estado_particionado WHERE modulo = ? AND particao = ?"
                                               #js ["pokemon-save-slots" "id"] #js {})
                  false (catch :default _ true)))
            (is (try
                  (storage/restauracao-executar! select #js ["pokemon-save-slots" "id"] #js {})
                  false (catch :default _ true)))))
        (p/resolved nil))) done)))

(deftest writer-compartilhado-neutraliza-snapshot-antigo-enfileirado
  (async done
    (concluir (banco
      (fn [{:keys [rows initial]}]
        (let [stale {"g" initial}
              desired (assoc initial "p" (conta "new"))
              restore-work (storage/com-writer-restauracao! "g"
                             #(p/let [before (storage/restauracao-ler-grupo! "g")
                                      applied (storage/restauracao-cas-grupo! "g" before (clj->js desired))]
                                (is (true? applied))
                                (storage/restauracao-instalar! "g" (clj->js desired))))
              stale-work (storage/salvar! "treinador" stale)]
          (p/let [_ (p/all [restore-work stale-work])]
            (is (= desired (js->clj (js/JSON.parse (get @rows ["treinador" "g"])))))
            (is (= desired (get-in @storage/confirmados ["treinador" "g"])))
            (is (= desired (get @treinador/contas "g"))))))) done)))

(deftest treinador-cas-aplica-delta-sem-apagar-outro-jogador-do-grupo
  (async done
    (concluir (banco
      (fn [{:keys [rows initial]}]
        (let [externo (assoc initial "other" (conta "updated-elsewhere"))
              desejado (assoc initial "p" (conta "new"))]
          ;; Outra instância já confirmou uma alteração de outro jogador após
          ;; a hidratação local. A atualização local só pode alterar p.
          (swap! rows assoc ["treinador" "g"] (json externo))
          (p/let [_ (storage/salvar! "treinador" {"g" desejado})
                  persistido (js->clj (js/JSON.parse (get @rows ["treinador" "g"])))
                  confirmado (get-in @storage/confirmados ["treinador" "g"])]
            (is (= (conta "new") (get persistido "p")))
            (is (= (conta "updated-elsewhere") (get persistido "other")))
            (is (= persistido confirmado)))))) done)))

(deftest treinador-cas-recusa-lock-duravel-e-preserva-a-particao
  (async done
    (concluir (banco
      (fn [{:keys [rows initial]}]
        (let [protocol (js/require "../runtime/restore-protocol.cjs")
              lock-key (.hash protocol (clj->js ["g" "account" "p"]))
              resource-hash (.hash protocol (clj->js {"type" "account" "id" "p"}))
              row-before (json initial)
              desired (assoc initial "p" (conta "must-not-write"))]
          (swap! rows assoc ["pokemon-save-transition-locks" lock-key]
                 (json {"schemaVersion" 1 "generation" 4 "owner" "pending-op"
                        "resourceHash" resource-hash}))
          (-> (storage/salvar! "treinador" {"g" desired})
              (p/then (fn [_] (is false "Writer precisa respeitar lock durável.")))
              (p/catch (fn [erro]
                         (is (= :save-transition-pending (:code (ex-data erro))))
                         (is (= row-before (get @rows ["treinador" "g"]))))))))) done)))

(deftest startup-indisponivel-nao-libera-barreira
  (async done
    (concluir (banco
      (fn [_]
        (reset! storage/client #js {:execute (fn [& _] (p/rejected (js/Error. "unavailable")))})
        (-> (restore/reconciliar-inicializacao!)
            (p/then (fn [_] (is false "Startup não deve ignorar falha Cassandra")))
            (p/catch (fn [_] (is (false? @restore/reconciliado?))))))) done)))

(deftest comando-batalha-bloqueado-na-particao-incerta
  (async done
    (concluir (banco
      (fn [_]
        (storage/bloquear-restauracao! "g" "op")
        ;; Same guard used before any command round in core/enfileirar-jogada.
        (is (try (treinador/pokemon-ativo "g" "p") false (catch :default _ true)))
        (is (nil? (storage/exigir-restauracao-livre! "outro")))
        (p/let [resultados (p/all
                            (for [cmd ["batalhar" "capturar pokebola"]]
                              (-> (core/jogar {:chat-id "g" :player-id "p"} cmd)
                                  (p/then (fn [_] false))
                                  (p/catch (fn [_] true)))))]
          (is (every? true? resultados))))) done)))

(deftest startup-recupera-cas-aplicado-com-resposta-perdida-e-atom-antigo
  (async done
    (concluir (banco
      (fn [_]
        (let [{:keys [protocol store]} @restore/controlador
              hooks (.-hooks store) original (.-casGroup hooks)]
          (set! (.-gate protocol) (fn [] true))
          (set! (.-casGroup hooks)
                (fn [& args] (p/let [_ (.apply original nil (to-array args))]
                               (throw (js/Error. "Resposta CAS perdida")))))
          (p/let [preview (.preview protocol "g" "p" (clj->js (conta "new")) 1)
                  _ (-> (storage/com-writer-restauracao! "g"
                           #(.confirm protocol "g" "p" (.-token preview)
                                      (fn [_] (p/resolved (clj->js (conta "new"))))))
                        (p/then (fn [_] (is false "Resposta perdida não deve indicar sucesso")))
                        (p/catch (fn [_] nil)))
                  _ (is (some? (get @storage/restauracao-bloqueios "g")))
                  _ (is (= "old" (get-in @treinador/contas ["g" "p" "equipe" 0 "id-pokemon"])))
                  _ (restore/reconciliar-inicializacao!)]
            (is (true? @restore/reconciliado?))
            (is (empty? @storage/restauracao-bloqueios))
            (is (= "new" (get-in @treinador/contas ["g" "p" "equipe" 0 "id-pokemon"]))))))) done)))

(deftest confirmacoes-compartilham-fila-chat-e-nao-duplicam-backup
  (async done
    (concluir (banco
      (fn [{:keys [rows]}]
        (let [{:keys [protocol]} @restore/controlador]
          (set! (.-gate protocol) (fn [] true))
          (restore/configurar-fila! core/enfileirar-operacao-interna!)
          (p/let [preview (.preview protocol "g" "p" (clj->js (conta "new")) 1)
                  respostas (p/all (repeatedly 2 #(restore/confirmar-interno!
                                                    "g" "p" (.-token preview)
                                                    (fn [_] (p/resolved (clj->js (conta "new")))))))]
            (is (every? #(= "completed" (.-status %)) respostas))
            (is (= 1 (count (filter #(= "pokemon-restauracao-backups" (first %)) (keys @rows)))))
            (is (= "new" (get-in @treinador/contas ["g" "p" "equipe" 0 "id-pokemon"]))))))) done)))

(deftest previa-backup-pela-interface-nao-executa-confirmacao
  (async done
    (concluir (banco
      (fn [_]
        (p/let [resposta (restore/comando! "g" "p" ["confirmar" "codigo"])
                desfazer (restore/comando! "g" "p" ["desfazer" "confirmar" "codigo"])
                lista (restore/comando! "g" "p" ["backups"])]
          (is (re-find #"desabilitada" resposta))
          (is (re-find #"desabilitada" desfazer))
          (is (re-find #"Nenhum backup" lista))
          (is (= "old" (get-in @treinador/contas ["g" "p" "equipe" 0 "id-pokemon"])))))) done)))

(deftest preparar-aceita-versoes-1-e-2-sem-emitir-token-e-comparar-orienta
  (async done
    (let [original @restore/controlador
          original-consulta storage/consultar-treinador-legado!
          source (conta "legacy")
          diagnostic (fn [_ _ _ version]
                       (p/resolved #js {:blockingOperations #js [] :sourceBlockingOperations #js []
                                        :currentRisks #js [] :sourceRisks #js []
                                        :entries #js [] :total 1 :currentCount 1 :version version
                                        :currentTrainer #js {:available true :level 4 :total 29 :xpCurrent 8}
                                        :legacyTrainer #js {:available true :level 10 :total 127 :xpCurrent 10}}))
          check (fn [_ _ _]
                  (p/resolved #js {:tokenIssued false :count 1 :currentCount 1 :transformations #js []}))
          protocol #js {:diagnostic diagnostic :checkPreparation check}]
      (reset! restore/controlador {:protocol protocol})
          (set! storage/consultar-treinador-legado!
                (fn [_] (p/resolved {:particionado [(json {"p" source})]
                                     :antigo [(json {"g" {"p" source}})]})))
      (-> (p/all [(restore/comando! "g" "p" ["1" "preparar"])
                  (restore/comando! "g" "p" ["2" "preparar"])
                  (restore/comando! "g" "p" ["3" "preparar"])
                  (restore/comando! "g" "p" ["1" "preparar" "extra"])
                  (restore/comando! "g" "p" ["1" "comparar"])
                  (restore/comando! "g" "p" ["2" "comparar"])
                  (restore/comando! "g" "p" ["1" "previa"])
                  (restore/comando! "g" "p" ["2" "previa"])])
          (p/then (fn [respostas]
                    (doseq [texto (take 2 respostas)]
                      (is (re-find #"Validações aprovadas — resumo da preparação" texto))
                      (is (re-find #"Nenhum código foi emitido" texto))
                      (is (not (re-find #"pk restaurar confirmar" texto))))
                    (doseq [texto (take 2 (drop 2 respostas))]
                      (is (re-find #"Confirmação de restauração desabilitada" texto)))
                    (doseq [texto (drop 4 respostas)]
                      (when (re-find #"Para comparar os Pokémon" texto)
                        (is (re-find #"comparar os Pokémon entre V1 e V2" texto))
                        (is (re-find #"pk recuperar comparar 2" texto))
                        (is (re-find #"consultar a V" texto))
                        (is (re-find #"pk restaurar [12] previa" texto)))
                      (when (re-find #"PRÉVIA DE RESTAURAÇÃO" texto)
                        (is (re-find #"consulta mostra o save V[12]" texto))
                        (is (re-find #"compara os totais com o treinador atual" texto))
                        (is (re-find #"não compara Pokémon individuais" texto))))))
          (p/catch #(is false (str %)))
          (p/finally (fn []
                       (set! storage/consultar-treinador-legado! original-consulta)
                       (reset! restore/controlador original)
                       (done)))))))

(deftest mensagens-de-restauracao-mostram-apenas-bloqueios-efetivos
  (async done
    (let [original @restore/controlador
          original-consulta storage/consultar-treinador-legado!
          source (conta "legacy")
          riscos #js [#js {:field "adventure-entregas" :category "delivered_reward"
                           :state "recibos válidos preserváveis" :rule "recibos preserváveis"
                           :blocking false}
                      #js {:field "ultima-cacada" :category "cooldown"
                           :state "timestamp válido" :rule "cooldown conservado" :blocking false}
                      #js {:field "times-liga" :category "permanent_configuration"
                           :state "configuração válida" :rule "configuração preservada" :blocking false}
                      #js {:field "ginásio ocupado: pedra (2 defensores: Pikachu, Eevee)"
                           :category "operation_pending" :state "ocupação ativa com defensores externos"
                           :rule "encerramento transacional não disponível"
                           :action "Aguarde outro treinador vencer normalmente." :blocking true}]
          diagnostico (fn [_ _ _ versao]
                        (p/resolved #js {:blockingOperations #js ["ginásio ocupado: pedra"]
                                         :sourceBlockingOperations #js ["xp-raids-pendente"]
                                         :currentRisks riscos
                                         :sourceRisks #js [#js {:field "xp-raids-pendente"
                                                                :category "operation_pending"
                                                                :state "XP pendente"
                                                                :rule "resolva pelo fluxo normal"
                                                                :action "Consuma o XP pelo fluxo normal."
                                                                :blocking true}]
                                         :entries #js [] :total 1 :currentCount 1 :version versao
                                         :currentTrainer #js {:available true :level 4 :total 29 :xpCurrent 8}
                                         :legacyTrainer #js {:available true :level 10 :total 127 :xpCurrent 10}}))
          protocol #js {:diagnostic diagnostico
                        :checkPreparation (fn [& _] (throw (js/Error. "não deve preparar com bloqueios")))}]
      (reset! restore/controlador {:protocol protocol})
      (set! storage/consultar-treinador-legado!
            (fn [_] (p/resolved {:particionado [(json {"p" source})]
                                 :antigo [(json {"g" {"p" source}})]})))
      (-> (p/all [(restore/comando! "g" "p" ["1" "preparar"])
                  (restore/comando! "g" "p" ["1" "previa"])])
          (p/then (fn [respostas]
                    (doseq [texto respostas]
                      (is (re-find #"ginásio ocupado: pedra \(2 defensores: Pikachu, Eevee\)" texto))
                      (is (re-find #"xp-raids-pendente" texto))
                      (is (re-find #"Próximo passo seguro" texto))
                      (is (not (re-find #"adventure-entregas|ultima-cacada|times-liga" texto)))
                      (is (re-find #"nenhum (código foi emitido|token foi gerado)" texto))
                      (is (or (re-find #"Nenhuma conta foi modificada" texto)
                              (re-find #"nenhum save foi alterado" texto))))))
          (p/catch #(is false (str %)))
          (p/finally (fn []
                       (set! storage/consultar-treinador-legado! original-consulta)
                       (reset! restore/controlador original)
                       (done)))))))
