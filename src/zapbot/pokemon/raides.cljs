(ns zapbot.pokemon.raides
  "Comandos de raides no adaptador WhatsApp."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.pokemon.resposta :as resposta]))

(def ^:private jvm-transport (js/require "../scripts/lib/pokemon-jvm-client.cjs"))
(def ^:private jvm-present (js/require "../scripts/lib/pokemon-jvm-present.cjs"))
(def ^:private jvm-media (js/require "../scripts/lib/pokemon-jvm-media.cjs"))

(defn executar!
  "Fluxo !pk raide / gin entrar|iniciar|atacar|… — gameplay JVM antes de mídia."
  [jvm ctx pedido chat-id player-id player-name args-raid]
  (let [sub (.resolveRaidSubcommand jvm-present (or (first args-raid) ""))
        resto (vec (rest args-raid))
        prefix (str config/prefix "pk")]
    (cond
      (or (nil? sub) (str/blank? (str sub)) (= "status" sub))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                       #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              present (.withRaidTurnMention jvm-present
                        (.formatRaidStatus jvm-present data prefix) (.-raid data))]
        (resposta/envelope-jvm pedido (.-text present) nil (js->clj (.-mentions present))))

      (= "abrir" sub)
      (let [token (str/trim (str/join " " resto))]
        (if (str/blank? token)
          (resposta/envelope-jvm pedido (.formatRaidOpenHelp jvm-present prefix))
          (let [ginasio (.resolveRaidGym jvm-present token)]
            (when-not ginasio
              (throw (resposta/erro-contrato
                      (str "❓ Treinador inválido. Use " prefix " raid abrir para ver a lista."))))
            (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                    op-id (.stableOperationId jvm-transport
                                              (:requestId pedido)
                                              (str "raid-open:" ginasio))
                    data (desempenho/medir! ctx "pokemon_jvm_raid_open"
                           #(.openRaid jvm
                              #js {:chatId chat-id
                                   :playerId player-id
                                   :saveId (.-activeSaveId ativo)
                                   :fenceGeneration (.-fenceGeneration ativo)
                                   :operationId op-id
                                   :ginasio ginasio}))]
              (resposta/envelope-jvm pedido (.formatRaidOpened jvm-present data prefix))))))

      (= "cancelar" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-cancel")
              data (desempenho/medir! ctx "pokemon_jvm_raid_cancel"
                     #(.cancelRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))]
        (resposta/envelope-jvm pedido (.formatRaidCancelled jvm-present data prefix)))

      (= "time" sub)
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_eligible"
                       #(.getRaidEligible jvm #js {:chatId chat-id :playerId player-id}))]
        (resposta/envelope-jvm pedido (.formatRaidEligible jvm-present data prefix)))

      (= "entrar" sub)
      ;; Default: Pokémon *ativo* (escolher). `auto` = melhor apto. `<n>` = índice.
      (let [raw (str/lower-case (str/trim (str (or (first resto) "ativo"))))
            auto? (= "auto" raw)
            use-active? (or (str/blank? raw) (= "ativo" raw) (= "active" raw)
                            (= "escolhido" raw))
            idx (when-not (or auto? use-active?)
                  (let [n (js/parseInt raw 10)]
                    (when-not (js/isNaN n) (dec n))))]
        (when (and (not auto?) (not use-active?) (or (nil? idx) (neg? idx)))
          (throw (resposta/erro-contrato
                  (str "❓ Use " prefix " raid entrar  ·  "
                       prefix " raid entrar auto  ·  "
                       prefix " raid entrar <n>"))))
        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                mode (cond auto? "auto" use-active? "ativo" :else (str idx))
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "raid-join:" mode))
                data (desempenho/medir! ctx "pokemon_jvm_raid_join"
                       #(.joinRaid jvm
                          (clj->js (cond-> {:chatId chat-id
                                            :playerId player-id
                                            :saveId (.-activeSaveId ativo)
                                            :fenceGeneration (.-fenceGeneration ativo)
                                            :operationId op-id
                                            :nome player-name}
                                     auto? (assoc :auto true)
                                     use-active? (assoc :useActive true)
                                     (and (not auto?) (not use-active?))
                                     (assoc :collectionIndex idx)))))]
          (resposta/envelope-jvm pedido (.formatRaidJoin jvm-present data prefix))))

      (= "sair" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-leave")
              data (desempenho/medir! ctx "pokemon_jvm_raid_leave"
                     #(.leaveRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))]
        (resposta/envelope-jvm pedido (.formatRaidLeave jvm-present data prefix)))

      (= "iniciar" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-start")
              data (desempenho/medir! ctx "pokemon_jvm_raid_start"
                     #(.startRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))
              img (resposta/render-seguro! ctx "pokemon_jvm_media_raid_start"
                    #(.renderRaidBattleCard jvm-media
                       #js {:ginasio (or (.-nomeGinasio (.-raid data))
                                         (.-ginasio (.-raid data)))
                            :chefe (some-> (.-raid data) .-chefe)
                            :atacante (some-> (.-raid data) .-vezPokemon)
                            :treinador (or (.-vezNome (.-raid data)) player-name)
                            :hpChefe (.-hpChefe (.-raid data))
                            :hpMax (.-hpMax (.-raid data))
                            :hpAtacante (.-vezHp (.-raid data))
                            :fase (.-fase (.-raid data))
                            :turnIndex (.-turnIndex (.-raid data))
                            :renderPhase "raid-start-manual"}))
              present (.withRaidTurnMention jvm-present
                        (.formatRaidStart jvm-present data prefix) (.-raid data))]
        (resposta/envelope-jvm pedido (.-text present) img (js->clj (.-mentions present))))

      (= "atacar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not raid
                  (throw (resposta/erro-contrato "❓ Nenhuma raid ativa neste chat.")))
              ;; Aceita `suaVez` (contrato) e `suaVez?` (legado camelCase com ?).
              ;; Também confere igualdade literal player-id === vez (identidade canônica).
              sua-vez? (or (true? (.-suaVez raid))
                           (true? (aget raid "suaVez?"))
                           (and player-id (.-vez raid) (= player-id (.-vez raid))))
              _ (when-not sua-vez?
                  (throw (resposta/erro-contrato "⏳ Não é a sua vez de atacar.")))
              move (js/parseInt (or (first resto) "1") 10)
              move-idx (if (js/isNaN move) 0 (max 0 (dec move)))
              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido)
                                        (str "raid-atk:" (.-raidId raid) ":"
                                             (.-turnIndex raid) ":" move-idx))
              data (desempenho/medir! ctx "pokemon_jvm_raid_attack"
                     #(.raidAttack jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id
                             :raidId (.-raidId raid)
                             :expectedTurnIndex (.-turnIndex raid)
                             :moveIndex move-idx}))
              img (resposta/render-seguro! ctx "pokemon_jvm_media_raid_atk"
                    #(.renderRaidBattleCard jvm-media
                       #js {:ginasio (or (.-nomeGinasio (.-raid data))
                                         (.-ginasio (.-raid data)))
                            :chefe (some-> (.-raid data) .-chefe)
                            :atacante (or (.-vezPokemon (.-raid data))
                                          (some-> raid .-vezPokemon))
                            :treinador (or (.-vezNome (.-raid data))
                                           (.-vezNome raid)
                                           player-name)
                            :hpChefe (.-hpChefe (.-raid data))
                            :hpMax (.-hpMax (.-raid data))
                            :hpAtacante (or (.-vezHp (.-raid data))
                                            (.-vezHp raid))
                            :fase (.-fase (.-raid data))
                            :turnIndex (.-turnIndex (.-raid data))
                            :renderPhase "raid-attack"}))
              present (.withRaidTurnMention jvm-present
                        (.formatRaidAttack jvm-present data prefix) (.-raid data))]
        (resposta/envelope-jvm pedido (resposta/append-trainer-level-ups (.-text present) data) img
                      (js->clj (or (.-mentions present) #js []))))

      ;; fruta / frambo / fruta frambo — espelha caça selvagem: prepare + UI completa
      (or (= "frambo" sub) (= "fruta" sub))
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not (and raid (.-capturaPendente raid))
                  (throw (resposta/erro-contrato "❓ Captura de raid indisponível agora.")))
              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido)
                                        (str "raid-frambo:" (.-raidId raid)))
              _prep (desempenho/medir! ctx "pokemon_jvm_raid_frambo"
                      #(.raidFrambo jvm
                         #js {:chatId chat-id
                              :playerId player-id
                              :saveId (.-activeSaveId ativo)
                              :fenceGeneration (.-fenceGeneration ativo)
                              :operationId op-id
                              :raidId (.-raidId raid)}))
              ;; Re-fetch: chances reais + estoque após prepare (formatter compartilhado).
              raid-ui (desempenho/medir! ctx "pokemon_jvm_raid_active"
                         #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))]
        (resposta/envelope-jvm pedido
                      (.formatRaidCaptureUi jvm-present raid-ui prefix
                                            #js {:afterFrambo true})))

      (= "capturar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not (and raid (.-capturaPendente raid))
                  (throw (resposta/erro-contrato "❓ Captura de raid indisponível agora.")))]
        (if (str/blank? (str/join " " resto))
          (resposta/envelope-jvm pedido (.formatRaidCaptureUi jvm-present raid-data prefix))
          (let [tokens (->> resto (map str) (remove str/blank?) vec)
                frambo? (boolean (some #(contains? #{"frambo" "framb" "razz"}
                                                   (str/lower-case %))
                                       tokens))
                bola (->> tokens
                          (remove #(contains? #{"frambo" "framb" "razz"}
                                              (str/lower-case %)))
                          (str/join " "))
                _ (when (str/blank? bola)
                    (throw (resposta/erro-contrato
                            (str "❓ Use " prefix
                                 " raid capturar 1|2|3"
                                 " (ou pokebola|grande-bola|ultra-bola)"))))]
            (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                    op-id (.stableOperationId jvm-transport
                                              (:requestId pedido)
                                              (str "raid-cap:" (.-raidId raid) ":"
                                                   bola (when frambo? ":frambo")))
                    data (desempenho/medir! ctx "pokemon_jvm_raid_capture"
                           #(.raidCapture jvm
                              (clj->js (cond-> {:chatId chat-id
                                                :playerId player-id
                                                :saveId (.-activeSaveId ativo)
                                                :fenceGeneration (.-fenceGeneration ativo)
                                                :operationId op-id
                                                :raidId (.-raidId raid)
                                                :bola bola}
                                         frambo? (assoc :frambo true)))))
                    img (resposta/render-seguro! ctx "pokemon_jvm_media_raid_cap"
                          #(.renderCaptureBall jvm-media
                              #js {:bola (or (.-bola data) "pokebola")
                                   :capturou (boolean (.-capturou data))
                                   :fugiu false}))]
              (resposta/envelope-jvm pedido
                            (resposta/append-trainer-level-ups
                             (.formatRaidCapture jvm-present data prefix) data) img)))))

      (or (= "ajuda" sub) (= "help" sub) (= "?" sub))
      (resposta/envelope-jvm pedido (.formatHelp jvm-present "raid" prefix))

      :else
      (resposta/envelope-jvm pedido
                    (str "❓ Comando de raid não reconhecido.\n"
                         "Use " prefix " ajuda raid para consultar os comandos disponíveis.")))))
