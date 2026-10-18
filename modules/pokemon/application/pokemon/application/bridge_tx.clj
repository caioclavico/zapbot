(ns pokemon.application.bridge-tx
  "Casos de uso M4.2/M4.4/M4.5 da bridge: ensure, saves, activate, encontro/caça.
  Sem seed admin; save-id + fence + operation-id explícitos nas escritas."
  (:require [clojure.string :as str]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.mundo :as mundo]))

(defn- save-summary
  [store chat-id player-id slot]
  (when-let [save (ports/get-save store chat-id player-id slot)]
    {:save-id slot
     :coins (:coins save 0)
     :revision (:revision save 0)
     :trainer-xp (:trainer-xp save 0)
     :trainer-level (:trainer-level save 1)
     :collection-count (count (ports/list-collection store chat-id player-id slot))
     :party-count (count (ports/list-party store chat-id player-id slot))
     :inventory (ports/list-inventory store chat-id player-id slot)}))

(defn ensure-trainer!
  "Cria treinador+saves A/B se ainda não existir (idempotente)."
  [store {:keys [chat-id player-id]}]
  (model/validate-create-player! {:chat-id chat-id :player-id player-id})
  (let [existing (ports/get-player store chat-id player-id)
        player (if existing
                 existing
                 (trainer/create-trainer! store {:chat-id chat-id :player-id player-id}))]
    {:created (nil? existing)
     :chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)}))

(defn list-saves
  [store chat-id player-id]
  (let [player (or (ports/get-player store chat-id player-id)
                   (throw (err/error :player_not_found
                                     {:chat-id chat-id :player-id player-id})))
        slots (vec model/default-slots)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :saves (vec (keep #(save-summary store chat-id player-id %) slots))}))

(defn activate-save!
  "Ativa save via TX Datomic (fence sobe). Opcionalmente exige fence esperado."
  [store {:keys [chat-id player-id save-id expected-fence-generation]}]
  (model/validate-save-slot! save-id)
  (let [player (or (ports/get-player store chat-id player-id)
                   (throw (err/error :player_not_found
                                     {:chat-id chat-id :player-id player-id})))
        current-fence (:fence-generation player 0)]
    (when (and (some? expected-fence-generation)
               (not= (long expected-fence-generation) (long current-fence)))
      (throw (err/error :stale_writer_fence
                        {:expected expected-fence-generation
                         :fence current-fence
                         :active (:active-save-id player)})))
    (let [snap (trainer/switch-save! store chat-id player-id save-id)
          p (:player snap)]
      {:chat-id chat-id
       :player-id player-id
       :active-save-id (:active-save-id p)
       :fence-generation (:fence-generation p)
       :save (select-keys (:save snap)
                          [:slot-id :coins :revision :trainer-xp :trainer-level])})))

(defn credit-coins!
  "Mutação representativa: credita moedas no save congelado (fence + recibo).

  Exige save-id, fence-generation e operation-id do cliente — não recongela
  o ativo automaticamente a partir do pedido."
  [store {:keys [chat-id player-id save-id fence-generation operation-id coins-delta]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (when-not (integer? coins-delta)
    (throw (err/error :invalid_coins_delta {:coins-delta coins-delta})))
  (model/validate-save-slot! save-id)
  (let [writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        result (reward-app/apply-gameplay-reward!
                store writer-ctx
                {:operation-id (str operation-id)
                 :coins-delta (long coins-delta)})
        save (ports/get-save store chat-id player-id save-id)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :channel (:channel result)
     :payload-hash (:payload-hash result)
     :save-id save-id
     :fence-generation (long fence-generation)
     :coins (:coins save 0)
     :summary (:summary result)}))

(defn get-operation
  "Reconciliação pós-timeout. Exige chat-id+player-id do contexto confiável
  e só devolve recibo do mesmo jogador (M4.3)."
  [store operation-id chat-id player-id]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when (or (str/blank? (str chat-id)) (str/blank? (str player-id)))
    (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
  (let [receipt (ports/get-receipt store (str operation-id))]
    (when-not receipt
      (throw (err/error :operation_not_found {:operation-id operation-id})))
    (let [expected (id/player-key chat-id player-id)]
      (when-not (= expected (:player-key receipt))
        (throw (err/error :operation_forbidden
                          {:operation-id operation-id})))
      (assoc receipt :persisted true))))

(defn- camel->kebab
  [s]
  (-> (str s)
      (str/replace #"\?" "")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

(defn- keywordize-keys
  "JSON string keys → keywords kebab (atqEsp / atq-esp → :atq-esp)."
  [x]
  (cond
    (map? x)
    (into {}
          (map (fn [[k v]]
                 [(cond
                    (keyword? k) k
                    (string? k) (keyword (camel->kebab k))
                    :else k)
                  (keywordize-keys v)])
               x))
    (vector? x) (mapv keywordize-keys x)
    (sequential? x) (mapv keywordize-keys x)
    :else x))

(defn- normalize-ball
  [raw]
  (let [chave (-> (or raw "")
                  str/trim
                  str/lower-case
                  (str/replace #"\s+" "-"))
        mapped (get {"1" "pokebola" "normal" "pokebola" "poke" "pokebola"
                     "pokebola" "pokebola" "pokébola" "pokebola"
                     "2" "grande-bola" "grande" "grande-bola"
                     "grande-bola" "grande-bola"
                     "3" "ultra-bola" "ultra" "ultra-bola"
                     "ultra-bola" "ultra-bola"}
                    chave)]
    (or mapped
        (when (contains? capture/bolas chave) chave))))

(defn- merge-capture-receipt
  "Idempotência: recibo duplicado → campos de captura a partir do summary."
  [result]
  (if (and (:duplicate result) (map? (:summary result)))
    (merge result (:summary result) {:duplicate true})
    result))

(defn attempt-encounter-capture!
  "POST captura no encontro ativo. Exige save-id + fence + operation-id."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 bola battle-id roll fuga-roll]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (let [ball (normalize-ball bola)]
    (when-not ball
      (throw (err/error :unknown_ball {:bola bola})))
    (let [writer-ctx {:chat-id chat-id
                      :player-id player-id
                      :save-id save-id
                      :fence-generation (long fence-generation)}
          raw (capture-app/attempt-encounter-capture!
               store writer-ctx
               (cond-> {:operation-id (str operation-id)
                        :bola ball}
                 battle-id (assoc :battle-id battle-id)
                 (some? roll) (assoc :roll (long roll))
                 (some? fuga-roll) (assoc :fuga-roll (long fuga-roll))))
          result (merge-capture-receipt raw)
          save (ports/get-save store chat-id player-id save-id)
          inv (ports/list-inventory store chat-id player-id save-id)]
      ;; Chaves sem `?` — o encoder JSON camelCase preservaria o sufixo.
      {:operation-id (:operation-id result)
       :duplicate (boolean (:duplicate result))
       :channel (:channel result "gameplay")
       :payload-hash (:payload-hash result)
       :save-id save-id
       :fence-generation (long fence-generation)
       :battle-id (:battle-id result)
       :capturou (boolean (:capturou? result))
       :fugiu (boolean (:fugiu? result))
       :chance (:chance result)
       :chance-base (:chance-base result)
       :tentativa (:tentativa result)
       :bola (:bola result ball)
       :indice (:indice result)
       :id-pokemon (:id-pokemon result)
       :recompensa (:recompensa result)
       :subida (:subida result)
       :registro (:registro result)
       :coins (:coins save 0)
       :sequencia-capturas (:sequencia-capturas save 0)
       :collection-count (count (ports/list-collection store chat-id player-id save-id))
       :inventory inv
       :encounter (when-let [enc (:encounter result)]
                    {:battle-id (:battle-id enc)
                     :aguardando-captura (boolean (:aguardando-captura? enc))
                     :phase (:phase enc)
                     :tentativas-captura (:tentativas-captura enc 0)
                     :bioma (:bioma enc)
                     :area (when-let [a (mundo/obter-area (str (:bioma enc)))]
                             (hunt/area-dto a))
                     :pokemon-o (select-keys (get-in enc [:pokemons :o] {})
                                             [:nome :species :raridade :hp
                                              :imagem :imagem-shiny :altura
                                              :tipos :shiny :shiny?])})})))

(defn start-wild-battle!
  "Harness/e2e: inicia batalha selvagem no save congelado (dev)."
  [store {:keys [chat-id player-id save-id fence-generation operation-id]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (let [writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        normalized (-> (dissoc req :fence-generation)
                       (update :pokemon-x keywordize-keys)
                       (update :pokemon-o keywordize-keys)
                       (assoc :operation-id (str operation-id)))
        result (battle-app/start-wild-battle! store writer-ctx normalized)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :battle (:battle result)
     :battle-id (get-in result [:battle :battle-id])
     :save-id save-id
     :fence-generation (long fence-generation)}))

(defn start-hunt!
  "Inicia caça com candidatos já buscados na PokeAPI (Node).
  `allow-test-rng` controla se shinyRoll do body é honrado."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 area-id tipos-encontro surto? poder-alvo candidates
                 shiny-roll clima-id agora-ms allow-test-rng battle-id]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (when (or (str/blank? (str area-id)) (empty? tipos-encontro) (nil? poder-alvo))
    (throw (err/error :invalid_query
                      {:required ["areaId" "tiposEncontro" "poderAlvo" "candidates"]})))
  (when (empty? candidates)
    (throw (err/error :hunt_no_candidates {})))
  (let [cands (mapv keywordize-keys candidates)
        writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        result (hunt-app/start-hunt!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :area-id (str area-id)
                         :tipos-encontro tipos-encontro
                         :surto? (boolean surto?)
                         :poder-alvo (long poder-alvo)
                         :candidates cands
                         :clima-id clima-id
                         :agora-ms agora-ms}
                  battle-id (assoc :battle-id battle-id)
                  (and allow-test-rng (some? shiny-roll))
                  (assoc :shiny-roll (long shiny-roll))))
        battle (:battle result)
        selvagem (:selvagem result)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :battle-id (:battle-id result)
     :save-id save-id
     :fence-generation (long fence-generation)
     :somente-batalha (boolean (:somente-batalha? result))
     :surto (boolean (:surto? result))
     :area-id (:area-id result)
     :area (when-let [a (mundo/obter-area (str (:area-id result)))]
             (hunt/area-dto a))
     :selvagem (when selvagem
                 (select-keys selvagem [:nome :species :slug-especie :raridade
                                        :shiny :shiny? :tipos :hp :nivel :altura
                                        :imagem :imagem-shiny :golpes :status
                                        :habilidade]))
     :battle (when battle
               {:battle-id (:battle-id battle)
                :active (boolean (:active? battle))
                :hp (:hp battle)
                :phase (:phase battle)
                :bioma (:bioma battle)
                :clima (:clima battle)
                :pokemon-o (select-keys (get-in battle [:pokemons :o] {})
                                        [:nome :species :raridade :shiny :shiny?
                                         :hp :tipos :imagem :imagem-shiny :altura
                                         :golpes :status])
                :pokemon-x (select-keys (get-in battle [:pokemons :x] {})
                                        [:nome :species :hp :nivel :imagem
                                         :imagem-shiny :altura :tipos :golpes
                                         :status])})}))

(defn confirm-battle-turn!
  "Harness/e2e: confirma turno (injeta rng para testes)."
  [store {:keys [chat-id player-id save-id fence-generation operation-id]
          :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (let [writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        normalized (-> (dissoc req :fence-generation)
                       (update :rng keywordize-keys)
                       (assoc :operation-id (str operation-id))
                       (cond-> (string? (:action req))
                         (update :action keyword)))
        result (battle-app/confirm-turn! store writer-ctx normalized)
        battle (:battle result)
        px (get-in battle [:pokemons :x])
        po (get-in battle [:pokemons :o])]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :battle {:battle-id (:battle-id battle)
              :active (boolean (:active? battle))
              :hp (:hp battle)
              :status (:status battle)
              :phase (:phase battle)
              :outcome (:outcome battle)
              :turn-index (:turn-index battle 0)
              :bioma (:bioma battle)
              :clima (:clima battle)
              :area (when-let [a (mundo/obter-area (str (:bioma battle)))]
                      (hunt/area-dto a))
              :aguardando-captura (boolean (:aguardando-captura? battle))
              :last-action (:last-action battle)
              :pokemon-x (when px
                           (select-keys px [:nome :species :hp :nivel :imagem
                                            :imagem-shiny :altura :tipos :golpes
                                            :status :shiny :shiny?]))
              :pokemon-o (when po
                           (select-keys po [:nome :species :raridade :hp :imagem
                                            :imagem-shiny :altura :tipos :golpes
                                            :status :shiny :shiny?]))
              ;; forma legada aninhada (compatível com present.formatBattleTurn)
              :pokemons {:x px :o po}}
     :battle-id (:battle-id battle)
     :aguardando-captura (boolean (:aguardando-captura? battle))
     :outcome (:outcome battle)
     :chance-base-captura (:chance-base-captura battle)
     :save-id save-id
     :fence-generation (long fence-generation)}))
