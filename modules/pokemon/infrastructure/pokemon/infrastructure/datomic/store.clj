(ns pokemon.infrastructure.datomic.store
  "Adapter Datomic Local para TrainerStore.
  Efeitos externos (WhatsApp, HTTP, timers) NÃO entram nas transações."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.battle-idle :as battle-idle]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.evolution :as evo]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.onboarding :as onboard]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.pvp :as pvp]
            [pokemon.domain.raid :as raid-domain]
            [pokemon.domain.raid-auto :as raid-auto]
            [pokemon.domain.raid-boss :as raid-boss]
            [pokemon.infrastructure.datomic.raid-auto-log :as raid-auto-log]
            [pokemon.domain.reward :as reward]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.xp :as xp]
            [pokemon.domain.status :as status]
            [pokemon.application.mission-queries :as mission-queries]
            [pokemon.application.mission-economy :as mission-economy-app]
            [pokemon.infrastructure.datomic.mission-queries :as mission-reads]
            [pokemon.infrastructure.datomic.mission-events :as mission-events]
            [pokemon.infrastructure.datomic.mission-economy :as mission-economy]
            [pokemon.infrastructure.datomic.account-provisioning :as provisioning]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]
            [pokemon.infrastructure.datomic.battle-idle-store :as battle-idle-store]
            [pokemon.infrastructure.datomic.battle-leave-store :as battle-leave-store]
            [pokemon.infrastructure.datomic.gym-store :as gym-store]
            [pokemon.infrastructure.datomic.onboarding-store :as onboarding-store]
            [pokemon.infrastructure.datomic.bag-store :as bag-store]
            [pokemon.infrastructure.datomic.pvp-store :as pvp-store]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]
            [pokemon.infrastructure.datomic.shop-store :as shop-store]
            [pokemon.infrastructure.datomic.economy-store :as economy-store]
            [pokemon.infrastructure.datomic.trade-store :as trade-store]
            [pokemon.infrastructure.datomic.profile-store :as profile-store]
            [pokemon.infrastructure.datomic.schema :as schema]))

(defn- normalize-team-name
  [value]
  (-> (str value)
      str/lower-case
      (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
      (str/replace #"[\\u0300-\\u036f]" "")
      str/trim))

(def ^:private max-cas-attempts 12)

(defn- anomaly-conflict?
  [ex]
  (err/conflict? ex))

(defn- value-too-large?
  "Datomic Local rejeita valores de atributo ~≥4096 com 'Item too large'.
   Não é conflito de CAS/fence — não deve virar :writer_fence_conflict."
  [ex]
  (boolean (re-find #"(?i)item too large|value too large"
                    (str (ex-message ex) " " (ex-data ex)))))

(defn- player-entity
  [db chat-id player-id]
  (d/pull db '[*] [:player/key (id/player-key chat-id player-id)]))

(defn- save-entity
  [db chat-id player-id save-id]
  (d/pull db '[*] [:save/key (id/save-key chat-id player-id save-id)]))

(defn- save-entities
  [db chat-id player-id]
  (->> (d/q '[:find (pull ?s [*])
              :in $ ?pk
              :where
              [?p :player/key ?pk]
              [?s :save/player ?p]]
            db (id/player-key chat-id player-id))
       (map first)
       (sort-by :save/slot-id)
       vec))

(defn- present?
  [entity]
  (boolean (:db/id entity)))

(defn- edn-read
  "Lê EDN de attrs; battle/state-edn e team-edn podem vir compactados (odisseu.gz;)."
  [s]
  (battle/decode-compact-edn s))

(defn- ->player
  [e]
  (when (present? e)
    {:chat-id (:player/chat-id e)
     :player-id (:player/player-id e)
     :player-key (:player/key e)
     :active-save-id (:player/active-save-id e)
     :fence-generation (:player/fence-generation e)
     :active-battle-id (:player/active-battle-id e)
     :gym-defense-count (long (:player/gym-defense-count e 0))}))

(defn- battle-entity
  [db battle-id]
  (d/pull db '[*] [:battle/id battle-id]))

(defn- active-battle-id-for
  [db chat-id player-id]
  (:player/active-battle-id (player-entity db chat-id player-id)))

(defn- ->save
  [e]
  (when (present? e)
    (let [base {:save-key (:save/key e)
                :slot-id (:save/slot-id e)
                :display-name (:save/display-name e)
                :origin (:save/origin e)
                :origin-player-id (:save/origin-player-id e)
                :legacy-version (:save/legacy-version e)
                :importer-version (:save/importer-version e)
                :import-id (:save/import-id e)
                :import-status (:save/import-status e)
                :imported-at (:save/imported-at e)
                :original-record-ids (edn-read (:save/original-record-ids e))
                :last-rename-operation-id (:save/last-rename-operation-id e)
                :last-rename-name (:save/last-rename-name e)
                :coins (:save/coins e 0)
                :revision (:save/revision e 0)
                :ativo-index (:save/ativo-index e 0)
                :vitorias-treinador (:save/vitorias-treinador e 0)
                :pe-ginasios (:save/pe-ginasios e 0)
                :pe-raids (:save/pe-raids e 0)
                :xp-missoes (:save/xp-missoes e 0)
                :doacoes-pokemon (long (:save/doacoes-pokemon e 0))
                :economy-status (:save/economy-status e)
                :coins-operational (:save/coins-operational e)
                :coins-operational-earned (:save/coins-operational-earned e)
                :coins-operational-spent (:save/coins-operational-spent e)
                :coins-historical-status (:save/coins-historical-status e)
                :coins-historical-amount (:save/coins-historical-amount e)}
          maior-seq (long (:save/maior-sequencia-capturas e 0))
          ;; Master deriva xp-insignias dos marcos; não usa campo estático solto.
          xp-insignias (xp/xp-insignias-from-metrics
                        {:doacoes-pokemon (:doacoes-pokemon base)
                         :vitorias-treinador (:vitorias-treinador base)
                         :maior-sequencia-capturas maior-seq})
          eco (economy-store/economia-mapa base)
          base (assoc base
                      :xp-insignias xp-insignias
                      :coins-spendable (:coins-spendable eco)
                      :economia-confiavel (:economia-confiavel eco)
                      :coins-status (:coins-status eco))
          ;; Leituras: saldo exibido comprovado = spendable (não vaza placeholder).
          base (assoc base :coins (:coins-spendable eco))
          computed (xp/xp-treinador-total base)
          ;; M3.1: componentes são a fonte. M2 apply-reward ainda grava
          ;; :save/trainer-xp|level direto quando não há vitórias/PE.
          stored-xp (long (:save/trainer-xp e 0))
          stored-level (long (:save/trainer-level e 1))
          xp (if (pos? computed) computed stored-xp)
          perfil (xp/progresso-treinador xp)
          nivel (if (pos? computed) (:nivel perfil) stored-level)]
      (assoc base
             :trainer-xp xp
             :trainer-level nivel
             :xp-atual (:xp-atual perfil)
             :xp-necessario (:xp-necessario perfil)
             :sequencia-capturas (long (:save/sequencia-capturas e 0))
             :maior-sequencia-capturas maior-seq
             :expansoes-pokemon-50 (long (:save/expansoes-pokemon-50 e 0))
             :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada e 0))
             :expansoes-mochila-50 (long (:save/expansoes-mochila-50 e 0))
             :capacidade-mochila-legada (long (:save/capacidade-mochila-legada e 50))
             :ultima-cacada-at (long (:save/ultima-cacada-at e 0))
             :kit-inicial-resgatado (boolean (:save/kit-inicial-resgatado e))
             :ultimo-bonus-diario (:save/ultimo-bonus-diario e)
             :sequencia-bonus-diario (long (:save/sequencia-bonus-diario e 0))
             :inicial-escolhido (boolean (:save/inicial-escolhido e))
             :favorito-id-pokemon (:save/favorito-id-pokemon e)
             :title (:save/title e)
             :ginasios (or (edn-read (:save/ginasios-edn e)) {})
             :time-ginasio (or (edn-read (:save/time-ginasio-edn e)) [])
             :times-prontos (or (edn-read (:save/times-prontos-edn e)) {})
             :time-liga (or (edn-read (:save/time-liga-edn e)) [])
             :liga-id (:save/liga-id e)))))

(defn- ->battle
  [e]
  (when (present? e)
    (let [b (merge (or (edn-read (:battle/state-edn e)) {})
                   (battle-idle-store/idle-fields-from-entity e)
                   {:battle-id (:battle/id e)
                    :kind (keyword (:battle/kind e "wild"))
                    :active? (boolean (:battle/active? e))
                    :turn-index (long (:battle/turn-index e 0))
                    :phase (keyword (:battle/phase e "active"))
                    :save-id (:battle/save-id e)
                    :collection-index (:battle/collection-index e 0)})]
      (if (= :pvp (:kind b))
        (pvp/normalize-battle-sides b)
        b))))

(defn- open-battle-id-fallback
  "Se o ponteiro active-battle-id sumiu mas ainda há batalha aberta do jogador.
  Usa :find relacional — Client API rejeita collection find [?id ...]."
  [db chat-id player-id]
  (let [pk (id/player-key chat-id player-id)
        ids (mapv first
                  (d/q '[:find ?id
                         :in $ ?pk
                         :where
                         [?e :battle/player-key ?pk]
                         [?e :battle/id ?id]]
                       db pk))]
    (->> ids
         (keep (fn [id]
                 (let [b (->battle (battle-entity db id))]
                   (when (and b (or (:active? b)
                                    (:aguardando-captura? b)
                                    (pvp/aguardando? b)))
                     id))))
         first)))

(defn- assert-no-busy-battle!
  "Bloqueia mutações de time/golpes/evolução com caça ou batalha ativa."
  [db chat-id player-id]
  (when-let [bid (active-battle-id-for db chat-id player-id)]
    (let [b (battle-entity db bid)
          st (when (present? b) (->battle b))]
      (when (and st (or (:active? st) (:aguardando-captura? st)))
        (throw (err/error :battle_busy {:battle-id bid}))))))

(defn- ->pokemon
  [e]
  (when (present? e)
    (cond-> {:pokemon-key (:pokemon/key e)
             :id-pokemon (:pokemon/id-pokemon e)
             :species (:pokemon/species e)
             :nome (or (:pokemon/nickname e) (:pokemon/species e))
             :nickname (:pokemon/nickname e)
             :shiny (boolean (:pokemon/shiny e))
             :xp (:pokemon/xp e)
             :level (:pokemon/level e)
             :nivel (:pokemon/level e)
             :in-party (boolean (:pokemon/in-party e))
             :location (or (:pokemon/location e)
                           (when (:pokemon/in-party e) "equipe")
                           "equipe")
             :collection-index (:pokemon/collection-index e)
             :hp (:pokemon/hp e)
             :hp-atual (:pokemon/hp-atual e)
             :status (:pokemon/status e)
             :xp-desde-nivel (:pokemon/xp-desde-nivel e 0)
             :amizade (:pokemon/amizade e 70)
             :ataque (:pokemon/ataque e 0)
             :defesa (:pokemon/defesa e 0)
             :atq-esp (:pokemon/atq-esp e 0)
             :def-esp (:pokemon/def-esp e 0)
             :veloc (:pokemon/veloc e 0)
             :tipos (vec (:pokemon/tipos e))
             :raridade (:pokemon/raridade e "comum")
             :golpes (vec (or (edn-read (:pokemon/golpes-edn e)) []))
             :golpes-removidos (vec (or (edn-read (:pokemon/golpes-removidos-edn e)) []))
             :historico-golpes (or (edn-read (:pokemon/historico-golpes-edn e)) {})}
      (:pokemon/habilidade e) (assoc :habilidade (:pokemon/habilidade e))
      (:pokemon/imagem e) (assoc :imagem (:pokemon/imagem e))
      (:pokemon/imagem-shiny e) (assoc :imagem-shiny (:pokemon/imagem-shiny e))
      (:pokemon/ivs-edn e) (assoc :ivs (edn-read (:pokemon/ivs-edn e)))
      (:pokemon/ofertas-golpes-edn e)
      (assoc :golpes-oferecidos (edn-read (:pokemon/ofertas-golpes-edn e))
             :ofertas-golpes (edn-read (:pokemon/ofertas-golpes-edn e)))
      (some? (:pokemon/pronto-em e)) (assoc :pronto-em (:pokemon/pronto-em e))
      (:pokemon/item e) (assoc :item (:pokemon/item e))
      (some? (:pokemon/altura e)) (assoc :altura (:pokemon/altura e))
      (some? (:pokemon/peso e)) (assoc :peso (:pokemon/peso e))
      (some? (:pokemon/taxa-captura e)) (assoc :taxa-captura (:pokemon/taxa-captura e))
      (:pokemon/legado-edn e) (assoc :legado (edn-read (:pokemon/legado-edn e)))
      (:pokemon/origin-edn e) (assoc :origin-edn (:pokemon/origin-edn e)))))

(defn- pokemon-entity-map
  "Mapa de atributos Datomic a partir do registro de domínio.
   Joy collect / readmit: location=equipe; não copia pronto-em."
  [sk registro idx]
  (let [id-poke (:id-pokemon registro)
        poke-key (or id-poke (str sk "|poke|" idx))]
    (cond-> {:pokemon/key poke-key
             :pokemon/id-pokemon id-poke
             :pokemon/save [:save/key sk]
             :pokemon/species (or (:species registro) (:nome registro))
             :pokemon/nickname (or (:nome registro) (:nickname registro)
                                   (:species registro))
             :pokemon/shiny (boolean (:shiny registro))
             :pokemon/xp (long (:xp-desde-nivel registro 0))
             :pokemon/level (long (or (:nivel registro) (:level registro) 1))
             :pokemon/in-party true
             :pokemon/location "equipe"
             :pokemon/collection-index idx
             :pokemon/hp (long (:hp registro 0))
             :pokemon/hp-atual (long (:hp-atual registro 0))
             :pokemon/xp-desde-nivel (long (:xp-desde-nivel registro 0))
             :pokemon/amizade (long (:amizade registro 70))
             :pokemon/ataque (long (:ataque registro 0))
             :pokemon/defesa (long (:defesa registro 0))
             :pokemon/atq-esp (long (:atq-esp registro 0))
             :pokemon/def-esp (long (:def-esp registro 0))
             :pokemon/veloc (long (:veloc registro 0))
             :pokemon/raridade (or (:raridade registro) "comum")
             :pokemon/golpes-edn (pr-str (or (:golpes registro) []))}
      (seq (:tipos registro)) (assoc :pokemon/tipos (vec (:tipos registro)))
      (:status registro) (assoc :pokemon/status (:status registro))
      (:habilidade registro) (assoc :pokemon/habilidade (:habilidade registro))
      (:imagem registro) (assoc :pokemon/imagem (:imagem registro))
      (:imagem-shiny registro) (assoc :pokemon/imagem-shiny (:imagem-shiny registro))
      (seq (:golpes-removidos registro))
      (assoc :pokemon/golpes-removidos-edn (pr-str (:golpes-removidos registro)))
      (seq (:historico-golpes registro))
      (assoc :pokemon/historico-golpes-edn (pr-str (:historico-golpes registro)))
      (:ivs registro) (assoc :pokemon/ivs-edn (pr-str (:ivs registro)))
      (:origin-edn registro) (assoc :pokemon/origin-edn (:origin-edn registro))
      (seq (:legado registro)) (assoc :pokemon/legado-edn (pr-str (:legado registro)))
      (number? (:altura registro)) (assoc :pokemon/altura (double (:altura registro)))
      (number? (:peso registro)) (assoc :pokemon/peso (double (:peso registro)))
      (number? (:taxa-captura registro))
      (assoc :pokemon/taxa-captura (long (:taxa-captura registro)))
      (and (contains? registro :item) (some? (:item registro)))
      (assoc :pokemon/item (str (:item registro))))))

(defn- inventory-adjust-tx
  "Retorna tx-data para delta de item; lança se qty ficaria negativa."
  [db sk item-id delta]
  (let [ik (id/inventory-item-key sk item-id)
        cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])
        old-qty (long (:inventory-item/qty cur 0))
        next-qty (+ old-qty (long delta))]
    (when (neg? next-qty)
      (throw (err/error :insufficient_item
                        {:item-id item-id :qty old-qty :delta delta})))
    [{:inventory-item/key ik
      :inventory-item/save [:save/key sk]
      :inventory-item/item-id item-id
      :inventory-item/qty next-qty}]))

(defn- apply-pokemon-xp-tx
  "TX fragments para conceder XP a um Pokémon (por id-pokemon)."
  [poke quantidade]
  (let [applied (xp/aplicar-xp-pokemon poke quantidade)
        r (:registro applied)
        lookup [:pokemon/id-pokemon (:id-pokemon poke)]]
    {:tx [[:db/add lookup :pokemon/level (long (:nivel r))]
          [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
          [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
          [:db/add lookup :pokemon/hp (long (:hp r))]
          [:db/add lookup :pokemon/hp-atual (long (:hp-atual r))]
          [:db/add lookup :pokemon/ataque (long (:ataque r))]
          [:db/add lookup :pokemon/defesa (long (:defesa r))]
          [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
          [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
          [:db/add lookup :pokemon/veloc (long (:veloc r))]
          [:db/add lookup :pokemon/amizade (long (:amizade r))]]
     :subida (when (pos? (:subidas applied))
               {:nome (:nome applied)
                :nivel (:nivel-final applied)
                :niveis-subidos (:subidas applied)})}))

(defn- pokemon-hp-status-tx
  ([poke hp-atual st]
   (pokemon-hp-status-tx poke hp-atual st (:status poke)))
  ([poke hp-atual st previous-status]
   (when poke
     (let [lookup [:pokemon/id-pokemon (:id-pokemon poke)]
           st-str (xp/status->str st)]
       (cond-> [[:db/add lookup :pokemon/hp-atual (long hp-atual)]]
         st-str (conj [:db/add lookup :pokemon/status st-str])
         (and (nil? st-str) previous-status)
         (conj [:db/retract lookup :pokemon/status previous-status]))))))

(defn- sync-colecao-from-gym-battle
  "Atualiza HP/status em memória a partir do estado 3×3 (antes do occupy)."
  [colecao battle]
  (let [fainted (set (map long (or (:fainted-x battle) #{})))
        active-idx (when-let [i (get-in battle [:indices-ativos :x])] (long i))
        hp-x (long (get-in battle [:hp :x] 0))
        st-x (get-in battle [:status :x])
        defeated? (= :lose (:outcome battle))]
    (mapv (fn [poke]
            (let [idx (long (:collection-index poke))]
              (cond
                ;; Ao perder o ginásio, toda a equipe foi nocauteada. O estado
                ;; canônico não pode manter status residuais de antes da luta.
                defeated?
                (assoc poke :hp-atual 0 :status nil)
                (contains? fainted idx)
                (assoc poke :hp-atual 0 :status nil)
                (= idx active-idx)
                (assoc poke :hp-atual hp-x :status (status/->kw st-x))
                :else poke)))
          colecao)))

(defn- gym-battle-hp-txs
  [colecao battle]
  (let [synced (sync-colecao-from-gym-battle colecao battle)]
    (mapcat (fn [before after]
              (when (or (not= (:hp-atual before) (:hp-atual after))
                        (not= (:status before) (:status after)))
                (pokemon-hp-status-tx after (:hp-atual after) (:status after)
                                        (:status before))))
            colecao
            synced)))

(defn- scale-npc-team
  [candidates gym]
  (mapv (fn [raw]
          (let [base (-> raw
                         (update :golpes #(or % []))
                         (assoc :status nil))
                scaled (gym/escalar-nivel base (:nivel gym))]
            (assoc scaled :hp-atual (:hp scaled))))
        candidates))

(defn- ->item
  [e]
  {:item-id (:inventory-item/item-id e)
   :qty (:inventory-item/qty e)})

(defn- query-party
  [db save-key*]
  (->> (d/q '[:find (pull ?p [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?p :pokemon/save ?s]
              [?p :pokemon/in-party true]]
            db save-key*)
       (map (comp ->pokemon first))
       (sort-by :collection-index)
       vec))

(defn- query-collection
  [db save-key*]
  (->> (d/q '[:find (pull ?p [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?p :pokemon/save ?s]
              [?p :pokemon/collection-index]]
            db save-key*)
       (map (comp ->pokemon first))
       (sort-by :collection-index)
       vec))

(defn- ->pokedex-entry
  [e]
  (when (present? e)
    (cond-> {:species (:pokedex-entry/species e)
             :seen (boolean (:pokedex-entry/seen e))
             :caught (boolean (:pokedex-entry/caught e))
             :seen-count (long (:pokedex-entry/seen-count e 0))
             :caught-count (long (:pokedex-entry/caught-count e 0))
             :max-level (long (:pokedex-entry/max-level e 0))
             :shiny-caught (long (:pokedex-entry/shiny-caught e 0))
             :raridade (or (:pokedex-entry/raridade e) "comum")
             :tipos (vec (:pokedex-entry/tipos e))}
      (:pokedex-entry/species e)
      (assoc :nome (str/capitalize (:pokedex-entry/species e))))))

(defn- query-pokedex
  [db save-key*]
  (->> (d/q '[:find (pull ?e [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?e :pokedex-entry/save ?s]]
            db save-key*)
       (map (comp ->pokedex-entry first))
       (remove nil?)
       (sort-by :species)
       vec))

(defn- pokedex-entity
  [db save-key* species]
  (when (and species (not (str/blank? (str species))))
    (d/pull db '[*] [:pokedex-entry/key (id/pokedex-entry-key save-key* species)])))

(defn- pokedex-seen-tx
  "Upsert avistamento: seen + seen-count."
  [db sk species]
  (let [prev (pokedex-entity db sk species)
        prev-count (long (or (when (present? prev) (:pokedex-entry/seen-count prev)) 0))]
    {:pokedex-entry/key (id/pokedex-entry-key sk species)
     :pokedex-entry/save [:save/key sk]
     :pokedex-entry/species (str species)
     :pokedex-entry/seen true
     :pokedex-entry/seen-count (inc prev-count)}))

(defn- pokedex-caught-tx
  "Upsert captura: seen+caught + contadores/nível/shiny/tipos/raridade."
  [db sk registro]
  (let [species (or (:species registro) (:nome registro))
        prev (pokedex-entity db sk species)
        prev-caught (long (or (when (present? prev) (:pokedex-entry/caught-count prev)) 0))
        prev-seen (long (or (when (present? prev) (:pokedex-entry/seen-count prev)) 0))
        prev-max (long (or (when (present? prev) (:pokedex-entry/max-level prev)) 0))
        prev-shiny (long (or (when (present? prev) (:pokedex-entry/shiny-caught prev)) 0))
        nivel (long (or (:nivel registro) (:level registro) 1))
        shiny? (boolean (or (:shiny registro) (:shiny? registro)))]
    (cond-> {:pokedex-entry/key (id/pokedex-entry-key sk species)
             :pokedex-entry/save [:save/key sk]
             :pokedex-entry/species (str species)
             :pokedex-entry/seen true
             :pokedex-entry/caught true
             :pokedex-entry/seen-count (max prev-seen 1)
             :pokedex-entry/caught-count (inc prev-caught)
             :pokedex-entry/max-level (max prev-max nivel)
             :pokedex-entry/shiny-caught (+ prev-shiny (if shiny? 1 0))
             :pokedex-entry/raridade (or (:raridade registro) "comum")}
      (seq (:tipos registro)) (assoc :pokedex-entry/tipos (vec (:tipos registro))))))

(defn- writer-fence-tx
  "CAS que exige save ativo + fence congelados (operações iniciadas em A
  falham após alternância para B)."
  [chat-id player-id writer-ctx]
  (let [pk (id/player-key chat-id player-id)]
    [[:db/cas [:player/key pk] :player/fence-generation
      (:fence-generation writer-ctx) (:fence-generation writer-ctx)]
     [:db/cas [:player/key pk] :player/active-save-id
      (:save-id writer-ctx) (:save-id writer-ctx)]]))

(defn- transact-with-writer!
  [conn writer-ctx build-tx]
  (let [{:keys [chat-id player-id save-id]} writer-ctx
        mission-instant (mission-events/*clock*)]
    (loop [attempt 0]
      (let [db (d/db conn)
            player (player-entity db chat-id player-id)
            save (save-entity db chat-id player-id save-id)]
        (when-not (present? player)
          (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
        (when-not (present? save)
          (throw (err/error :save_not_found {:save-id save-id})))
        (when (or (not= (:player/active-save-id player) save-id)
                  (not= (:player/fence-generation player) (:fence-generation writer-ctx)))
          (throw (err/error :stale_writer_fence
                            {:expected writer-ctx
                             :active (:player/active-save-id player)
                             :fence (:player/fence-generation player)})))
        (let [built (mission-events/attach db writer-ctx (build-tx db save) mission-instant)
              tx (into (writer-fence-tx chat-id player-id writer-ctx) (:tx built))
              result (try
                       (d/transact conn {:tx-data tx})
                       {:ok true :value (:result built)}
                       (catch Exception ex
                         {:ok false :ex ex}))]
          (if (:ok result)
            (:value result)
            (let [ex (:ex result)]
              (cond
                (value-too-large? ex)
                (throw (err/error :datomic_value_too_large
                                  {:cause (ex-message ex)
                                   :attempt attempt
                                   :writer writer-ctx}))
                (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                (recur (inc attempt))
                ;; Após esgotar retries: se o fence mudou, classifica stale
                ;; (mensagem melhor) em vez de conflitar genérico.
                :else
                (let [db2 (d/db conn)
                      player2 (player-entity db2 chat-id player-id)]
                  (if (or (not= (:player/active-save-id player2) save-id)
                          (not= (:player/fence-generation player2)
                                (:fence-generation writer-ctx)))
                    (throw (err/error :stale_writer_fence
                                      {:expected writer-ctx
                                       :active (:player/active-save-id player2)
                                       :fence (:player/fence-generation player2)
                                       :cause (ex-message ex)
                                       :attempt attempt}))
                    (throw (err/error :writer_fence_conflict
                                      {:cause (ex-message ex)
                                       :attempt attempt
                                       :writer writer-ctx}))))))))))))

(defn- query-inventory
  [db save-key*]
  (->> (d/q '[:find (pull ?i [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?i :inventory-item/save ?s]]
            db save-key*)
       (map (comp ->item first))
       vec))

(defn- receipt-entity
  [db operation-id]
  (d/pull db '[*] [:operation-receipt/id operation-id]))

(defn- ->receipt
  [e]
  (when (present? e)
    {:operation-id (:operation-receipt/id e)
     :kind (:operation-receipt/kind e)
     :channel (:operation-receipt/channel e)
     :payload-hash (:operation-receipt/payload-hash e)
     :player-key (:operation-receipt/player-key e)
     :save-id (:operation-receipt/save-id e)
     :summary (when-let [s (:operation-receipt/summary e)]
                (edn/read-string s))
     :duplicate true}))

(defn- resolve-receipt
  "Idempotência: mesmo hash → duplicate; hash diferente → erro."
  [e expected-hash operation-id]
  (when (present? e)
    (reward/assert-receipt-payload!
     (:operation-receipt/payload-hash e) expected-hash operation-id)
    (->receipt e)))

(defn- resolve-pvp-receipt
  "Recibo PvP duplicado + batalha reidratada do Datomic (para present HTTP)."
  [conn e expected-hash operation-id]
  (when-let [r (resolve-receipt e expected-hash operation-id)]
    (cond-> r
      (get-in r [:summary :battle-id])
      (assoc :battle (->battle (battle-entity (d/db conn) (get-in r [:summary :battle-id]))))
      (get-in r [:summary :rewards])
      (assoc :rewards (get-in r [:summary :rewards])))))

(defn- activate-save!*
  [conn chat-id player-id save-id]
  (model/validate-save-slot! save-id)
  (let [pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)]
    (loop [attempt 0]
      (let [db (d/db conn)
            player (player-entity db chat-id player-id)
            save (save-entity db chat-id player-id save-id)
            _ (when-not (present? player)
                (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
            _ (when-not (present? save)
                (throw (err/error :save_not_found {:save-id save-id})))
            active-bid (:player/active-battle-id player)
            _ (when (and active-bid
                         (not= (:player/active-save-id player) save-id))
                (let [b (battle-entity db active-bid)
                      state (when (present? b) (->battle b))]
                  (when (and state (or (:active? state) (:aguardando-captura? state)))
                    (throw (err/error :battle_pending
                                      {:battle-id active-bid
                                       :save-id (:battle/save-id b)
                                       :aguardando-captura? (boolean (:aguardando-captura? state))})))))
            _ (when (and (pos? (long (:player/gym-defense-count player 0)))
                         (not= (:player/active-save-id player) save-id))
                (throw (err/error :gym_defense_pending
                                  {:count (:player/gym-defense-count player)
                                   :active-save (:player/active-save-id player)})))
            decision (model/assert-activate
                      {:active-save-id (:player/active-save-id player)
                       :fence-generation (:player/fence-generation player)}
                      save-id)]
        (if (= :noop (:action decision))
          {:status :already-active
           :active-save-id save-id
           :fence-generation (:fence-generation decision)}
          (let [expected-fence (long (:expected-fence decision 0))
                next-fence (long (:next-fence decision (inc expected-fence)))
                tx [[:db/cas [:player/key pk] :player/fence-generation
                     expected-fence next-fence]
                    [:db/add [:player/key pk] :player/active-save-id save-id]]
                result (try
                         {:ok true
                          :value (do
                                   (d/transact conn {:tx-data tx})
                                   {:status (if (= :set-initial (:action decision))
                                              :activated :switched)
                                    :active-save-id save-id
                                    :from (:from decision)
                                    :fence-generation next-fence
                                    :save-key sk})}
                         (catch Exception ex
                           {:ok false :ex ex}))]
            (if (:ok result)
              (:value result)
              (let [ex (:ex result)]
                (if (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                  (recur (inc attempt))
                  (throw (err/error :activate_conflict
                                    {:cause (ex-message ex)
                                     :attempt attempt
                                     :target save-id})))))))))))

(defn- reward-tx-data
  [db request save channel payload-hash]
  (let [{:keys [operation-id chat-id player-id save-id
                coins-delta pokemon item]} request
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        xp (long (:save/trainer-xp save 0))
        level (long (:save/trainer-level save 1))
        delta (long (or coins-delta 0))
        xp-delta (long (or (:trainer-xp-delta request) 0))
        coin-part (economy-store/apply-delta-tx
                   {:sk sk :save save :delta delta
                    :operation-id operation-id
                    :source (if (= channel "admin") "admin" "reward")
                    :role "primary"})
        next-xp (+ xp xp-delta)
        next-level (long (or (:trainer-level request) level))
        poke pokemon
        poke-key (or (:key poke) (when poke (str operation-id ":pokemon")))
        item-id (:item-id item)
        item-qty (long (or (:qty item) 0))
        summary (model/reward-summary
                 {:coins-delta delta
                  :pokemon-key poke-key
                  :species (:species poke)
                  :shiny (:shiny poke)
                  :item-id item-id
                  :item-qty item-qty})
        base (into [{:operation-receipt/id operation-id
                     :operation-receipt/kind "reward"
                     :operation-receipt/channel channel
                     :operation-receipt/payload-hash payload-hash
                     :operation-receipt/player-key pk
                     :operation-receipt/save-id save-id
                     :operation-receipt/summary (pr-str summary)}
                    [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                    [:db/add [:save/key sk] :save/trainer-xp next-xp]
                    [:db/add [:save/key sk] :save/trainer-level next-level]]
                   (:tx coin-part))
        with-poke (if-not poke
                    base
                    (conj base
                          {:pokemon/key poke-key
                           :pokemon/save [:save/key sk]
                           :pokemon/species (:species poke)
                           :pokemon/nickname (or (:nickname poke) (:species poke))
                           :pokemon/shiny (boolean (:shiny poke))
                           :pokemon/xp (long (or (:xp poke) 0))
                           :pokemon/level (long (or (:level poke) 1))
                           :pokemon/in-party (boolean (get poke :in-party true))}
                          {:pokedex-entry/key (id/pokedex-entry-key sk (:species poke))
                           :pokedex-entry/save [:save/key sk]
                           :pokedex-entry/species (:species poke)
                           :pokedex-entry/seen true
                           :pokedex-entry/caught true}))
        with-item (if-not (and item-id (not (zero? item-qty)))
                    with-poke
                    (let [ik (id/inventory-item-key sk item-id)
                          cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])
                          old-qty (long (:inventory-item/qty cur 0))
                          next-qty (+ old-qty item-qty)]
                      (when (neg? next-qty)
                        (throw (err/error :insufficient_item
                                          {:item-id item-id :qty old-qty :delta item-qty})))
                      (conj with-poke
                            {:inventory-item/key ik
                             :inventory-item/save [:save/key sk]
                             :inventory-item/item-id item-id
                             :inventory-item/qty next-qty})))]
    {:tx with-item :summary summary}))

(defn- admin-seed-reward!*
  "Seed/harness: sem fence; com payload-hash."
  [conn request]
  (let [payload (reward/reward-payload request)
        phash (reward/payload-hash payload)
        {:keys [operation-id chat-id player-id save-id]} request
        existing (receipt-entity (d/db conn) operation-id)]
    (or (resolve-receipt existing phash operation-id)
        (loop [attempt 0]
          (let [db (d/db conn)
                again (receipt-entity db operation-id)]
            (or (resolve-receipt again phash operation-id)
                (let [save (save-entity db chat-id player-id save-id)
                      _ (when-not (present? save)
                          (throw (err/error :save_not_found {:save-id save-id})))
                      {:keys [tx summary]} (reward-tx-data db request save "admin" phash)
                      result (try
                               (d/transact conn {:tx-data tx})
                               {:ok true
                                :value {:operation-id operation-id
                                        :duplicate false
                                        :channel "admin"
                                        :payload-hash phash
                                        :summary summary
                                        :save (->save (save-entity (d/db conn) chat-id player-id save-id))}}
                               (catch Exception ex
                                 {:ok false :ex ex}))]
                  (if (:ok result)
                    (:value result)
                    (let [ex (:ex result)
                          dup (receipt-entity (d/db conn) operation-id)]
                      (cond
                        (present? dup)
                        (resolve-receipt dup phash operation-id)

                        (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                        (recur (inc attempt))

                        :else
                        (throw (err/error :reward_conflict
                                          {:cause (ex-message ex)
                                           :operation-id operation-id}))))))))))))

(defn- apply-gameplay-reward!*
  "Gameplay: fence+ativo na mesma TX; payload-hash no recibo."
  [conn writer-ctx request]
  (let [full (merge request
                    (select-keys writer-ctx
                                 [:chat-id :player-id :save-id :fence-generation]))
        payload (reward/reward-payload full)
        phash (reward/payload-hash payload)
        operation-id (:operation-id full)
        existing (receipt-entity (d/db conn) operation-id)]
    (or (resolve-receipt existing phash operation-id)
        (try
          (transact-with-writer!
           conn writer-ctx
           (fn [db save]
             (let [{:keys [tx summary]} (reward-tx-data db full save "gameplay" phash)]
               {:tx tx
                :result {:operation-id operation-id
                         :duplicate false
                         :channel "gameplay"
                         :payload-hash phash
                         :summary summary}})))
          (catch Exception ex
            (let [dup (receipt-entity (d/db conn) operation-id)]
              (if (present? dup)
                (resolve-receipt dup phash operation-id)
                (throw ex))))))))

(defn- load-raid
  [db raid-id]
  (let [e (d/pull db '[*] [:raid/id raid-id])]
    (when (present? e)
      (let [parts (->> (d/q '[:find (pull ?p [*])
                              :in $ ?rid
                              :where [?p :raid-participant/raid-id ?rid]]
                            db raid-id)
                       (map (comp #(raid-store/participant->map % edn-read present?)
                                  first))
                       (filter some?)
                       vec)
            caps (->> (d/q '[:find (pull ?x [*])
                             :in $ ?rid
                             :where [?x :raid-capture/raid-id ?rid]]
                           db raid-id)
                      (map (fn [[x]]
                             [(:raid-capture/player-id x)
                              {:tentativas (long (:raid-capture/tentativas x 0))
                               :capturou (boolean (:raid-capture/capturou x))
                               :frambo-preparada
                               (boolean (:raid-capture/frambo-preparada x))}])))]
        (raid-store/raid->map e parts caps edn-read present?)))))

(defn- ensure-chat-raid-pointer!
  "Garante entidade do ponteiro (sem raid-id) para CAS nil→id na abertura."
  [conn chat-id]
  (let [e (d/pull (d/db conn) '[*] [:chat-active-raid/chat-id chat-id])]
    (when-not (present? e)
      (try
        (d/transact conn {:tx-data [{:chat-active-raid/chat-id chat-id}]})
        (catch Exception ex
          (when-not (err/conflict? ex)
            (throw ex)))))))

(defn- expire-raid-lobby-conn!
  "Eager expire de lobby vencido no chat (sem timer em memória)."
  ([conn chat-id agora]
   (expire-raid-lobby-conn! conn chat-id agora nil))
  ([conn chat-id agora announce-texto]
   (let [agora (long (or agora (System/currentTimeMillis)))
         db (d/db conn)
         ptr (d/pull db '[*] [:chat-active-raid/chat-id chat-id])
         raid (when (and (present? ptr) (:chat-active-raid/raid-id ptr))
                (load-raid db (:chat-active-raid/raid-id ptr)))]
     (when raid
       (let [built (raid-store/build-expire-lobby-tx
                    (cond-> {:raid raid :agora agora}
                      announce-texto (assoc :announce-texto announce-texto)))]
         (when (seq (:tx built))
           (try
             (d/transact conn {:tx-data (:tx built)})
             (catch Exception ex
               (when-not (err/conflict? ex) (throw ex)))))
         (or (load-raid (d/db conn) (:raid-id raid))
             (:raid (:result built))))))))

(defn- load-intent
  "Próximo slot planejado (categoria/lembretes) — receipt sem migration."
  [db chat-id]
  (let [e (receipt-entity db (raid-auto/intent-receipt-id chat-id))]
    (when (present? e)
      (let [s (:summary (->receipt e))]
        (when (map? s)
          {:next-at (when-let [n (:next-at s)] (long n))
           :categoria (when-let [c (:categoria s)] (str c))
           :reminders? (boolean (:reminders? s))})))))

(defn- load-cadence
  "Overrides de cadência (intervalos + daily-limit) — receipt opcional."
  [db chat-id]
  (let [e (receipt-entity db (raid-auto/cadence-receipt-id chat-id))]
    (when (present? e)
      (let [s (:summary (->receipt e))]
        (when (map? s)
          (cond-> {}
            (or (:category-intervals s)
                (and (map? s) (contains? s "raro")))
            (assoc :category-intervals
                   (raid-auto/normalize-category-intervals
                    (or (:category-intervals s) s)))
            (some? (:daily-limit s))
            (assoc :daily-limit (long (:daily-limit s)))
            (some? (:special-reserve s))
            (assoc :special-reserve (long (:special-reserve s)))))))))

(defn- enrich-schedule
  "Mescla intent + cadence no mapa da agenda (campos não-schema)."
  [db schedule]
  (when schedule
    (let [chat-id (:chat-id schedule)
          intent (load-intent db chat-id)
          cadence (load-cadence db chat-id)
          intent-match? (and intent
                             (= (long (:next-at schedule 0))
                                (long (:next-at intent 0))))]
      (cond-> schedule
        (:category-intervals cadence)
        (assoc :category-intervals (:category-intervals cadence))
        (some? (:daily-limit cadence))
        (assoc :daily-limit (:daily-limit cadence))
        (some? (:special-reserve cadence))
        (assoc :special-reserve (:special-reserve cadence))
        intent-match?
        (assoc :next-categoria (:categoria intent)
               :next-reminders? (boolean (:reminders? intent)))))))

(defn- auto-spawns-on-day
  "Spawns auto no dia BRT de `agora` → {:count :specials :rows}."
  [db chat-id agora]
  (let [day (raid-auto/day-key-brt agora)
        rows (->> (d/q '[:find ?criada ?chefe
                         :in $ ?chat
                         :where
                         [?e :raid/chat-id ?chat]
                         [?e :raid/origem "auto"]
                         [?e :raid/criada-em ?criada]
                         [?e :raid/chefe-edn ?chefe]]
                       db (str chat-id))
                  (keep (fn [[criada raw]]
                          (when (= day (raid-auto/day-key-brt criada))
                            (let [c (edn-read raw)
                                  cat (str (or (:raridade c) (get c "raridade") "raro"))]
                              {:at (long criada) :categoria cat}))))
                  vec)]
    {:count (count rows)
     :specials (count (filter #(raid-auto/special-category? (:categoria %)) rows))
     :rows rows}))

(defn- load-schedule
  [db chat-id]
  (enrich-schedule
   db
   (raid-store/schedule->map
    (d/pull db '[*] [:raid-auto-schedule/chat-id chat-id])
    present?)))

(defn- last-auto-by-categoria
  "Último criada-em por raridade do chefe (raids auto). Sem migration."
  [db chat-id]
  (->> (d/q '[:find ?criada ?chefe
              :in $ ?chat
              :where
              [?e :raid/chat-id ?chat]
              [?e :raid/origem "auto"]
              [?e :raid/criada-em ?criada]
              [?e :raid/chefe-edn ?chefe]]
            db (str chat-id))
       (reduce
        (fn [acc [criada raw]]
          (let [c (edn-read raw)
                cat (str (or (:raridade c) (get c "raridade") "raro"))
                prev (get acc cat)]
            (if (or (nil? prev) (> (long criada) (long prev)))
              (assoc acc cat (long criada))
              acc)))
        {})))

(defn- due-schedule-chats
  [db agora limit]
  (->> (d/q '[:find ?chat
              :in $ ?agora
              :where
              [?e :raid-auto-schedule/enabled true]
              [?e :raid-auto-schedule/chat-id ?chat]
              [?e :raid-auto-schedule/next-at ?n]
              [(<= ?n ?agora)]]
            db (long agora))
       (map first)
       (sort)
       (take (long limit))
       vec))

(defn- outbox-entity
  [db event-id]
  (when (string? event-id)
    (d/pull db '[*] [:pokemon-outbox/id event-id])))

(defn- outbox-exists?
  "True se o id já foi materializado (pendente ou acked) — base da idempotência."
  [db event-id]
  (let [e (outbox-entity db event-id)]
    (boolean (and (present? e) (:pokemon-outbox/id e)))))

(defn- reminder-candidate-chats
  "Agendas enabled com next-at no futuro e dentro da janela máxima de lembrete (30 min)."
  [db agora limit]
  (let [agora (long agora)
        horizon (+ agora raid-auto/reminder-offset-30-ms)]
    (->> (d/q '[:find ?chat
                :in $ ?agora ?horizon
                :where
                [?e :raid-auto-schedule/enabled true]
                [?e :raid-auto-schedule/chat-id ?chat]
                [?e :raid-auto-schedule/next-at ?n]
                [(< ?agora ?n)]
                [(<= ?n ?horizon)]]
              db agora horizon)
         (map first)
         (sort)
         (take (long limit))
         vec)))

(defn- intent-planning-candidate-chats
  "12.7.3: enabled com next-at futuro dentro do lead de planejamento (antes de T-30)."
  [db agora limit]
  (let [agora (long agora)
        horizon (+ agora raid-auto/intent-planning-lead-ms)]
    (->> (d/q '[:find ?chat
                :in $ ?agora ?horizon
                :where
                [?e :raid-auto-schedule/enabled true]
                [?e :raid-auto-schedule/chat-id ?chat]
                [?e :raid-auto-schedule/next-at ?n]
                [(< ?agora ?n)]
                [(<= ?n ?horizon)]]
              db agora horizon)
         (map first)
         (sort)
         (take (long limit))
         vec)))

(defn- ensure-intent-for-chat!
  "Persiste intent do slot atual sem spawn. Idempotente; não re-sorteia se já
   houver next-categoria (intent match ou reserva explícita)."
  [conn chat-id agora]
  (let [db (d/db conn)
        schedule (or (load-schedule db chat-id)
                     (raid-auto/default-schedule chat-id agora))]
    (if-not (raid-auto/needs-intent-planning? schedule agora)
      {:acao :noop :chat-id chat-id}
      (let [last-by (last-auto-by-categoria db chat-id)
            day-stats (auto-spawns-on-day db chat-id agora)
            intent (raid-auto/plan-slot-intent
                    schedule last-by
                    (cond-> {:spawns-today (:count day-stats)
                             :specials-today (:specials day-stats)}
                      (:category-intervals schedule)
                      (assoc :category-intervals (:category-intervals schedule))
                      (some? (:daily-limit schedule))
                      (assoc :daily-limit (:daily-limit schedule))
                      (some? (:special-reserve schedule))
                      (assoc :special-reserve (:special-reserve schedule))))
            intent-exists? (present?
                            (receipt-entity
                             db (raid-auto/intent-receipt-id chat-id)))
            tx (raid-store/intent-receipt-tx chat-id intent intent-exists?)]
        (if (nil? tx)
          {:acao :noop :chat-id chat-id}
          (do
            (d/transact conn {:tx-data [tx]})
            (raid-auto-log/emit!
             :raid_auto_intent_planned
             {:chat-id chat-id
              :next-at (:next-at intent)
              :due-at (:next-at intent)
              :result (if (:reminders? intent) "special" "easy")})
            {:acao :planned
             :chat-id chat-id
             :intent intent
             :next-categoria (:categoria intent)
             :next-reminders? (:reminders? intent)}))))))
(defn- pending-reminder-outbox-ids
  [db]
  (->> (d/q '[:find ?id ?k
              :where
              [?e :pokemon-outbox/id ?id]
              [?e :pokemon-outbox/kind ?k]]
            db)
       (keep (fn [[id kind]]
               (let [e (outbox-entity db id)]
                 (when (and (present? e)
                            (nil? (:pokemon-outbox/acked-at e))
                            (raid-auto/reminder-kind? kind))
                   id))))
       vec))

(defn- reminder-still-valid?
  [db chat-id due-at]
  (let [sch (load-schedule db chat-id)]
    (boolean
     (and sch
          (:enabled sch)
          (= (long due-at) (long (:next-at sch 0)))))))

(defn- ack-outbox-now!
  [conn event-id agora]
  (let [db (d/db conn)
        e (outbox-entity db event-id)]
    (when (and (present? e)
               (:pokemon-outbox/id e)
               (nil? (:pokemon-outbox/acked-at e)))
      (try
        (d/transact conn {:tx-data
                          [[:db/add [:pokemon-outbox/id event-id]
                            :pokemon-outbox/acked-at (long agora)]]})
        true
        (catch Exception _
          false)))))

(defn- suppress-stale-reminders!
  "Se agenda desligada ou next-at mudou, não entrega lembretes órfãos pendentes."
  [conn agora]
  (let [db (d/db conn)]
    (->> (pending-reminder-outbox-ids db)
         (keep (fn [event-id]
                 (when-let [parsed (raid-auto/parse-reminder-id event-id)]
                   (when-not (reminder-still-valid? db (:chat-id parsed) (:due-at parsed))
                     (when (ack-outbox-now! conn event-id agora)
                       (raid-auto-log/emit!
                        :raid_auto_reminder_suppressed
                        {:chat-id (:chat-id parsed)
                         :due-at (:due-at parsed)
                         :offset-ms (:offset-ms parsed)
                         :event-id event-id
                         :result "stale"})
                       {:event-id event-id
                        :chat-id (:chat-id parsed)
                        :acao :suppressed})))))
         vec)))

(defn- emit-reminders-for-chat!
  [conn chat-id agora]
  (let [db (d/db conn)
        schedule (or (load-schedule db chat-id)
                     (raid-auto/default-schedule chat-id agora))
        plans (raid-auto/plan-reminders schedule agora)]
    (->> plans
         (mapv
          (fn [plan]
            (if (outbox-exists? (d/db conn) (:event-id plan))
              (do
                (raid-auto-log/emit!
                 :raid_auto_reminder
                 {:chat-id chat-id :event-id (:event-id plan)
                  :due-at (:due-at plan) :offset-ms (:offset-ms plan)
                  :kind (:kind plan) :result "duplicate"})
                {:acao :remind :chat-id chat-id :duplicate true
                 :outbox-id (:event-id plan) :kind (:kind plan)
                 :due-at (:due-at plan) :offset-ms (:offset-ms plan)})
              (let [built (raid-store/build-reminder-outbox-tx
                           {:plan plan :agora agora})]
                (try
                  (d/transact conn {:tx-data (:tx built)})
                  (raid-auto-log/emit!
                   :raid_auto_reminder
                   {:chat-id chat-id :event-id (:event-id plan)
                    :due-at (:due-at plan) :offset-ms (:offset-ms plan)
                    :kind (:kind plan) :result "enqueued"})
                  (assoc (:result built) :duplicate false)
                  (catch Exception ex
                    (if (outbox-exists? (d/db conn) (:event-id plan))
                      {:acao :remind :chat-id chat-id :duplicate true
                       :outbox-id (:event-id plan) :kind (:kind plan)
                       :due-at (:due-at plan) :offset-ms (:offset-ms plan)}
                      (throw ex)))))))))))

(defn- active-raid-chats
  [db]
  (->> (d/q '[:find ?chat
              :where
              [?e :chat-active-raid/chat-id ?chat]
              [?e :chat-active-raid/raid-id _]]
            db)
       (map first)
       vec))

(defn- recent-auto-boss-species
  "Espécies dos últimos spawns auto do chat (anti-repetição sem migration)."
  [db chat-id limit]
  (->> (d/q '[:find ?criada ?chefe
              :in $ ?chat
              :where
              [?e :raid/chat-id ?chat]
              [?e :raid/origem "auto"]
              [?e :raid/criada-em ?criada]
              [?e :raid/chefe-edn ?chefe]]
            db (str chat-id))
       (sort-by first >)
       (take (long limit))
       (keep (fn [[_ raw]]
               (let [c (edn-read raw)]
                 (some-> (or (:species c) (get c "species")) str))))
       vec))

(defn- spawn-auto-for-chat!
  [conn chat-id agora]
  (ensure-chat-raid-pointer! conn chat-id)
  (loop [attempt 0]
    (let [db (d/db conn)
          schedule (or (load-schedule db chat-id)
                       (raid-auto/default-schedule chat-id agora))
          ptr (d/pull db '[*] [:chat-active-raid/chat-id chat-id])
          rid (when (present? ptr) (:chat-active-raid/raid-id ptr))
          active (when rid (load-raid db rid))
          recent (recent-auto-boss-species db chat-id raid-boss/recent-window-default)
          last-by (last-auto-by-categoria db chat-id)
          day-stats (auto-spawns-on-day db chat-id agora)
          plan (raid-auto/plan-spawn
                schedule active agora
                (cond-> {:recent-species recent
                         :last-by-categoria last-by
                         :spawns-today (:count day-stats)
                         :specials-today (:specials day-stats)}
                  (:category-intervals schedule)
                  (assoc :category-intervals (:category-intervals schedule))
                  (some? (:daily-limit schedule))
                  (assoc :daily-limit (:daily-limit schedule))
                  (some? (:special-reserve schedule))
                  (assoc :special-reserve (:special-reserve schedule))
                  (:next-categoria schedule)
                  (assoc :forced-categoria (:next-categoria schedule)
                         :preserve-next-categoria (:next-categoria schedule))))]
      (if (contains? #{:noop :skip-disabled} (:acao plan))
        {:acao (:acao plan) :chat-id chat-id}
        (let [spawn-id (:spawn-id plan)
              existing (when spawn-id (receipt-entity db spawn-id))]
          (if (present? existing)
            ;; Retry/timeout após TX confirmada: não avança agenda de novo.
            (let [kind (:operation-receipt/kind existing)
                  summary (:summary (->receipt existing))
                  acao (case kind
                         "raid-auto-skip" :skip-busy
                         "raid-auto-skip-cap" :skip-daily-cap
                         :spawn)
                  out {:acao acao
                       :chat-id chat-id
                       :duplicate true
                       :spawn-id spawn-id
                       :due-at (:due-at plan)
                       :raid-id (:raid-id summary)
                       :confirmed true}]
              (raid-auto-log/emit!
               (case acao
                 :skip-busy :raid_auto_skip
                 :skip-daily-cap :raid_auto_skip_cap
                 :raid_auto_spawn)
               {:chat-id chat-id :spawn-id spawn-id :due-at (:due-at plan)
                :raid-id (:raid-id out) :result "duplicate"})
              out)
              (let [announce (when (= :spawn (:acao plan))
                             (raid-auto/format-opened
                              {:ginasio (:ginasio plan)
                               :nome-ginasio (:nome-ginasio plan)
                               :liga (:liga plan)
                               :chefe (:chefe plan)
                               :boss-categoria (:boss-categoria plan)
                               :lobby (:lobby plan)
                               :min-jogadores (:min-jogadores plan)
                               :recomendados (:recomendados plan)
                               :max-jogadores (:max-jogadores plan)
                               :lobby-minutos (get-in plan [:lobby :minutos])
                               :criada-em agora
                               :expira (+ agora (long (:duracao-ms plan
                                                                  raid-domain/duracao-inscricoes)))}))
                  intent-exists? (present?
                                  (receipt-entity
                                   db (raid-auto/intent-receipt-id chat-id)))
                  built (raid-store/build-auto-spawn-tx
                         {:plan plan :schedule schedule :active-raid active
                          :announce-texto announce :agora agora
                          :intent-exists? intent-exists?})]
              (if (empty? (:tx built))
                (assoc (:result built) :chat-id chat-id)
                (let [tx-result
                      (try
                        (d/transact conn {:tx-data (:tx built)})
                        {:ok true}
                        (catch Exception ex
                          {:ok false :ex ex}))]
                  (if (:ok tx-result)
                    (let [out (assoc (:result built) :chat-id chat-id
                                     :due-at (:due-at plan)
                                     :confirmed true)]
                      (raid-auto-log/emit!
                       (case (:acao out)
                         :skip-busy :raid_auto_skip
                         :skip-daily-cap :raid_auto_skip_cap
                         :raid_auto_spawn)
                       {:chat-id chat-id
                        :spawn-id spawn-id
                        :due-at (:due-at plan)
                        :raid-id (get-in out [:raid :raid-id])
                        :next-at (get-in out [:schedule :next-at])
                        :spawns-today (:spawns-today plan)
                        :daily-limit (:daily-limit plan)
                        :result (name (:acao out))})
                      out)
                    (let [ex (:ex tx-result)]
                      ;; Conflito/incerto: NÃO avança agenda (TX rollback).
                      (if (and (err/conflict? ex) (< attempt 8))
                        (recur (inc attempt))
                        (let [dup (receipt-entity (d/db conn) spawn-id)]
                          (if (present? dup)
                            (let [summary (get-in (->receipt dup) [:summary])
                                  out {:acao :spawn :chat-id chat-id
                                       :duplicate true :spawn-id spawn-id
                                       :due-at (:due-at plan)
                                       :raid-id (:raid-id summary)
                                       :confirmed true}]
                              (raid-auto-log/emit!
                               :raid_auto_spawn
                               {:chat-id chat-id :spawn-id spawn-id
                                :due-at (:due-at plan) :raid-id (:raid-id out)
                                :result "duplicate_after_conflict"})
                              out)
                            (do
                              (raid-auto-log/emit!
                               :raid_auto_spawn
                               {:chat-id chat-id :spawn-id spawn-id
                                :due-at (:due-at plan)
                                :result "persist_failed"})
                              (throw ex))))))))))))))))

(defn- load-eggs-for-account
  [db account-key]
  (->> (d/q '[:find (pull ?e [*])
              :in $ ?ak
              :where [?e :egg/account-key ?ak]]
            db account-key)
       (map (comp #(adventure-store/egg-entity->map % edn-read present?) first))
       (filter some?)
       vec))

(defn- load-adventure
  [db chat-id player-id]
  (let [ak (id/adventure-account-key chat-id player-id)
        e (d/pull db '[*] [:adventure-account/key ak])]
    (when (present? e)
      (adventure-store/merge-estado
       (adventure-store/account-shell e edn-read present?)
       (load-eggs-for-account db ak)))))

(defn- load-enfermaria
  [db save-key*]
  (->> (d/q '[:find (pull ?b [*])
              :in $ ?sk
              :where [?b :enfermaria-bed/save-key ?sk]]
            db save-key*)
       (map (fn [[e]]
              (let [poke (or (edn-read (:enfermaria-bed/pokemon-edn e)) {})
                    poke (cond-> poke
                           (:enfermaria-bed/origin-edn e)
                           (assoc :origin-edn (:enfermaria-bed/origin-edn e))
                           (:enfermaria-bed/legado-edn e)
                           (assoc :legado (edn-read (:enfermaria-bed/legado-edn e))))]
                {:bed-id (:enfermaria-bed/bed-id e)
                 :pokemon poke
                 :pronto-em (:enfermaria-bed/pronto-em e)})))
       vec))

(defn- clear-chat-wild-tx
  "Remove trava de caça do chat se ainda apontar para esta batalha."
  [db chat-id battle-id]
  (let [g (d/pull db '[*] [:group/chat-id chat-id])]
    (when (and (present? g) (= battle-id (:group/active-wild-battle-id g)))
      (cond-> []
        (:group/active-wild-battle-id g)
        (conj [:db/retract [:group/chat-id chat-id] :group/active-wild-battle-id battle-id])
        (:group/active-wild-player-id g)
        (conj [:db/retract [:group/chat-id chat-id] :group/active-wild-player-id
               (:group/active-wild-player-id g)])))))

(defn- run-mission-operation!*
  [conn writer kind request]
  (mission-economy-app/validate! writer request)
  (when (= kind :gift) (mission-economy-app/validate-gift! writer request))
  (let [{:keys [chat-id player-id save-id]} writer
        operation-id (:operation-id request)
        payload (merge {:kind kind :chat-id chat-id :player-id player-id :save-id save-id}
                       (select-keys request [:period :target-player-id]))
        phash (reward/payload-hash payload)
        recover (fn [db]
                  (let [receipt (receipt-entity db operation-id)]
                    (when (present? receipt)
                      (when-not (and (= (id/player-key chat-id player-id) (:operation-receipt/player-key receipt))
                                     (= save-id (:operation-receipt/save-id receipt)))
                        (throw (err/error :operation_forbidden {:operation-id operation-id})))
                      (reward/assert-receipt-payload! (:operation-receipt/payload-hash receipt) phash operation-id)
                      (let [result (:result (edn-read (:operation-receipt/summary receipt)))]
                        (when-not result (throw (err/error :receipt_payload_mismatch {:operation-id operation-id})))
                        (assoc result :duplicate true)))))]
    (or (recover (d/db conn))
        (let [instant (mission-events/*clock*)
              target (when (= kind :gift) (mission-economy/freeze-target (d/db conn) chat-id (:target-player-id request)))
              _ (when (= kind :gift)
                  (when-not (some #(and (= "cartao-presente" (:item-id %)) (pos? (:qty %)))
                                  (query-inventory (d/db conn) (id/save-key chat-id player-id save-id)))
                    (throw (err/error :insufficient_item {:item-id "cartao-presente"})))
                  (provisioning/ensure-recipient! conn chat-id (:target-player-id request)))
              draw (memoize (fn [_ n] (mission-economy/*rand-int* n)))]
          (try
            (transact-with-writer!
             conn writer
             (fn [db save]
               (when (recover db)
                 (throw (err/error :mission_operation_already_recorded {:operation-id operation-id})))
               (mission-economy/build {:db db :writer writer :kind kind :save save
                                       :request (assoc request :payload-hash phash)
                                       :instant instant :target target :draw draw
                                       :inventory-query query-inventory :adjust inventory-adjust-tx})))
            (catch Exception ex
              (or (recover (d/db conn)) (throw ex))))))))

(defn- rename-save!*
  [conn {:keys [chat-id player-id save-id display-name expected-fence-generation operation-id]}]
  (model/validate-save-slot! save-id)
  (let [name (model/validate-save-display-name! display-name)
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)]
    (loop [attempt 0]
      (let [db (d/db conn)
            player (player-entity db chat-id player-id)
            save (save-entity db chat-id player-id save-id)]
        (when-not (present? player)
          (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
        (when-not (present? save)
          (throw (err/error :save_not_found {:save-id save-id})))
        (let [fence (long (:player/fence-generation player 0))
              expected (if (some? expected-fence-generation)
                         (long expected-fence-generation)
                         fence)]
          (when (not= expected fence)
            (throw (err/error :stale_writer_fence {:expected expected :fence fence})))
          (cond
            (and operation-id
                 (= (str operation-id) (:save/last-rename-operation-id save)))
            (if (= name (:save/last-rename-name save))
              {:save-id save-id :display-name name :fence-generation fence :status :already-renamed}
              (throw (err/error :receipt_payload_mismatch
                                {:operation-id operation-id :save-id save-id})))

            (= name (:save/display-name save))
            {:save-id save-id :display-name name :fence-generation fence :status :already-named}

            :else
            (let [result (try
                           (d/transact conn {:tx-data (cond-> [[:db/cas [:player/key pk] :player/fence-generation
                                                                 fence (inc fence)]
                                                                [:db/add [:save/key sk] :save/display-name name]]
                                                         operation-id (conj [:db/add [:save/key sk]
                                                                             :save/last-rename-operation-id
                                                                             (str operation-id)])
                                                         operation-id (conj [:db/add [:save/key sk]
                                                                             :save/last-rename-name name]))})
                           {:ok true}
                           (catch Exception ex {:ok false :ex ex}))]
              (if (:ok result)
                {:save-id save-id :display-name name :fence-generation (inc fence)
                 :status :renamed}
                (let [ex (:ex result)]
                  (if (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                    (recur (inc attempt))
                    (throw (err/error :writer_fence_conflict
                                      {:cause (ex-message ex) :save-id save-id}))))))))))))

(defrecord DatomicTrainerStore [conn]
  mission-queries/MissionQueries
  (read-missions [_ chat-id player-id save-id options]
    (mission-reads/read-missions conn chat-id player-id save-id options))
  (prepare-missions! [_ writer options]
    (mission-reads/prepare! conn writer options))

  ports/TrainerStore

  (create-trade! [_ request]
    (trade-store/create! conn request))
  (get-trade [_ trade-id]
    (trade-store/get-trade conn trade-id))
  (list-trades [_ chat-id player-id]
    (trade-store/list-trades conn chat-id player-id))
  (accept-trade! [_ request]
    (trade-store/accept! conn request))
  (refuse-trade! [_ request]
    (trade-store/refuse! conn request))
  (cancel-trade! [_ request]
    (trade-store/cancel! conn request))
  (complete-trade! [_ request]
    (trade-store/complete! conn request))
  (get-friendship [_ chat player save idx] (profile-store/friendship conn chat player save idx))
  (list-titles [_ chat player save] (profile-store/titles conn chat player save))
  (select-title! [_ request] (profile-store/select-title! conn request (:title-index request)))
  (professor-cards [_ chat player save] (profile-store/professor-cards conn chat player save))
  (professor-send! [_ request]
    (profile-store/professor-send!
     conn request
     (or (:collection-indexes request) (:collection-index request))
     (:now request)))
  (professor-cancel! [_ request] (profile-store/professor-cancel! conn request))
  (professor-confirm! [_ request] (profile-store/professor-confirm! conn request (:token request) (:now request)))
  (professor-use! [_ request] (profile-store/professor-use! conn request (:collection-index request)))

  (claim-daily-missions! [_ writer request]
    (run-mission-operation!* conn writer :daily request))
  (claim-weekly-missions! [_ writer request]
    (run-mission-operation!* conn writer :weekly request))
  (send-gift! [_ writer request]
    (run-mission-operation!* conn writer :gift request))
  (collect-mission-pending! [_ writer request]
    (run-mission-operation!* conn writer :pending request))

  (ensure-schema! [_]
    (schema/install! conn)
    :ok)

  (create-player! [_ chat-id player-id]
    (->player (provisioning/ensure-player! conn chat-id player-id)))

  (create-save! [_ chat-id player-id save-id]
    (model/validate-save-slot! save-id)
    (let [db (d/db conn)]
      (when-not (present? (player-entity db chat-id player-id))
        (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
      (when (present? (save-entity db chat-id player-id save-id))
        (throw (err/error :save_already_exists {:save-id save-id})))
      (->save (provisioning/ensure-save! conn chat-id player-id save-id))))

  (list-saves [_ chat-id player-id]
    (mapv ->save (save-entities (d/db conn) chat-id player-id)))

  (rename-save! [_ request]
    (rename-save!* conn request))

  (get-player [_ chat-id player-id]
    (->player (player-entity (d/db conn) chat-id player-id)))

  (get-save [_ chat-id player-id save-id]
    (->save (save-entity (d/db conn) chat-id player-id save-id)))

  (list-party [_ chat-id player-id save-id]
    (query-party (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-collection [_ chat-id player-id save-id]
    (query-collection (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-pokedex-entries [_ chat-id player-id save-id]
    (query-pokedex (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-inventory [_ chat-id player-id save-id]
    (query-inventory (d/db conn) (id/save-key chat-id player-id save-id)))

  (active-save-id [_ chat-id player-id]
    (:player/active-save-id (player-entity (d/db conn) chat-id player-id)))

  (activate-save! [_ chat-id player-id save-id]
    (activate-save!* conn chat-id player-id save-id))

  (apply-gameplay-reward! [_ writer-ctx request]
    (let [result (apply-gameplay-reward!* conn writer-ctx request)]
      (if (:duplicate result)
        result
        (assoc result
               :save (->save (save-entity (d/db conn)
                                          (:chat-id writer-ctx)
                                          (:player-id writer-ctx)
                                          (:save-id writer-ctx)))))))

  (admin-seed-reward! [_ request]
    (admin-seed-reward!* conn request))

  (add-to-collection! [_ writer-ctx registro]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             idx (coll/next-index colecao)
             entity (pokemon-entity-map sk registro idx)
             rev (long (:save/revision save 0))]
         {:tx [entity
               (pokedex-caught-tx db sk registro)
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:destino :equipe
                   :indice idx
                   :id-pokemon (:id-pokemon registro)
                   :registro (assoc registro :collection-index idx)}}))))

  (update-hp-status! [_ writer-ctx idx hp-atual status]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             _ (coll/validate-index! idx (count colecao))
             poke (nth colecao idx)
             status-str (xp/status->str status)
             lookup [:pokemon/id-pokemon (:id-pokemon poke)]
             rev (long (:save/revision save 0))
             tx (cond-> [[:db/add lookup :pokemon/hp-atual (long hp-atual)]
                         [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
                  status-str (conj [:db/add lookup :pokemon/status status-str])
                  (and (nil? status-str) (:status poke))
                  (conj [:db/retract lookup :pokemon/status (:status poke)]))]
         {:tx tx
          :result {:ok true :idx idx :hp-atual (long hp-atual) :status status-str}}))))

  (grant-pokemon-xp! [_ writer-ctx idx quantidade]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             _ (coll/validate-index! idx (count colecao))
             poke (nth colecao idx)
             applied (xp/aplicar-xp-pokemon poke quantidade)
             r (:registro applied)
             lookup [:pokemon/id-pokemon (:id-pokemon poke)]
             rev (long (:save/revision save 0))
             tx [[:db/add lookup :pokemon/level (long (:nivel r))]
                 [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
                 [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
                 [:db/add lookup :pokemon/hp (long (:hp r))]
                 [:db/add lookup :pokemon/hp-atual (long (:hp-atual r))]
                 [:db/add lookup :pokemon/ataque (long (:ataque r))]
                 [:db/add lookup :pokemon/defesa (long (:defesa r))]
                 [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
                 [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
                 [:db/add lookup :pokemon/veloc (long (:veloc r))]
                 [:db/add lookup :pokemon/amizade (long (:amizade r))]
                 [:db/cas [:save/key sk] :save/revision rev (inc rev)]]]
         {:tx tx
          :result (when (pos? (:subidas applied))
                    {:nome (:nome applied)
                     :nivel (:nivel-final applied)
                     :niveis-subidos (:subidas applied)})}))))

  (register-trainer-victory! [_ writer-ctx]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             v (long (:save/vitorias-treinador save 0))
             rev (long (:save/revision save 0))]
         {:tx [[:db/add [:save/key sk] :save/vitorias-treinador (inc v)]
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:vitorias (inc v)}}))))

  (attempt-capture! [_ writer-ctx request]
    (let [payload (ops/capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     save-m (->save save)
                     colecao (query-collection db sk)
                     bola (:bola request)
                     pokemon (:pokemon request)
                     storage (capacity/resumo-armazenamento-pokemon (count colecao) save-m)
                     _ (when (:cheio? storage)
                         (throw (err/error :collection_full
                                           {:count (:ocupacao storage)
                                            :capacidade (:capacidade storage)
                                            :excesso (:excesso storage)
                                            :nivel (:nivel storage)})))
                     ball-tx (inventory-adjust-tx db sk bola -1)
                     attempt (capture/attempt-capture
                              {:pokemon pokemon
                               :status (:status request)
                               :sequencia-capturas (:sequencia-capturas save-m 0)
                               :bola bola
                               :tentativas-anteriores (:tentativas-anteriores request 0)
                               :roll (:roll request)
                               :fuga-roll (:fuga-roll request)})
                     rev (long (:save/revision save 0))
                     seq-atual (long (:sequencia-capturas save-m 0))
                     maior (long (:maior-sequencia-capturas save-m 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     base-tx (into ball-tx
                                   [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                    {:operation-receipt/id operation-id
                                     :operation-receipt/kind "capture"
                                     :operation-receipt/channel "gameplay"
                                     :operation-receipt/payload-hash phash
                                     :operation-receipt/player-key pk
                                     :operation-receipt/save-id (:save-id writer-ctx)
                                     :operation-receipt/summary
                                     (pr-str {:capturou? (:capturou? attempt)
                                              :fugiu? (:fugiu? attempt)
                                              :bola bola
                                              :chance (:chance attempt)})}])
                     resultado
                     (cond
                       (:capturou? attempt)
                       (let [registro (coll/pokemon->registro pokemon
                                                              (or (:hp pokemon) (:hp-atual pokemon) 0)
                                                              nil)
                             idx (coll/next-index colecao)
                             entity (pokemon-entity-map sk registro idx)
                             seq-nova (inc seq-atual)
                             rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova seq-nova
                                   :primeira-tentativa? (zero? (long (:tentativas-anteriores request 0)))
                                   :capturou? true})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             coin-part (economy-store/apply-delta-tx
                                        {:sk sk :save save :delta (long (:moedas rew 0))
                                         :operation-id operation-id :source "capture"
                                         :role "primary"})
                             tx (cond-> (into base-tx
                                              (concat
                                               [entity
                                                [:db/add [:save/key sk] :save/sequencia-capturas seq-nova]
                                                [:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                 (max maior seq-nova)]
                                                (pokedex-caught-tx db sk registro)]
                                               (:tx coin-part)))
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :capturou? true
                                   :fugiu? false
                                   :chance (:chance attempt)
                                   :indice idx
                                   :id-pokemon (:id-pokemon registro)
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :registro (assoc registro :collection-index idx)}})

                       (:fugiu? attempt)
                       (let [rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova 0
                                   :primeira-tentativa? false
                                   :capturou? false})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             tx (cond-> (into base-tx
                                              [[:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                (max maior seq-atual)]
                                               [:db/add [:save/key sk] :save/sequencia-capturas 0]])
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :capturou? false
                                   :fugiu? true
                                   :chance (:chance attempt)
                                   :recompensa rew
                                   :subida (:subida xp-part)}})

                       :else
                       {:tx base-tx
                        :result {:operation-id operation-id
                                 :duplicate false
                                 :channel "gameplay"
                                 :payload-hash phash
                                 :capturou? false
                                 :fugiu? false
                                 :chance (:chance attempt)
                                 :tentativa (:tentativa attempt)}})]
                 resultado)))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (attempt-encounter-capture! [_ writer-ctx request]
    (let [payload (ops/encounter-capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     pk (id/player-key chat-id player-id)
                     battle-id (or (:battle-id request)
                                   (active-battle-id-for db chat-id player-id))
                     _ (when-not battle-id
                         (throw (err/error :encounter_not_found
                                           {:chat-id chat-id :player-id player-id})))
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :encounter_not_found {:battle-id battle-id})))
                     _ (when-not (= (:battle/save-id be) (:save-id writer-ctx))
                         (throw (err/error :battle_save_mismatch
                                           {:battle (:battle/save-id be)
                                            :writer (:save-id writer-ctx)})))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     _ (when (and (not (true? (:battle/idle-expired? be)))
                                  (battle-idle/expired? (:battle/idle-expires-at be) agora))
                         (throw (err/error :battle_idle_expired
                                           {:battle-id battle-id
                                            :expires-at (:battle/idle-expires-at be)
                                            :agora agora})))
                     _ (battle-idle-store/assert-battle-not-idle-expired! be agora)
                     state (->battle be)
                     _ (when-not (= player-id (:player-id state))
                         (throw (err/error :encounter_owner_mismatch
                                           {:owner (:player-id state)
                                            :player-id player-id})))
                     _ (when-not (:aguardando-captura? state)
                         (throw (err/error :encounter_not_ready
                                           {:battle-id battle-id
                                            :phase (:phase state)
                                            :active? (:active? state)})))
                     save-m (->save save)
                     colecao (query-collection db sk)
                     bola (:bola request)
                     pokemon (get-in state [:pokemons :o])
                     storage (capacity/resumo-armazenamento-pokemon (count colecao) save-m)
                     _ (when (:cheio? storage)
                         (throw (err/error :collection_full
                                           {:count (:ocupacao storage)
                                            :capacidade (:capacidade storage)
                                            :excesso (:excesso storage)
                                            :nivel (:nivel storage)})))
                     ball-tx (inventory-adjust-tx db sk bola -1)
                     tentativas-ant (long (:tentativas-captura state 0))
                     chance-base (or (:chance-base-captura state)
                                     (capture/chance-base-pos-batalha
                                      pokemon
                                      (get-in state [:status :o])
                                      (:sequencia-capturas save-m 0)))
                     attempt (capture/attempt-capture
                              {:pokemon pokemon
                               :status (get-in state [:status :o])
                               :sequencia-capturas (:sequencia-capturas save-m 0)
                               :chance-base chance-base
                               :bola bola
                               :tentativas-anteriores tentativas-ant
                               :roll (:roll request)
                               :fuga-roll (:fuga-roll request)})
                     rev (long (:save/revision save 0))
                     seq-atual (long (:sequencia-capturas save-m 0))
                     maior (long (:maior-sequencia-capturas save-m 0))
                     encerrado? (or (:capturou? attempt) (:fugiu? attempt))
                     turn-now (long (:turn-index state 0))
                     idle-touch (when-not encerrado?
                                  (battle-idle-store/touch-attrs agora))
                     next-state (cond-> (assoc state
                                               :tentativas-captura (:tentativa attempt)
                                               :chance-base-captura chance-base
                                               :turn-index (inc turn-now))
                                  encerrado? (assoc :aguardando-captura? false
                                                    :phase :finished
                                                    :active? false
                                                    :capture-outcome
                                                    (cond
                                                      (:capturou? attempt) :caught
                                                      (:fugiu? attempt) :fled))
                                  idle-touch (assoc :last-activity-at (:battle/last-activity-at idle-touch)
                                                    :idle-expires-at (:battle/idle-expires-at idle-touch)))
                     ;; CAS serializa tentativas concorrentes (espelha turn-index da batalha).
                     battle-tx (cond-> [[:db/cas [:battle/id battle-id] :battle/turn-index
                                         turn-now (inc turn-now)]
                                        [:db/add [:battle/id battle-id] :battle/active?
                                         (boolean (:active? next-state))]
                                        [:db/add [:battle/id battle-id] :battle/phase
                                         (name (:phase next-state))]
                                        [:db/add [:battle/id battle-id] :battle/state-edn
                                         (battle/encode-state-edn next-state)]]
                                 idle-touch (into (map (fn [[k v]]
                                                         [:db/add [:battle/id battle-id] k v])
                                                       idle-touch)))
                     clear-active (when encerrado?
                                    (into [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
                                          (or (clear-chat-wild-tx db chat-id battle-id) [])))
                     base-tx (into (into ball-tx battle-tx)
                                   (concat
                                    (or clear-active [])
                                    [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                     {:operation-receipt/id operation-id
                                      :operation-receipt/kind "encounter-capture"
                                      :operation-receipt/channel "gameplay"
                                      :operation-receipt/payload-hash phash
                                      :operation-receipt/player-key pk
                                      :operation-receipt/save-id (:save-id writer-ctx)
                                      :operation-receipt/summary
                                      (pr-str {:capturou? (:capturou? attempt)
                                               :fugiu? (:fugiu? attempt)
                                               :bola bola
                                               :chance (:chance attempt)
                                               :battle-id battle-id
                                               :tentativa (:tentativa attempt)})}]))
                     resultado
                     (cond
                       (:capturou? attempt)
                       (let [registro (coll/pokemon->registro pokemon
                                                              (or (:hp pokemon) (:hp-atual pokemon) 0)
                                                              nil)
                             idx (coll/next-index colecao)
                             entity (pokemon-entity-map sk registro idx)
                             seq-nova (inc seq-atual)
                             rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova seq-nova
                                   :primeira-tentativa? (zero? tentativas-ant)
                                   :capturou? true})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             coin-part (economy-store/apply-delta-tx
                                        {:sk sk :save save :delta (long (:moedas rew 0))
                                         :operation-id operation-id :source "capture"
                                         :role "primary"})
                             tx (cond-> (into base-tx
                                              (concat
                                               [entity
                                                [:db/add [:save/key sk] :save/sequencia-capturas seq-nova]
                                                [:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                 (max maior seq-nova)]
                                                (pokedex-caught-tx db sk registro)]
                                               (:tx coin-part)))
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :battle-id battle-id
                                   :capturou? true
                                   :fugiu? false
                                   :chance (:chance attempt)
                                   :chance-base chance-base
                                   :tentativa (:tentativa attempt)
                                   :bola bola
                                   :indice idx
                                   :id-pokemon (:id-pokemon registro)
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :registro (assoc registro :collection-index idx)
                                   :encounter next-state}})

                       (:fugiu? attempt)
                       (let [rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova 0
                                   :primeira-tentativa? false
                                   :capturou? false})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             tx (cond-> (into base-tx
                                              [[:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                (max maior seq-atual)]
                                               [:db/add [:save/key sk] :save/sequencia-capturas 0]])
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :battle-id battle-id
                                   :capturou? false
                                   :fugiu? true
                                   :chance (:chance attempt)
                                   :chance-base chance-base
                                   :tentativa (:tentativa attempt)
                                   :bola bola
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :encounter next-state}})

                       :else
                       {:tx base-tx
                        :result {:operation-id operation-id
                                 :duplicate false
                                 :channel "gameplay"
                                 :payload-hash phash
                                 :battle-id battle-id
                                 :capturou? false
                                 :fugiu? false
                                 :chance (:chance attempt)
                                 :chance-base chance-base
                                 :tentativa (:tentativa attempt)
                                 :bola bola
                                 :encounter next-state}})]
                 resultado)))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (start-hunt! [_ writer-ctx request]
    (let [payload (ops/hunt-start-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     pk (id/player-key chat-id player-id)
                     save-m (->save save)
                     agora (long (or (:agora-ms request) (System/currentTimeMillis)))
                     _ (when-not (hunt/pode-cacar? (:ultima-cacada-at save-m 0) agora)
                         (throw (err/error :hunt_cooldown
                                           {:restante-ms (hunt/cooldown-restante-ms
                                                          (:ultima-cacada-at save-m 0) agora)})))
                     prev (active-battle-id-for db chat-id player-id)
                     _ (when prev
                         (let [st (->battle (battle-entity db prev))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     group-e (d/pull db '[*] [:group/chat-id chat-id])
                     _ (when (and (present? group-e)
                                  (:group/active-wild-battle-id group-e)
                                  (not= player-id (:group/active-wild-player-id group-e)))
                         (throw (err/error :chat_hunt_busy
                                           {:battle-id (:group/active-wild-battle-id group-e)
                                            :owner (:group/active-wild-player-id group-e)})))
                     colecao (query-collection db sk)
                     _ (when (empty? colecao)
                         (throw (err/error :no_active_pokemon {})))
                     ativo-idx (long (:ativo-index save-m 0))
                     pokemon-x (nth colecao (min ativo-idx (dec (count colecao))))
                     _ (when-not (pos? (long (:hp-atual pokemon-x 0)))
                         (throw (err/error :active_pokemon_fainted
                                           {:idx ativo-idx})))
                     candidates (mapv hunt/validar-candidato-basico!
                                      (or (:candidates request) []))
                     tipos (set (:tipos-encontro request))
                     surto? (boolean (:surto? request))
                     selvagem
                     (if surto?
                       (let [c (first candidates)]
                         (when-not c
                           (throw (err/error :hunt_no_candidates {:surto? true})))
                         c)
                       (hunt/selecionar-selvagem
                        candidates
                        (long (:poder-alvo request (hunt/poder-alvo-caca
                                                   (:trainer-level save-m 1))))
                        tipos))
                     selvagem (let [base (hunt/com-raridade selvagem)
                                    ;; Produção não manda shinyRoll; nil não pode ir
                                    ;; para (long) — ver NPE doubleValue no !pk caçar.
                                    com-shiny (if (some? (:shiny-roll request))
                                                (hunt/sortear-shiny base (:shiny-roll request))
                                                (hunt/sortear-shiny base))]
                                (update com-shiny :golpes
                                        #(moves/garantir-ataque-do-tipo
                                          (or % []) (:tipos com-shiny))))
                     somente-batalha? (not (capacity/cabe-pokemon? (count colecao) save-m))
                     battle-id (or (:battle-id request) operation-id)
                     state (battle/create-wild-battle
                            {:battle-id battle-id
                             :chat-id chat-id
                             :player-id player-id
                             :save-id (:save-id writer-ctx)
                             :fence-generation (:fence-generation writer-ctx)
                             :pokemon-x pokemon-x
                             :pokemon-o selvagem
                             :hp-x (:hp-atual pokemon-x)
                             :hp-o (:hp selvagem)
                             :status-x (:status pokemon-x)
                             :collection-index ativo-idx})
                     state (assoc state
                                  :bioma (:area-id request)
                                  :clima (:clima-id request)
                                  :surto? surto?
                                  :somente-batalha? somente-batalha?
                                  :tipos-encontro (vec tipos))
                     rev (long (:save/revision save 0))
                     species (or (:species selvagem) (:slug-especie selvagem)
                                 (:nome selvagem))
                     group-tx (if (present? group-e)
                                [[:db/add [:group/chat-id chat-id] :group/active-wild-battle-id battle-id]
                                 [:db/add [:group/chat-id chat-id] :group/active-wild-player-id player-id]]
                                [{:group/chat-id chat-id
                                  :group/label "grupo"
                                  :group/active-wild-battle-id battle-id
                                  :group/active-wild-player-id player-id}])
                     idle (battle-idle-store/touch-attrs agora)
                     state (merge state idle
                                  {:last-activity-at (:battle/last-activity-at idle)
                                   :idle-expires-at (:battle/idle-expires-at idle)})
                     tx (into group-tx
                              [(merge {:battle/id battle-id
                                       :battle/save [:save/key sk]
                                       :battle/player-key pk
                                       :battle/save-id (:save-id writer-ctx)
                                       :battle/kind "wild"
                                       :battle/active? true
                                       :battle/turn-index 0
                                       :battle/phase "active"
                                       :battle/collection-index (long ativo-idx)
                                       :battle/state-edn (battle/encode-state-edn state)}
                                      idle)
                               [:db/add [:player/key pk] :player/active-battle-id battle-id]
                               [:db/add [:save/key sk] :save/ultima-cacada-at agora]
                               [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                               (pokedex-seen-tx db sk species)
                               {:operation-receipt/id operation-id
                                :operation-receipt/kind "hunt-start"
                                :operation-receipt/channel "gameplay"
                                :operation-receipt/payload-hash phash
                                :operation-receipt/player-key pk
                                :operation-receipt/save-id (:save-id writer-ctx)
                                :operation-receipt/summary
                                (pr-str {:battle-id battle-id
                                         :species species
                                         :raridade (:raridade selvagem)
                                         :shiny? (boolean (:shiny? selvagem))
                                         :somente-batalha? somente-batalha?})}])]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle-id battle-id
                           :battle state
                           :selvagem selvagem
                           :somente-batalha? somente-batalha?
                           :area-id (:area-id request)
                           :surto? surto?}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (evolve-pokemon! [_ writer-ctx request]
    (let [payload (ops/evolve-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     evoluido (evo/aplicar-evolucao poke (:dados request))
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     item-tx (when-let [item-id (:item-id request)]
                               (inventory-adjust-tx db sk item-id -1))
                     old-tipos (set (:tipos poke))
                     new-tipos (set (:tipos evoluido))
                     tipo-tx (concat
                              (map (fn [t] [:db/retract lookup :pokemon/tipos t])
                                   (remove new-tipos old-tipos))
                              (map (fn [t] [:db/add lookup :pokemon/tipos t])
                                   (remove old-tipos new-tipos)))
                     tx (into (or item-tx [])
                              (concat
                               tipo-tx
                               [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                [:db/add lookup :pokemon/species (:species evoluido)]
                                [:db/add lookup :pokemon/nickname (:nome evoluido)]
                                [:db/add lookup :pokemon/hp (long (:hp evoluido))]
                                [:db/add lookup :pokemon/hp-atual (long (:hp-atual evoluido))]
                                [:db/add lookup :pokemon/ataque (long (:ataque evoluido))]
                                [:db/add lookup :pokemon/defesa (long (:defesa evoluido))]
                                [:db/add lookup :pokemon/atq-esp (long (:atq-esp evoluido))]
                                [:db/add lookup :pokemon/def-esp (long (:def-esp evoluido))]
                                [:db/add lookup :pokemon/veloc (long (:veloc evoluido))]
                                [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes evoluido))]
                                {:operation-receipt/id operation-id
                                 :operation-receipt/kind "evolve"
                                 :operation-receipt/channel "gameplay"
                                 :operation-receipt/payload-hash phash
                                 :operation-receipt/player-key pk
                                 :operation-receipt/save-id (:save-id writer-ctx)
                                 :operation-receipt/summary
                                 (pr-str {:idx idx :nome-novo (:nome evoluido)})}]))
                     tx (cond-> tx
                          (:habilidade evoluido)
                          (conj [:db/add lookup :pokemon/habilidade (:habilidade evoluido)])
                          (:imagem evoluido)
                          (conj [:db/add lookup :pokemon/imagem (:imagem evoluido)])
                          (:imagem-shiny evoluido)
                          (conj [:db/add lookup :pokemon/imagem-shiny (:imagem-shiny evoluido)]))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :registro evoluido}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (learn-move! [_ writer-ctx request]
    (let [payload (ops/learn-move-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     oferta? (boolean (:oferta? request))
                     atualizado (moves/aplicar-aprendizado
                                 poke (:golpe request) (:slot request) oferta?)
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     item-tx (when-let [item-id (:consume-item-id request)]
                               (inventory-adjust-tx db sk item-id -1))
                     tx (cond-> (vec (concat (or item-tx [])
                                             [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                 [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes atualizado))]
                                 [:db/add lookup :pokemon/historico-golpes-edn
                                  (pr-str (:historico-golpes atualizado))]
                                 [:db/add lookup :pokemon/golpes-removidos-edn
                                  (pr-str (:golpes-removidos atualizado))]
                                 {:operation-receipt/id operation-id
                                  :operation-receipt/kind "learn-move"
                                  :operation-receipt/channel "gameplay"
                                  :operation-receipt/payload-hash phash
                                  :operation-receipt/player-key pk
                                  :operation-receipt/save-id (:save-id writer-ctx)
                                  :operation-receipt/summary
                                  (pr-str {:idx idx :slug (moves/chave (:golpe request))})}]))
                           oferta?
                           (conj [:db/add lookup :pokemon/ofertas-golpes-edn
                                  (pr-str (or (:ofertas-golpes atualizado)
                                              (:golpes-oferecidos atualizado) []))]))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (set-active-pokemon! [_ writer-ctx request]
    (let [payload (ops/set-active-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add [:save/key sk] :save/ativo-index idx]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "set-active"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:idx idx :nome (:nome poke)})}]]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :ativo-index idx
                           :pokemon poke}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (set-favorite-pokemon! [_ writer-ctx request]
    (let [clear? (boolean (:clear? request))
          payload (ops/set-favorite-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     save-m (->save save)
                     atual-id (:favorito-id-pokemon save-m)]
                 (if clear?
                   (let [tx (cond-> [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                     {:operation-receipt/id operation-id
                                      :operation-receipt/kind "set-favorite"
                                      :operation-receipt/channel "gameplay"
                                      :operation-receipt/payload-hash phash
                                      :operation-receipt/player-key pk
                                      :operation-receipt/save-id (:save-id writer-ctx)
                                      :operation-receipt/summary (pr-str {:clear? true})}]
                              atual-id
                              (conj [:db/retract [:save/key sk] :save/favorito-id-pokemon atual-id]))]
                     {:tx tx
                      :result {:operation-id operation-id
                               :duplicate false
                               :channel "gameplay"
                               :payload-hash phash
                               :clear? true
                               :favorito nil}})
                   (let [idx (long (:idx request))
                         _ (coll/validate-index! idx (count colecao))
                         poke (nth colecao idx)
                         poke-id (:id-pokemon poke)
                         _ (when (str/blank? (str poke-id))
                             (throw (err/error :invalid_collection_index
                                               {:reason "missing-id-pokemon" :idx idx})))
                         tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                             [:db/add [:save/key sk] :save/favorito-id-pokemon poke-id]
                             {:operation-receipt/id operation-id
                              :operation-receipt/kind "set-favorite"
                              :operation-receipt/channel "gameplay"
                              :operation-receipt/payload-hash phash
                              :operation-receipt/player-key pk
                              :operation-receipt/save-id (:save-id writer-ctx)
                              :operation-receipt/summary
                              (pr-str {:idx idx :id poke-id :nome (:nome poke)})}]
                         ativo-idx (long (:ativo-index save-m 0))
                         ativou? (and (pos? (long (:hp-atual poke 0)))
                                      (not= ativo-idx idx))]
                     {:tx (cond-> tx
                            ativou?
                            (conj [:db/add [:save/key sk] :save/ativo-index idx]))
                      :result {:operation-id operation-id
                               :duplicate false
                               :channel "gameplay"
                               :payload-hash phash
                               :clear? false
                               :idx idx
                               :ativado? ativou?
                               :favorito {:id poke-id
                                          :nome (:nome poke)
                                          :collection-index idx}
                               :pokemon poke}})))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (forget-move! [_ writer-ctx request]
    (let [payload (ops/forget-move-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     atualizado (moves/remover-golpe poke (:slot request))
                     removido (nth (mapv moves/registro->golpe (:golpes poke))
                                   (long (:slot request)))
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes atualizado))]
                         [:db/add lookup :pokemon/historico-golpes-edn
                          (pr-str (:historico-golpes atualizado))]
                         [:db/add lookup :pokemon/golpes-removidos-edn
                          (pr-str (:golpes-removidos atualizado))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "forget-move"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:idx idx :slug (moves/chave removido)})}]]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :removido removido
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (offer-move! [_ writer-ctx request]
    (let [payload (ops/offer-move-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     offered (moves/oferecer-golpe poke (:golpe request))
                     atualizado (:registro offered)
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes atualizado))]
                         [:db/add lookup :pokemon/historico-golpes-edn
                          (pr-str (:historico-golpes atualizado))]
                         [:db/add lookup :pokemon/golpes-removidos-edn
                          (pr-str (or (:golpes-removidos atualizado) []))]
                         [:db/add lookup :pokemon/ofertas-golpes-edn
                          (pr-str (or (:ofertas-golpes atualizado)
                                      (:golpes-oferecidos atualizado) []))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "offer-move"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:idx idx
                                   :resultado (:resultado offered)
                                   :slug (moves/chave (:golpe request))})}]]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :resultado (:resultado offered)
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (refuse-offer! [_ writer-ctx request]
    (let [payload (ops/refuse-offer-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     atualizado (moves/recusar-oferta poke)
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add lookup :pokemon/ofertas-golpes-edn
                          (pr-str (or (:ofertas-golpes atualizado) []))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "refuse-offer"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:idx idx})}]]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (relearn-move! [_ writer-ctx request]
    (let [payload (ops/relearn-move-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     slug (str (:slug request))
                     golpe (or (moves/golpe-do-historico poke slug)
                               (throw (err/error :move_not_in_history {:slug slug})))
                     atualizado (moves/preparar-aprendizado poke golpe (:slot request))
                     save-eco (economy-store/entity->economy-save save)
                     preco (long moves/preco-reaprender)
                     _ (economy-store/assert-debit! save-eco preco)
                     coin-part (economy-store/apply-delta-tx
                                {:sk sk :save save :delta (- preco)
                                 :operation-id operation-id :source "relearn"
                                 :role "primary"})
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     tx (into [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                               [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes atualizado))]
                               [:db/add lookup :pokemon/historico-golpes-edn
                                (pr-str (:historico-golpes atualizado))]
                               [:db/add lookup :pokemon/golpes-removidos-edn
                                (pr-str (:golpes-removidos atualizado))]
                               {:operation-receipt/id operation-id
                                :operation-receipt/kind "relearn-move"
                                :operation-receipt/channel "gameplay"
                                :operation-receipt/payload-hash phash
                                :operation-receipt/player-key pk
                                :operation-receipt/save-id (:save-id writer-ctx)
                                :operation-receipt/summary
                                (pr-str {:idx idx :slug slug :coins (- preco)})}]
                              (:tx coin-part))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :slug slug
                           :preco preco
                           :coins-depois (:operational-after coin-part)
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (start-wild-battle! [_ writer-ctx request]
    (let [payload (ops/start-battle-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          battle-id (or (:battle-id request) operation-id)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     prev (active-battle-id-for db (:chat-id writer-ctx) (:player-id writer-ctx))
                     _ (when prev
                         (let [b (battle-entity db prev)
                               st (when (present? b) (->battle b))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     idle (battle-idle-store/touch-attrs agora)
                     state (merge (battle/create-wild-battle
                                   (merge request
                                          {:battle-id battle-id
                                           :chat-id (:chat-id writer-ctx)
                                           :player-id (:player-id writer-ctx)
                                           :save-id (:save-id writer-ctx)
                                           :fence-generation (:fence-generation writer-ctx)}))
                                  {:last-activity-at (:battle/last-activity-at idle)
                                   :idle-expires-at (:battle/idle-expires-at idle)})
                     rev (long (:save/revision save 0))]
                 {:tx [(merge {:battle/id battle-id
                               :battle/save [:save/key sk]
                               :battle/player-key pk
                               :battle/save-id (:save-id writer-ctx)
                               :battle/kind "wild"
                               :battle/active? true
                               :battle/turn-index 0
                               :battle/phase "active"
                               :battle/collection-index (long (:collection-index state 0))
                               :battle/state-edn (battle/encode-state-edn state)}
                              idle)
                       [:db/add [:player/key pk] :player/active-battle-id battle-id]
                       [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                       {:operation-receipt/id operation-id
                        :operation-receipt/kind "start-battle"
                        :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key pk
                        :operation-receipt/save-id (:save-id writer-ctx)
                        :operation-receipt/summary (pr-str {:battle-id battle-id})}]
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle state}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (confirm-battle-turn! [_ writer-ctx request]
    (let [payload (ops/battle-turn-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora-pre (long (or (:agora request) (System/currentTimeMillis)))
          battle-id (:battle-id request)
          ;; Eager expire se prazo já passou — impede ação e limpa ponteiros.
          _ (when battle-id
              (let [be0 (battle-entity (d/db conn) battle-id)]
                (when (and (present? be0)
                           (not (true? (:battle/idle-expired? be0)))
                           (battle-idle/expired? (:battle/idle-expires-at be0) agora-pre))
                  (battle-idle-store/expire-battle! conn battle-id agora-pre)
                  (throw (err/error :battle_idle_expired
                                    {:battle-id battle-id
                                     :expires-at (:battle/idle-expires-at be0)
                                     :agora agora-pre})))))]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [battle-id (:battle-id request)
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :battle_not_found {:battle-id battle-id})))
                     _ (when-not (= (:battle/save-id be) (:save-id writer-ctx))
                         (throw (err/error :battle_save_mismatch
                                           {:battle (:battle/save-id be)
                                            :writer (:save-id writer-ctx)})))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     _ (battle-idle-store/assert-battle-not-idle-expired! be agora)
                     state (->battle be)
                     expected (long (:expected-turn-index request (:turn-index state)))
                     _ (when-not (= expected (long (:turn-index state)))
                         (throw (err/error :stale_turn_index
                                           {:expected expected
                                            :actual (:turn-index state)})))
                     action (keyword (:action request))
                     item-id (when (= :item action)
                               (shop/normalizar-item (str (:item-id request))))
                     ;; Poção/cura mid-caça: valida inventário antes do turno (legado).
                     inv-tx (when (= :item action)
                              (inventory-adjust-tx db
                                                   (id/save-key (:chat-id writer-ctx)
                                                                (:player-id writer-ctx)
                                                                (:save-id writer-ctx))
                                                   item-id -1))
                     next-state (battle/apply-player-turn
                                 state
                                 {:action action
                                  :move-index (:move-index request)
                                  :item-id item-id}
                                 (:rng request))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     rev (long (:save/revision save 0))
                     colecao (query-collection db sk)
                     gym? (= :gym (:kind next-state))
                     gym-finished? (and gym? (not (:active? next-state)))
                     idx (long (or (get-in next-state [:indices-ativos :x])
                                   (:collection-index next-state 0)))
                     poke (when (and (seq colecao) (< idx (count colecao)))
                            (nth colecao idx))
                     save-m (->save save)
                     next-state (if (:aguardando-captura? next-state)
                                  (assoc next-state
                                         :tentativas-captura 0
                                         :chance-base-captura
                                         (capture/chance-base-pos-batalha
                                          (get-in next-state [:pokemons :o])
                                          (get-in next-state [:status :o])
                                          (:sequencia-capturas save-m 0)))
                                  next-state)
                     ;; Bolsa cheia na vitória (somente-batalha ou capacidade esgotada):
                     ;; libera selvagem + XP raridade+1 — espelha preparar-captura master.
                     next-state (if (and (:aguardando-captura? next-state)
                                         (or (:somente-batalha? next-state)
                                             (not (capacity/cabe-pokemon?
                                                   (count colecao) save-m))))
                                  (assoc next-state
                                         :aguardando-captura? false
                                         :phase :finished
                                         :selvagem-liberado-bolsa-cheia? true)
                                  next-state)
                     ;; Master: vitória sem captura → raridade+1 XP Pokémon, 0 moedas.
                     rew (if (:selvagem-liberado-bolsa-cheia? next-state)
                           (let [r (capture/recompensa-vitoria-sem-captura
                                    {:raridade (or (get-in next-state [:pokemons :o :raridade])
                                                   (get-in next-state [:pokemons :o "raridade"])
                                                   "comum")})]
                             {:pokemon-xp (long (:xp r)) :coins 0
                              :bonus-xp (:bonus-xp r)})
                           (battle/reward-for-outcome (:outcome next-state)))
                     xp-part (when (and (not gym?) poke (pos? (long (:pokemon-xp rew 0)))
                                        (not (:active? next-state))
                                        (not (:aguardando-captura? next-state)))
                               (apply-pokemon-xp-tx
                                (assoc poke :hp-atual (get-in next-state [:hp :x]))
                                (long (:pokemon-xp rew 0))))
                     hp-sync (if gym?
                               (gym-battle-hp-txs colecao next-state)
                               (pokemon-hp-status-tx
                                poke (get-in next-state [:hp :x])
                                (get-in next-state [:status :x])
                                (:status poke)))
                     clear-active (when (and (not (:active? next-state))
                                             (not (:aguardando-captura? next-state)))
                                    (into [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
                                          (or (clear-chat-wild-tx db (:chat-id writer-ctx) battle-id) [])))
                     colecao' (if gym-finished?
                                (sync-colecao-from-gym-battle colecao next-state)
                                colecao)
                     venceu? (= :win (:outcome next-state))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     dia (or (:dia request)
                             (mundo/dia-atual (java.time.Instant/ofEpochMilli agora)))
                     pedra-roll (or (:pedra-roll request)
                                    (get-in request [:rng :pedra])
                                    (rand-int 100))
                     gk (when gym-finished?
                          (id/gym-occupation-key (:chat-id writer-ctx) (:gym-id next-state)))
                     slot (when gym-finished?
                            (d/pull db '[*] [:gym-occupation/key gk]))
                     statistics (when gym-finished?
                                  (d/pull db '[*]
                                          [:gym-statistics/key
                                           (gym-store/statistics-key
                                            (:chat-id writer-ctx) (:gym-id next-state))]))
                     ocup (when gym-finished?
                            (gym-store/occupation->map slot edn-read present?))
                     resolve-part
                     (when gym-finished?
                       (gym-store/build-resolve-tx
                        {:db db :save save :writer-ctx writer-ctx
                         :omit-receipt? true
                         :request {:operation-id operation-id
                                   :payload-hash phash
                                   :gym-id (:gym-id next-state)
                                   :venceu? venceu?
                                   :dia dia
                                   :agora agora
                                   :pedra-roll pedra-roll
                                   :nome (:desafiante-nome next-state)
                                   :participacao (or (get-in next-state [:participacao :x]) {})
                                   :ginasios (:ginasios save-m {})
                                   :indices (when venceu? (:team-x-indices next-state))
                                   :expected-revision (:expected-revision next-state 0)}
                         :ocupacao ocup :colecao colecao'
                         :statistics-entity statistics
                         :slot-revision (long (or (:gym-occupation/revision slot) 0))
                         :query-collection query-collection
                         :save-entity save-entity
                         :player-entity player-entity
                         :pokemon-entity-map pokemon-entity-map
                         :apply-pokemon-xp-tx apply-pokemon-xp-tx
                         :inventory-adjust-tx inventory-adjust-tx}))
                     still-open? (or (:active? next-state) (:aguardando-captura? next-state))
                     idle-touch (when still-open?
                                  (battle-idle-store/touch-attrs agora))
                     next-state (cond-> next-state
                                  idle-touch
                                  (assoc :last-activity-at (:battle/last-activity-at idle-touch)
                                         :idle-expires-at (:battle/idle-expires-at idle-touch)))
                     battle-core (cond-> [[:db/cas [:battle/id battle-id] :battle/turn-index
                                           expected (long (:turn-index next-state))]
                                          [:db/add [:battle/id battle-id] :battle/active?
                                           (boolean (:active? next-state))]
                                          [:db/add [:battle/id battle-id] :battle/phase
                                           (name (:phase next-state))]
                                          [:db/add [:battle/id battle-id] :battle/state-edn
                                           (battle/encode-state-edn next-state)]
                                          {:operation-receipt/id operation-id
                                           :operation-receipt/kind "battle-turn"
                                           :operation-receipt/channel "gameplay"
                                           :operation-receipt/payload-hash phash
                                           :operation-receipt/player-key pk
                                           :operation-receipt/save-id (:save-id writer-ctx)
                                           :operation-receipt/summary
                                           (pr-str {:battle-id battle-id
                                                    :turn (:turn-index next-state)
                                                    :outcome (:outcome next-state)
                                                    :kind (:kind next-state)
                                                    :gym-id (:gym-id next-state)})}]
                                   idle-touch (into (map (fn [[k v]]
                                                           [:db/add [:battle/id battle-id] k v])
                                                         idle-touch)))
                     ;; Ginásio finalizado: revision CAS vem do resolve.
                     rev-tx (when-not gym-finished?
                              [[:db/cas [:save/key sk] :save/revision rev (inc rev)]])
                     tx (into (into battle-core (or rev-tx []))
                              (concat (or inv-tx [])
                                      (or hp-sync [])
                                      (or clear-active [])
                                      (or (:tx xp-part) [])
                                      (or (:tx resolve-part) [])))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle next-state
                           :subida (:subida xp-part)
                           :reward (if gym-finished? (:reward (:result resolve-part)) rew)
                           :gym-resolve (when gym-finished? (:result resolve-part))}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (get-battle [_ battle-id]
    (->battle (battle-entity (d/db conn) battle-id)))

  (active-battle [_ chat-id player-id]
    (when-let [bid (active-battle-id-for (d/db conn) chat-id player-id)]
      (let [b (->battle (battle-entity (d/db conn) bid))]
        (when (or (:active? b) (:aguardando-captura? b))
          b))))

  (get-gym-occupation [_ chat-id gym-id]
    (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key (id/gym-occupation-key chat-id gym-id)])]
      (gym-store/occupation->map e edn-read present?)))

  (get-gym-statistics [_ chat-id gym-id]
    (let [e (d/pull (d/db conn) '[*]
                    [:gym-statistics/key (gym-store/statistics-key chat-id gym-id)])]
      (gym-store/statistics->map e edn-read present?)))

  (list-gym-occupations-for-player [_ chat-id player-id]
    (let [pk (id/player-key chat-id player-id)
          rows (d/q '[:find (pull ?g [*])
                      :in $ ?pk
                      :where [?g :gym-occupation/player-key ?pk]]
                    (d/db conn) pk)]
      (mapv (fn [[e]] (gym-store/occupation->map e edn-read present?)) rows)))

  (set-gym-team! [_ writer-ctx indices]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             idxs (mapv long indices)
             _ (when-not (and (= 3 (count idxs)) (= 3 (count (set idxs))))
                 (throw (err/error :invalid_gym_team {:indices idxs})))
             colecao (query-collection db sk)
             _ (doseq [i idxs] (coll/validate-index! i (count colecao)))
             rev (long (:save/revision save 0))]
         {:tx [[:db/add [:save/key sk] :save/time-ginasio-edn (pr-str idxs)]
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:time-ginasio idxs}}))))

  (list-named-teams [_ chat-id player-id save-id]
    (let [db (d/db conn)
          _ (when-not (present? (player-entity db chat-id player-id))
              (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
          e (save-entity db chat-id player-id save-id)
          _ (when-not (present? e)
              (throw (err/error :save_not_found {:save-id save-id})))
          teams (or (edn-read (:save/times-prontos-edn e)) {})
          collection (query-collection db (id/save-key chat-id player-id save-id))
          by-id (into {} (map (juxt :id-pokemon identity) collection))]
      (mapv (fn [[name team]]
              (let [ids (vec (:pokemon-ids team []))]
                {:name name
                 :pokemon-ids ids
                 :indices (mapv #(some-> (get by-id %) :collection-index) ids)
                 :available? (every? #(contains? by-id %) ids)}))
            (sort-by key teams))))

  (save-named-team! [_ writer-ctx request]
    (let [op (:operation-id request)
          payload (select-keys request [:name :indices :pokemon-ids])
          phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) op)]
      (or (resolve-receipt existing phash op)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [name (normalize-team-name (:name request))
                     idxs (mapv long (or (:indices request) []))
                     _ (when (str/blank? name) (throw (err/error :invalid_team {:reason :blank_name})))
                     _ (when-not (and (= 3 (count idxs)) (= 3 (count (set idxs))))
                         (throw (err/error :invalid_team {:indices idxs})))
                     collection (query-collection db (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx)))
                     _ (doseq [i idxs] (coll/validate-index! i (count collection)))
                     ids (mapv #(get (nth collection %) :id-pokemon) idxs)
                     _ (when (some nil? ids) (throw (err/error :invalid_team {:indices idxs})))
                     teams (assoc (or (edn-read (:save/times-prontos-edn save)) {}) name {:pokemon-ids ids})
                     rev (long (:save/revision save 0))
                     result {:operation-id op :duplicate false :name name :pokemon-ids ids :indices idxs}]
                 {:tx [[:db/add [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/times-prontos-edn (pr-str teams)]
                       [:db/cas [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/revision rev (inc rev)]
                       {:operation-receipt/id op :operation-receipt/kind "named-team-save"
                        :operation-receipt/channel "gameplay" :operation-receipt/payload-hash phash
                        :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                        :operation-receipt/save-id (:save-id writer-ctx)
                        :operation-receipt/summary (pr-str result)}]
                  :result result})))
            (catch Exception ex
              (or (resolve-receipt (receipt-entity (d/db conn) op) phash op) (throw ex)))))))

  (use-named-team! [_ writer-ctx request]
    (let [op (:operation-id request) destination (keyword (str/lower-case (str (or (:destination request) "liga"))))
          payload (select-keys request [:name :destination]) phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) op)]
      (or (resolve-receipt existing phash op)
          (try
            (transact-with-writer! conn writer-ctx
             (fn [db save]
               (let [name (normalize-team-name (:name request))
                     teams (or (edn-read (:save/times-prontos-edn save)) {})
                     ids (vec (get-in teams [name :pokemon-ids]))
                     collection (query-collection db (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx)))
                     by-id (into {} (map (juxt :id-pokemon identity) collection))
                     idxs (mapv by-id ids)
                     _ (when-not (seq ids) (throw (err/error :team_not_found {:name name})))
                     _ (when-not (= 3 (count ids)) (throw (err/error :invalid_team {:name name})))
                     attr (case destination :ginasio :save/time-ginasio-edn :liga :save/time-liga-edn
                                  (throw (err/error :invalid_destination {:destination destination})))
                     _ (when (some nil? idxs) (throw (err/error :team_pokemon_missing {:name name})))
                     league-id (:save/liga-id save)
                     _ (when (and (= destination :liga) (str/blank? (str league-id)))
                         (throw (err/error :league_not_selected {})))
                     _ (when (and (= destination :liga)
                                  (some #(not (raid-domain/pokemon-elegivel-liga? league-id %)) idxs))
                         (throw (err/error :team_league_ineligible {:league-id league-id :name name})))
                     _ (when (and (= destination :liga)
                                  (some #(not (pos? (long (or (:hp-atual %) (:hp %) 0)))) idxs))
                         (throw (err/error :team_pokemon_unhealthy {:name name})))
                     idxs (mapv :collection-index idxs)
                     rev (long (:save/revision save 0))
                     result {:operation-id op :duplicate false :name name :destination (clojure.core/name destination)
                             :pokemon-ids ids :indices idxs}]
                 {:tx [[:db/add [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] attr (pr-str idxs)]
                       [:db/cas [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/revision rev (inc rev)]
                       {:operation-receipt/id op :operation-receipt/kind "named-team-use"
                        :operation-receipt/channel "gameplay" :operation-receipt/payload-hash phash
                        :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                        :operation-receipt/save-id (:save-id writer-ctx)
                        :operation-receipt/summary (pr-str result)}]
                  :result result})))
            (catch Exception ex
              (or (resolve-receipt (receipt-entity (d/db conn) op) phash op) (throw ex)))))))

  (delete-named-team! [_ writer-ctx request]
    (let [op (:operation-id request) payload (select-keys request [:name]) phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) op)]
      (or (resolve-receipt existing phash op)
          (try
            (transact-with-writer! conn writer-ctx
             (fn [db save]
               (let [name (normalize-team-name (:name request))
                     teams (or (edn-read (:save/times-prontos-edn save)) {})
                     _ (when-not (contains? teams name) (throw (err/error :team_not_found {:name name})))
                     rev (long (:save/revision save 0))
                     result {:operation-id op :duplicate false :name name :deleted true}]
                 {:tx [[:db/add [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/times-prontos-edn
                        (pr-str (dissoc teams name))]
                       [:db/cas [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/revision rev (inc rev)]
                       {:operation-receipt/id op :operation-receipt/kind "named-team-delete"
                        :operation-receipt/channel "gameplay" :operation-receipt/payload-hash phash
                        :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                        :operation-receipt/save-id (:save-id writer-ctx)
                        :operation-receipt/summary (pr-str result)}]
                  :result result})))
            (catch Exception ex
              (or (resolve-receipt (receipt-entity (d/db conn) op) phash op) (throw ex)))))))

  (select-league! [_ writer-ctx request]
    (let [op (:operation-id request) league (str/lower-case (str/trim (str (:league-id request))))
          phash (reward/payload-hash {:league-id league})
          existing (receipt-entity (d/db conn) op)]
      (or (resolve-receipt existing phash op)
          (try
            (transact-with-writer! conn writer-ctx
             (fn [_db save]
               (when-not (raid-domain/obter-liga league)
                 (throw (err/error :invalid_league {:league-id league})))
               (let [rev (long (:save/revision save 0))
                     result {:operation-id op :duplicate false :league-id league}]
                 {:tx [[:db/add [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/liga-id league]
                       [:db/cas [:save/key (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))] :save/revision rev (inc rev)]
                       {:operation-receipt/id op :operation-receipt/kind "league-select" :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                        :operation-receipt/save-id (:save-id writer-ctx) :operation-receipt/summary (pr-str result)}]
                  :result result})))
            (catch Exception ex
              (or (resolve-receipt (receipt-entity (d/db conn) op) phash op) (throw ex)))))))

  (start-gym-challenge! [_ writer-ctx request]
    (let [payload (ops/gym-challenge-start-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          gym-id (str (:gym-id request))
          chat-id (:chat-id writer-ctx)
          gk (id/gym-occupation-key chat-id gym-id)
          _ (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key gk])]
              (when-not (present? e)
                (try
                  (d/transact conn {:tx-data [{:gym-occupation/key gk
                                              :gym-occupation/chat-id chat-id
                                              :gym-occupation/gym-id gym-id
                                              :gym-occupation/revision 0}]})
                  (catch Exception _))))
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [g (or (gym/obter-ginasio gym-id)
                           (throw (err/error :unknown_gym {:gym-id gym-id})))
                     player-id (:player-id writer-ctx)
                     save-id (:save-id writer-ctx)
                     pk (id/player-key chat-id player-id)
                     sk (id/save-key chat-id player-id save-id)
                     save-m (->save save)
                     insignias (:ginasios save-m {})
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     dia (or (:dia request)
                             (mundo/dia-atual (java.time.Instant/ofEpochMilli agora)))
                     _ (when-not (gym/desbloqueado? insignias gym-id)
                         (throw (err/error :gym_locked {:gym-id gym-id})))
                     prev (active-battle-id-for db chat-id player-id)
                     _ (when prev
                         (let [st (->battle (battle-entity db prev))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     slot (d/pull db '[*] [:gym-occupation/key gk])
                     ocup (gym-store/occupation->map slot edn-read present?)
                     _ (when (and ocup (= (:player-key ocup) pk)
                                  (= (:save-id ocup) save-id))
                         (throw (err/error :cannot_challenge_own_gym
                                           {:gym-id gym-id :save-id save-id})))
                     _ (when (and (nil? ocup)
                                  (= dia (get insignias gym-id)))
                         (throw (err/error :gym_daily_limit
                                           {:gym-id gym-id :dia dia})))
                     idxs (vec (or (:time-ginasio save-m) []))
                     colecao (query-collection db sk)
                     _ (when-not (and (= 3 (count idxs)) (= 3 (count (set idxs))))
                         (throw (err/error :invalid_gym_team {:indices idxs})))
                     team-regs (mapv (fn [i]
                                       (coll/validate-index! i (count colecao))
                                       (nth colecao i))
                                     idxs)
                     _ (when-not (every? #(pos? (long (:hp-atual % 0))) team-regs)
                         (throw (err/error :gym_team_unhealthy {:indices idxs})))
                     team-x (mapv (fn [i poke]
                                    (assoc poke :collection-index (long i)))
                                  idxs team-regs)
                     team-o (if ocup
                              (gym/time-defensor ocup agora)
                              (let [cands (or (:candidates request) [])]
                                (when-not (= 3 (count cands))
                                  (throw (err/error :invalid_gym_npc_team
                                                    {:count (count cands)
                                                     :expected (:time g)})))
                                (scale-npc-team cands g)))
                     expected-rev (long (:gym-occupation/revision slot 0))
                     battle-id (or (:battle-id request) operation-id)
                     state (battle/create-gym-battle
                            {:battle-id battle-id
                             :chat-id chat-id
                             :player-id player-id
                             :save-id save-id
                             :fence-generation (:fence-generation writer-ctx)
                             :gym-id gym-id
                             :gym-nome (:nome g)
                             :lider-nome (or (:nome ocup) (:lider g))
                             :desafiante-nome (or (:nome request) player-id)
                             :team-x team-x
                             :team-o team-o
                             :expected-revision expected-rev})
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     idle (battle-idle-store/touch-attrs agora)
                     state (assoc state
                                  :last-activity-at (:battle/last-activity-at idle)
                                  :idle-expires-at (:battle/idle-expires-at idle))
                     rev (long (:save/revision save 0))]
                 {:tx [(merge {:battle/id battle-id
                               :battle/save [:save/key sk]
                               :battle/player-key pk
                               :battle/save-id save-id
                               :battle/kind "gym"
                               :battle/active? true
                               :battle/turn-index 0
                               :battle/phase "active"
                               :battle/collection-index (long (first idxs))
                               :battle/state-edn (battle/encode-state-edn state)}
                              idle)
                       [:db/add [:player/key pk] :player/active-battle-id battle-id]
                       [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                       {:operation-receipt/id operation-id
                        :operation-receipt/kind "gym-challenge-start"
                        :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key pk
                        :operation-receipt/save-id save-id
                        :operation-receipt/summary
                        (pr-str {:battle-id battle-id :gym-id gym-id})}]
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle-id battle-id
                           :battle state
                           :gym-id gym-id}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (resolve-gym-challenge! [_ writer-ctx request]
    (let [payload (ops/gym-resolve-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          gym-id (:gym-id request)
          chat-id (:chat-id writer-ctx)
          gk (id/gym-occupation-key chat-id gym-id)
          ;; Stub rev=0 para CAS na primeira ocupação (corrida segura).
          _ (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key gk])]
              (when-not (present? e)
                (try
                  (d/transact conn {:tx-data [{:gym-occupation/key gk
                                              :gym-occupation/chat-id chat-id
                                              :gym-occupation/gym-id gym-id
                                              :gym-occupation/revision 0}]})
                  (catch Exception _))))
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id (:player-id writer-ctx) (:save-id writer-ctx))
                     save-m (->save save)
                     slot (d/pull db '[*] [:gym-occupation/key gk])
                     statistics (d/pull db '[*]
                                         [:gym-statistics/key
                                          (gym-store/statistics-key chat-id gym-id)])
                     ocup (gym-store/occupation->map slot edn-read present?)
                     slot-rev (long (:gym-occupation/revision slot 0))
                     colecao (query-collection db sk)]
                 (gym-store/build-resolve-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request
                                   :ginasios (:ginasios save-m {})
                                   :payload-hash phash)
                   :ocupacao ocup :colecao colecao
                   :statistics-entity statistics
                   :slot-revision slot-rev
                   :query-collection query-collection
                   :save-entity save-entity
                   :player-entity player-entity
                   :pokemon-entity-map pokemon-entity-map
                   :apply-pokemon-xp-tx apply-pokemon-xp-tx
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (heal-gym-motivation! [_ writer-ctx request]
    (let [payload (reward/canonicalize
                   {:gym-id (:gym-id request)
                    :indice (long (:indice request))
                    :item-id (:item-id request)
                    :pontos (long (:pontos request))})
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [ocup (let [e (d/pull db '[*] [:gym-occupation/key
                                                   (id/gym-occupation-key (:chat-id writer-ctx)
                                                                          (:gym-id request))])]
                            (gym-store/occupation->map e edn-read present?))]
                 (gym-store/build-heal-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :ocupacao ocup
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (get-raid [_ raid-id]
    (load-raid (d/db conn) raid-id))

  (active-raid [_ chat-id]
    (let [ptr (d/pull (d/db conn) '[*] [:chat-active-raid/chat-id chat-id])]
      (when (and (present? ptr) (:chat-active-raid/raid-id ptr))
        (load-raid (d/db conn) (:chat-active-raid/raid-id ptr)))))

  (expire-raid-lobby! [_ chat-id agora]
    (expire-raid-lobby-conn! conn chat-id agora))

  (get-raid-auto-schedule [_ chat-id]
    (load-schedule (d/db conn) chat-id))

  (upsert-raid-auto-schedule! [_ schedule]
    (let [chat-id (:chat-id schedule)
          db (d/db conn)
          cur (load-schedule db chat-id)
          expected (long (:revision cur 0))
          s (raid-auto/normalize-schedule schedule chat-id
                                          (or (:next-at schedule)
                                              (System/currentTimeMillis)))
          built (raid-store/build-upsert-schedule-tx
                 {:schedule (assoc s :revision expected)
                  :expected-revision expected})
          cadence-exists? (present?
                           (receipt-entity
                            db (raid-auto/cadence-receipt-id chat-id)))
          intent-exists? (present?
                          (receipt-entity
                           db (raid-auto/intent-receipt-id chat-id)))
          cadence-tx (when (or (:category-intervals schedule)
                               (some? (:daily-limit schedule))
                               (some? (:special-reserve schedule)))
                       (raid-store/cadence-receipt-tx
                        chat-id
                        (cond-> {}
                          (:category-intervals schedule)
                          (assoc :category-intervals (:category-intervals schedule))
                          (some? (:daily-limit schedule))
                          (assoc :daily-limit (:daily-limit schedule))
                          (some? (:special-reserve schedule))
                          (assoc :special-reserve (:special-reserve schedule)))
                        cadence-exists?))
          intent-tx (when (and (:next-categoria s) (:next-at s))
                      (raid-store/intent-receipt-tx
                       chat-id
                       {:next-at (long (:next-at s))
                        :categoria (str (:next-categoria s))
                        :reminders?
                        (boolean
                         (or (:next-reminders? s)
                             (raid-auto/special-category?
                              (:next-categoria s))))}
                       intent-exists?))
          tx-data (cond-> (:tx built)
                    cadence-tx (conj cadence-tx)
                    intent-tx (conj intent-tx))]
      (try
        (d/transact conn {:tx-data tx-data})
        (enrich-schedule (d/db conn) (:schedule (:result built)))
        (catch Exception ex
          (if (err/conflict? ex)
            (throw (err/error :raid_auto_schedule_conflict
                              {:chat-id chat-id}))
            (throw ex))))))

  (tick-raid-auto! [_ request]
    (let [agora (long (or (:agora request) (System/currentTimeMillis)))
          limit (long (or (:limit request) 32))
          chat-filter (:chat-id request)
          ;; 1) Expire lobbies vencidos antes de spawn (libera ponteiro).
          expired
          (mapv
           (fn [chat]
             (let [r (let [ptr (d/pull (d/db conn) '[*]
                                       [:chat-active-raid/chat-id chat])
                           rid (when (present? ptr)
                                 (:chat-active-raid/raid-id ptr))]
                       (when rid (load-raid (d/db conn) rid)))]
               (if (and r (= "inscricoes" (str (:fase r)))
                        (<= (long (:expira r 0)) agora))
                 (let [txt (if (= "auto" (str (:origem r)))
                             (let [preview (raid-domain/expirar-inscricoes r agora)]
                               (case (:acao preview)
                                 :combate (raid-auto/format-started (:raid preview))
                                 :cancelada (raid-auto/format-cancelled r)
                                 nil))
                             nil)
                       after (expire-raid-lobby-conn! conn chat agora txt)
                       acao (cond
                              (nil? after) :gone
                              (= "combate" (:fase after)) :combate
                              (= "cancelada" (:fase after)) :cancelada
                              :else :noop)
                       row {:chat-id chat :acao acao
                            :raid-id (:raid-id r)
                            :due-at (:expira r)
                            :origem (:origem r)}]
                   (raid-auto-log/emit!
                    :raid_auto_lobby_expired
                    {:chat-id chat :raid-id (:raid-id r)
                     :due-at (:expira r) :origem (:origem r)
                     :result (name acao)})
                   (when (= :combate acao)
                     (raid-auto-log/emit!
                      :raid_auto_started
                      {:chat-id chat :raid-id (:raid-id r)
                       :due-at (:expira r) :result "combate"}))
                   (when (= :cancelada acao)
                     (raid-auto-log/emit!
                      :raid_auto_cancelled
                      {:chat-id chat :raid-id (:raid-id r)
                       :due-at (:expira r) :result "cancelada"}))
                   row)
                 {:chat-id chat :acao :noop})))
           (active-raid-chats (d/db conn)))
          ;; 2) Lembretes órfãos (enabled:false / nextAt mudou) → ack sem entrega.
          suppressed (suppress-stale-reminders! conn agora)
          db1 (d/db conn)
          ;; 3) 12.7.3 — intent do slot atual antes dos ovos (sem spawn).
          intent-chats
          (if (string? chat-filter)
            [chat-filter]
            (intent-planning-candidate-chats db1 agora limit))
          planned (mapv #(ensure-intent-for-chat! conn % agora) intent-chats)
          db1b (d/db conn)
          ;; 4) Lembretes T-30/15/5 (antes do spawn oficial).
          reminder-chats
          (if (string? chat-filter)
            (let [s (load-schedule db1b chat-filter)]
              (if (and s (:enabled s)
                       (< agora (long (:next-at s 0)))
                       (<= (long (:next-at s 0))
                           (+ agora raid-auto/reminder-offset-30-ms)))
                [chat-filter]
                []))
            (reminder-candidate-chats db1b agora limit))
          reminded (vec (mapcat #(emit-reminders-for-chat! conn % agora)
                                reminder-chats))
          db2 (d/db conn)
          ;; 5) Spawns due.
          due (if (string? chat-filter)
                (let [s (load-schedule db2 chat-filter)]
                  (if (and s (raid-auto/due? s agora)) [chat-filter] []))
                (due-schedule-chats db2 agora limit))
          spawned (mapv #(spawn-auto-for-chat! conn % agora) due)]
      {:enabled true
       :agora agora
       :planned (filterv #(not= :noop (:acao %)) planned)
       :spawned spawned
       :reminded reminded
       :suppressed suppressed
       :expired (filterv #(not= :noop (:acao %)) expired)}))
  (list-pokemon-outbox [_ limit]
    (let [db (d/db conn)
          rows (->> (d/q '[:find ?id ?created
                           :where
                           [?e :pokemon-outbox/id ?id]
                           [?e :pokemon-outbox/created-at ?created]]
                         db)
                    (sort-by second)
                    vec)]
      (->> rows
           (keep (fn [[id _]]
                   (when-let [ev (raid-store/outbox->map
                                  (d/pull db '[*] [:pokemon-outbox/id id])
                                  present?)]
                     (if-let [rid (raid-store/opened-raid-id (:id ev))]
                       (if-let [media (raid-store/opened-media-from-raid
                                       (load-raid db rid))]
                         (assoc ev :media media)
                         ev)
                       ev))))
           (take (long (or limit 20)))
           vec)))

  (ack-pokemon-outbox! [_ event-id agora]
    (let [db (d/db conn)
          e (d/pull db '[*] [:pokemon-outbox/id event-id])]
      (cond
        (not (present? e))
        {:acked false :reason "not_found"}

        (some? (:pokemon-outbox/acked-at e))
        {:acked true :duplicate true :id event-id}

        :else
        (try
          (d/transact conn {:tx-data
                            [[:db/add [:pokemon-outbox/id event-id]
                              :pokemon-outbox/acked-at (long agora)]]})
          {:acked true :duplicate false :id event-id}
          (catch Exception ex
            (if (err/conflict? ex)
              {:acked true :duplicate true :id event-id}
              (throw ex)))))))

  (open-raid! [_ writer-ctx request]
    (let [payload (ops/raid-open-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora (or (:agora request) (System/currentTimeMillis))
          chat-id (:chat-id writer-ctx)]
      (or (resolve-receipt existing phash operation-id)
          (do
            (expire-raid-lobby-conn! conn chat-id agora)
            (ensure-chat-raid-pointer! conn chat-id)
            (try
              (transact-with-writer!
               conn writer-ctx
               (fn [db _save]
                 (let [ptr (d/pull db '[*] [:chat-active-raid/chat-id chat-id])
                       rid (when (present? ptr) (:chat-active-raid/raid-id ptr))
                       active (when rid (load-raid db rid))]
                   (raid-store/build-open-tx
                    {:active-raid active
                     :request (assoc request
                                     :chat-id chat-id
                                     :payload-hash phash
                                     :agora agora)}))))
              (catch Exception ex
                (let [dup (receipt-entity (d/db conn) operation-id)]
                  (if (present? dup)
                    (resolve-receipt dup phash operation-id)
                    ;; Corrida no CAS do ponteiro → chat busy se já houver raide.
                    (let [ptr (d/pull (d/db conn) '[*]
                                      [:chat-active-raid/chat-id chat-id])
                          rid (when (present? ptr)
                                (:chat-active-raid/raid-id ptr))
                          active (when rid (load-raid (d/db conn) rid))]
                      (if (and active (raid-domain/lobby-ou-combate-vivo? active agora))
                        (throw (err/error :raid_chat_busy
                                          {:raid-id (:raid-id active)
                                           :fase (:fase active)}))
                        (throw ex)))))))))))

  (join-raid! [_ writer-ctx request]
    (let [payload (ops/raid-join-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora (or (:agora request) (System/currentTimeMillis))]
      (or (resolve-receipt existing phash operation-id)
          (do
            (expire-raid-lobby-conn! conn (:chat-id writer-ctx) agora)
            (try
              (transact-with-writer!
               conn writer-ctx
               (fn [db save]
                 (let [raid (or (load-raid db (:raid-id request))
                                (throw (err/error :raid_not_found
                                                  {:raid-id (:raid-id request)})))
                       sk (id/save-key (:chat-id writer-ctx)
                                       (:player-id writer-ctx)
                                       (:save-id writer-ctx))
                       colecao (query-collection db sk)]
                   (raid-store/build-join-tx
                    {:db db :save save :writer-ctx writer-ctx
                     :request (assoc request :payload-hash phash :agora agora)
                     :raid raid :colecao colecao}))))
              (catch Exception ex
                (let [dup (receipt-entity (d/db conn) operation-id)]
                  (if (present? dup)
                    (resolve-receipt dup phash operation-id)
                    (throw ex)))))))))

  (leave-raid! [_ writer-ctx request]
    (let [payload (ops/raid-join-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora (or (:agora request) (System/currentTimeMillis))]
      (or (resolve-receipt existing phash operation-id)
          (do
            (expire-raid-lobby-conn! conn (:chat-id writer-ctx) agora)
            (try
              (transact-with-writer!
               conn writer-ctx
               (fn [db save]
                 (let [raid (or (load-raid db (:raid-id request))
                                (throw (err/error :raid_not_found
                                                  {:raid-id (:raid-id request)})))]
                   (raid-store/build-leave-tx
                    {:save save :writer-ctx writer-ctx
                     :request (assoc request :payload-hash phash :agora agora)
                     :raid raid}))))
              (catch Exception ex
                (let [dup (receipt-entity (d/db conn) operation-id)]
                  (if (present? dup)
                    (resolve-receipt dup phash operation-id)
                    (throw ex)))))))))

  (start-raid! [_ writer-ctx request]
    (let [payload (reward/canonicalize
                   {:raid-id (:raid-id request) :action "start"})
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora (or (:agora request) (System/currentTimeMillis))]
      (or (resolve-receipt existing phash operation-id)
          (do
            (expire-raid-lobby-conn! conn (:chat-id writer-ctx) agora)
            (try
              (transact-with-writer!
               conn writer-ctx
               (fn [db save]
                 (let [raid (or (load-raid db (:raid-id request))
                                (throw (err/error :raid_not_found
                                                  {:raid-id (:raid-id request)})))]
                   (raid-store/build-start-tx
                    {:save save :writer-ctx writer-ctx
                     :request (assoc request :payload-hash phash :agora agora)
                     :raid raid}))))
              (catch Exception ex
                (let [dup (receipt-entity (d/db conn) operation-id)]
                  (if (present? dup)
                    (resolve-receipt dup phash operation-id)
                    (throw ex)))))))))

  (cancel-raid! [_ writer-ctx request]
    (let [payload (reward/canonicalize
                   {:raid-id (:raid-id request) :action "cancel"})
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora (or (:agora request) (System/currentTimeMillis))]
      (or (resolve-receipt existing phash operation-id)
          (do
            (expire-raid-lobby-conn! conn (:chat-id writer-ctx) agora)
            (try
              (transact-with-writer!
               conn writer-ctx
               (fn [db save]
                 (let [raid (or (load-raid db (:raid-id request))
                                (throw (err/error :raid_not_found
                                                  {:raid-id (:raid-id request)})))]
                   (raid-store/build-cancel-tx
                    {:save save :writer-ctx writer-ctx
                     :request (assoc request :payload-hash phash :agora agora)
                     :raid raid}))))
              (catch Exception ex
                (let [dup (receipt-entity (d/db conn) operation-id)]
                  (if (present? dup)
                    (resolve-receipt dup phash operation-id)
                    (throw ex)))))))))

  (raid-attack! [_ writer-ctx request]
    (let [payload (ops/raid-attack-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))]
                 (raid-store/build-attack-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid
                   :apply-pokemon-xp-tx apply-pokemon-xp-tx
                   :save-entity save-entity
                   :query-collection query-collection}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (raid-capture! [_ writer-ctx request]
    (let [payload (ops/raid-capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))
                     sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     save-m (->save save)]
                 (raid-store/build-capture-tx
                  {:db db :save save :save-m save-m :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid :colecao colecao
                   :pokemon-entity-map pokemon-entity-map
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (raid-frambo-prepare! [_ writer-ctx request]
    (let [payload (ops/raid-frambo-prep-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))]
                 (raid-store/build-frambo-prepare-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (ensure-adventure-account! [_ chat-id player-id]
    (let [ak (id/adventure-account-key chat-id player-id)
          e (d/pull (d/db conn) '[*] [:adventure-account/key ak])]
      (if (present? e)
        (load-adventure (d/db conn) chat-id player-id)
        (do
          (d/transact conn {:tx-data (:tx (adventure-store/build-ensure-account-tx
                                           chat-id player-id))})
          (load-adventure (d/db conn) chat-id player-id)))))

  (get-adventure-account [_ chat-id player-id]
    (load-adventure (d/db conn) chat-id player-id))

  (get-egg [_ egg-id]
    (adventure-store/egg-entity->map
     (d/pull (d/db conn) '[*] [:egg/id egg-id]) edn-read present?))

  (grant-egg! [_ writer-ctx request]
    (let [payload (ops/egg-grant-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db _save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     _ (when-not (present? e)
                         (throw (err/error :adventure_account_missing {})))
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)]
                 (adventure-store/build-grant-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (incubate-egg! [_ writer-ctx request]
    (let [payload (ops/egg-incubate-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db _save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)]
                 (adventure-store/build-incubate-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (hatch-egg! [_ writer-ctx request]
    (let [payload (ops/egg-hatch-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)
                     sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (adventure-store/build-hatch-tx
                  {:db db :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs
                   :save save :colecao colecao
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (list-enfermaria [_ chat-id player-id save-id]
    (load-enfermaria (d/db conn) (id/save-key chat-id player-id save-id)))

  (joy-send! [_ writer-ctx request]
    (let [payload (ops/joy-send-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (adventure-store/build-joy-send-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :enfermaria (load-enfermaria db sk)
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (joy-collect! [_ writer-ctx request]
    (let [payload (ops/joy-collect-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (adventure-store/build-joy-collect-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :enfermaria (load-enfermaria db sk)
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (buy-item! [_ writer-ctx request]
    (let [payload (ops/shop-buy-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (shop-store/build-buy-tx
                  {:db db
                   :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :inventory (query-inventory db sk)
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (use-item! [_ writer-ctx request]
    (let [payload (ops/shop-use-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (assert-no-busy-battle! db (:chat-id writer-ctx) (:player-id writer-ctx))
                 (shop-store/build-use-tx
                  {:db db
                   :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (open-pvp-challenge! [_ writer-ctx request]
    (let [payload (ops/pvp-open-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-pvp-receipt conn existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     save-id (:save-id writer-ctx)
                     pk (id/player-key chat-id player-id)
                     sk (id/save-key chat-id player-id save-id)
                     _ (pvp-store/assert-player-idle! db chat-id player-id)
                     group-e (d/pull db '[*] [:group/chat-id chat-id])
                     existing-pvp (:group/active-pvp-battle-id group-e)
                     _ (when existing-pvp
                         (let [be (battle-entity db existing-pvp)
                               st (when (present? be) (->battle be))]
                           (when (and st (:active? st)
                                      (or (pvp/aguardando? st)
                                          (= :active (:phase st))))
                             (throw (err/error :pvp_chat_busy
                                               {:battle-id existing-pvp})))))
                     wild (:group/active-wild-battle-id group-e)
                     _ (when wild
                         (throw (err/error :chat_hunt_busy {:battle-id wild})))
                     colecao (query-collection db sk)
                     idx (long (:ativo-index (->save save) 0))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     _ (pvp/assert-healthy-active! poke (:hp-atual poke))
                     battle-id (or (:battle-id request) operation-id)
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     state (battle/create-pvp-challenge
                            {:battle-id battle-id
                             :chat-id chat-id
                             :player-id player-id
                             :player-name (:player-name request)
                             :save-id save-id
                             :fence-generation (:fence-generation writer-ctx)
                             :pokemon-x poke
                             :hp-x (:hp-atual poke)
                             :status-x (:status poke)
                             :collection-index idx
                             :agora agora})
                     idle (battle-idle-store/touch-attrs agora)
                     state (assoc state
                                  :last-activity-at (:battle/last-activity-at idle)
                                  :idle-expires-at (:battle/idle-expires-at idle))
                     rev (long (:save/revision save 0))
                     group-tx (if (present? group-e)
                                [[:db/add [:group/chat-id chat-id]
                                  :group/active-pvp-battle-id battle-id]]
                                [{:group/chat-id chat-id
                                  :group/label "grupo"
                                  :group/active-pvp-battle-id battle-id}])]
                 {:tx (into [(merge {:battle/id battle-id
                                     :battle/save [:save/key sk]
                                     :battle/player-key pk
                                     :battle/save-id save-id
                                     :battle/kind "pvp"
                                     :battle/active? true
                                     :battle/turn-index 0
                                     :battle/phase "waiting"
                                     :battle/collection-index idx
                                     :battle/state-edn (battle/encode-state-edn state)}
                                    idle)
                             [:db/add [:player/key pk] :player/active-battle-id battle-id]
                             [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                             {:operation-receipt/id operation-id
                              :operation-receipt/kind "pvp-open"
                              :operation-receipt/channel "gameplay"
                              :operation-receipt/payload-hash phash
                              :operation-receipt/player-key pk
                              :operation-receipt/save-id save-id
                              :operation-receipt/summary
                              (pr-str {:battle-id battle-id :idx idx})}]
                            group-tx)
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle state}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-pvp-receipt conn dup phash operation-id)
                  (throw ex))))))))

  (accept-pvp-challenge! [_ writer-ctx request]
    (let [payload (ops/pvp-accept-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-pvp-receipt conn existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     save-id (:save-id writer-ctx)
                     pk (id/player-key chat-id player-id)
                     sk (id/save-key chat-id player-id save-id)
                     group-e (d/pull db '[*] [:group/chat-id chat-id])
                     battle-id (or (:battle-id request)
                                   (:group/active-pvp-battle-id group-e))
                     _ (when-not battle-id
                         (throw (err/error :pvp_no_challenge {})))
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :battle_not_found {:battle-id battle-id})))
                     state (->battle be)
                     ;; Self-accept antes do idle — desafiante já tem active-battle-id.
                     _ (when (= player-id (get-in state [:jogadores :x]))
                         (throw (err/error :pvp_self_accept {})))
                     _ (pvp-store/assert-player-idle! db chat-id player-id)
                     _ (when-not (pvp/aguardando? state)
                         (throw (err/error :pvp_not_waiting {:phase (:phase state)})))
                     colecao (query-collection db sk)
                     idx (long (:ativo-index (->save save) 0))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     _ (battle-idle-store/assert-battle-not-idle-expired! be agora)
                     next (battle/accept-pvp-challenge
                           state
                           {:player-id player-id
                            :player-name (:player-name request)
                            :save-id save-id
                            :fence-generation (:fence-generation writer-ctx)
                            :pokemon-o poke
                            :hp-o (:hp-atual poke)
                            :status-o (:status poke)
                            :collection-index idx
                            :agora agora
                            :first-turn-tie (:first-turn-tie request)})
                     idle (battle-idle-store/touch-attrs agora)
                     next (assoc next
                                 :last-activity-at (:battle/last-activity-at idle)
                                 :idle-expires-at (:battle/idle-expires-at idle))
                     rev (long (:save/revision save 0))]
                 {:tx [(merge {:battle/id battle-id
                               :battle/player-o-key pk
                               :battle/active? true
                               :battle/phase "active"
                               :battle/turn-index 0
                               :battle/state-edn (battle/encode-state-edn next)}
                              idle)
                       [:db/add [:player/key pk] :player/active-battle-id battle-id]
                       [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                       {:operation-receipt/id operation-id
                        :operation-receipt/kind "pvp-accept"
                        :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key pk
                        :operation-receipt/save-id save-id
                        :operation-receipt/summary
                        (pr-str {:battle-id battle-id :vez (:vez next)})}]
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle next}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-pvp-receipt conn dup phash operation-id)
                  (throw ex))))))))

  (cancel-pvp! [_ writer-ctx request]
    (let [payload (ops/pvp-cancel-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-pvp-receipt conn existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     pk (id/player-key chat-id player-id)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     battle-id (or (:battle-id request)
                                   (active-battle-id-for db chat-id player-id)
                                   (:group/active-pvp-battle-id
                                    (d/pull db '[*] [:group/chat-id chat-id])))
                     _ (when-not battle-id
                         (throw (err/error :pvp_no_challenge {})))
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :battle_not_found {:battle-id battle-id})))
                     state (->battle be)
                     side (pvp/side-of-player state player-id)
                     _ (when-not side
                         (throw (err/error :pvp_not_participant {})))
                     _ (when (and (pvp/aguardando? state) (not= side :x))
                         (throw (err/error :pvp_cancel_forbidden {})))
                     next (battle/forfeit-pvp state side)
                     rev (long (:save/revision save 0))
                     colecao-x (query-collection
                                db (id/save-key chat-id
                                                (get-in state [:jogadores :x])
                                                (get-in state [:saves :x])))
                     colecao-o (when (get-in state [:jogadores :o])
                                 (query-collection
                                  db (id/save-key chat-id
                                                  (get-in state [:jogadores :o])
                                                  (get-in state [:saves :o]))))
                     poke-x (when (seq colecao-x)
                              (nth colecao-x (get-in state [:indices-ativos :x] 0) nil))
                     poke-o (when (and colecao-o (get-in state [:indices-ativos :o]))
                              (nth colecao-o (get-in state [:indices-ativos :o]) nil))
                     sync-x (when (and poke-x (get-in next [:hp :x]))
                              (pvp-store/pokemon-sync-tx
                               poke-x (get-in next [:hp :x]) (get-in next [:status :x])))
                     sync-o (when (and poke-o (get-in next [:hp :o]))
                              (pvp-store/pokemon-sync-tx
                               poke-o (get-in next [:hp :o]) (get-in next [:status :o])))
                     clear-x [[:db/retract
                               [:player/key (id/player-key chat-id (get-in state [:jogadores :x]))]
                               :player/active-battle-id battle-id]]
                     clear-o (when (get-in state [:jogadores :o])
                               [[:db/retract
                                 [:player/key (id/player-key chat-id (get-in state [:jogadores :o]))]
                                 :player/active-battle-id battle-id]])
                     clear-chat (or (pvp-store/clear-chat-pvp-tx db chat-id battle-id) [])]
                 {:tx (into [[:db/add [:battle/id battle-id] :battle/active? false]
                             [:db/add [:battle/id battle-id] :battle/phase "finished"]
                             [:db/add [:battle/id battle-id] :battle/state-edn (battle/encode-state-edn next)]
                             [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                             {:operation-receipt/id operation-id
                              :operation-receipt/kind "pvp-cancel"
                              :operation-receipt/channel "gameplay"
                              :operation-receipt/payload-hash phash
                              :operation-receipt/player-key pk
                              :operation-receipt/save-id (:save-id writer-ctx)
                              :operation-receipt/summary
                              (pr-str {:battle-id battle-id :outcome (:outcome next)})}]
                            (concat clear-x (or clear-o []) clear-chat
                                    (or sync-x []) (or sync-o [])))
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle next}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-pvp-receipt conn dup phash operation-id)
                  (throw ex))))))))

  (confirm-pvp-turn! [_ writer-ctx request]
    (let [payload (ops/pvp-turn-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          agora-pre (long (or (:agora request) (System/currentTimeMillis)))
          battle-id (:battle-id request)
          _ (when battle-id
              (let [be0 (battle-entity (d/db conn) battle-id)]
                (when (and (present? be0)
                           (not (true? (:battle/idle-expired? be0)))
                           (battle-idle/expired? (:battle/idle-expires-at be0) agora-pre))
                  (battle-idle-store/expire-battle! conn battle-id agora-pre)
                  (throw (err/error :battle_idle_expired
                                    {:battle-id battle-id
                                     :expires-at (:battle/idle-expires-at be0)
                                     :agora agora-pre})))))]
      (or (resolve-pvp-receipt conn existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     battle-id (:battle-id request)
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :battle_not_found {:battle-id battle-id})))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     _ (battle-idle-store/assert-battle-not-idle-expired! be agora)
                     state (->battle be)
                     _ (when-not (= :pvp (:kind state))
                         (throw (err/error :not_pvp_battle {:kind (:kind state)})))
                     side (pvp/side-of-player state player-id)
                     _ (when-not side
                         (throw (err/error :pvp_not_participant {})))
                     expected (long (:expected-turn-index request (:turn-index state)))
                     _ (when-not (= expected (long (:turn-index state)))
                         (throw (err/error :stale_turn_index
                                           {:expected expected
                                            :actual (:turn-index state)})))
                     action (keyword (:action request))
                     item-id (when (= :item action)
                               (shop/normalizar-item (str (:item-id request))))
                     sk-actor (id/save-key chat-id player-id (:save-id writer-ctx))
                     inv-tx (when (= :item action)
                              (inventory-adjust-tx db sk-actor item-id -1))
                     next-state (battle/apply-pvp-turn
                                 state side
                                 {:action action
                                  :move-index (:move-index request)
                                  :item-id item-id}
                                 (:rng request))
                     pk (id/player-key chat-id player-id)
                     rev (long (:save/revision save 0))
                     finished? (not (:active? next-state))
                     outcome (:outcome next-state)
                     ;; Sync HP ambos os lados ao encerrar.
                     sync-both
                     (when finished?
                       (mapcat
                        (fn [marca]
                          (let [pid (get-in state [:jogadores marca])
                                sid (get-in state [:saves marca])
                                idx (get-in state [:indices-ativos marca])]
                            (when (and pid sid (some? idx))
                              (let [col (query-collection
                                         db (id/save-key chat-id pid sid))
                                    poke (when (and (seq col) (< idx (count col)))
                                           (nth col idx))]
                                (pvp-store/pokemon-sync-tx
                                 poke
                                 (get-in next-state [:hp marca])
                                 (get-in next-state [:status marca]))))))
                        [:x :o]))
                     winner-side (when (contains? #{:x :o} outcome) outcome)
                     ;; XP de Pokémon nos dois lados (nocautes); bônus só no vencedor.
                     reward-parts
                     (when finished?
                       (let [desafiante (get-in state [:pokemons :x])
                             xp-parts
                             (keep
                              (fn [marca]
                                (let [pid (get-in state [:jogadores marca])
                                      sid (get-in state [:saves marca])
                                      idx (get-in state [:indices-ativos marca])
                                      nome (get-in state [:nomes marca])]
                                  (when (and pid sid (some? idx))
                                    (let [sk-side (id/save-key chat-id pid sid)
                                          col (query-collection db sk-side)
                                          poke (when (and (seq col) (< idx (count col)))
                                                 (nth col idx))
                                          kos (long (get-in next-state
                                                            [:participacao marca idx] 0))]
                                      (when poke
                                        (pvp-store/pokemon-xp-tx
                                         (assoc poke :hp-atual
                                                (get-in next-state [:hp marca]))
                                         kos nome))))))
                              [:x :o])
                             bonus
                             (when winner-side
                               (let [wpid (get-in state [:jogadores winner-side])
                                     wsid (get-in state [:saves winner-side])
                                     wsk (id/save-key chat-id wpid wsid)
                                     wsave (save-entity db chat-id wpid wsid)]
                                 (when (present? wsave)
                                   (pvp-store/winner-bonus-tx
                                    db wsk wsave desafiante operation-id))))]
                         {:tx (into (or (:tx bonus) [])
                                    (mapcat #(or (:tx %) []) xp-parts))
                          :summary (cond-> {:pokemon (mapv :summary xp-parts)}
                                     bonus (merge (:summary bonus)))}))
                     reward-tx (or (:tx reward-parts) [])
                     rewards-summary (:summary reward-parts)
                     clear-players
                     (when finished?
                       (vec
                        (keep (fn [marca]
                                (when-let [pid (get-in state [:jogadores marca])]
                                  [:db/retract
                                   [:player/key (id/player-key chat-id pid)]
                                   :player/active-battle-id battle-id]))
                              [:x :o])))
                     clear-chat (when finished?
                                  (or (pvp-store/clear-chat-pvp-tx db chat-id battle-id) []))
                     idle-touch (when-not finished?
                                  (battle-idle-store/touch-attrs agora))
                     next-state (cond-> next-state
                                  idle-touch
                                  (assoc :last-activity-at (:battle/last-activity-at idle-touch)
                                         :idle-expires-at (:battle/idle-expires-at idle-touch)))
                     tx (into (cond-> [[:db/cas [:battle/id battle-id] :battle/turn-index
                                        expected (long (:turn-index next-state))]
                                       [:db/add [:battle/id battle-id] :battle/active?
                                        (boolean (:active? next-state))]
                                       [:db/add [:battle/id battle-id] :battle/phase
                                        (name (:phase next-state))]
                                       [:db/add [:battle/id battle-id] :battle/state-edn
                                        (battle/encode-state-edn next-state)]
                                       [:db/cas [:save/key sk-actor] :save/revision rev (inc rev)]
                                       {:operation-receipt/id operation-id
                                        :operation-receipt/kind "pvp-turn"
                                        :operation-receipt/channel "gameplay"
                                        :operation-receipt/payload-hash phash
                                        :operation-receipt/player-key pk
                                        :operation-receipt/save-id (:save-id writer-ctx)
                                        :operation-receipt/summary
                                        (pr-str {:battle-id battle-id
                                                 :turn (:turn-index next-state)
                                                 :outcome outcome
                                                 :vez (:vez next-state)
                                                 :rewards rewards-summary})}]
                                idle-touch (into (map (fn [[k v]]
                                                        [:db/add [:battle/id battle-id] k v])
                                                      idle-touch)))
                              (concat (or inv-tx [])
                                      (or sync-both [])
                                      reward-tx
                                      (or clear-players [])
                                      (or clear-chat [])))]
                 {:tx tx
                  :result (cond-> {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :battle next-state}
                            rewards-summary (assoc :rewards rewards-summary))})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-pvp-receipt conn dup phash operation-id)
                  (throw ex))))))))

  (active-pvp-battle [_ chat-id]
    (let [db (d/db conn)
          g (d/pull db '[*] [:group/chat-id chat-id])
          bid (:group/active-pvp-battle-id g)]
      (when bid
        (when-let [be (battle-entity db bid)]
          (when (present? be)
            (->battle be))))))

  (ensure-group! [_ chat-id label]
    (let [db (d/db conn)
          e (d/pull db '[*] [:group/chat-id chat-id])]
      (if (present? e)
        {:chat-id (:group/chat-id e) :label (:group/label e)}
        (do
          (d/transact conn {:tx-data [{:group/chat-id chat-id
                                       :group/label (or label "grupo-ficticio")}]})
          {:chat-id chat-id :label (or label "grupo-ficticio")}))))

  (get-group [_ chat-id]
    (let [e (d/pull (d/db conn) '[*] [:group/chat-id chat-id])]
      (when (present? e)
        {:chat-id (:group/chat-id e)
         :label (:group/label e)
         :active-wild-battle-id (:group/active-wild-battle-id e)
         :active-wild-player-id (:group/active-wild-player-id e)
         :active-pvp-battle-id (:group/active-pvp-battle-id e)})))

  (get-receipt [_ operation-id]
    (let [e (receipt-entity (d/db conn) operation-id)]
      (when (present? e)
        (dissoc (->receipt e) :duplicate))))

  (claim-starter-kit! [_ writer-ctx request]
    (let [operation-id (:operation-id request)
          payload (reward/reward-payload
                   {:item {:item-id "pokebola" :qty 10}})
          phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) operation-id)
          chat-id (:chat-id writer-ctx)
          player-id (:player-id writer-ctx)
          db0 (d/db conn)
          kit-any? (onboarding-store/kit-claimed-any? db0 chat-id player-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     inv (query-inventory db sk)]
                 (onboarding-store/build-claim-kit-tx
                  {:db db :writer-ctx writer-ctx :request request :save save
                   :inventory inv
                   :kit-claimed-any? (or kit-any?
                                         (onboarding-store/kit-claimed-any? db chat-id player-id))
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (claim-bag-daily! [_ writer-ctx request]
    (let [operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          chat-id (:chat-id writer-ctx)
          player-id (:player-id writer-ctx)]
      (or (when (present? existing)
            (let [sum (:summary (->receipt existing))]
              (merge (dissoc (->receipt existing) :duplicate)
                     {:duplicate true
                      :daily-already-claimed (boolean (:daily-already-claimed sum true))
                      :dia (:dia sum)
                      :sequencia (:sequencia sum)
                      :items (:items sum)
                      :added (:added sum)
                      :pending (:pending sum)
                      :rewards-text (:rewards-text sum)
                      :fruta-dourada? (:fruta-dourada? sum)})))
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     inv (query-inventory db sk)
                     draw (:draw request)]
                 (bag-store/build-claim-daily-tx
                  {:db db :writer-ctx writer-ctx :request request :save save
                   :inventory inv
                   :inventory-adjust-tx inventory-adjust-tx
                   :draw draw
                   :instant (:instant request)
                   :timezone (:timezone request)}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (let [sum (:summary (->receipt dup))]
                    (merge (dissoc (->receipt dup) :duplicate)
                           {:duplicate true
                            :dia (:dia sum)
                            :items (:items sum)
                            :added (:added sum)
                            :pending (:pending sum)}))
                  (throw ex))))))))

  (choose-starter! [_ writer-ctx request]
    (let [operation-id (:operation-id request)
          starter (onboard/resolve-starter (:choice request))
          payload (reward/reward-payload
                   {:pokemon {:key (str operation-id ":starter")
                              :species (:species starter)
                              :nickname (:nome starter)
                              :shiny false :xp 0 :level 1 :in-party true}})
          phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) operation-id)
          chat-id (:chat-id writer-ctx)
          player-id (:player-id writer-ctx)
          kit-any? (onboarding-store/kit-claimed-any? (d/db conn) chat-id player-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (onboarding-store/build-choose-starter-tx
                  {:db db :writer-ctx writer-ctx
                   :request (assoc request :kit-claimed-any?
                                   (or kit-any?
                                       (onboarding-store/kit-claimed-any? db chat-id player-id)))
                   :save save
                   :colecao colecao
                   :pokemon-entity-map pokemon-entity-map
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (leave-battle! [_ writer-ctx request]
    (let [payload (ops/battle-leave-payload
                   (select-keys request [:battle-id :raid-id]))
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)
          hydrate (fn [r]
                    (let [sum (:summary r)
                          bid (:battle-id sum)
                          rid (:raid-id sum)]
                      (cond-> (assoc r
                                     :status (or (:status sum) "left")
                                     :modality (:modality sum)
                                     :payload-hash phash)
                        bid (assoc :battle (->battle (battle-entity (d/db conn) bid)))
                        rid (assoc :raid (load-raid (d/db conn) rid)))))]
      (or (some-> (resolve-receipt existing phash operation-id) hydrate)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     bid (or (:battle-id request)
                             (active-battle-id-for db chat-id player-id)
                             (open-battle-id-fallback db chat-id player-id))
                     be (when bid (battle-entity db bid))
                     battle (when (and be (present? be)) (->battle be))
                     ;; Se idle já persistiu, trata como encerrada (não reabre).
                     idle-done? (or (true? (:battle/idle-expired? be))
                                    (= :idle-timeout (:outcome battle)))
                     open-battle? (and battle (not idle-done?)
                                       (or (:active? battle)
                                           (:aguardando-captura? battle)
                                           (pvp/aguardando? battle)))
                     ;; Leave voluntário prevalece sobre prazo idle ainda não
                     ;; materializado pelo sweeper (sem eager-expire aqui).
                     raid-ptr (d/pull db '[*] [:chat-active-raid/chat-id chat-id])
                     raid (when (present? raid-ptr)
                            (load-raid db (:chat-active-raid/raid-id raid-ptr)))
                     in-raid? (and raid
                                   (not (true? (:idle-expired? raid)))
                                   (get-in raid [:participantes player-id]))
                     req' (assoc request :payload-hash phash :agora agora)]
                 (cond
                   (and open-battle? (= :pvp (:kind battle)))
                   (battle-leave-store/build-leave-pvp-tx
                    {:db db :save save :writer-ctx writer-ctx
                     :request (assoc req'
                                     :colecao-x
                                     (query-collection
                                      db (id/save-key chat-id
                                                      (get-in battle [:jogadores :x])
                                                      (get-in battle [:saves :x])))
                                     :colecao-o
                                     (when (get-in battle [:jogadores :o])
                                       (query-collection
                                        db (id/save-key chat-id
                                                        (get-in battle [:jogadores :o])
                                                        (get-in battle [:saves :o])))))
                     :battle battle
                     :pokemon-sync-tx pvp-store/pokemon-sync-tx})

                   (and open-battle? (contains? #{:wild :gym} (:kind battle)))
                   (battle-leave-store/build-leave-wild-or-gym-tx
                    {:db db :save save :writer-ctx writer-ctx
                     :request (assoc req' :colecao (query-collection db sk)
                                     :be be)
                     :battle battle
                     :pokemon-hp-status-tx pokemon-hp-status-tx
                     :gym-battle-hp-txs gym-battle-hp-txs})

                   (and in-raid? (= "inscricoes" (str (:fase raid))))
                   (let [built (raid-store/build-leave-tx
                                {:save save :writer-ctx writer-ctx
                                 :request req' :raid raid})]
                     (update built :result merge
                             {:status "left" :modality "raid"}))

                   (and in-raid? (= "combate" (str (:fase raid))))
                   (battle-leave-store/build-leave-raid-combat-tx
                    {:db db :save save :writer-ctx writer-ctx
                     :request req' :raid raid})

                   (and battle (not open-battle?))
                   {:tx [[:db/cas [:save/key sk] :save/revision
                          (long (:save/revision save 0))
                          (inc (long (:save/revision save 0)))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "battle-leave"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key
                          (id/player-key chat-id player-id)
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:battle-id bid
                                   :status "already_finished"
                                   :outcome (:outcome battle)})}]
                    :result {:operation-id operation-id
                             :duplicate false
                             :status "already_finished"
                             :battle battle
                             :payload-hash phash}}

                   :else
                   {:tx [[:db/cas [:save/key sk] :save/revision
                          (long (:save/revision save 0))
                          (inc (long (:save/revision save 0)))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "battle-leave"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key
                          (id/player-key chat-id player-id)
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:status "no_battle"})}]
                    :result {:operation-id operation-id
                             :duplicate false
                             :status "no_battle"
                             :payload-hash phash}}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (hydrate (resolve-receipt dup phash operation-id))
                  (let [code (err/error-code ex)]
                    ;; CAS active? falhou → batalha já encerrada por idle/ataque.
                    (if (or (= :writer_fence_conflict code)
                            (err/conflict? ex))
                      (let [bid (or (:battle-id request)
                                    (active-battle-id-for (d/db conn)
                                                         (:chat-id writer-ctx)
                                                         (:player-id writer-ctx)))
                            b (when bid (->battle (battle-entity (d/db conn) bid)))]
                        (if (and b (or (:active? b) (:aguardando-captura? b)
                                       (pvp/aguardando? b)))
                          (throw ex)
                          {:operation-id operation-id
                           :duplicate false
                           :status "already_finished"
                           :battle b
                           :payload-hash phash}))
                      (throw ex))))))))))

  (expire-idle-battles! [_ opts]
    (battle-idle-store/expire-due! conn opts))

  (list-battle-idle-notifications [_ opts]
    (battle-idle-store/list-pending-notifications (d/db conn) (or opts {})))

  (ack-battle-idle-notification! [_ notify-id]
    (battle-idle-store/ack-notification! conn notify-id)))

(defn make-store
  "Constrói store a partir de uma conexão Datomic já aberta."
  [conn]
  (->DatomicTrainerStore conn))
