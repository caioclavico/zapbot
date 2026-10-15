(ns pokemon-service.restauracao
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]))
(defonce controlador (atom nil))
(defonce reconciliado? (atom false))
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
                        :preflight (fn [cid pid] (@preflight cid pid))})
        protocol (new (.-RestoreProtocol protocol-module) store
                      #js {:gate (fn [] (and config/restore-enabled? @reconciliado?))})]
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
  (if-let [protocol (:protocol @controlador)] (.previewBackup protocol cid pid id)
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

(defn- resumo-diagnostico! [pid source protocol cid versao]
  (p/let [diagnostico (.diagnostic protocol cid pid source versao)
          registros (registros-diagnostico (.-entries diagnostico))
          por-local (fn [local] (count (filter #(= local (:local %)) registros)))
          compat (count (filter #(= "compatible" (:status %)) registros))
          convert (count (filter #(= "converted" (:status %)) registros))
          invalid (count (filter #(= "invalid" (:status %)) registros))
          bloqueios (vec (array-seq (or (.-blockingOperations diagnostico) #js [])))]
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
         (if (seq bloqueios)
           (str "\n\n⚠️ *Confirmação bloqueada*\nOperações: " (str/join ", " bloqueios))
           "\n\n✅ Nenhuma operação pendente foi identificada.")
         "\n\n⚠️ A restauração substituirá o treinador atual e não mesclará as coleções."
         "\n🔎 Consulta somente leitura; nenhum token foi gerado e nenhum save foi alterado.")))

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
          (and protocol (= 2 (count args)) (= "confirmar" (first args))
               config/restore-enabled?)
          (p/let [_ (confirmar-interno! cid pid (second args)
                                        (fn [versao] (origem! cid pid versao)))]
            "✅ Restauração confirmada e persistida com sucesso.")
          (and protocol (contains? #{"1" "2"} (first args))
               (or (= 1 (count args))
                   (and (= 2 (count args)) (= "previa" (second args)))))
          (p/let [source (origem! cid pid (first args))]
            (resumo-diagnostico! pid source protocol cid (first args)))
          (and protocol (= 3 (count args)) (= "desfazer" (first args)) (= "previa" (last args)))
          (p/let [preview (previa-backup! cid pid (second args))]
            (str "📦 Prévia do backup validada: " (.-count preview) " Pokémon.\n🔒 Confirmação desabilitada."))
          :else (p/resolved "🔒 Confirmação de restauração desabilitada. Consultas: !pk restaurar 1 previa, 2 previa, backups ou desfazer ID previa."))
        (p/catch (fn [erro]
                   (str "⚠️ Prévia bloqueada: "
                        (case (.-code erro)
                          "normalization_review_required" "XP exige conversão que altera atributos; revisão necessária."
                          "operational_review_required" "Há dados operacionais que exigem revisão."
                          "invalid_pokemon_identity" "Identidades Pokémon ausentes ou duplicadas."
                          "Save incompatível ou consulta indisponível.")
                        " Nenhuma conta foi modificada."))))))
