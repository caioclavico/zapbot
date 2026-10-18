(ns pokemon.migration.inventory
  "Inventário somente leitura de dumps Cassandra offline (EDN/JSON).
   Nunca conecta a rede; recusa paths/meta de produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.export :as export]
            [pokemon.migration.profiles :as profiles]))

(defn- assert-offline-safe!
  [dump path]
  (when path
    (let [p (str path)]
      (when (re-find #"(?i)prod|oracle|odisseu-live|production|gcp" p)
        (throw (ex-info "inventory_path_blocked"
                        {:path p :reason "caminho suspeito de produção"})))))
  (when-not (or (get-in dump [:meta :synthetic])
                (get-in dump [:meta :offline-dump])
                (true? (get-in dump [:meta :authorized-offline-copy])))
    (throw (ex-info "inventory_requires_offline_meta"
                    {:hint "Exiga :meta {:synthetic true} | {:offline-dump true} | {:authorized-offline-copy true}"})))
  dump)

(defn- pokemon-list
  "Contagem canônica alinhada ao restore-protocol: equipe + pc + enfermaria."
  [account]
  (let [a (if (map? account) account {})
        getv (fn [k]
               (or (get a k)
                   (get a (name k))
                   (get a (keyword k))
                   []))]
    (vec (concat (or (getv :equipe) (getv "equipe") [])
                 (or (getv :pc) (getv "pc") [])
                 (or (getv :enfermaria) (getv "enfermaria") [])))))

(defn- poke-field
  [p k]
  (or (get p k)
      (get p (name k))
      (get p (keyword (name k)))))

(defn- summarize-pokemon
  [pokes]
  (let [norm (map (fn [p]
                    (let [species (profiles/normalize-species
                                   (or (poke-field p :nome)
                                       (poke-field p :especie)
                                       (poke-field p :species)))
                          shiny? (boolean (or (poke-field p :shiny)
                                              (poke-field p :shiny?)))
                          id (poke-field p :id-pokemon)]
                      {:id-pokemon id
                       :species species
                       :shiny shiny?
                       :nivel (long (or (poke-field p :nivel)
                                        (poke-field p :level)
                                        0))
                       :xp-desde-nivel (long (or (poke-field p :xp-desde-nivel)
                                                 (poke-field p :xp)
                                                 0))}))
                  pokes)
        shinies (filter :shiny norm)]
    {:pokemon-count (count norm)
     :levels (->> norm (map :nivel) sort vec)
     :level-sum (reduce + 0 (map :nivel norm))
     :xp-sum (reduce + 0 (map :xp-desde-nivel norm))
     :shiny-count (count shinies)
     :shiny-species (->> shinies (map :species) set)
     :shiny-ids (->> shinies (map :id-pokemon) (remove nil?) sort vec)
     :ids (->> norm (map :id-pokemon) (remove nil?) sort vec)
     :missing-id-count (count (filter #(nil? (:id-pokemon %)) norm))
     :missing-species-count (count (filter #(nil? (:species %)) norm))}))

(defn- account-progress
  [account]
  (let [a (or account {})
        g (fn [k] (or (get a k) (get a (name k))))]
    {:vitorias-treinador (long (or (g "vitorias-treinador") (g :vitorias-treinador) 0))
     :pe-ginasios (long (or (g "pe-ginasios") (g :pe-ginasios) 0))
     :pe-raids (long (or (g "pe-raids") (g :pe-raids) 0))
     :xp-insignias (long (or (g "xp-insignias") (g :xp-insignias) 0))
     :xp-missoes (long (or (g "xp-missoes") (g :xp-missoes) 0))
     :ginasios (or (g "ginasios") (g :ginasios) {})}))

(defn- loja-summary
  [loja-conta]
  (let [l (or loja-conta {})]
    {:moedas (long (or (get l "moedas") (get l :moedas) 0))
     :inventario (or (get l "inventario") (get l :inventario) {})
     :expansoes-pokemon-50 (long (or (get l "expansoes-pokemon-50")
                                     (get l :expansoes-pokemon-50) 0))
     :expansoes-mochila-50 (long (or (get l "expansoes-mochila-50")
                                     (get l :expansoes-mochila-50) 0))}))

(defn- candidate
  [{:keys [origin snapshot-id slot-id chat-id player-id created-at
           source source-version content-hash account loja]}]
  (let [pokes (pokemon-list account)
        poke-sum (summarize-pokemon pokes)
        progress (account-progress account)
        shop (loja-summary loja)]
    (merge
     {:candidate-id (or snapshot-id
                        (str origin "|" (or slot-id "live") "|" chat-id "|" player-id))
      :origin origin
      :snapshot-id snapshot-id
      :slot-id slot-id
      :chat-id chat-id
      :player-id player-id
      :created-at created-at
      :source source
      :source-version source-version
      :content-hash (or content-hash
                        (reward/payload-hash
                         {:ids (:ids poke-sum)
                          :shiny (:shiny-ids poke-sum)
                          :coins (:moedas shop)}))
      :sourceVersion-not-identity true}
     poke-sum
     progress
     shop)))

(defn- rows-by-module
  [dump]
  (reduce (fn [acc row]
            (let [m (or (:modulo row) (get row "modulo"))
                  p (or (:particao row) (get row "particao"))
                  v (or (:valor row) (get row "valor"))]
              (assoc-in acc [m p] v)))
          {}
          (or (:estado_particionado dump) [])))

(defn- scan-live-treinador
  [modules]
  (let [treinador (get modules "treinador")
        loja (get modules "loja")]
    (vec
     (for [[chat players] (or treinador {})
           [player account] players]
       (candidate {:origin :treinador-live
                   :snapshot-id (str "live:" chat "|" player)
                   :slot-id nil
                   :chat-id chat
                   :player-id player
                   :created-at nil
                   :source "treinador"
                   :source-version nil
                   :account account
                   :loja (get-in loja [chat player])})))))

(defn- scan-save-slot-envelopes
  "Lê envelopes no formato Etapa 1 (pokemon-save-slots)."
  [modules]
  (let [slots (get modules "pokemon-save-slots")]
    (vec
     (for [[partition envelope] (or slots {})
           :let [meta (or (:metadata envelope) (get envelope "metadata"))
                 account (or (:account envelope) (get envelope "account"))
                 slot-id (or (:slotId envelope) (get envelope "slotId")
                             (:slot-id envelope))
                 ;; loja pode estar embutida na conta histórica ou ausente
                 loja-emb (or (get account "loja") (get account :loja)
                              {"moedas" (or (get account "moedas")
                                            (get account :moedas)
                                            0)
                               "inventario" (or (get account "inventario")
                                                (get account :inventario)
                                                {})})]]
       (candidate {:origin :pokemon-save-slots
                   :snapshot-id (str "slot:" partition)
                   :slot-id slot-id
                   :chat-id nil ;; identidade hasheada no Cassandra; dump offline pode trazer overlay
                   :player-id nil
                   :created-at (or (:createdAt meta) (get meta "createdAt"))
                   :source (or (:source meta) (get meta "source"))
                   :source-version (or (:sourceVersion meta) (get meta "sourceVersion"))
                   :content-hash (or (:contentHash meta) (get meta "contentHash"))
                   :account account
                   :loja loja-emb})))))

(defn- scan-historical-overlays
  "Envelopes explícitos do harness M5.0/M5.1 (com chat/player em claro)."
  [dump]
  (vec
   (for [snap (or (:historical_snapshots dump) [])]
     (candidate {:origin :historical-envelope
                 :snapshot-id (:snapshot-id snap)
                 :slot-id (:target-slot snap)
                 :chat-id (:chat-id snap)
                 :player-id (:player-id snap)
                 :created-at (:created-at snap)
                 :source (get-in snap [:source :module])
                 :source-version (get-in snap [:source :sourceVersion])
                 :account (:treinador snap)
                 :loja (:loja snap)}))))

(defn- scan-active-overlay
  [dump modules]
  (when-let [prov (:active_provenance dump)]
    (let [chat (:chat-id prov)
          player (:player-id prov)
          account (get-in modules ["treinador" chat player])
          loja (get-in modules ["loja" chat player])]
      (when account
        [(candidate {:origin :active-provenance
                     :snapshot-id (:snapshot-id prov)
                     :slot-id (:target-slot prov)
                     :chat-id chat
                     :player-id player
                     :source (get-in prov [:source :module])
                     :account account
                     :loja loja})]))))

(defn inventory-dump
  "Inventário completo de candidatos (somente leitura)."
  [dump & {:keys [path]}]
  (assert-offline-safe! dump path)
  (let [modules (rows-by-module dump)
        candidates (vec
                    (concat (scan-live-treinador modules)
                            (scan-save-slot-envelopes modules)
                            (scan-historical-overlays dump)
                            (or (scan-active-overlay dump modules) [])))
        ;; Dedup por content-hash + pokemon-count quando ids iguais
        by-id (group-by :candidate-id candidates)]
    {:meta (:meta dump)
     :candidate-count (count candidates)
     :candidates candidates
     :duplicate-candidate-ids
     (->> by-id
          (filter (fn [[_ xs]] (> (count xs) 1)))
          (map first)
          vec)
     :warnings
     (cond-> []
       (seq (filter #(and (:source-version %)
                          (contains? #{"1" "2"} (str (:source-version %))))
                    candidates))
       (conj {:code :sourceVersion-present
              :hint "sourceVersion 1/2 não identifica os históricos; use contagens/shiny/hashes."}))}))

(defn inventory-path
  [path]
  (inventory-dump (export/load-edn-file path) :path path))

(defn write-inventory!
  [inv out-path]
  (io/make-parents out-path)
  (spit out-path (pr-str inv))
  out-path)
