(ns pokemon.tests.move-mechanics-a6-test
  "Famílias de golpe: proteção, flinch, veneno grave, semente, estágios,
  vários acertos, carga, recarga, limpeza, troca e raid."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.mechanics :as mechanics]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.status :as status]))

(def ^:private rng-base
  {:flee 99 :wild-move 0 :accuracy 0 :status-accuracy 0 :status-golpe 0
   :crit 99 :damage-var 0 :contagio 99 :impede-para 99 :cura-sono 99
   :cura-gelo 99 :cura-confusao 99 :auto-confusao 99 :protecao 0 :flinch 0
   :chance-alt 0 :multi-hit 0 :residual-turns 0})

(defn- mon
  [nome tipos golpes]
  {:nome nome :hp 160 :ataque 80 :defesa 80 :atq-esp 80 :def-esp 80 :veloc 50
   :tipos tipos :nivel 20 :raridade "comum" :hp-atual 160
   :golpes golpes})

(defn- batalha
  [x o]
  (battle/create-wild-battle
   {:battle-id "mec" :chat-id "c" :player-id "p" :save-id "s"
    :fence-generation 1 :pokemon-x x :pokemon-o o :collection-index 0}))

(defn- agir
  [b idx rng]
  (battle/apply-player-turn b {:action :attack :move-index idx}
                            (merge rng-base rng)))

(deftest protecao-bloqueia-e-falha-se-repetida
  (let [x (mon "Mew" ["psychic"]
               [{:slug "protect" :nome-exibicao "Proteção" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        hp (get-in t1 [:hp :x])]
    (is (re-find #"Proteção ativada" (:mensagem (:last-action t1))))
    (is (re-find #"Proteção bloqueou o ataque" (:mensagem (:last-rival-action t1))))
    (is (= hp (get-in t1 [:hp :x])))
    (let [t2 (agir t1 0 {:protecao 50})]
      (is (re-find #"proteção falhou" (:mensagem (:last-action t2)))))))

(deftest flinch-respeita-quem-ainda-nao-agiu
  (let [x (mon "Rato" ["normal"]
               [{:slug "fake-out" :nome-exibicao "Falso Choro" :tipo "normal"
                 :poder 40 :classe :fisico}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 80 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})]
    (is (re-find #"recuou e perdeu a ação" (:mensagem (:last-rival-action t1))))
    (is (= 160 (get-in t1 [:hp :x]))))
  (let [x (mon "Rato" ["normal"]
               [{:slug "fake-out" :nome-exibicao "Falso Choro" :tipo "normal"
                 :poder 40 :classe :fisico}])
        o (mon "Lento" ["normal"]
               [{:slug "fake-out" :nome-exibicao "Falso Choro" :tipo "normal"
                 :poder 40 :classe :fisico}])
        base (batalha x o)
        desafio (battle/create-pvp-challenge
                 {:battle-id "pvp-flinch" :chat-id "c" :player-id "a"
                  :player-name "A" :save-id "sa" :fence-generation 1
                  :pokemon-x x :collection-index 0 :agora 0})
        vivo (battle/accept-pvp-challenge
              desafio
              {:player-id "b" :player-name "B" :save-id "sb" :fence-generation 1
               :pokemon-o o :collection-index 0 :agora 0 :first-turn-tie :x})
        t1 (battle/apply-pvp-turn vivo :x {:action :attack :move-index 0} rng-base)
        hp-o (get-in t1 [:hp :o])
        t2 (battle/apply-pvp-turn t1 :o {:action :attack :move-index 0} rng-base)]
    (is (= :o (:vez t1)))
    (is (re-find #"recuou e perdeu a ação" (:mensagem (:last-action t2))))
    (is (= hp-o (get-in t2 [:hp :o])))))

(deftest veneno-grave-sobe-e-respeita-imunidade
  (is (= 10 (mechanics/dano-veneno-grave 160 1)))
  (is (= 20 (mechanics/dano-veneno-grave 160 2)))
  (let [x (mon "Tox" ["normal"]
               [{:slug "toxic" :nome-exibicao "Tóxico" :tipo "poison"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico :precisao 50}])
        t1 (agir (batalha x o) 0 {:accuracy 99})]
    (is (= :envenenado-grave (get-in t1 [:status :o])))
    (is (re-find #"veneno grave causou 10" (:mensagem (:last-rival-action t1))))
    (is (= 150 (get-in t1 [:hp :o])))
    (let [t2 (agir t1 0 {})]
      (is (re-find #"veneno grave causou 20" (:mensagem (:last-rival-action t2))))))
  (let [x (mon "Tox" ["normal"]
               [{:slug "toxic" :nome-exibicao "Tóxico" :tipo "poison"
                 :poder 0 :classe :status}])
        o (mon "Aço" ["steel"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})]
    (is (nil? (get-in t1 [:status :o])))
    (is (not (re-find #"veneno grave causou" (str (:mensagem (:last-action t1))))))))

(deftest semente-drena-e-some-na-troca
  (let [x (mon "Planta" ["grass"]
               [{:slug "leech-seed" :nome-exibicao "Semente Sanguessuga"
                 :tipo "grass" :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico :precisao 50}])
        t1 (agir (batalha x o) 0 {:accuracy 99})]
    (is (re-find #"Semente Sanguessuga drenou 20 HP" (:mensagem (:last-rival-action t1))))
    (is (= 140 (get-in t1 [:hp :o])))
    (is (= 160 (get-in t1 [:hp :x]))))
  (let [grama (mon "Grama" ["grass"]
                   [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                     :poder 1 :classe :fisico}])
        x (mon "Planta" ["grass"]
               [{:slug "leech-seed" :nome-exibicao "Semente Sanguessuga"
                 :tipo "grass" :poder 0 :classe :status}])
        t1 (agir (batalha x grama) 0 {})]
    (is (re-find #"não pegou neste tipo" (:mensagem (:last-action t1))))
    (is (nil? (get-in t1 [:volatil :o :semente]))))
  (let [x (mon "A" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        o (mon "B" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        desafio (battle/create-pvp-challenge
                 {:battle-id "pvp-seed" :chat-id "c" :player-id "a"
                  :save-id "sa" :fence-generation 1 :pokemon-x x
                  :collection-index 0 :agora 0})
        vivo (battle/accept-pvp-challenge
              desafio
              {:player-id "b" :save-id "sb" :fence-generation 1
               :pokemon-o o :collection-index 0 :agora 0 :first-turn-tie :x})
        semeado (-> vivo
                   (assoc-in [:volatil :o :semente] :x)
                   (assoc-in [:estagios :o :ataque] 2)
                   (assoc :vez :o))
        reserva (assoc o :nome "Reserva")
        trocou (battle/apply-pvp-turn
                semeado :o
                {:action :switch :collection-index 1 :pokemon reserva :agora 1000}
                rng-base)]
    (is (nil? (get-in trocou [:volatil :o :semente])))
    (is (empty? (get-in trocou [:estagios :o])))))

(deftest precisao-e-evasao-alteram-o-acerto
  (let [golpe {:poder 40 :classe :fisico :tipo "normal" :precisao 100}
        base {:golpe golpe
              :atacante {:ataque 80 :nome "A"}
              :defensor {:defesa 80 :nome "B" :tipos ["normal"]}
              :estagios-atacante {}
              :defendendo? false
              :hp-atacante-atual 100
              :rng {:accuracy 60 :crit 99 :damage-var 0 :dodge 99}}]
    (is (true? (:acertou? (dmg/resolver-ataque (assoc base :estagios-defensor {})))))
    (is (true? (:errou? (dmg/resolver-ataque
                         (assoc base :estagios-defensor {:evasao 2})))))
    (is (true? (:acertou? (dmg/resolver-ataque
                           (assoc base :estagios-defensor {:evasao 6}
                                  :golpe (assoc golpe :ignora-precisao true))))))))

(deftest varios-acertos-usam-a-distribuicao-classica
  (is (= 2 (mechanics/rolar-acertos 2 5 {:multi-hit 0})))
  (is (= 3 (mechanics/rolar-acertos 2 5 {:multi-hit 35})))
  (is (= 4 (mechanics/rolar-acertos 2 5 {:multi-hit 70})))
  (is (= 5 (mechanics/rolar-acertos 2 5 {:multi-hit 85})))
  (let [r (dmg/resolver-ataque
           {:golpe {:poder 10 :classe :fisico :tipo "normal"
                    :min-acertos 2 :max-acertos 2}
            :atacante {:ataque 50 :nome "A"}
            :defensor {:defesa 50 :tipos ["normal"] :nome "B"}
            :estagios-atacante {} :estagios-defensor {}
            :defendendo? false :hp-atacante-atual 50
            :rng {:accuracy 0 :crit 99 :damage-var 0 :multi-hit 0}})]
    (is (= 2 (:acertos r)))
    (is (re-find #"Acertou 2 vezes"
                 (dmg/mensagem-ataque
                  {:classe :fisico :nome-exibicao "Duplo Tapa"}
                  {:nome "A"} {:nome "B"} r false)))))

(deftest preparacao-recarga-e-limpeza
  (let [x (mon "Sol" ["grass"]
               [{:slug "solar-beam" :nome-exibicao "Raio Solar" :tipo "grass"
                 :poder 120 :classe :especial}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        hp1 (get-in t1 [:hp :o])]
    (is (re-find #"se preparando" (:mensagem (:last-action t1))))
    (is (= 160 hp1))
    (let [t2 (agir t1 0 {})]
      (is (< (get-in t2 [:hp :o]) hp1))))
  (let [x (mon "Hiper" ["normal"]
               [{:slug "hyper-beam" :nome-exibicao "Hiper-raio" :tipo "normal"
                 :poder 150 :classe :especial}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        t2 (agir t1 0 {})]
    (is (< (get-in t1 [:hp :o]) 160))
    (is (re-find #"precisa recarregar" (:mensagem (:last-action t2)))))
  (let [x (mon "Cura" ["normal"]
               [{:slug "heal-bell" :nome-exibicao "Sino da Cura" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        base (assoc-in (batalha x o) [:status :x] :envenenado)
        t1 (agir base 0 {})]
    (is (nil? (get-in t1 [:status :x])))
    (is (re-find #"status da equipe foram curados" (:mensagem (:last-action t1))))))

(deftest ginasio-usa-a-mesma-protecao
  (let [x (mon "Mew" ["psychic"]
               [{:slug "protect" :nome-exibicao "Proteção" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Líder" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 80 :classe :fisico}])
        base (assoc (batalha x o) :kind :gym)
        t1 (agir base 0 {})]
    (is (= :gym (:kind t1)))
    (is (re-find #"Proteção bloqueou o ataque" (:mensagem (:last-rival-action t1))))
    (is (= 160 (get-in t1 [:hp :x])))))

(deftest raid-mantem-o-golpe-simples-e-multiplica-os-acertos
  (let [agora 1
        poke {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50
              :def-esp 50 :veloc 40 :tipos ["fighting"]
              :golpes [{:nome-exibicao "Brick Break" :tipo "fighting"
                        :poder 80 :classe :fisico}]}
        raid {:fase "combate" :expira 100 :vez "p" :ordem ["p"]
              :hp-chefe 640 :defesa 50.0 :def-esp 50.0 :turn-index 0
              :chefe {:tipos ["rock"] :nome "Onix"}
              :participantes {"p" {:pokemon poke :hp 200 :party [{:pokemon poke :hp 200}]
                                   :active-party-idx 0 :dano 0}}}
        um (raid/atacar raid "p" 0 agora {:raid-damage-var 0})
        duplo-poke (update-in poke [:golpes 0] assoc :min-acertos 2 :max-acertos 2)
        raid2 (assoc-in raid [:participantes "p" :pokemon] duplo-poke)
        dois (raid/atacar raid2 "p" 0 agora {:raid-damage-var 0 :multi-hit 0})]
    (is (= 1 (:acertos um)))
    (is (= (* 2 (:dano um)) (:dano dois)))
    (is (= 2 (:acertos dois)))
    (let [direto (raid/calcular-dano (first (:golpes poke)) poke raid {:raid-damage-var 0})]
      (is (= (:dano direto) (:dano um))))))

(deftest estado-volatil-sobrevive-ao-edn-da-batalha
  (let [x (mon "A" ["normal"] [])
        o (mon "B" ["normal"] [])
        b (assoc-in (batalha x o) [:volatil :o :veneno-contador] 3)
        volta (battle/decode-compact-edn (battle/encode-state-edn b))]
    (is (= 3 (get-in volta [:volatil :o :veneno-contador])))))

(deftest toxic-enriquecido-nao-e-veneno-comum
  (let [g (move-effects/enriquecer {:slug "toxic" :poder 0 :classe :status})]
    (is (= :envenenado-grave (:status-causado g)))
    (is (= :veneno-grave (:familia g))))
  (is (nil? (status/tentar-status-do-golpe
             {:status-causado :envenenado-grave :chance-status 100}
             nil {:status-golpe 0} ["steel"]))))
