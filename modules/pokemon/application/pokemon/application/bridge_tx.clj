(ns pokemon.application.bridge-tx
  "Casos de uso M4.2/M4.4 da bridge: ensure, saves, activate, encontro/captura.
  Sem seed admin; save-id + fence + operation-id explícitos nas escritas."
  (:require [clojure.string :as str]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]))

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

(defn- keywordize-keys
  [x]
  (cond
    (map? x)
    (into {}
          (map (fn [[k v]]
                 [(cond
                    (keyword? k) k
                    (string? k) (keyword k)
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
                     :pokemon-o (select-keys (get-in enc [:pokemons :o] {})
                                             [:nome :species :raridade :hp])})})))

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
        battle (:battle result)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :battle battle
     :battle-id (:battle-id battle)
     :aguardando-captura (boolean (:aguardando-captura? battle))
     :outcome (:outcome battle)
     :chance-base-captura (:chance-base-captura battle)
     :save-id save-id
     :fence-generation (long fence-generation)}))
