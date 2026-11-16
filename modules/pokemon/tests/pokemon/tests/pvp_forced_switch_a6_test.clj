(ns pokemon.tests.pvp-forced-switch-a6-test
  "Domínio: PvP substituição obrigatória; caça permanece 1×1."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.pvp :as pvp]))

(def ^:private pikachu
  {:nome "Pikachu" :species "pikachu" :nivel 20 :hp 45 :hp-atual 45
   :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
   :tipos ["electric"] :golpes []})

(def ^:private squirtle
  {:nome "Squirtle" :species "squirtle" :nivel 20 :hp 44 :hp-atual 44
   :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43
   :tipos ["water"] :golpes []})

(defn- pvp-battle
  []
  (-> (battle/create-pvp-challenge
       {:battle-id "b1" :chat-id "c" :player-id "ash" :save-id "a"
        :fence-generation 1 :pokemon-x pikachu :hp-x 45 :collection-index 0
        :agora 1000})
      (battle/accept-pvp-challenge
       {:player-id "misty" :save-id "a" :fence-generation 1
        :pokemon-o squirtle :hp-o 44 :collection-index 0 :agora 1000
        :first-turn-tie :x})))

(deftest resolve-faint-pvp-aguarda-substituicao
  (let [b (assoc-in (pvp-battle) [:hp :o] 0)
        out (#'battle/resolve-faint b :o)]
    (is (true? (:active? out)))
    (is (= :o (:aguardando-substituicao out)))
    (is (contains? (get-in out [:eliminados :o]) 0))
    (is (nil? (:outcome out)))))

(deftest finalize-sem-elegivel-encerra
  (let [b (-> (pvp-battle)
              (assoc-in [:hp :o] 0)
              (#'battle/resolve-faint :o))
        done (battle/finalize-pvp-replacement-gate b [pikachu] [squirtle])]
    (is (false? (:active? done)))
    (is (= :x (:outcome done)))))

(deftest finalize-com-segundo-mon-mantem-aguardando
  (let [b (-> (pvp-battle)
              (assoc-in [:hp :o] 0)
              (#'battle/resolve-faint :o))
        segundo (assoc squirtle :hp-atual 40)
        kept (battle/finalize-pvp-replacement-gate b [pikachu] [squirtle segundo])]
    (is (true? (:active? kept)))
    (is (= :o (:aguardando-substituicao kept)))
    (is (true? (pvp/tem-substituicao-elegivel? kept :o [squirtle segundo])))))

(deftest wild-faint-sem-substituicao
  (testing "caça: desmaio do jogador = derrota imediata (sem aguardar troca)"
    (let [wild (battle/create-wild-battle
                {:battle-id "w1" :chat-id "c" :player-id "ash" :save-id "a"
                 :fence-generation 1
                 :pokemon-x pikachu :pokemon-o squirtle
                 :hp-x 45 :hp-o 44})
          lost (#'battle/resolve-faint (assoc-in wild [:hp :x] 0) :x)]
      (is (false? (:active? lost)))
      (is (contains? #{:lose :defeat} (:outcome lost)))
      (is (nil? (:aguardando-substituicao lost))))))
