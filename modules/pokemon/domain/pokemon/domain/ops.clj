(ns pokemon.domain.ops
  "Payload canônico + validação de identidade para captura/evolução/golpes."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.model :as model]
            [pokemon.domain.reward :as reward]))

(defn validate-mutation!
  [{:keys [operation-id chat-id player-id save-id fence-generation] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id)
    (throw (err/error :missing_reward_identity
                      {:need [:chat-id :player-id :save-id]})))
  (when-not (integer? fence-generation)
    (throw (err/error :missing_fence_generation {})))
  (model/validate-save-slot! save-id)
  req)

(defn capture-payload
  [{:keys [bola status tentativas-anteriores pokemon roll fuga-roll]}]
  (reward/canonicalize
   (cond-> {:bola bola
            :tentativas-anteriores (long (or tentativas-anteriores 0))
            :pokemon (reward/canonicalize
                      (select-keys pokemon
                                   [:nome :species :shiny :shiny? :nivel :raridade
                                    :hp :ataque :defesa :atq-esp :def-esp :veloc
                                    :tipos :id-pokemon :golpes]))}
     (some? status) (assoc :status (name (if (keyword? status) status (keyword status))))
     (some? roll) (assoc :roll (long roll))
     (some? fuga-roll) (assoc :fuga-roll (long fuga-roll)))))

(defn evolve-payload
  [{:keys [idx dados item-id]}]
  (reward/canonicalize
   (cond-> {:idx (long idx)
            :dados (reward/canonicalize
                    (select-keys dados
                                 [:nome-novo :imagem :imagem-shiny :tipos :habilidade
                                  :hp :ataque :defesa :atq-esp :def-esp :veloc]))}
     item-id (assoc :item-id item-id))))

(defn learn-move-payload
  [{:keys [idx golpe slot]}]
  (reward/canonicalize
   (cond-> {:idx (long idx)
            :golpe (reward/canonicalize
                    (select-keys golpe [:slug :nome-exibicao :tipo :poder :classe]))}
     (some? slot) (assoc :slot (long slot)))))

(defn start-battle-payload
  [{:keys [battle-id kind pokemon-x pokemon-o collection-index]}]
  (reward/canonicalize
   {:battle-id battle-id
    :kind (name (or kind :wild))
    :collection-index (long (or collection-index 0))
    :pokemon-x (reward/canonicalize
                (select-keys pokemon-x
                             [:nome :species :id-pokemon :hp :ataque :defesa
                              :atq-esp :def-esp :veloc :tipos :habilidade :item
                              :golpes :nivel :raridade :shiny]))
    :pokemon-o (reward/canonicalize
                (select-keys pokemon-o
                             [:nome :species :hp :ataque :defesa :atq-esp :def-esp
                              :veloc :tipos :habilidade :item :golpes :nivel :raridade]))}))

(defn battle-turn-payload
  [{:keys [battle-id expected-turn-index action move-index rng]}]
  (reward/canonicalize
   (cond-> {:battle-id battle-id
            :expected-turn-index (long expected-turn-index)
            :action (name action)}
     (some? move-index) (assoc :move-index (long move-index))
     (map? rng) (assoc :rng (reward/canonicalize rng)))))

(defn gym-resolve-payload
  [{:keys [gym-id venceu? participacao dia pedra-roll indices]}]
  (reward/canonicalize
   (cond-> {:gym-id gym-id
            :venceu? (boolean venceu?)
            :dia dia
            :participacao (reward/canonicalize (or participacao {}))}
     (some? pedra-roll) (assoc :pedra-roll (long pedra-roll))
     (seq indices) (assoc :indices (mapv long indices)))))

(defn gym-occupy-payload
  [{:keys [gym-id indices expected-revision]}]
  (reward/canonicalize
   {:gym-id gym-id
    :indices (mapv long indices)
    :expected-revision (long (or expected-revision 0))}))

(defn raid-open-payload
  [{:keys [raid-id ginasio liga]}]
  (reward/canonicalize
   {:raid-id raid-id :ginasio ginasio :liga (or liga "iniciante")}))

(defn raid-join-payload
  [{:keys [raid-id collection-index]}]
  (reward/canonicalize
   {:raid-id raid-id :collection-index (long collection-index)}))

(defn raid-attack-payload
  [{:keys [raid-id expected-turn-index move-index]}]
  (reward/canonicalize
   {:raid-id raid-id
    :expected-turn-index (long expected-turn-index)
    :move-index (long move-index)}))

(defn raid-reward-payload
  [{:keys [raid-id player-id]}]
  (reward/canonicalize
   {:raid-id raid-id :player-id player-id :kind "raid-victory"}))

(defn raid-capture-payload
  [{:keys [raid-id bola roll]}]
  (reward/canonicalize
   (cond-> {:raid-id raid-id :bola bola}
     (some? roll) (assoc :roll (long roll)))))

(defn egg-grant-payload
  [{:keys [egg-id category species]}]
  (reward/canonicalize
   {:egg-id egg-id :category (name category) :species species}))

(defn egg-incubate-payload
  [{:keys [egg-id incubator-id]}]
  (reward/canonicalize
   {:egg-id egg-id :incubator-id incubator-id}))

(defn egg-hatch-payload
  [{:keys [egg-id hatch-id]}]
  (reward/canonicalize
   {:egg-id egg-id :hatch-id hatch-id}))

(defn joy-send-payload
  [{:keys [idx]}]
  (reward/canonicalize {:idx (long idx)}))

(defn joy-collect-payload
  [{:keys [agora]}]
  (reward/canonicalize {:action "joy-collect" :agora (long agora)}))

(def payload-hash reward/payload-hash)
(def assert-receipt-payload! reward/assert-receipt-payload!)
