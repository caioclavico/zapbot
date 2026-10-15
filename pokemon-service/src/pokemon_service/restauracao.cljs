(ns pokemon-service.restauracao
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]))
(defonce controlador (atom nil))
(defonce reconciliado? (atom false))
(def ^:private saves-independentes-disponiveis? false)
(defonce fila-chat (atom nil))
(defonce preflight (atom (fn [_ _] nil)))
(defn configurar-fila! [enfileirar] (reset! fila-chat enfileirar))
(defn configurar-preflight! [verificar] (reset! preflight verificar))
(defn criar! []
  (let [store-module (js/require "../runtime/restore-store.cjs")
        protocol-module (js/require "../runtime/restore-protocol.cjs")
        store (new (.-RestoreStore store-module) storage/restauracao-executar! config/cassandra-keyspace
                   #js {:readGroup storage/restauracao-ler-grupo!
                        :casGroup storage/restauracao-cas-grupo!
                        :install storage/restauracao-instalar!
                        :block storage/bloquear-restauracao!
                        :unblock storage/liberar-restauracao!
                        :preflight (fn [cid pid] (clj->js (@preflight cid pid)))})
        protocol (new (.-RestoreProtocol protocol-module) store
                      #js {:gate (fn [] (and saves-independentes-disponiveis?
                                             config/restore-enabled? @reconciliado?))})]
    (reset! controlador {:store store :protocol protocol})))
(defn reconciliar-inicializacao! []
  (reset! reconciliado? false)
  (criar!)
  (let [{:keys [store protocol]} @controlador]
    (p/let [pendentes (.pending store)]
      ;; Protect every involved partition before attempting any recovery.
      (doseq [^js item (array-seq pendentes)] (storage/bloquear-restauracao! (.-chat item) (.-id item)))
      (p/let [_ (p/all (for [^js item (array-seq pendentes)]
                         (storage/com-writer-restauracao! (.-chat item)
                           #(-> (.reconcile protocol (.-id item))
                                (p/catch (fn [_]
                                           (js/console.error "[Restauração] Operação incerta; grupo permanece bloqueado.")))))))]
        (reset! reconciliado? true)))))
(defn backups! [cid pid]
  (if-let [store (:store @controlador)] (.listBackups store cid pid)
      (p/rejected (js/Error. "Reconciliação ainda não disponível."))))
(defn previa-backup! [cid pid id]
  (if-let [protocol (:protocol @controlador)] (.checkBackup protocol cid pid id)
      (p/rejected (js/Error. "Reconciliação ainda não disponível."))))

(defn confirmar-interno! [cid pid codigo ler-origem]
  ;; This is not an HTTP/domain export. The production controller's gate stays
  ;; closed; tests can use a synthetic instance with the exact same writer queue.
  (if (and (:protocol @controlador) @fila-chat)
    (@fila-chat cid
      (fn [] (storage/com-writer-restauracao! cid
               #(.confirm (:protocol @controlador) cid pid codigo ler-origem))) true)
    (p/rejected (js/Error. "Reconciliação/fila ainda não disponível."))))

(defn- origem! [cid pid numero]
  (if (and (some? numero) (not (string? numero)))
    (let [versao (if (map? numero) numero (js->clj numero :keywordize-keys true))
          id (:backup versao)
          store (:store @controlador)]
      (p/let [backup (when id (.readBackupSerial store id))]
        (when-not (and backup (= cid (.-chat backup)) (= pid (.-player backup)))
          (throw (js/Error. "source_unavailable")))
        (clj->js (.-account backup))))
    (p/let [dados (storage/consultar-treinador-legado! cid)]
      (let [valores (case numero "1" (:particionado dados) "2" (:antigo dados) nil)]
        (when-not (= 1 (count valores)) (throw (js/Error. "source_unavailable")))
        (let [dados (js->clj (js/JSON.parse (first valores)))
              conta (if (= numero "1") (get dados pid) (get-in dados [cid pid]))]
          (when-not (map? conta) (throw (js/Error. "source_unavailable")))
          (clj->js conta))))))

(defn- registros-diagnostico [entries]
  (mapv (fn [entry]
          {:local (get entry "local")
           :indice (get entry "index")
           :pokemon (get entry "pokemon")
           :original (get entry "original")
           :status (get entry "status")
           :detalhe (get entry "detail")})
        (js->clj entries)))

(defn- formatar-riscos [riscos]
  (->> (array-seq (or riscos #js []))
       (map #(js->clj % :keywordize-keys true))
       (map (fn [{:keys [field category state rule action]}]
              (str "• " field " — " state " [" (name (keyword (or category "unknown"))) "]: " rule
                   (when (seq action) (str " Próximo passo seguro: " action)))))
       (str/join "\n")))

(defn- resumo-diagnostico! [pid source protocol cid versao]
  (p/let [diagnostico (.diagnostic protocol cid pid source versao)
          registros (registros-diagnostico (.-entries diagnostico))
          por-local (fn [local] (count (filter #(= local (:local %)) registros)))
          compat (count (filter #(= "compatible" (:status %)) registros))
          convert (count (filter #(= "converted" (:status %)) registros))
          invalid (count (filter #(= "invalid" (:status %)) registros))
          bloqueios (vec (array-seq (or (.-blockingOperations diagnostico) #js [])))
          bloqueios-origem (vec (array-seq (or (.-sourceBlockingOperations diagnostico) #js [])))
          riscos-atual (formatar-riscos (.-currentRisks diagnostico))
          riscos-origem (formatar-riscos (.-sourceRisks diagnostico))]
    (str "📦 *PRÉVIA DE RESTAURAÇÃO — VERSÃO " versao "*\n"
         "• Pokémon antigos: " (.-total diagnostico) "\n"
         "• Pokémon atuais: " (.-currentCount diagnostico) "\n\n"
         "📋 *Distribuição do save antigo*\n"
         "• Equipe: " (por-local "Equipe") "\n"
         "• PC: " (por-local "PC") "\n"
         "• Enfermaria: " (por-local "Enfermaria") "\n\n"
         "🧪 *Validação*\n"
         "• XP compatível: " compat "\n"
         "• Conversão proposta: " convert "\n"
         "• Registros inválidos: " invalid
         "\n\n⭐ *Comparação dos treinadores*\n"
         (let [atual (js->clj (.-currentTrainer diagnostico) :keywordize-keys true)
               antigo (js->clj (.-legacyTrainer diagnostico) :keywordize-keys true)
               linha (fn [label chave]
                       (str "• " label ": atual "
                            (if (:available atual) (get atual chave) "indisponível")
                            " | save antigo "
                            (if (:available antigo) (get antigo chave) "indisponível") "\n"))]
           (str (linha "Nível" :level)
                (linha "XP total" :total)
                (linha "XP no nível" :xpCurrent)
                "• Pokémon: atual " (.-currentCount diagnostico) " | save antigo " (.-total diagnostico)))
         (if (or (seq bloqueios) (seq bloqueios-origem))
           (str "\n\n⚠️ *Confirmação bloqueada*"
                (when (seq bloqueios) (str "\nConta atual:\n" riscos-atual))
                (when (seq bloqueios-origem) (str "\nSave antigo:\n" riscos-origem)))
           (str "\n\n✅ *Prévia sem bloqueios conhecidos.* Esta consulta mostra o save V" versao
                " e compara os totais com o treinador atual; não compara Pokémon individuais. Para executar as validações finais, use: "
                config/prefix "pk restaurar " versao " preparar. A confirmação continua bloqueada até haver saves independentes."))
         "\n\n⚠️ A restauração substituirá o treinador atual e não mesclará as coleções."
         "\n🔎 Consulta somente leitura; nenhum token foi gerado e nenhum save foi alterado.")))

(defn- preparar! [pid source protocol cid versao]
  ;; Validate the source and current account through the same protocol used by
  ;; token preparation. Save switching is not yet implemented, so this command
  ;; must never issue a destructive one-slot restore token.
  (p/let [diagnostico (.diagnostic protocol cid pid source versao)
          bloqueios (concat (array-seq (or (.-blockingOperations diagnostico) #js []))
                            (array-seq (or (.-sourceBlockingOperations diagnostico) #js [])))]
    (if (seq bloqueios)
      (do
       (js/console.warn "[Restauração] Preparação bloqueada; campos/estados:",
                        (str/join " | " (remove str/blank? [(formatar-riscos (.-currentRisks diagnostico))
                                                             (formatar-riscos (.-sourceRisks diagnostico))])))
       (str "⚠️ *Preparação bloqueada; nenhum código foi emitido.*\n"
           (when (seq (.-blockingOperations diagnostico))
             (str "Conta atual:\n" (formatar-riscos (.-currentRisks diagnostico)) "\n"))
           (when (seq (.-sourceBlockingOperations diagnostico))
             (str "Save antigo:\n" (formatar-riscos (.-sourceRisks diagnostico))))
           "\nNenhuma conta foi modificada."))
      (p/let [preview (.checkPreparation protocol cid pid source)
            atual (js->clj (.-currentTrainer diagnostico) :keywordize-keys true)
            antigo (js->clj (.-legacyTrainer diagnostico) :keywordize-keys true)
            linha (fn [label chave]
                    (str "• " label ": atual "
                         (if (:available atual) (get atual chave) "indisponível")
                         " | save antigo "
                         (if (:available antigo) (get antigo chave) "indisponível") "\n"))]
      (str "✅ *Validações da preparação concluídas*\n"
           "• Pokémon: atual " (.-currentCount preview) " | save antigo " (.-count preview) "\n"
           "• Save selecionado: versão " versao "\n"
           "\n⭐ *Treinadores*\n"
           (linha "Nível" :level)
           (linha "XP total" :total)
           (linha "XP no nível" :xpCurrent)
           "\n🧪 *Alterações previstas*\n"
           (if (seq (.-transformations preview))
             (str/join "\n" (map #(str "• " %) (array-seq (.-transformations preview))))
             "• Nenhuma conversão adicional")
           "\n\n🔐 Nenhum backup foi criado; a operação foi somente de leitura.\n"
           "⚠️ A troca destrutiva está bloqueada até haver saves independentes com persistência e recuperação transacionais."
           " Nenhum código foi emitido e nenhum save foi alterado.")))))

(defn comando! [cid pid args]
  ;; Entered before navigation, XP, Joy collection or automatic persistence.
  ;; Confirmation stays closed until cross-module operational references have
  ;; been audited: a trainer-only CAS cannot cancel a fight in another module.
  (let [protocol (:protocol @controlador)]
    (-> (cond
          (= args ["backups"])
          (p/let [items (backups! cid pid)]
            (if (zero? (alength items)) "📦 Nenhum backup desta conta."
                (str "📦 Backups desta conta\n"
                     (apply str (map #(str "• " (.-id %) "\n") (array-seq items))))))
          ;; The destructive one-slot restore stays closed until independent
          ;; save slots and their cross-domain receipt semantics are implemented.
          (and protocol (= 2 (count args)) (= "confirmar" (first args))
               config/restore-enabled? saves-independentes-disponiveis?)
          (p/let [_ (confirmar-interno! cid pid (second args)
                                        (fn [versao] (origem! cid pid versao)))]
            "✅ Restauração confirmada e persistida com sucesso.")
          (and (= 2 (count args)) (contains? #{"1" "2"} (first args))
               (= "comparar" (second args)))
          (p/resolved (str "Para comparar os Pokémon entre V1 e V2, use " config/prefix
                           "pk recuperar comparar (páginas: " config/prefix
                           "pk recuperar comparar 2, " config/prefix "pk recuperar comparar 3...).\n"
                           "Para consultar a V" (first args) " e comparar apenas os totais com o treinador atual, use "
                           config/prefix "pk restaurar " (first args) " previa."))
          (and protocol (= 2 (count args))
               (contains? #{"1" "2"} (first args))
               (= "preparar" (second args)))
          (p/let [source (origem! cid pid (first args))]
            (preparar! pid source protocol cid (first args)))
          (and protocol (contains? #{"1" "2"} (first args))
               (or (= 1 (count args))
                   (and (= 2 (count args)) (= "previa" (second args)))))
          (p/let [source (origem! cid pid (first args))]
            (resumo-diagnostico! pid source protocol cid (first args)))
          (and protocol (= 3 (count args)) (= "desfazer" (first args)) (= "previa" (last args)))
          (p/let [preview (previa-backup! cid pid (second args))]
            (str "📦 Prévia do backup validada: " (.-count preview) " Pokémon.\n🔎 Consulta sem código ou alterações.\n🔒 A troca continua bloqueada até haver saves independentes."))
          :else (p/resolved "🔒 Confirmação de restauração desabilitada. Consultas: !pk restaurar 1 previa, 2 previa; preparação: !pk restaurar 1 preparar; backups ou desfazer ID previa."))
        (p/catch (fn [erro]
                   (let [riscos (formatar-riscos (.-findings erro))]
                     (js/console.warn "[Restauração] comando de consulta/preparação falhou; código:",
                                      (if (string? (.-code erro)) (.-code erro) "erro_sem_codigo"))
                     (when (seq riscos)
                       (js/console.warn "[Restauração] Validação bloqueou; campos e regras:", riscos))
                     (str "⚠️ Prévia bloqueada: "
                          (case (.-code erro)
                            "normalization_review_required" "XP exige conversão que altera atributos; revisão necessária."
                            "operational_review_required" "Há dados operacionais que exigem revisão."
                            "external_operation_pending" (str "Operações Pokémon ainda ativas: "
                                                              (str/join ", " (array-seq (or (.-operations erro) #js []))) ". Aguarde sua conclusão.")
                            "source_unavailable" "Não foi possível localizar unicamente esse save histórico para o jogador e grupo autenticados."
                            "invalid_pokemon_identity" "Identidades Pokémon ausentes ou duplicadas."
                            "Save incompatível ou consulta indisponível.")
                          (when (seq riscos) (str "\n" riscos))
                          " Nenhuma conta foi modificada.")))))))
