(ns pokemon.application.bridge-tx
  "Casos de uso M4.2/M4.4/M4.5 da bridge: ensure, saves, activate, encontro/caça.
  Sem seed admin; save-id + fence + operation-id explícitos nas escritas."
  (:require [clojure.string :as str]
            [pokemon.application.active :as active-app]
            [pokemon.application.adventure :as adv]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.evolution :as evo-app]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.joy :as joy-app]
            [pokemon.application.moves :as moves-app]
            [pokemon.application.onboarding :as onboard-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.shop :as shop-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.shop :as shop-domain]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.raid :as raid-domain]))

(defn- present-pokemon
  "DTO de apresentação para Node (HP atual/máx, golpes, sprites).
   Não inventa atributos — só projeta o que já está no estado de batalha."
  [p hp-atual]
  (when p
    (cond-> (select-keys p [:nome :species :nickname :shiny :shiny? :nivel :hp
                            :raridade :tipos :ataque :defesa :atq-esp :def-esp
                            :veloc :imagem :imagem-shiny :altura :golpes
                            :status :habilidade :collection-index
                            :motivacao-ginasio])
      true (assoc :hp-atual (long (or hp-atual (:hp-atual p) (:hp p) 0)))
      (:shiny? p) (assoc :shiny true))))

(defn- present-battle
  "Fatia de batalha com HP, golpes e área visual para UI de caça/turno."
  [battle]
  (when battle
    (let [px (get-in battle [:pokemons :x])
          po (get-in battle [:pokemons :o])
          hp (:hp battle)]
      {:battle-id (:battle-id battle)
       :kind (name (:kind battle :wild))
       :gym-id (:gym-id battle)
       :gym-nome (:gym-nome battle)
       :lider-nome (:lider-nome battle)
       :active (boolean (:active? battle))
       :hp hp
       :status (:status battle)
       :phase (:phase battle)
       :outcome (:outcome battle)
       :turn-index (:turn-index battle 0)
       :bioma (:bioma battle)
       :clima (let [c (:clima battle)]
                (cond
                  (map? c) (hunt/clima-dto c)
                  (and c (not (str/blank? (str c))))
                  (some-> (mundo/obter-clima (str c)) hunt/clima-dto)
                  :else nil))
       :area (when-let [a (mundo/obter-area (str (:bioma battle)))]
               (hunt/area-dto a))
       :aguardando-captura (boolean (:aguardando-captura? battle))
       :somente-batalha (boolean (:somente-batalha? battle))
       :last-action (:last-action battle)
       :last-switch (:last-switch battle)
       :reservas-x (count (get-in battle [:reservas :x] []))
       :reservas-o (count (get-in battle [:reservas :o] []))
       :pokemon-x (present-pokemon px (get hp :x))
       :pokemon-o (present-pokemon po (get hp :o))})))

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

(defn onboarding-status
  [store chat-id player-id]
  (onboard-app/status store chat-id player-id))

(defn start-journey!
  "ensure + kit (10 Pokébolas) idempotente; retorna menu de starters se pendente."
  [store {:keys [chat-id player-id operation-id] :as req}]
  (model/validate-create-player! {:chat-id chat-id :player-id player-id})
  (onboard-app/start-journey! store req))

(defn claim-starter-kit!
  [store req]
  (onboard-app/claim-kit! store req))

(defn choose-starter!
  [store req]
  (onboard-app/choose-starter! store req))

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
     :selvagem (present-pokemon selvagem (get-in battle [:hp :o]))
     :battle (present-battle battle)}))

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
        gym-res (:gym-resolve result)
        presented (present-battle battle)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :battle (assoc presented
                    :pokemons {:x (get-in battle [:pokemons :x])
                               :o (get-in battle [:pokemons :o])})
     :battle-id (:battle-id battle)
     :aguardando-captura (boolean (:aguardando-captura? battle))
     :outcome (:outcome battle)
     :chance-base-captura (:chance-base-captura battle)
     :gym-resolve (when gym-res
                    {:venceu (boolean (:venceu? gym-res))
                     :premio (when-let [p (:premio gym-res)] (name p))
                     :occupied (boolean (:occupied? gym-res))
                     :pe-delta (:pe-delta gym-res)
                     :reward (:reward gym-res)})
     :save-id save-id
     :fence-generation (long fence-generation)}))

(def ^:private heal-by-item
  "Pontos de motivação derivados do item — nunca do cliente."
  {"pocao" {:item-id "pocao" :pontos 40 :label "Poção de Vida"}
   "fruta" {:item-id "fruta" :pontos 20 :label "Fruta Frambo"}
   "fruta-dourada" {:item-id "fruta-dourada" :pontos 100 :label "Fruta Dourada"}})

(defn set-gym-team!
  "Salva escalação 3 índices no save congelado."
  [store {:keys [chat-id player-id save-id fence-generation indices]
          :as _req}]
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (let [idxs (mapv long (or indices []))
        writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        result (gym-app/set-gym-team! store writer-ctx idxs)]
    {:save-id save-id
     :fence-generation (long fence-generation)
     :indices (:time-ginasio result idxs)
     :time-ginasio (:time-ginasio result idxs)}))

(defn start-gym-challenge!
  "Inicia desafio 3×3. NPC: candidates da PokeAPI; ocupado: time do slot."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 gym-id candidates nome agora-ms dia battle-id]
          :as _req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (when (str/blank? (str gym-id))
    (throw (err/error :invalid_query {:required ["gymId"]})))
  (let [writer-ctx {:chat-id chat-id
                    :player-id player-id
                    :save-id save-id
                    :fence-generation (long fence-generation)}
        cands (mapv keywordize-keys (or candidates []))
        result (gym-app/start-challenge!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :gym-id (str gym-id)
                         :candidates cands
                         :nome (or nome player-id)}
                  battle-id (assoc :battle-id battle-id)
                  (some? agora-ms) (assoc :agora agora-ms)
                  (some? dia) (assoc :dia dia)))
        battle (:battle result)
        px (get-in battle [:pokemons :x])
        po (get-in battle [:pokemons :o])]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :battle-id (:battle-id result)
     :gym-id (:gym-id result)
     :battle {:battle-id (:battle-id battle)
              :kind "gym"
              :gym-id (:gym-id battle)
              :gym-nome (:gym-nome battle)
              :lider-nome (:lider-nome battle)
              :active (boolean (:active? battle))
              :hp (:hp battle)
              :phase (:phase battle)
              :turn-index (:turn-index battle 0)
              :reservas-x (count (get-in battle [:reservas :x] []))
              :reservas-o (count (get-in battle [:reservas :o] []))
              :pokemon-x (when px
                           (select-keys px [:nome :species :hp :nivel :imagem
                                            :imagem-shiny :altura :tipos :golpes
                                            :status :shiny :shiny?]))
              :pokemon-o (when po
                           (select-keys po [:nome :species :hp :nivel :imagem
                                            :imagem-shiny :altura :tipos :golpes
                                            :status :shiny :shiny?
                                            :motivacao-ginasio]))}}))

(defn heal-gym-motivation!
  "Recupera motivação do líder. `item` → pontos no servidor."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 gym-id indice item agora-ms]
          :as _req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  (let [spec (get heal-by-item (str/lower-case (str (or item ""))))]
    (when-not spec
      (throw (err/error :unknown_heal_item
                        {:item item :allowed (keys heal-by-item)})))
    (when-not (and (integer? indice) (<= 0 (long indice) 2))
      (throw (err/error :invalid_motivation_index {:idx indice})))
    (let [writer-ctx {:chat-id chat-id
                      :player-id player-id
                      :save-id save-id
                      :fence-generation (long fence-generation)}
          result (gym-app/heal-motivation!
                  store writer-ctx
                  {:operation-id (str operation-id)
                   :gym-id (str gym-id)
                   :indice (long indice)
                   :item-id (:item-id spec)
                   :pontos (:pontos spec)
                   :agora (or agora-ms (System/currentTimeMillis))})]
      {:operation-id (:operation-id result)
       :duplicate (boolean (:duplicate result))
       :save-id save-id
       :fence-generation (long fence-generation)
       :gym-id (str gym-id)
       :indice (long indice)
       :item (:item-id spec)
       :label (:label spec)
       :antes (:antes result)
       :depois (:depois result)})))

(defn- raid-summary
  [raid]
  (when raid
    {:raid-id (:raid-id raid)
     :fase (:fase raid)
     :ginasio (:ginasio raid)
     :nome-ginasio (:nome-ginasio raid)
     :liga (:liga raid)
     :hp-chefe (:hp-chefe raid)
     :hp-max (:hp-max raid)
     :vez (:vez raid)
     :turn-index (:turn-index raid 0)
     :ordem (:ordem raid [])
     :n-participantes (count (:ordem raid []))
     :chefe (select-keys (or (:chefe raid) {})
                         [:nome :species :raridade :hp :tipos :imagem
                          :imagem-shiny :nivel])
     :expira (:expira raid)
     :captura-expira (:captura-expira raid)
     :rewards-paid (boolean (:rewards-paid? raid))}))

(defn- require-writer!
  [{:keys [chat-id player-id save-id fence-generation operation-id]}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id (integer? fence-generation))
    (throw (err/error :missing_writer_context
                      {:need [:chat-id :player-id :save-id :fence-generation]})))
  (model/validate-save-slot! save-id)
  {:chat-id chat-id
   :player-id player-id
   :save-id save-id
   :fence-generation (long fence-generation)})

(defn open-raid!
  "Harness/teste: abre inscrições. Nunca pelo WhatsApp."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id ginasio nome-ginasio nivel-ginasio liga chefe agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        chefe-m (keywordize-keys (or chefe {}))
        result (raid-app/open-raid!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :raid-id (or raid-id (str operation-id))
                         :ginasio (str ginasio)
                         :nome-ginasio (or nome-ginasio ginasio)
                         :nivel-ginasio (long (or nivel-ginasio 1))
                         :liga (or liga "iniciante")
                         :chefe chefe-m
                         :agora (or agora-ms (System/currentTimeMillis))}
                  true identity))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :raid (raid-summary (:raid result))}))

(defn join-raid!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id collection-index nome agora-ms auto?]
          :as req}]
  (let [writer-ctx (require-writer! req)
        raid (or (when raid-id (raid-app/get-raid store raid-id))
                 (raid-app/active-raid store chat-id)
                 (throw (err/error :raid_not_found {:raid-id raid-id})))
        colecao (ports/list-collection store chat-id player-id save-id)
        idx (if auto?
              (let [score (fn [p]
                            (reduce + 0 (map #(long (or (get p %) 0))
                                             [:hp-atual :ataque :defesa
                                              :atq-esp :def-esp :veloc])))
                    aptos (keep
                           (fn [p]
                             (when (and (pos? (long (:hp-atual p 0)))
                                        (raid-domain/pokemon-elegivel-liga?
                                         (:liga raid) p)
                                        (some (fn [g]
                                                (let [c (or (:classe g) (get g "classe"))
                                                      poder (long (or (:poder g) (get g "poder") 0))]
                                                  (and (contains? #{:fisico :especial "fisico" "especial"} c)
                                                       (pos? poder))))
                                              (or (:golpes p) [])))
                               p))
                           colecao)]
                (if (seq aptos)
                  (long (:collection-index (apply max-key score aptos)))
                  (throw (err/error :invalid_raid_pokemon {:auto? true}))))
              (long collection-index))
        result (raid-app/join-raid!
                store writer-ctx
                {:operation-id (str operation-id)
                 :raid-id (:raid-id raid)
                 :collection-index (long idx)
                 :nome (or nome player-id)
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :raid (raid-summary (:raid result))
     :collection-index (long idx)
     :participant (select-keys (:participant result)
                               [:nome :hp :dano :collection-index])}))

(defn leave-raid!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        raid (or (when raid-id (raid-app/get-raid store raid-id))
                 (raid-app/active-raid store chat-id)
                 (throw (err/error :raid_not_found {})))
        result (raid-app/leave-raid!
                store writer-ctx
                ;; payload-hash de leave reusa raid-join (índice irrelevante).
                {:operation-id (str operation-id)
                 :raid-id (:raid-id raid)
                 :collection-index 0
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :raid (raid-summary (:raid result))}))

(defn start-raid!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        raid (or (when raid-id (raid-app/get-raid store raid-id))
                 (raid-app/active-raid store chat-id)
                 (throw (err/error :raid_not_found {})))
        result (raid-app/start-raid!
                store writer-ctx
                {:operation-id (str operation-id)
                 :raid-id (:raid-id raid)
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :raid (raid-summary (:raid result))}))

(defn raid-attack!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id expected-turn-index move-index agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        raid (or (when raid-id (raid-app/get-raid store raid-id))
                 (raid-app/active-raid store chat-id)
                 (throw (err/error :raid_not_found {})))
        result (raid-app/attack!
                store writer-ctx
                {:operation-id (str operation-id)
                 :raid-id (:raid-id raid)
                 :expected-turn-index (long (or expected-turn-index
                                                (:turn-index raid 0)))
                 :move-index (long (or move-index 0))
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :dano (:dano result)
     :revide (:revide result)
     :venceu (boolean (:venceu? result))
     :derrota (boolean (:derrota? result))
     :rewards (:rewards result)
     :raid (raid-summary (:raid result))}))

(defn raid-capture!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 raid-id bola roll agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        raid (or (when raid-id (raid-app/get-raid store raid-id))
                 (raid-app/active-raid store chat-id)
                 (throw (err/error :raid_not_found {})))
        bola-id (or (normalize-ball bola)
                    (throw (err/error :unknown_ball {:bola bola})))
        result (raid-app/capture!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :raid-id (:raid-id raid)
                         :bola bola-id
                         :agora (or agora-ms (System/currentTimeMillis))}
                  (some? roll) (assoc :roll (long roll))))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :capturou (boolean (:sucesso? result))
     :chance (:chance result)
     :roll (:roll result)
     :tentativas (:tentativas result)
     :bola bola-id
     :raid-id (:raid-id raid)
     :save-id save-id
     :fence-generation (long fence-generation)}))

(defn- egg-summary
  [egg]
  (when egg
    {:egg-id (:egg-id egg)
     :category (name (or (:category egg) :comum))
     :state (name (or (:state egg) :inventory))
     :species (:species egg)
     :due-at (:due-at egg)
     :save-id (:save-id egg)
     :fence-generation (:fence-generation egg)
     :incubator-id (:incubator-id egg)
     :points (:points egg 0)
     :points-goal (:points-goal egg 0)}))

(defn joy-send!
  "Envia um ferido (índice 0-based da coleção) à enfermaria."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (joy-app/send!
                store writer-ctx
                {:operation-id (str operation-id)
                 :idx (long idx)
                 :agora (or agora-ms (System/currentTimeMillis))})
        entrada (:entrada result)]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :pronto-em (:pronto-em result)
     :bed-id (:bed-id entrada)
     :pokemon (select-keys (or (:pokemon entrada) {})
                           [:nome :species :nivel :hp :hp-atual :status
                            :collection-index])}))

(defn joy-collect!
  "Recolhe todos os leitos prontos do save ativo."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (joy-app/collect!
                store writer-ctx
                {:operation-id (str operation-id)
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :n (long (:n result 0))
     :curados (mapv #(select-keys % [:nome :species :nivel :hp :hp-atual :status])
                    (or (:curados result) []))}))

(defn grant-egg!
  "Harness/teste: concede ovo. Nunca pelo WhatsApp."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 egg-id category species agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        _ (adv/ensure-account! store chat-id player-id)
        result (adv/grant-egg!
                store writer-ctx
                {:operation-id (str operation-id)
                 :egg-id (or egg-id (str operation-id))
                 :category (or category :comum)
                 :species (or species "Magikarp")
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :egg (egg-summary (:egg result))}))

(defn incubate-egg!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 egg-id incubator-id agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        _ (adv/ensure-account! store chat-id player-id)
        result (adv/incubate!
                store writer-ctx
                {:operation-id (str operation-id)
                 :egg-id (str egg-id)
                 :incubator-id (or incubator-id "permanente")
                 :agora (or agora-ms (System/currentTimeMillis))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :egg (egg-summary (:egg result))}))

(defn hatch-egg!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 egg-id hatch-id agora-ms]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (adv/hatch!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :egg-id (str egg-id)
                         :agora (or agora-ms (System/currentTimeMillis))}
                  hatch-id (assoc :hatch-id (str hatch-id))))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :pokemon-id (:pokemon-id result)
     :egg (egg-summary (:egg result))
     :registro (select-keys (or (:registro result) {})
                            [:nome :species :nivel :hp :hp-atual])}))

(defn buy-item!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 item-id qty]
          :as req}]
  (let [writer-ctx (require-writer! req)
        id (shop-domain/normalizar-item item-id)
        result (shop-app/buy!
                store writer-ctx
                {:operation-id (str operation-id)
                 :item-id id
                 :qty (long (or qty 1))})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :item-id (:item-id result)
     :nome (:nome result)
     :emoji (:emoji result)
     :preco (:preco result)
     :coins (:coins-depois result)
     :expansao (boolean (:expansao? result))
     :expansoes-mochila-50 (:expansoes-mochila-50 result)}))

(defn use-item!
  "Usa poção/cura. Se item-id vazio e kind=curar, resolve pelo status do alvo."
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 item-id idx kind]
          :as req}]
  (let [writer-ctx (require-writer! req)
        colecao (ports/list-collection store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id save-id)
        idx* (long (or idx (:ativo-index save 0)))
        poke (when (and (seq colecao) (<= 0 idx* (dec (count colecao))))
               (nth colecao idx*))
        id (cond
             (and (seq (str item-id)) (not= "auto" (str item-id)))
             (shop-domain/normalizar-item item-id)

             (or (= "curar" (str kind)) (= "cura" (str kind)))
             (or (shop-domain/cura-status-item (:status poke))
                 (throw (err/error :no_status_to_cure
                                   {:idx idx* :status (:status poke)})))

             :else
             (throw (err/error :missing_item_id {})))
        result (shop-app/use-item!
                store writer-ctx
                {:operation-id (str operation-id)
                 :item-id id
                 :idx idx*})]
    (merge {:operation-id (:operation-id result)
            :duplicate (boolean (:duplicate result))
            :save-id save-id
            :fence-generation (long fence-generation)
            :item-id (:item-id result)
            :kind (:kind result)
            :idx (:idx result)
            :pokemon (:pokemon result)}
           (select-keys result [:hp-antes :hp-depois :hp-max :status-antes]))))

(defn- pokemon-summary
  [p]
  (when p
    (select-keys p [:id-pokemon :nome :species :nivel :hp :hp-atual :tipos
                    :golpes :ofertas-golpes :golpes-oferecidos :historico-golpes
                    :golpes-removidos :imagem :imagem-shiny :shiny :raridade
                    :habilidade :ataque :defesa :atq-esp :def-esp :veloc])))

(defn set-active-pokemon!
  [store {:keys [chat-id player-id save-id fence-generation operation-id idx]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (active-app/set-active!
                store writer-ctx
                {:operation-id (str operation-id)
                 :idx (long idx)})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :ativo-index (:ativo-index result idx)
     :pokemon (pokemon-summary (:pokemon result))}))

(defn evolve-pokemon!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx item-id dados]
          :as req}]
  (let [writer-ctx (require-writer! req)
        dados* (keywordize-keys (or dados {}))
        result (evo-app/evolve!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :idx (long idx)
                         :dados dados*}
                  item-id (assoc :item-id (str item-id))))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :item-id item-id
     :pokemon (pokemon-summary (:registro result))}))

(defn learn-move!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx golpe slot oferta?]
          :as req}]
  (let [writer-ctx (require-writer! req)
        golpe* (keywordize-keys (or golpe {}))
        result (moves-app/learn-move!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :idx (long idx)
                         :golpe golpe*
                         :oferta? (boolean oferta?)}
                  (some? slot) (assoc :slot (long slot))))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :pokemon (pokemon-summary (:registro result))}))

(defn forget-move!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx slot]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (moves-app/forget-move!
                store writer-ctx
                {:operation-id (str operation-id)
                 :idx (long idx)
                 :slot (long slot)})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :removido (:removido result)
     :pokemon (pokemon-summary (:registro result))}))

(defn offer-move!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx golpe]
          :as req}]
  (let [writer-ctx (require-writer! req)
        golpe* (keywordize-keys (or golpe {}))
        result (moves-app/offer-move!
                store writer-ctx
                {:operation-id (str operation-id)
                 :idx (long idx)
                 :golpe golpe*})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :resultado (:resultado result)
     :pokemon (pokemon-summary (:registro result))}))

(defn refuse-offer!
  [store {:keys [chat-id player-id save-id fence-generation operation-id idx]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (moves-app/refuse-offer!
                store writer-ctx
                {:operation-id (str operation-id)
                 :idx (long idx)})]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :pokemon (pokemon-summary (:registro result))}))

(defn relearn-move!
  [store {:keys [chat-id player-id save-id fence-generation operation-id
                 idx slug slot]
          :as req}]
  (let [writer-ctx (require-writer! req)
        result (moves-app/relearn-move!
                store writer-ctx
                (cond-> {:operation-id (str operation-id)
                         :idx (long idx)
                         :slug (str slug)}
                  (some? slot) (assoc :slot (long slot))))]
    {:operation-id (:operation-id result)
     :duplicate (boolean (:duplicate result))
     :save-id save-id
     :fence-generation (long fence-generation)
     :idx (:idx result)
     :slug (:slug result)
     :preco (:preco result moves/preco-reaprender)
     :coins-depois (:coins-depois result)
     :pokemon (pokemon-summary (:registro result))}))
