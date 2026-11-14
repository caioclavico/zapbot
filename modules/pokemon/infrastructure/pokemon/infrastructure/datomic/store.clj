Warning: truncated output (original token count: 71116)
Total output lines: 5311

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
            [pokemon.domain.level-evolution :as level-evo]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.mt-learnset :as mt-learnset]
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
            [pokemon.domain.growth :as growth]
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
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.thematic-event-store :as thematic-store]
            [pokemon.infrastructure.datomic.adventure-activity :as adventure-activity]
            [pokemon.infrastructure.datomic.trainer-progression :as trainer-progression]
            [pokemon.domain.events :as events]))

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

(defn- datoms-conflict-ex?
  [ex]
  (err/datoms-conflict? ex))

(defn- conflicting-add-assertions
  "Detecta [:db/add e a v] duplicados com valores distintos na mesma TX.
  Usado para enriquecer :tx_datoms_conflict (diagnóstico; não altera a TX)."
  [tx]
  (->> tx
       (filter #(and (vector? %)
                     (= :db/add (first %))
                     (= 4 (count %))))
       (group-by (fn [[_ e a _]] [e a]))
       (keep (fn [[[e a] rows]]
               (let [vs (vec (distinct (map #(nth % 3) rows)))]
                 (when (> (count vs) 1)
                   {:entity (pr-str e)
                    :attr (str a)
                    :values vs}))))
       vec))

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
          perfil (xp/perfil-treinador
                  (assoc base
                         :trainer-xp (max computed stored-xp)
                         :trainer-level stored-level
                         :trainer-xp-level-progress (:save/trainer-xp-level-progress e)))
          trainer-total (:xp perfil)
          nivel (:nivel perfil)]
      (assoc base
             :trainer-xp trainer-total
             :trainer-level nivel
             :trainer-xp-level-progress (:save/trainer-xp-level-progress e)
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
      (some? (:pokemon/xp-total e)) (assoc :xp-total (:pokemon/xp-total e))
      (some? (:pokemon/xp-progress-version e))
      (assoc :xp-progress-version (:pokemon/xp-progress-version e))
      (some? (:pokemon/xp-legacy-residual e))
      (assoc :xp-legacy-residual (:pokemon/xp-legacy-residual e))
      (some? (:pokemon/xp-legacy-nivel e))
      (assoc :xp-legacy-nivel (:pokemon/xp-legacy-nivel e))
      (some? (:pokemon/growth-rate e))
      (assoc :growth-rate (keyword (:pokemon/growth-rate e)))
      (some? (:pokemon/hp-base e)) (assoc :hp-base (:pokemon/hp-base e))
      (some? (:pokemon/hp-formula-version e))
      (assoc :hp-formula-version (:pokemon/hp-formula-version e))
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
      (some? (:xp-total registro))
      (assoc :pokemon/xp-total (long (:xp-total registro)))
      (some? (:xp-progress-version registro))
      (assoc :pokemon/xp-progress-version (str (:xp-progress-version registro)))
      (some? (:xp-legacy-residual registro))
      (assoc :pokemon/xp-legacy-residual (long (:xp-legacy-residual registro)))
      (some? (:xp-legacy-nivel registro))
      (assoc :pokemon/xp-legacy-nivel (long (:xp-legacy-nivel registro)))
      (some? (:growth-rate registro))
      (assoc :pokemon/growth-rate
             (let [gr (:growth-rate registro)]
               (if (keyword? gr) (name gr) (str gr))))
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
      (assoc :pokemon/item (str (:item registro)))
      (number? (:hp-base registro))
      (assoc :pokemon/hp-base (long (:hp-base registro)))
      (string? (:hp-formula-version registro))
      (assoc :pokemon/hp-formula-version (str (:hp-formula-version registro))))))

(defn- inventory-qty
  [db sk item-id]
  (let [ik (id/inventory-item-key sk item-id)
        cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])]
    (long (:inventory-item/qty cur 0))))

(defn- inventory-adjust-tx
  "Retorna tx-data para delta de item; lança se qty ficaria negativa."
  [db sk item-id delta]
  (let [ik (id/inventory-item-key sk item-id)
        old-qty (inventory-qty db sk item-id)
        next-qty (+ old-qty (long delta))]
    (when (neg? next-qty)
      (throw (err/error :insufficient_item
                        {:item-id item-id :qty old-qty :delta delta})))
    [{:inventory-item/key ik
      :inventory-item/save [:save/key sk]
      :inventory-item/item-id item-id
      :inventory-item/qty next-qty}]))

(defn- inventory-adjust-ball-tx
  "Débito de bola/fruta: consome canônico primeiro; senão alias legado
  (`great-ball` → `grande-bola`). Crédito (+n) sempre no ID canônico.
  Alinha UI `inv-qty`/`qtyBall` com o que a captura realmente consome."
  [db sk item-id delta]
  (let [canonical (str item-id)
        d (long delta)]
    (if (pos? d)
      (inventory-adjust-tx db sk canonical d)
      (let [ids (capture/bola-alias-ids canonical)
            ordered (into [canonical] (disj ids canonical))
            need (Math/abs d)
            chosen (first (filter #(>= (inventory-qty db sk %) need) ordered))]
        (inventory-adjust-tx db sk (or chosen canonical) d)))))

(defn- evolution-tx-fragments
  "TX de troca de espécie no mesmo id-pokemon (preserva identidade).

  Não reescreve HP/stats/golpes: `apply-pokemon-xp-tx` já emite esses attrs
  uma vez a partir do registro pós-evo. Duplicar `:pokemon/hp-atual` (ou stats)
  com valores divergentes (level-up proporcional × override de combate × evo)
  causa `:db.error/datoms-conflict` na mesma TX."
  [lookup poke evoluido]
  (let [old-tipos (set (:tipos poke))
        new-tipos (set (:tipos evoluido))
        tipo-tx (concat
                 (map (fn [t] [:db/retract lookup :pokemon/tipos t])
                      (remove new-tipos old-tipos))
                 (map (fn [t] [:db/add lookup :pokemon/tipos t])
                      (remove old-tipos new-tipos)))]
    (cond-> (into (vec tipo-tx)
                  [[:db/add lookup :pokemon/species (:species evoluido)]
                   [:db/add lookup :pokemon/nickname (:nome evoluido)]])
      (:habilidade evoluido)
      (conj [:db/add lookup :pokemon/habilidade (:habilidade evoluido)])
      (:imagem evoluido)
      (conj [:db/add lookup :pokemon/imagem (:imagem evoluido)])
      (:imagem-shiny evoluido)
      (conj [:db/add lookup :pokemon/imagem-shiny (:imagem-shiny evoluido)]))))

(defn- apply-pokemon-xp-tx
  "TX fragments para conceder XP a um Pokémon (por id-pokemon).
  Após subidas, tenta evolução por nível via catálogo canônico (sem I/O);
  ramificadas/especiais não entram no catálogo e não evoluem automaticamente.

  `opts`:
  - `:hp-atual-override` — HP canônico pós-combate (vitória/Explosion). Sem isso,
    level-up recalcula `:hp-atual` e conflita com `pokemon-hp-status-tx` na mesma TX
    (`:db.error/datoms-conflict`).
  - `:omit-hp-atual?` — quando true, não emite `:pokemon/hp-atual` (caller já sincroniza
    via `pokemon-hp-status-tx`). Preferir com override em turnos de batalha."
  ([poke quantidade] (apply-pokemon-xp-tx poke quantidade nil))
  ([poke quantidade {:keys [hp-atual-override omit-hp-atual? unidade]
                    :or {unidade :atividade}
                    :as opts}]
   (let [nivel-antes (long (or (:nivel poke) 1))
         xp-opts (cond-> (dissoc (or opts {}) :hp-atual-override :omit-hp-atual?)
                   true (assoc :unidade unidade))
         applied (xp/aplicar-xp-pokemon poke quantidade xp-opts)
         r0 (:registro applied)
         prepared (level-evo/prepare r0)
         r1 (if prepared
              (-> (level-evo/apply-prepared r0 prepared)
                  growth/after-evolution)
              r0)
         r (if (pos? (long (:subidas applied 0)))
             (moves/aplicar-ofertas-por-nivel r1 nivel-antes (:nivel r1))
             r1)
         hp-atual (long (if (some? hp-atual-override)
                          hp-atual-override
                          (:hp-atual r)))
         ;; Alinha registro em memória ao override (Explosion HP 0 / combate).
         r (cond-> r (some? hp-atual-override) (assoc :hp-atual hp-atual))
         lookup [:pokemon/id-pokemon (:id-pokemon poke)]
         ;; Um único writer de hp-atual neste fragmento (nunca evo-tx).
         base-tx (cond-> [[:db/add lookup :pokemon/level (long (:nivel r))]
                          [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
                          [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
                          [:db/add lookup :pokemon/xp-total (long (:xp-total r 0))]
                          [:db/add lookup :pokemon/xp-progress-version
                           (str (or (:xp-progress-version r) growth/progress-version))]
                          [:db/add lookup :pokemon/growth-rate
                           (name (or (:growth-rate r) :medium-fast))]
                          [:db/add lookup :pokemon/hp (long (:hp r))]
                          [:db/add lookup :pokemon/ataque (long (:ataque r))]
                          [:db/add lookup :pokemon/defesa (long (:defesa r))]
                          [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
                          [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
                          [:db/add lookup :pokemon/veloc (long (:veloc r))]
                          [:db/add lookup :pokemon/amizade (long (:amizade r))]]
                   (some? (:xp-legacy-residual r))
                   (conj [:db/add lookup :pokemon/xp-legacy-residual
                          (long (:xp-legacy-residual r))])
                   ;; Nível legado de auditoria: grava na 1ª conversão; retries
                   ;; preservam o valor já persistido no poke (não sobrescrevem).
                   (let [legacy-nv (or (:xp-legacy-nivel poke)
                                       (:xp-legacy-nivel r))]
                     (some? legacy-nv))
                   (conj [:db/add lookup :pokemon/xp-legacy-nivel
                          (long (or (:xp-legacy-nivel poke)
                                    (:xp-legacy-nivel r)))])
                   (not omit-hp-atual?)
                   (conj [:db/add lookup :pokemon/hp-atual hp-atual]))
         oferta-tx (when (not= (or (:ofertas-golpes r) (:golpes-oferecidos r) [])
                               (or (:ofertas-golpes poke) (:golpes-oferecidos poke) []))
                     [[:db/add lookup :pokemon/ofertas-golpes-edn
                       (pr-str (or (:ofertas-golpes r) (:golpes-oferecidos r) []))]])
         golpes-tx (when (not= (or (:golpes r) []) (or (:golpes poke) []))
                     [[:db/add lookup :pokemon/golpes-edn (pr-str (or (:golpes r) []))]])
         evo-tx (when prepared (evolution-tx-fragments lookup poke r))]
     {:tx (-> base-tx
              (into (or oferta-tx []))
              (into (or golpes-tx []))
              (into (or evo-tx [])))
      :subida (when (pos? (:subidas applied))
                {:nome (or (:nome r) (:nome applied))
                 :nivel (:nivel-final applied)
                 :niveis-subidos (:subidas applied)
                 :ofertas (or (:ofertas-golpes r) (:golpes-oferecidos r) [])})
      :evolucao (when prepared
                  {:nome-antigo (or (:nome poke) (:species poke))
                   :nome-novo (:nome r)
                   :species (:species r)
                   :nivel (:nivel r)
                   :catalog-version (:catalog-version prepared)})})))

(defn- pokemon-hp-status-tx
  ([poke hp-atual st]
   (pokemon-hp-status-tx poke hp-atual st (:status poke)))
  ([poke hp-atual st previous-status]
   (pokemon-hp-status-tx poke hp-atual st previous-status nil))
  ([poke hp-atual st previous-status {:keys [omit-hp-atual?] :as _opts}]
   (when poke
     (let [lookup [:pokemon/id-pokemon (:id-pokemon poke)]
           st-str (xp/status->str st)]
       (cond-> []
         (not omit-hp-atual?)
         (conj [:db/add lookup :pokemon/hp-atual (long hp-atual)])
         st-str (conj [:db/add lookup :pokemon/status st-str])
         (and (nil? st-str) previous-status)
         (conj [:db/retract lookup :pokemon/status previous-status]))))))

(defn- sync-colecao-from-gym-battle
  "Atualiza HP/status em memória a partir do estado 3×3 (antes do occupy).

  Na derrota, só os índices do time de ginásio (3) ficam a 0 — nunca a coleção
  inteira. Pokémon fora do desafio preservam hp-atual/status."
  [colecao battle]
  (let [fainted (set (map long (or (:fainted-x battle) #{})))
        active-idx (when-let [i (get-in battle [:indices-ativos :x])] (long i))
        team-idxs (set (map long (or (:team-x-indices battle) [])))
        hp-x (long (get-in battle [:hp :x] 0))
        st-x (get-in battle [:status :x])
        defeated? (= :lose (:outcome battle))]
    (mapv (fn [poke]
            (let [idx (long (:collection-index poke))]
              (cond
                ;; Derrota: só o time 3×3 (nunca a box/coleção inteira).
                (and defeated? (contains? team-idxs idx))
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

(defn- enrich-hp-base
  "Completa `:hp-base` pelo catálogo só quando ausente.
  DTOs de teste com HP artificial (ex.: hostile 500) e sem `hp-base` ficam
  intactos — produção Node sempre envia `hp-base` oficial."
  [pokemon]
  (cond
    (and (number? (:hp-base pokemon)) (pos? (long (:hp-base pokemon))))
    pokemon
    ;; Override explícito de combate/teste: não forçar catálogo.
    (true? (:species-hp-skip? pokemon))
    pokemon
    :else
    (let [sp (or (:species pokem…56116 tokens truncated…        _ (when-not side
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
                     ;; HP 0 é legítimo (Explosion/nocaute) — não usar truthiness.
                     sync-x (when (and poke-x (some? (get-in next [:hp :x])))
                              (pvp-store/pokemon-sync-tx
                               poke-x (get-in next [:hp :x]) (get-in next [:status :x])))
                     sync-o (when (and poke-o (some? (get-in next [:hp :o])))
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
                     :request req' :raid raid
                     :query-collection query-collection
                     :pokemon-hp-status-tx pokemon-hp-status-tx})

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
