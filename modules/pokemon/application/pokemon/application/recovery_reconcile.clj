(ns pokemon.application.recovery-reconcile
  "Reconciliação pura de saves históricos (A6.4).

  Sem I/O: sem Cassandra, Datomic, HTTP ou filesystem.
  Separação: descoberta de fingerprint → comparação → classificação → anonimização.
  Persistência/import ficam fora deste namespace."
  (:require [clojure.string :as str]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.account-shape :as shape]))

(def sources #{:cassandra-v1 :cassandra-v2 :cassandra-operational :datomic-legacy})

(defn- g
  [m k]
  (when (map? m)
    (or (get m k)
        (get m (name k))
        (get m (keyword (name k))))))

(defn identity-kind
  "Classifica um id WhatsApp sem inferir equivalência entre kinds."
  [id]
  (let [s (str id)]
    (cond
      (str/includes? s "@g.us") :g.us
      (str/includes? s "@lid") :lid
      (str/includes? s "@c.us") :c.us
      (str/blank? s) :blank
      :else :other)))

(defn- long*
  [v]
  (cond
    (nil? v) 0
    (number? v) (long v)
    (string? v) (try (Long/parseLong v) (catch Exception _ 0))
    :else 0))

(defn- pokemon-stats
  [account]
  (let [xs (shape/pokemon-in-location account)
        levels (mapv #(long* (or (g (:pokemon %) :nivel) (g (:pokemon %) :level) 1)) xs)
        species (->> xs
                     (map #(or (g (:pokemon %) :nome)
                               (g (:pokemon %) :especie)
                               (g (:pokemon %) :species)))
                     (map shape/normalize-species)
                     (remove nil?)
                     sort
                     vec)
        ids (->> xs
                 (map #(g (:pokemon %) :id-pokemon))
                 (remove nil?)
                 (map str)
                 sort
                 vec)
        hp-atual (mapv #(long* (or (g (:pokemon %) :hp-atual) (g (:pokemon %) :hp) 0)) xs)]
    {:location-counts (shape/location-counts account)
     :level-sum (reduce + 0 levels)
     :level-max (if (seq levels) (apply max levels) 0)
     :level-min (if (seq levels) (apply min levels) 0)
     :hp-sum (reduce + 0 hp-atual)
     :species-count (count (distinct species))
     :species-digest (reward/payload-hash {:species species})
     :id-digest (reward/payload-hash {:ids ids})
     :pokemon-count (count xs)}))

(defn- inventory-stats
  [account]
  (let [loja (or (g account :loja) {})
        inv (or (g loja :inventario) (g account :inventario) {})
        coins (or (g loja :moedas) (g account :moedas) 0)
        skus (when (map? inv)
               (->> inv
                    (map (fn [[k v]] [(str k) (long* v)]))
                    (filter #(pos? (second %)))
                    (sort-by first)
                    vec))]
    {:coins (long* coins)
     :inventory-sku-count (count skus)
     :inventory-qty-sum (reduce + 0 (map second (or skus [])))
     :inventory-digest (reward/payload-hash {:skus (or skus [])})}))

(defn- gym-stats
  [account]
  (let [gmap (or (g account :ginasios) {})]
    {:gym-count (if (map? gmap) (count gmap) 0)
     :gym-digest (reward/payload-hash
                  {:keys (when (map? gmap) (sort (map str (keys gmap))))})}))

(defn account-fingerprint
  "Resumo estrutural comparável de uma conta legada.
   Não inclui chat-id/player-id nem nomes de jogador.
   field-keys ficam só como metadado — não entram no hash (evita falso
   conflito V1/V2 por chaves extras como colecao-unificada)."
  [account]
  (when (map? account)
    (let [poke (pokemon-stats account)
          inv (inventory-stats account)
          gym (gym-stats account)
          fields (->> (keys account) (map #(if (keyword? %) (name %) (str %))) sort vec)
          ;; Comparável: progresso (pokémon/níveis/ids/moedas/inventário/ginásios)
          body (merge poke inv gym)]
      (assoc body
             :field-keys fields
             :fingerprint (reward/payload-hash body)))))

(defn source-record
  "Registro de uma aparição de save em uma fonte.
   identity: {:player-id :chat-id} — caller decide anonimizar depois.
   context-kind separa identidade de jogador (:lid/:c.us) do chat (:g.us/:lid)."
  [{:keys [source player-id chat-id account]}]
  (when-not (contains? sources source)
    (throw (ex-info "recovery_reconcile_unknown_source" {:source source})))
  {:source source
   :player-id player-id
   :chat-id chat-id
   :player-kind (identity-kind player-id)
   :chat-kind (identity-kind chat-id)
   :fingerprint (account-fingerprint account)})

(defn group-by-player
  "Agrupa aparições pela identidade literal do player-id (sem alias)."
  [records]
  (->> records
       (remove nil?)
       (group-by :player-id)
       (into (sorted-map))))

(defn compare-fingerprints
  "Compara dois fingerprints. Retorna :equal | :diverged | :incomplete."
  [a b]
  (cond
    (or (nil? a) (nil? b)) :incomplete
    (= (:fingerprint a) (:fingerprint b)) :equal
    :else :diverged))

(defn- source-set
  [recs]
  (set (map :source recs)))

(defn- fingerprint-clusters
  [recs]
  (->> recs
       (map :fingerprint)
       (remove nil?)
       (group-by :fingerprint)
       (map (fn [[fp items]]
              {:fingerprint fp
               :count (count items)
               :sources (sort (map :source
                                   (filter #(= fp (:fingerprint %)) recs)))}))
       vec))

(defn classify-player
  "Classifica o conjunto de aparições de um mesmo player-id literal.

  :resolved     — uma fonte, ou todas as fontes com fingerprint idêntico
  :pending      — ≥2 fingerprints distintos entre fontes presentes
  :inconclusive — falta fingerprint em alguma aparição crítica / dados incompletos"
  [player-id records]
  (let [recs (vec records)
        fps (mapv :fingerprint recs)
        complete? (every? some? fps)
        clusters (fingerprint-clusters recs)
        distinct-fp (count clusters)
        sources (source-set recs)
        chats (set (map :chat-id recs))
        status (cond
                 (empty? recs) :inconclusive
                 (not complete?) :inconclusive
                 (= 1 distinct-fp) :resolved
                 :else :pending)]
    {:player-id player-id
     :player-kind (identity-kind player-id)
     :status status
     :sources (sort sources)
     :chat-count (count chats)
     :chat-kinds (sort (set (map :chat-kind recs)))
     :appearance-count (count recs)
     :fingerprint-clusters distinct-fp
     :pairings
     (for [a recs
           b recs
           :when (and (not= (:source a) (:source b))
                      (pos? (compare (name (:source a)) (name (:source b)))))]
       {:left (:source a)
        :right (:source b)
        :same-chat? (= (:chat-id a) (:chat-id b))
        :comparison (compare-fingerprints (:fingerprint a) (:fingerprint b))})}))

(defn reconcile
  "Produz matriz por player-id literal a partir de source-records."
  [records]
  (let [grouped (group-by-player records)
        rows (mapv (fn [[pid recs]] (classify-player pid recs)) grouped)
        by-status (frequencies (map :status rows))]
    {:player-count (count rows)
     :status-counts by-status
     :rows rows}))

(defn propose-aliases
  "Nunca infere alias. Apenas lista candidatos explícitos fornecidos.

  entries: [{:current-player-id :historical-player-id :evidence ...}]
  Rejeita pares com kinds iguais sem evidence, e qualquer par sem :evidence."
  [entries]
  (->> entries
       (keep (fn [{:keys [current-player-id historical-player-id evidence] :as e}]
               (when (and (string? current-player-id)
                          (string? historical-player-id)
                          (not (str/blank? (str evidence)))
                          (not= current-player-id historical-player-id))
                 {:current-player-id current-player-id
                  :historical-player-id historical-player-id
                  :current-kind (identity-kind current-player-id)
                  :historical-kind (identity-kind historical-player-id)
                  :evidence (str evidence)
                  :status :pending-human})))
       vec))

(defn anonymize-id
  "Hash estável truncado para relatório versionado."
  [id]
  (when id
    (subs (reward/payload-hash {:id (str id)}) 0 12)))

(defn anonymize-row
  "Remove ids literais; preserva kinds, status e contagens."
  [row]
  (-> row
      (dissoc :player-id)
      (assoc :player-hash (anonymize-id (:player-id row)))
      (update :pairings
              (fn [ps]
                (mapv #(select-keys % [:left :right :same-chat? :comparison]) ps)))))

(defn anonymize-matrix
  [matrix]
  (-> matrix
      (update :rows (fn [rows] (mapv anonymize-row rows)))
      (assoc :anonymized? true)))

(defn import-candidate
  "Sugestão estrutural — nunca escolhe vencedor em divergência.

  Retorna :
  - {:action :skip :reason _}
  - {:action :await-human :reason _ :sources _}
  - {:action :eligible :sources-equal #{...} :notes _}"
  [{:keys [status sources] :as row}]
  (case status
    :inconclusive {:action :await-human :reason :incomplete-fingerprint :sources sources}
    :pending {:action :await-human :reason :divergent-fingerprints :sources sources}
    :resolved
    (let [has-legacy? (or (contains? (set sources) :cassandra-v1)
                          (contains? (set sources) :cassandra-v2))
          has-op? (contains? (set sources) :cassandra-operational)]
      (cond
        (and has-legacy? has-op?)
        {:action :eligible
         :sources-equal (set sources)
         :notes :legacy-and-operational-agree}
        has-legacy?
        {:action :eligible
         :sources-equal (set sources)
         :notes :legacy-only}
        has-op?
        {:action :await-human
         :reason :operational-without-legacy
         :sources sources}
        :else
        {:action :await-human
         :reason :no-cassandra-source
         :sources sources}))
    {:action :await-human :reason :unknown-status :sources sources}))

(defn plan-import-batch
  "Plano de lote sem side-effects. Não marca vencedor V1/V2/operacional."
  [matrix]
  (let [rows (:rows matrix)
        plans (mapv (fn [row]
                      (merge {:player-hash (anonymize-id (:player-id row))
                              :status (:status row)}
                             (import-candidate row)))
                    rows)]
    {:eligible (filterv #(= :eligible (:action %)) plans)
     :await-human (filterv #(= :await-human (:action %)) plans)
     :skip (filterv #(= :skip (:action %)) plans)
     :counts (frequencies (map :action plans))}))
