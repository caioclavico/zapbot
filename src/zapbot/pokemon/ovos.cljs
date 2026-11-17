(ns zapbot.pokemon.ovos
  "Comandos de ovos e incubadoras no adaptador WhatsApp."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.pokemon.resposta :as resposta]))

(def ^:private jvm-transport (js/require "../scripts/lib/pokemon-jvm-client.cjs"))
(def ^:private jvm-present (js/require "../scripts/lib/pokemon-jvm-present.cjs"))
(def ^:private jvm-media (js/require "../scripts/lib/pokemon-jvm-media.cjs"))

(defn- hatch-egg-jvm!
  [jvm ctx pedido chat-id player-id egg]
  (let [prefix (str config/prefix "pk")]
    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
            op-id (.stableOperationId jvm-transport
                                      (:requestId pedido)
                                      (str "egg-hatch:" (.-eggId egg)))
            data (desempenho/medir! ctx "pokemon_jvm_egg_hatch"
                   #(.hatchEgg jvm
                      #js {:chatId chat-id
                           :playerId player-id
                           :saveId (.-activeSaveId ativo)
                           :fenceGeneration (.-fenceGeneration ativo)
                           :operationId op-id
                           :eggId (.-eggId egg)
                           :hatchId (str "hatch:" (.-eggId egg))}))]
      (.formatEggHatch jvm-present data prefix))))

(defn executar!
  "Fluxo !pk ovos / incubar / incubadoras / incubacao / chocar — sob demanda.
   ovos/incubadoras anexam PNG Sharp (egg-cards); falha de mídia → só texto."
  [jvm ctx pedido chat-id player-id sub args]
  (let [prefix (str config/prefix "pk")
        sub (str/lower-case (str (or sub "")))]
    (cond
      (or (= "incubadoras" sub) (= "incubadora" sub))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_adventure"
                       #(.getAdventure jvm #js {:chatId chat-id :playerId player-id}))
              texto (.formatIncubators jvm-present data prefix)
              img (resposta/render-seguro! ctx "pokemon_jvm_media_incubators"
                    #(.renderIncubatorCard jvm-media data
                                           #js {:filename "incubadoras.png"}))]
        (resposta/envelope-jvm pedido texto img))

      (or (= "incubacao" sub) (= "incubação" sub) (= "pi" sub))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_adventure"
                       #(.getAdventure jvm #js {:chatId chat-id :playerId player-id}))]
        (resposta/envelope-jvm pedido (.formatIncubationHelp jvm-present data prefix)))

      (= "incubar" sub)
      (let [n-str (first args)
            kind (str/lower-case (str (or (second args) "permanente")))
            incubator (case kind
                        ("extra" "especial" "temporaria" "temporária") kind
                        kind)]
        (when (str/blank? (str n-str))
          (throw (resposta/erro-contrato (str "❓ Use " prefix " incubar <n> [incubadora]"))))
        (p/let [adv (.getAdventure jvm #js {:chatId chat-id :playerId player-id})
                egg (.resolveEggIndex jvm-present adv n-str)
                _ (when-not egg
                    (throw (resposta/erro-contrato "❓ Índice de ovo inválido. Use !pk ovos.")))
                _ (when-not (= "inventory" (str (.-state egg)))
                    (throw (resposta/erro-contrato "❓ Esse ovo não está disponível para incubar.")))
                ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "egg-inc:" (.-eggId egg)))
                data (desempenho/medir! ctx "pokemon_jvm_egg_incubate"
                       #(.incubateEgg jvm
                          #js {:chatId chat-id
                               :playerId player-id
                               :saveId (.-activeSaveId ativo)
                               :fenceGeneration (.-fenceGeneration ativo)
                               :operationId op-id
                               :eggId (.-eggId egg)
                               :incubatorId (or incubator "permanente")}))]
          (resposta/envelope-jvm pedido (.formatEggIncubate jvm-present data prefix))))

      (or (= "nascer" sub) (= "hatch" sub) (= "chocar" sub))
      (p/let [adv (.getAdventure jvm #js {:chatId chat-id :playerId player-id})
              n-str (first args)
              eggs (or (.-eggs adv) #js [])
              prontos (.filter eggs
                               (fn [e]
                                 (let [st (str (.-state e))]
                                   (or (= st "ready-to-hatch")
                                       (= st "delivery-pending")))))
              sem-prontos (let [incubando (.filter eggs #(= "incubating" (.-state %)))
                                faltam (map #(str (.-category %) ": " (.-pointsRestante %) " PI")
                                            (array-seq incubando))]
                            (str "❓ Nenhum ovo pronto. "
                                 (when (seq faltam) (str "Faltam " (str/join " · " faltam) ". "))
                                 "Use " prefix " incubadoras."))
              alvos (if (str/blank? (str n-str))
                      (vec (array-seq prontos))
                      (let [egg (.resolveHatchEggIndex jvm-present adv n-str)]
                        (when-not egg
                          (throw (resposta/erro-contrato
                                  (if (zero? (.-length prontos))
                                    sem-prontos
                                    (str "❓ Índice inválido entre ovos prontos. Use "
                                         prefix " incubadoras.")))))
                        [egg]))
              _ (when (empty? alvos)
                  (throw (resposta/erro-contrato sem-prontos)))
              textos (atom [])]
        (-> (reduce (fn [p egg]
                      (p/then p (fn [_]
                                  (p/then (hatch-egg-jvm! jvm ctx pedido chat-id player-id egg)
                                          (fn [txt] (swap! textos conj txt))))))
                    (p/resolved nil)
                    alvos)
            (p/then (fn [_]
                      (resposta/envelope-jvm pedido (str/join "\n\n" @textos))))))

      :else
      ;; ovos / ovo / ajuda → status + card PNG
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_adventure"
                       #(.getAdventure jvm #js {:chatId chat-id :playerId player-id}))
              texto (.formatAdventureStatus jvm-present data prefix)
              img (resposta/render-seguro! ctx "pokemon_jvm_media_eggs"
                    #(.renderEggInventoryCard jvm-media data
                                              #js {:filename "ovos.png"}))]
        (resposta/envelope-jvm pedido texto img)))))
