(ns zapbot.adventure-store
  (:refer-clojure :exclude [uuid])
  (:require [promesa.core :as p]
            [clojure.walk :as walk]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.ovos :as ovos]))

(defn uuid [] (.randomUUID (js/require "crypto")))
(defn json [valor] (js/JSON.stringify (clj->js valor)))
(defn ler-json [texto]
  (walk/postwalk (fn [valor]
                   (if (and (map? valor) (:registro valor))
                     (update valor :registro walk/stringify-keys) valor))
                 (js->clj (js/JSON.parse texto) :keywordize-keys true)))
(defn fingerprint [valor]
  (.digest (.update (.createHash (js/require "crypto") "sha256") (pr-str valor)) "hex"))
(defn batch-id [id]
  (let [hex (subs (fingerprint id) 0 32)]
    (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16) "-"
         (subs hex 16 20) "-" (subs hex 20 32))))
(defn dia [ms] (subs (.toISOString (js/Date. ms)) 0 10))
(defn mes [ms] (subs (dia ms) 0 7))
(defn- numero [^js x] (if (number? x) x (.toNumber x)))
(defn consulta! [op args] (armazenamento/executar-adventure! op args))

(defn- ler-estado [^js row]
  (let [estado (ler-json (.-payload row)) versao (numero (.-version row))]
    (when-not (and (= 1 (.-schema_version row)) (= 1 (:schema-version estado))
                   (js/Number.isSafeInteger versao) (<= 0 versao))
      (ovos/erro! :schema-incompatible "Estado Adventure incompatível."))
    (assoc estado :version versao)))

(defn ler! [id]
  (p/let [^js resultado (consulta! :state-read [id])]
    (when-let [row (.first resultado)]
      (ler-estado row))))

(defn resolver! [cid pid criar? agora]
  (p/let [^js leitura (consulta! :binding-read [cid pid])
          ^js row (.first leitura)
          id (if row (str (.-account_id row))
                 (when criar?
                   (p/let [id (uuid)
                           ^js resposta (consulta! :binding-insert [cid pid id (js/Date. agora)])]
                     (if (.wasApplied resposta) id
                         (str (.-account_id (.first resposta)))))))]
    (when id
      (p/let [estado (ler! id)]
        (if estado
          (do (when-not (= [cid pid] [(:chat-id estado) (:player-id estado)])
                (ovos/erro! :account-conflict "Vínculo da conta incompatível.")) estado)
          (when criar?
            (p/let [_ (consulta! :state-insert
                        [id 0 1 (js/Date. agora) (json (ovos/estado-inicial id cid pid (uuid)))])]
              (ler! id))))))))

(defn recibo! [id event-id day]
  (p/let [^js leitura (consulta! :receipt-read [id day (batch-id event-id)])]
    (when-let [row (.first leitura)] (ler-json (.-payload row)))))

(defn- tamanho! [estado]
  (when (> (.byteLength js/Buffer (json estado)) (* 512 1024))
    (ovos/erro! :state-limit "Estado cheio; preservar recibos até compactação segura.")))

(defn cas! [antes depois agora]
  (tamanho! depois)
  (p/let [^js resultado (consulta! :state-cas
                         [(inc (:version antes)) (json (dissoc depois :version))
                          (js/Date. agora) (:account-id antes) (:version antes)])]
    (.wasApplied resultado)))

(defn alterar! [id mudar agora]
  (letfn [(tentar [restantes]
            (p/let [antes (ler! id)
                    _ (when-not antes (ovos/erro! :account-not-found "Conta inexistente."))
                    depois (mudar antes)]
              (if (= antes depois) antes
                  (-> (cas! antes depois agora)
                      (p/then (fn [aplicado?]
                                (if aplicado? (assoc depois :version (inc (:version antes)))
                                    (if (pos? restantes) (tentar (dec restantes))
                                        (ovos/erro! :concurrent-conflict "Estado concorrente; consulte e repita o mesmo ID.")))))
                      (p/catch (fn [erro]
                                 (if (:code (ex-data erro)) (throw erro)
                                     (throw (ex-info "Resultado Cassandra incerto; recuperar com o mesmo ID."
                                                     {:code :result-unknown} erro)))))))))]
    (tentar 3)))

(defn transicionar-legado! [id agora]
  "Aplica a conversão v1->v2 sob o CAS existente. Não cria nascimento nem
   executa a conversão novamente quando o marcador já foi persistido."
  (alterar! id ovos/transicionar-legado agora))

(defn operar! [id event-id hash tipo day agora mudar]
  (p/let [externo (recibo! id event-id day)]
    (when (and externo (not= hash (:fingerprint externo)))
      (ovos/erro! :event-conflict "Evento já usado com outro conteúdo."))
    (if externo
      (p/let [atual (ler! id)] {:receipt externo :state atual})
      (p/let [estado (alterar! id
                      (fn [antes]
                        (if-let [recibo (some #(when (= event-id (:event-id %)) %) (:receipts antes))]
                          (do (when-not (= hash (:fingerprint recibo))
                                (ovos/erro! :event-conflict "Evento já usado com outro conteúdo.")) antes)
                          (let [[depois resultado] (mudar antes)]
                            (update depois :receipts conj
                                    {:event-id event-id :fingerprint hash :kind tipo :day day
                                     :at agora :result resultado})))) agora)
              recibo (some #(when (= event-id (:event-id %)) %) (:receipts estado))]
        {:receipt recibo :state estado}))))

(defn- confirmar-projecoes! [operacoes]
  ;; Uma falha não libera a fila enquanto outras escritas do grupo continuam.
  (p/let [resultados (.allSettled js/Promise (to-array operacoes))]
    (when-let [falha (some #(when (= "rejected" (.-status %)) %) (array-seq resultados))]
      (throw (.-reason falha)))))

(defn projetar! [estado agora]
  ;; Não compactar antes da confirmação de TODAS as projeções necessárias.
  (let [recibos (filterv #(not (:projected? %)) (:receipts estado))]
  (p/let [_ (confirmar-projecoes! (map (fn [recibo]
                         (consulta! :receipt-write
                           [(:account-id estado) (:day recibo) (batch-id (:event-id recibo))
                            (:fingerprint recibo) (:version estado) (json recibo)]))
                       recibos))
          _ (confirmar-projecoes! (map (fn [h]
                         (consulta! :history-write
                           [(:account-id estado) (mes (:hatched-at h)) (:seq h) (:hatch-id h)
                            (:egg-id h) (:pokemon-id h) (json h)])) (:history-pending estado)))
          novo (alterar! (:account-id estado)
                 (fn [atual]
                   (let [ids (set (map :hatch-id (:history-pending estado)))
                         ovos-remover (set (map :egg-id (:history-pending estado)))
                         eventos (set (map :event-id recibos))]
                     (-> atual
                         (update :finalized-before #(max (or % 0) (- agora ovos/janela-ms)))
                         (update :history-pending #(vec (remove (fn [h] (contains? ids (:hatch-id h))) %)))
                         (update :eggs #(vec (remove (fn [ovo] (and (= "hatched" (:state ovo))
                                                                  (contains? ovos-remover (:egg-id ovo)))) %)))
                         (update :receipts
                                 #(->> %
                                       (map (fn [r] (if (contains? eventos (:event-id r)) (assoc r :projected? true) r)))
                                       (remove (fn [r] (and (= "distance" (:kind r)) (:projected? r)
                                                           (< (:at r) (- agora ovos/janela-ms))))) vec))))) agora)]
    novo)))

(defn historico! [id month cursor]
  (p/let [^js resposta (consulta! :history-read [id month cursor])]
    (mapv (fn [^js row] (ler-json (.-payload row))) (array-seq (.-rows resposta)))))

(defn pagina-estados! [pagina]
  (p/let [^js resposta (armazenamento/executar-adventure! :state-scan [] pagina)]
    {:states (mapv ler-estado (array-seq (.-rows resposta)))
     :next-page (.-pageState resposta)}))
