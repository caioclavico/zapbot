(ns pokemon.application.hunt
  "Caça selvagem — plano (leitura) + start com fence/operation-id."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.xp :as xp]))

(defn- trainer-level
  [store chat-id player-id]
  (let [slot (ports/active-save-id store chat-id player-id)
        save (when slot (ports/get-save store chat-id player-id slot))]
    (long (or (:trainer-level save)
              (:nivel (xp/perfil-treinador save))
              1))))

(defn plan
  "Plano de caça (RNG de tipos/surto no servidor, injetável só em testes)."
  [store chat-id player-id {:keys [area-id agora-ms timezone-id roll-tipos roll-surto]}]
  (let [player (ports/get-player store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        nivel (trainer-level store chat-id player-id)
        agora (long (or agora-ms (System/currentTimeMillis)))
        ultima (long (:ultima-cacada-at save 0))
        restante (hunt/cooldown-restante-ms ultima agora)
        battle (ports/active-battle store chat-id player-id)
        group (ports/get-group store chat-id)
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        ativo-idx (long (:ativo-index save 0))
        ativo (when (and colecao (seq colecao) (< ativo-idx (count colecao)))
                (nth colecao ativo-idx))
        plano (hunt/montar-plano
               {:area-pedida area-id
                :agora-ms agora
                :timezone-id timezone-id
                :trainer-level nivel
                :roll-tipos roll-tipos
                :roll-surto roll-surto})
        cabe? (and save (capacity/cabe-pokemon? (count colecao) save))]
    (merge plano
           {:chat-id chat-id
            :player-id player-id
            :active-save-id slot
            :fence-generation (:fence-generation player 0)
            :eligibilidade
            {:tem-treinador (some? player)
             :tem-pokemon (boolean (seq colecao))
             :ativo-vivo (boolean (and ativo (pos? (long (:hp-atual ativo 0)))))
             :cooldown-ok (hunt/pode-cacar? ultima agora)
             :cooldown-restante-s (long (Math/ceil (/ restante 1000.0)))
             :sem-batalha-propria (nil? battle)
             :chat-livre (or (nil? group)
                             (nil? (:active-wild-battle-id group))
                             (= (:active-wild-player-id group) player-id))
             :somente-batalha (not cabe?)}
            :ativo (when ativo
                     (select-keys ativo [:collection-index :nome :species :nivel
                                         :hp :hp-atual :status]))})))

(defn start-hunt!
  "Inicia caça no save congelado."
  ([store chat-id player-id request]
   (start-hunt! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/start-hunt! store writer-ctx full))))
