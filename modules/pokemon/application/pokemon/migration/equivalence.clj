(ns pokemon.migration.equivalence
  "Conferência campo a campo IR × Datomic (além de contagens/hashes)."
  (:require [clojure.data :as data]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.reward :as reward]))

(defn- inv-index
  [items]
  (into (sorted-map)
        (map (fn [{:keys [item-id qty]}] [item-id (long qty)]) items)))

(defn- poke-view
  [p]
  {:id-pokemon (:id-pokemon p)
   :species (or (:species p) (:nome p))
   :shiny (boolean (:shiny p))
   :nivel (long (or (:nivel p) (:level p) 0))
   :xp-desde-nivel (long (or (:xp-desde-nivel p) (:xp p) 0))
   :hp (long (:hp p 0))
   :hp-atual (long (:hp-atual p 0))
   :amizade (long (:amizade p 70))
   :ataque (long (:ataque p 0))
   :defesa (long (:defesa p 0))
   :atq-esp (long (:atq-esp p 0))
   :def-esp (long (:def-esp p 0))
   :veloc (long (:veloc p 0))
   :raridade (or (:raridade p) "comum")
   :collection-index (long (:collection-index p -1))})

(defn- expected-from-ir
  [save-ir]
  {:slot-id (:slot-id save-ir)
   :coins (long (:coins save-ir))
   :vitorias-treinador (long (:vitorias-treinador save-ir))
   :pe-ginasios (long (:pe-ginasios save-ir))
   :pe-raids (long (:pe-raids save-ir))
   :xp-insignias (long (:xp-insignias save-ir))
   :xp-missoes (long (:xp-missoes save-ir))
   :ativo-index (long (:ativo-index save-ir))
   :expansoes-pokemon-50 (long (:expansoes-pokemon-50 save-ir))
   :expansoes-mochila-50 (long (:expansoes-mochila-50 save-ir))
   :inventory (inv-index (:inventory save-ir))
   :ginasios (:ginasios save-ir)
   :pokemon-count (:pokemon-count save-ir)
   :pokemon (mapv poke-view (sort-by :collection-index (:pokemon save-ir)))
   :shiny-ids (vec (sort (:shiny-ids save-ir)))
   :content-hash (:content-hash save-ir)
   :provenance (:provenance save-ir)})

(defn- actual-from-store
  [store chat-id player-id slot-id]
  (let [snap (trainer/snapshot-save store chat-id player-id slot-id)
        col (ports/list-collection store chat-id player-id slot-id)
        save (:save snap)]
    {:slot-id slot-id
     :coins (long (:coins save 0))
     :vitorias-treinador (long (:vitorias-treinador save 0))
     :pe-ginasios (long (:pe-ginasios save 0))
     :pe-raids (long (:pe-raids save 0))
     :xp-insignias (long (:xp-insignias save 0))
     :xp-missoes (long (:xp-missoes save 0))
     :ativo-index (long (:ativo-index save 0))
     :expansoes-pokemon-50 (long (:expansoes-pokemon-50 save 0))
     :expansoes-mochila-50 (long (:expansoes-mochila-50 save 0))
     :inventory (inv-index (:inventory snap))
     :ginasios (:ginasios save)
     :pokemon-count (count col)
     :pokemon (mapv poke-view col)
     :shiny-ids (->> col (filter :shiny) (map :id-pokemon) sort vec)}))

(defn- field-diffs
  [expected actual]
  (let [keys-cmp [:coins :vitorias-treinador :pe-ginasios :pe-raids
                  :xp-insignias :xp-missoes :ativo-index
                  :expansoes-pokemon-50 :expansoes-mochila-50
                  :inventory :ginasios :pokemon-count :shiny-ids]
        scalar (->> keys-cmp
                    (keep (fn [k]
                            (when (not= (get expected k) (get actual k))
                              {:field k
                               :expected (get expected k)
                               :actual (get actual k)})))
                    vec)
        [only-e only-a both] (data/diff (:pokemon expected) (:pokemon actual))
        poke-diff (when (or only-e only-a)
                    {:field :pokemon
                     :only-in-expected only-e
                     :only-in-actual only-a
                     :note "diff estrutural; ver índices"})]
    (cond-> scalar
      poke-diff (conj poke-diff))))

(defn compare-save
  [store save-ir]
  (let [exp (expected-from-ir save-ir)
        act (actual-from-store store (:chat-id save-ir) (:player-id save-ir) (:slot-id save-ir))
        diffs (field-diffs exp act)
        ok? (empty? diffs)]
    {:slot-id (:slot-id save-ir)
     :ok? ok?
     :expected-count (:pokemon-count exp)
     :actual-count (:pokemon-count act)
     :expected-hash (:content-hash save-ir)
     :diffs diffs
     :provenance (:provenance save-ir)}))

(defn compare-ir
  [store ir]
  (let [per-save (mapv #(compare-save store %) (:saves ir))
        active (ports/active-save-id store (:chat-id ir) (:player-id ir))
        ok? (every? :ok? per-save)]
    {:ok? ok?
     :player-key (:player-key ir)
     :active-save-id active
     :saves per-save
     :quarantine-count (count (:quarantine ir))
     :summary-hash (reward/payload-hash
                    {:ok ok?
                     :slots (mapv #(select-keys % [:slot-id :ok? :expected-count :actual-count])
                                  per-save)})}))
