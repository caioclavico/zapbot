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

(def payload-hash reward/payload-hash)
(def assert-receipt-payload! reward/assert-receipt-payload!)
