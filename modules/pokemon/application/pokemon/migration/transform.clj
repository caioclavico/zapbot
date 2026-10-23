(ns pokemon.migration.transform
  "Transforma export Cassandra → IR intermediária auditável.
  Proveniência explícita por snapshot; sourceVersion NÃO identifica saves."
  (:require [pokemon.domain.identities :as id]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.account-shape :as shape]))

(defn- str-keys->kw
  "Converte chaves string de JSON Cassandra para keywords onde útil."
  [m]
  (cond
    (map? m) (into {} (map (fn [[k v]]
                             [(if (string? k) (keyword k) k)
                              (str-keys->kw v)])
                           m))
    (vector? m) (mapv str-keys->kw m)
    :else m))

(def ^:private known-poke-keys
  #{:id-pokemon :nome :especie :species :shiny :shiny? :nivel :level :xp :xp-desde-nivel
    :hp :hp-atual :amizade :ataque :defesa :atq-esp :def-esp :veloc :tipos :raridade
    :golpes :golpes-removidos :historico-golpes :golpes-oferecidos :ofertas-golpes
    :habilidade :imagem :imagem-shiny :status :item :altura :peso :taxa-captura :ivs
    :pronto-em :location :_location})

(defn- normalize-pokemon
  "Normaliza um Pokémon já desembrulhado. Nunca inventa id-pokemon."
  [idx {:keys [pokemon location pronto-em]}]
  (let [p (str-keys->kw pokemon)
        species (or (:nome p) (:especie p) (:species p))
        id-poke (:id-pokemon p)
        loc (or location :equipe)
        legado (into {} (remove (fn [[k _]] (known-poke-keys k)) p))
        ;; Snapshot integral verificável (mapa de origem pós-unwrap, sem inventar campos)
        origin-edn (pr-str (cond-> (into {} p)
                             (some? pronto-em) (assoc :pronto-em pronto-em)
                             true (assoc :location (name loc))))]
    (cond-> {:collection-index (long idx)
             :id-pokemon id-poke
             :species species
             :nome species
             :shiny (boolean (or (:shiny p) (:shiny? p)))
             :nivel (long (or (:nivel p) (:level p) 1))
             :level (long (or (:nivel p) (:level p) 1))
             :xp-desde-nivel (long (or (:xp-desde-nivel p) (:xp p) 0))
             :hp (long (or (:hp p) 0))
             :hp-atual (long (or (:hp-atual p) (:hp p) 0))
             :amizade (long (or (:amizade p) 70))
             :ataque (long (or (:ataque p) 0))
             :defesa (long (or (:defesa p) 0))
             :atq-esp (long (or (:atq-esp p) 0))
             :def-esp (long (or (:def-esp p) 0))
             :veloc (long (or (:veloc p) 0))
             :tipos (vec (or (:tipos p) []))
             :raridade (or (:raridade p) "comum")
             :golpes (vec (or (:golpes p) []))
             :golpes-removidos (vec (or (:golpes-removidos p) []))
             :historico-golpes (or (:historico-golpes p) {})
             :location loc
             :in-party (= loc :equipe)
             :origin-edn origin-edn}
      (nil? id-poke) (assoc :quarantine-flag :missing-id-pokemon)
      (some? (:status p)) (assoc :status (:status p))
      (:habilidade p) (assoc :habilidade (:habilidade p))
      (:imagem p) (assoc :imagem (:imagem p))
      (:imagem-shiny p) (assoc :imagem-shiny (:imagem-shiny p))
      (some? (:item p)) (assoc :item (:item p))
      (number? (:altura p)) (assoc :altura (:altura p))
      (number? (:peso p)) (assoc :peso (:peso p))
      (number? (:taxa-captura p)) (assoc :taxa-captura (:taxa-captura p))
      (or (:golpes-oferecidos p) (:ofertas-golpes p))
      (assoc :golpes-oferecidos (or (:golpes-oferecidos p) (:ofertas-golpes p)))
      (some? pronto-em) (assoc :pronto-em pronto-em)
      (seq legado) (assoc :legado legado))))

(defn- normalize-inventory
  [inv]
  (->> (or inv {})
       (map (fn [[k v]]
              {:item-id (name (if (keyword? k) k (keyword (str k))))
               :qty (long v)}))
       (sort-by :item-id)
       vec))

(defn- save-ir
  [{:keys [chat-id player-id slot provenance treinador loja]}]
  (let [t (str-keys->kw treinador)
        l (str-keys->kw (or loja {}))
        located (shape/pokemon-in-location t)
        buckets (shape/location-counts t)
        pokemon (mapv normalize-pokemon (range) located)
        ambiguous-flags (cond-> []
                          (nil? loja) (conj :loja-ausente-moedas-zero)
                          (some :quarantine-flag pokemon) (conj :pokemon-sem-id))]
    {:chat-id chat-id
     :player-id player-id
     :slot-id slot
     :player-key (id/player-key chat-id player-id)
     :save-key (id/save-key chat-id player-id slot)
     :provenance provenance
     :coins (long (or (:moedas l) 0))
     :inventory (normalize-inventory (:inventario l))
     :expansoes-pokemon-50 (long (or (:expansoes-pokemon-50 l) 0))
     :expansoes-mochila-50 (long (or (:expansoes-mochila-50 l) 0))
     :capacidade-mochila-legada (long (or (:capacidade-mochila l) 50))
     :ativo-index (long (or (:ativo t) 0))
     :vitorias-treinador (long (or (:vitorias-treinador t) 0))
     :pe-ginasios (long (or (:pe-ginasios t) 0))
     :pe-raids (long (or (:pe-raids t) 0))
     :xp-insignias (long (or (:xp-insignias t) 0))
     :xp-missoes (long (or (:xp-missoes t) 0))
     :ginasios (or (:ginasios t) {})
     :sequencia-capturas (long (or (:sequencia-capturas t) 0))
     :maior-sequencia-capturas (long (or (:maior-sequencia-capturas t) 0))
     :buckets buckets
     :pokemon pokemon
     :pokemon-count (count pokemon)
     :shiny-ids (->> pokemon (filter :shiny) (map :id-pokemon) (remove nil?) sort vec)
     :content-hash (reward/payload-hash
                    {:slot slot
                     :coins (or (:moedas l) 0)
                     :inv (:inventario l)
                     :ids (mapv :id-pokemon pokemon)
                     :levels (mapv :nivel pokemon)
                     :shiny (mapv :shiny pokemon)
                     :locations (mapv :location pokemon)})
     :flags ambiguous-flags}))

(defn- quarantine-entry
  [reason data]
  {:reason reason
   :at (str (java.time.Instant/now))
   :data data})

(defn transform-export
  "Produz IR com saves A/B, grupo, quarentena e hashes.
  Nunca descarta ambiguidade silenciosamente."
  [export]
  (let [modules (:modules export)
        treinador (get modules "treinador")
        loja (get modules "loja")
        ginasios (get modules "ginasios")
        active-prov (:active_provenance export)
        chat (:chat-id active-prov)
        player (:player-id active-prov)
        conta-t (get-in treinador [chat player])
        conta-l (get-in loja [chat player])
        quarantine (atom [])
        ;; loja sem treinador em qualquer partição
        _ (doseq [[cid jogadores] (or loja {})
                  [pid conta] jogadores]
            (when-not (get-in treinador [cid pid])
              (swap! quarantine conj
                     (quarantine-entry :loja-sem-treinador
                                       {:chat-id cid :player-id pid
                                        :moedas (get conta "moedas")}))))
        ;; treinador sem loja → flag no save, não quarentena total
        active-save (when conta-t
                      (save-ir {:chat-id chat
                                :player-id player
                                :slot (:target-slot active-prov "a")
                                :provenance active-prov
                                :treinador conta-t
                                :loja conta-l}))
        _ (when-not conta-t
            (swap! quarantine conj
                   (quarantine-entry :treinador-ativo-ausente
                                     {:chat-id chat :player-id player})))
        hist-saves
        (for [snap (:historical_snapshots export)]
          (let [src-ver (get-in snap [:source :sourceVersion])
                ;; Qualquer sourceVersion é aviso: NÃO é identidade dos dois históricos
                _ (when src-ver
                    (swap! quarantine conj
                           (quarantine-entry :sourceVersion-ignorado-como-identidade
                                             {:snapshot-id (:snapshot-id snap)
                                              :kind (:kind snap)
                                              :sourceVersion src-ver
                                              :hint "Identidade = snapshot-id + kind explícitos"})))
                slot (:target-slot snap)
                _ (when (and active-save (= slot (:slot-id active-save)))
                    (swap! quarantine conj
                           (quarantine-entry :slot-conflito-ativo-historico
                                             {:slot slot
                                              :active (:snapshot-id active-prov)
                                              :hist (:snapshot-id snap)})))]
            (save-ir {:chat-id (:chat-id snap)
                      :player-id (:player-id snap)
                      :slot slot
                      :provenance (dissoc snap :treinador :loja)
                      :treinador (:treinador snap)
                      :loja (:loja snap)})))
        saves (vec (remove nil? (cons active-save hist-saves)))
        ;; Duplicidade de player-key com hashes distintos
        by-pk (group-by :player-key saves)
        _ (doseq [[pk group] by-pk]
            (when (< 1 (count (distinct (map :slot-id group))))
              ;; ok A/B
              nil)
            (let [same-slot (group-by :slot-id group)]
              (doseq [[slot xs] same-slot]
                (when (< 1 (count xs))
                  (swap! quarantine conj
                         (quarantine-entry :save-duplicado-mesmo-slot
                                           {:player-key pk :slot slot
                                            :hashes (mapv :content-hash xs)}))))))
        ir {:schema-version "m5.0-ir-v1"
            :meta (:meta export)
            :source-kind (:source-kind export)
            :player-key (when (and chat player) (id/player-key chat player))
            :chat-id chat
            :player-id player
            :saves saves
            :group {:chat-id chat
                    :label (get-in export [:group :label])
                    :ginasios (get ginasios chat)}
            :descobertas (get modules "pokemon-descobertas")
            :quarantine @quarantine
            :ir-hash (reward/payload-hash
                      {:saves (mapv #(select-keys % [:slot-id :content-hash :pokemon-count])
                                    saves)
                       :q (count @quarantine)})}]
    ir))

(defn snapshot->save-ir
  "Converte um snapshot histórico isolado em um IR de save.
   Usado pela recuperação seletiva; não consulta rede nem altera o banco."
  [{:keys [chat-id player-id target-slot snapshot-id] :as snapshot}
   {:keys [slot-id] :or {slot-id target-slot}}]
  (when-not (and chat-id player-id snapshot-id slot-id)
    (throw (ex-info "recovery_snapshot_identity_incomplete"
                    {:required [:chat-id :player-id :snapshot-id :slot-id]
                     :snapshot (select-keys snapshot [:chat-id :player-id :snapshot-id :target-slot])})))
  (save-ir {:chat-id chat-id
            :player-id player-id
            :slot slot-id
            :provenance (dissoc snapshot :treinador :loja)
            :treinador (:treinador snapshot)
            :loja (:loja snapshot)}))

(defn write-ir!
  [ir path]
  (clojure.java.io/make-parents path)
  (spit path (pr-str ir))
  path)
