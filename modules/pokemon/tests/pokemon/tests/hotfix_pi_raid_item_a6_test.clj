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
        part' (get-in out [:raid :participantes "ash"])
        ;; Domínio: revide-chefe = ceil(120/5) = 24 (não fixar 35 como regra universal).
        revide-esperado (#'raid/revide-chefe 120)]
    (is (= 120 (:hp-depois out)))
    (is (= 12 (:hp-antes out)))
    (is (= revide-esperado (:revide out)))
    (is (= (- 120 revide-esperado) (:hp-apos-revide out)))
    (is (= (- 120 revide-esperado) (:hp part')))
    (is (= 4 (get-in out [:raid :turn-index])))
    (is (= "ash" (get-in out [:raid :vez])) "único vivo mantém a vez")
    (is (= "combate" (get-in out [:raid :fase])))))

(deftest raid-item-derrota-detecta-alvos-pi
  "Poção que finaliza a raid (último combatente KO no revide) liquida PI/ovos."
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "raid-item-1"
                 :operation-receipt/kind "raid-item"
                 :operation-receipt/summary
                 (pr-str {:item-id "pocao-maxima" :derrota? true})}
        built {:tx [receipt]
               :result {:derrota? true
                        :venceu? false
                        :raid {:raid-id "r1" :fase "derrota" :boss-categoria :raro}
                        :rewards [{:player-id "ash" :outcome :derrota}
                                  {:player-id "misty" :outcome :derrota}]}}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)]
    (is (= 2 (count targets)))
    (is (every? #(= :raid-defeat (get-in % [:opts :activity])) targets))
    (is (= #{"ash" "misty"} (set (map :player-id targets))))
    (is (= #{"egg-act:raid:r1:ash" "egg-act:raid:r1:misty"}
           (set (map #(get-in % [:opts :event-id]) targets))))))

(deftest raid-item-e-raid-reward-mesmo-event-id
  "Mesmo event-id que raid-reward → idempotência (sem PI duplicado)."
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "raid-item-2"
                 :operation-receipt/kind "raid-item"
                 :operation-receipt/summary (pr-str {:derrota? true})}
        built {:tx [receipt]
               :result {:derrota? true
                        :raid {:raid-id "r9"}
                        :rewards [{:player-id "ash" :outcome :derrota}]}}
        from-item (#'adventure-activity/detect-targets
                   nil writer built receipt 1000)
        receipt-rw {:operation-receipt/id "raid-reward|r9|ash"
                    :operation-receipt/kind "raid-reward"
                    :operation-receipt/player-key "c|ash"
                    :operation-receipt/summary
                    (pr-str {:raid-id "r9" :outcome :derrota})}
        from-rw (#'adventure-activity/detect-targets
                 nil writer {:tx [receipt-rw] :result {}} receipt-rw 1000)]
    (is (= (get-in from-item [0 :opts :event-id])
           (get-in from-rw [0 :opts :event-id])))))


(deftest raid-usar-item-hp-persiste-no-proximo-turno
  "Após poção+revide, o HP canônico da party é a base do próximo turno."
  (let [poke {:nome "Charizard" :species "charizard" :hp 120 :hp-atual 12
              :ataque 80 :defesa 50 :atq-esp 80 :def-esp 50 :veloc 80
              :tipos ["fogo"]
              :golpes [{:slug "ember" :nome-exibicao "Ember" :poder 40
                        :tipo "fogo" :classe :especial :precisao 100}]}
        part (#'raid/sync-active
              {:nome "Ash" :save-id "s1" :fence-generation 1 :dano 0
               :party [{:pokemon poke :hp 12 :collection-index 0}]
               :active-party-idx 0})
        agora (System/currentTimeMillis)
        raid {:raid-id "r2" :fase "combate" :chat-id "c"
              :expira (+ agora 600000)
              :hp-chefe 800 :hp-max 800 :defesa 40 :def-esp 40
              :vez "ash" :ordem ["ash"] :turn-index 1 :revision 1
              :participantes {"ash" part}
              :chefe {:nome "Boss" :tipos ["normal"] :hp 800
                      :ataque 50 :defesa 40 :atq-esp 40 :def-esp 40}}
        potion (raid/usar-item raid "ash" "pocao-maxima" agora)
        raid2 (:raid potion)
        hp-apos (:hp-apos-revide potion)
        atk (raid/atacar raid2 "ash" 0 (+ agora 1) nil)
        part-atk (get-in atk [:raid :participantes "ash"])]
    (is (= hp-apos (get-in raid2 [:participantes "ash" :hp])))
    ;; Próximo revide aplica sobre o HP já persistido pós-poção (não 120 cheio).
    (is (< (:hp part-atk) hp-apos))
    (is (= (inc (:turn-index raid2)) (get-in atk [:raid :turn-index])))))
