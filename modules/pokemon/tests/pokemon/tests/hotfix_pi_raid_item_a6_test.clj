(ns pokemon.tests.hotfix-pi-raid-item-a6-test
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.adventure-activity :as adventure-activity]))
(deftest wild-victory-adiado-quando-aguardando-captura
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "turn-1"
                 :operation-receipt/kind "battle-turn"
                 :operation-receipt/summary (pr-str {:battle-id "b1"})}
        built {:tx [receipt]
               :result {:battle {:battle-id "b1" :kind :wild
                                 :active? false :outcome :win
                                 :aguardando-captura? true}}}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)]
    (is (nil? targets) "não credita PI na entrada da captura")))

(deftest liberar-credita-wild-victory
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "rel-1"
                 :operation-receipt/kind "encounter-release"
                 :operation-receipt/summary (pr-str {:battle-id "b1"})}
        built {:tx [receipt] :result {:liberou? true :battle-id "b1"}}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)]
    (is (= 1 (count targets)))
    (is (= :wild-victory (get-in targets [0 :opts :activity])))))

(deftest egg-progress-no-summary
  (let [ovo (assoc (eggs/novo-ovo "e1" :comum 1000 "bulbasaur")
                   :state :incubating :points 40 :incubator-id "i1"
                   :points-goal 100)
        incu {:incubator-id "i1" :kind :infinite :uses nil :bonus-points 0
              :active-egg-id "e1"}
        st (-> (eggs/estado-inicial)
               (assoc :incubators [incu] :eggs [ovo]))
        r (egg-activity/apply-activity
           st {:event-id "ev-pi" :activity :hunt-complete :agora 2000})]
    (is (= 15 (:pi-granted r)))
    (is (seq (get-in r [:summary :egg-progress])))
    (is (= 40 (get-in r [:summary :egg-progress 0 :points-before])))
    (is (= 55 (get-in r [:summary :egg-progress 0 :points])))))

(deftest raid-usar-item-cura-party-e-revide
  (let [poke {:nome "Charizard" :species "charizard" :hp 120 :hp-atual 12
              :golpes [{:slug "ember" :poder 40 :tipo "fogo" :classe "especial"}]}
        part (#'raid/sync-active
              {:nome "Ash" :save-id "s1" :fence-generation 1 :dano 0
               :party [{:pokemon poke :hp 12 :collection-index 0}]
               :active-party-idx 0})
        raid {:raid-id "r1" :fase "combate" :chat-id "c"
              :expira (+ (System/currentTimeMillis) 600000)
              :hp-chefe 500 :hp-max 500 :defesa 50 :def-esp 50
              :vez "ash" :ordem ["ash"] :turn-index 3 :revision 1
              :participantes {"ash" part}
              :chefe {:nome "Dragonite" :tipos ["dragao"] :hp 500}}
        out (raid/usar-item raid "ash" "pocao-maxima" (System/currentTimeMillis))
        part' (get-in out [:raid :participantes "ash"])]
    (is (= 120 (:hp-depois out)))
    (is (= 12 (:hp-antes out)))
    (is (pos? (:revide out)))
    (is (= (- 120 (:revide out)) (:hp part')))
    (is (= 4 (get-in out [:raid :turn-index])))
    (is (= "ash" (get-in out [:raid :vez])) "único vivo mantém a vez")
    (is (= "combate" (get-in out [:raid :fase])))))
