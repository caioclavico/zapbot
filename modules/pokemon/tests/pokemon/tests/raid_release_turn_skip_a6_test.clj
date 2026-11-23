(ns pokemon.tests.raid-release-turn-skip-a6-test
  "FASE 1–6: liberar captura, skip 60s, idle 300s CAS, outbox idle."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.raid :as raid]
            ))

(defn- part
  [pid hp]
  {:hp hp
   :nome pid
   :pokemon {:nome "Pikachu" :hp 100 :hp-atual hp}
   :save-id "a"
   :fence-generation 1
   :party [{:pokemon {:nome "Pikachu" :hp 100 :hp-atual hp} :hp hp}]
   :active-party-idx 0
   :collection-index 0})

(defn- raid-combat
  [& {:keys [aptos agora turn-expires]}]
  (let [agora (long (or agora 1000000))
        ordem (vec aptos)
        parts (into {} (map (fn [pid] [pid (part pid 80)]) ordem))]
    {:raid-id "r-skip-1"
     :chat-id "chat-1"
     :fase "combate"
     :vez (first ordem)
     :ordem ordem
     :turn-index 3
     :expira (+ agora 1800000)
     :idle-expires-at (+ agora 300000)
     :idle-expired? false
     :turn-expires-at turn-expires
     :participantes parts
     :chefe {:nome "Dragonite" :raridade "raro"}
     :hp-chefe 500
     :hp-max 500}))

(deftest captura-pendente-respeita-liberou
  (let [agora 1000
        raid {:fase "vitoria"
              :captura-expira 5000
              :participantes {"p1" {:dano 10} "p2" {:dano 10}}
              :capturas {"p1" {:tentativas 0 :capturou false :liberou true}
                         "p2" {:tentativas 0 :capturou false}}}]
    (is (false? (raid/captura-pendente? raid "p1" agora)))
    (is (true? (raid/captura-pendente? raid "p2" agora)))
    (is (true? (raid/algum-captura-pendente? raid agora)))
    (is (false? (raid/deve-liberar-ponteiro? raid agora)))))

(deftest liberar-captura-domain
  (let [agora 1000
        raid {:fase "vitoria"
              :captura-expira 5000
              :participantes {"p1" {:dano 10}}
              :capturas {"p1" {:tentativas 1 :capturou false}}
              :chefe {:nome "Dragonite"}}
        out (raid/liberar-captura raid "p1" agora)]
    (is (true? (:liberou? out)))
    (is (true? (get-in out [:raid :capturas "p1" :liberou])))
    (is (true? (:pointer-cleared? out)))
    (is (false? (raid/captura-pendente? (:raid out) "p1" agora)))))

(deftest turn-window-solo-vs-multi
  (let [agora 1000]
    (is (nil? (:turn-expires-at (raid/turn-window agora ["a"] 60000))))
    (is (= (+ agora 60000)
           (:turn-expires-at (raid/turn-window agora ["a" "b"] 60000))))))

(deftest pular-turno-sem-dano
  (let [agora 1000
        r (raid-combat :aptos ["ash" "misty"] :agora agora
                       :turn-expires agora)
        hp0 (get-in r [:participantes "ash" :hp])
        out (raid/pular-turno r agora)
        r' (:raid out)]
    (is (= "ash" (:skipped-player out)))
    (is (= "misty" (:next-player out)))
    (is (= "misty" (:vez r')))
    (is (= 4 (:turn-index r')))
    (is (= hp0 (get-in r' [:participantes "ash" :hp])))
    (is (= (:idle-expires-at r) (:idle-expires-at r')))))

(deftest pular-turno-solo-recusa
  (let [agora 1000
        r (raid-combat :aptos ["ash"] :agora agora)]
    (is (thrown? clojure.lang.ExceptionInfo (raid/pular-turno r agora)))))

(deftest idle-messages-canonicas
  (is (re-find #"CAÇADA ENCERRADA" (idle/format-idle-message {:battle-kind :wild})))
  (is (re-find #"PVP ENCERRADO" (idle/format-idle-message {:battle-kind :pvp})))
  (is (re-find #"RAID ENCERRADA" (idle/format-idle-message
                                  {:battle-kind :raid :boss-name "Dragonite"})))
  (is (re-find #"Dragonite" (idle/format-idle-message
                             {:battle-kind :raid :boss-name "Dragonite"}))))

(deftest turn-timeout-config-default
  (is (= 60 (idle/turn-timeout-segundos)))
  (is (= 300 (idle/idle-timeout-segundos))))
