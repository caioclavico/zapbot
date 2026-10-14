(ns pokemon-service.restauracao
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]
            [zapbot.pokemon.recuperacao-cartao :as cartao]))
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

(def ^:private tamanho-pagina 12)

(defn- pagina-diagnostico [registros pagina]
  (let [registros (vec registros)
        total (count registros)
        paginas (max 1 (js/Math.ceil (/ total tamanho-pagina)))]
    (when (and (number? pagina) (js/Number.isSafeInteger pagina)
               (<= 1 pagina paginas))
      (let [inicio (* (dec pagina) tamanho-pagina)
            fim (min total (+ inicio tamanho-pagina))]
        {:pagina pagina :paginas paginas :total total :inicio inicio :fim fim
         :itens (subvec registros inicio fim)}))))

(defn- registros-diagnostico [entries]
  (mapv (fn [entry]
          {:local (get entry "local")
           :indice (get entry "index")
           :pokemon (get entry "pokemon")
           :original (get entry "original")
           :status (get entry "status")
           :detalhe (get entry "detail")})
        (js->clj entries)))

(defn- legenda-diagnostico [pid versao dados]
  (str "🧬 *Prévia do save legado — treinador " pid "*"
       "\nPágina " (:pagina dados) " de " (:paginas dados)
       " • Total: " (:total dados) " Pokémon"
       (when (pos? (:total dados)) (str " • Exibindo " (inc (:inicio dados)) "–" (:fim dados)))
       (when (> (:pagina dados) 1) (str "\n⬅️ !pk restaurar " versao " previa " (dec (:pagina dados))))
       (when (< (:pagina dados) (:paginas dados)) (str "\n➡️ !pk restaurar " versao " previa " (inc (:pagina dados))))
       "\n🔎 Consulta somente leitura. Nenhuma alteração foi persistida."))

(defn- resposta-diagnostico! [pid source pagina protocol cid versao]
  (p/let [diagnostico (.diagnostic protocol cid pid source versao)
          registros (registros-diagnostico (.-entries diagnostico))
          dados (pagina-diagnostico registros pagina)]
    (if-not dados
      (str "Página inexistente. Escolha uma página de 1 a "
           (max 1 (js/Math.ceil (/ (count registros) tamanho-pagina))) ".")
      (let [fallback (str (legenda-diagnostico pid versao dados)
                          "\n\n⚠️ A imagem não pôde ser gerada. Diagnóstico textual dos registros desta página:\n"
                          (if (seq (:itens dados))
                            (str/join "\n" (map (fn [{:keys [local indice pokemon status original]}]
                                                  (str "• " local " #" indice " — "
                                                       (or (get pokemon "nome") "registro inválido")
                                                       ", status=" status
                                                       ", nível " (or (get original "nivel") "?")
                                                       " → " (or (get pokemon "nivel") "?")))
                                                (:itens dados)))
                            "Nenhum Pokémon."))]
        (if (seq (:itens dados))
          (-> (cartao/criar! (:itens dados)
                             {:titulo (str "Save legado — " pid)
                              :subtitulo (str "Versão " versao " • Página " (:pagina dados) "/" (:paginas dados)
                                              " • " (:total dados) " Pokémon")
                              :arquivo (str "save-restauracao-" versao "-p" (:pagina dados) ".jpg")})
              (p/then (fn [media] {:media media :texto (legenda-diagnostico pid versao dados)}))
              (p/catch (fn [erro]
                         (js/console.warn "[Restauração] Card indisponível; diagnóstico textual." (.-message erro))
                         fallback)))
          (p/resolved (str (legenda-diagnostico pid versao dados) "\n\nNenhum Pokémon encontrado.")))))))

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
                   (and (<= 2 (count args) 3)
                        (= "previa" (second args))
                        (or (= 2 (count args))
                            (and (string? (nth args 2))
                                 (re-matches #"[1-9][0-9]{0,5}" (nth args 2)))))))
          (p/let [source (origem! cid pid (first args))
                  pagina (if (= 3 (count args)) (js/parseInt (nth args 2) 10) 1)]
            (resposta-diagnostico! pid source pagina protocol cid (first args)))
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
