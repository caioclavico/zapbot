(ns pokemon.tests.move-consistency-a6-test
  "Precisão real, armadilha, campo, nocaute, troca, crítico e estado de raid."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mechanics :as mechanics]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]))

(def ^:private rng-base
  {:flee 99 :wild-move 0 :accuracy 0 :status-accuracy 0 :status-golpe 0
   :crit 99 :damage-var 0 :contagio 99 :impede-para 99 :cura-sono 99
   :cura-gelo 99 :cura-confusao 99 :auto-confusao 99 :protecao 0 :flinch 0
   :chance-alt 0 :multi-hit 0 :residual-turns 0 :dodge 99})

(defn- mon
  [nome tipos golpes]
  {:nome nome :hp 160 :ataque 80 :defesa 80 :atq-esp 80 :def-esp 80 :veloc 40
   :tipos tipos :nivel 20 :raridade "comum" :hp-atual 160
   :golpes golpes})

(defn- batalha
  [x o]
  (battle/create-wild-battle
   {:battle-id "cons" :chat-id "c" :player-id "p" :save-id "s"
    :fence-generation 1 :pokemon-x x :pokemon-o o :collection-index 0}))

(defn- agir
  [b idx rng]
  (battle/apply-player-turn b {:action :attack :move-index idx}
                            (merge rng-base rng)))

(defn- pvp
  [x o]
  (let [desafio (battle/create-pvp-challenge
                 {:battle-id "pvp-cons" :chat-id "c" :player-id "a"
                  :player-name "A" :save-id "sa" :fence-generation 1
                  :pokemon-x x :collection-index 0 :agora 0})]
    (battle/accept-pvp-challenge
     desafio
     {:player-id "b" :player-name "B" :save-id "sb" :fence-generation 1
      :pokemon-o o :collection-index 0 :agora 0 :first-turn-tie :x})))

(defn- turno-pvp
  [b lado idx]
  (battle/apply-pvp-turn b lado {:action :attack :move-index idx} rng-base))

(defn- raid-de
  [poke & {:keys [chefe hp]}]
  {:fase "combate" :expira 100000 :vez "p" :ordem ["p"]
   :hp-chefe (or hp 640) :hp-max (or hp 640)
   :defesa 50.0 :def-esp 50.0 :turn-index 0
   :chefe (or chefe {:tipos ["rock"] :nome "Onix"})
   :participantes {"p" {:pokemon poke :hp 200
                        :party [{:pokemon poke :hp 200 :collection-index 0}]
                        :active-party-idx 0 :dano 0
                        :save-id "s" :fence-generation 1 :nome "A"}}})

(deftest precisao-numerica-estagios-e-clima
  (let [trovao (move-effects/enriquecer
                {:slug "thunder" :poder 110 :classe :especial :tipo "electric"})
        base {:atacante (mon "A" ["electric"] [])
              :defensor (mon "B" ["normal"] [])
              :rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 99}}]
    (is (= 70 (:precisao trovao)))
    (is (:errou? (dmg/resolver-ataque
                  (assoc base :golpe trovao :rng {:accuracy 70 :crit 99 :damage-var 0 :dodge 99}))))
    (is (:acertou? (dmg/resolver-ataque (assoc base :golpe trovao))))
    (is (:acertou? (dmg/resolver-ataque
                    (assoc base :golpe trovao :clima :chuva
                           :rng {:accuracy 99 :crit 99 :damage-var 0 :dodge 99}))))
    (is (:errou? (dmg/resolver-ataque
                  (assoc base :golpe trovao :clima :sol
                         :rng {:accuracy 50 :crit 99 :damage-var 0 :dodge 99}))))
    (let [evasao {:evasao 2}
          ajustada (dmg/chance-acerto 100 {} evasao false)]
      (is (= 50 ajustada))
      (is (:errou? (dmg/resolver-ataque
                    (assoc base :golpe {:poder 40 :classe :fisico :tipo "normal" :precisao 100}
                           :estagios-defensor evasao
                           :rng {:accuracy 50 :crit 99 :damage-var 0 :dodge 99})))))
    (is (:acertou? (dmg/resolver-ataque
                    (assoc base :golpe {:poder 60 :classe :especial :tipo "normal"
                                        :ignora-precisao true}
                           :estagios-defensor {:evasao 6}))))))

(deftest esquiva-por-velocidade-nao-muda
  (is (= 20 (dmg/chance-esquiva 40)))
  (let [defensor (mon "B" ["normal"] [])
        golpe {:poder 40 :classe :fisico :tipo "normal" :precisao 100}
        comum {:atacante (mon "A" ["normal"] [])
               :defensor defensor
               :defendendo? true
               :rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 0}}
        certa (dmg/resolver-ataque (assoc comum :golpe golpe))
        imprecisa (dmg/resolver-ataque
                   (assoc comum :golpe (assoc golpe :precisao 70)))]
    (is (:esquivou? certa))
    (is (:esquivou? imprecisa))
    (is (not (:esquivou? (dmg/resolver-ataque
                          (assoc comum :golpe golpe
                                 :rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 20})))))))

(deftest nocaute-respeita-nivel-e-precisao
  (let [x (mon "Furo" ["ground"]
               [{:slug "fissure" :nome-exibicao "Fissura" :tipo "ground"
                 :poder 0 :classe :fisico}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        acerto (agir (batalha x o) 0 {:accuracy 0})
        baixo (agir (batalha (assoc x :nivel 5) o) 0 {:accuracy 0})
        erro (agir (batalha x o) 0 {:accuracy 30})]
    (is (zero? (get-in acerto [:hp :o])))
    (is (re-find #"Nocaute de um golpe" (:mensagem (:last-action acerto))))
    (is (= 160 (get-in baixo [:hp :o])))
    (is (:errou? (:last-action baixo)))
    (is (= 160 (get-in erro [:hp :o])))
    (is (:errou? (:last-action erro)))))

(deftest troca-forcada-caca-ginasio-e-pvp
  (let [x (mon "Rugido" ["normal"]
               [{:slug "roar" :nome-exibicao "Rugido" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        t (agir (batalha x o) 0 {})]
    (is (= :flee (:outcome t)))
    (is (re-find #"obrigado a fugir" (:mensagem (:last-action t)))))
  (let [x (assoc (mon "Rugido" ["normal"]
                      [{:slug "roar" :nome-exibicao "Rugido" :tipo "normal"
                        :poder 0 :classe :status}])
                 :collection-index 0)
        lider (mon "Líder" ["normal"]
                   [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                     :poder 1 :classe :fisico}])
        reserva (mon "Reserva" ["normal"]
                     [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                       :poder 1 :classe :fisico}])
        g (battle/create-gym-battle
           {:battle-id "g" :chat-id "c" :player-id "p" :save-id "s"
            :fence-generation 1 :gym-id "g1" :gym-nome "Ginásio"
            :team-x [x (assoc x :collection-index 1) (assoc x :collection-index 2)]
            :team-o [lider reserva reserva]})
        t (agir g 0 {})]
    (is (= "Reserva" (get-in t [:pokemons :o :nome])))
    (is (re-find #"obrigado a trocar" (:mensagem (:last-action t))))
    (is (nil? (:last-rival-action t))))
  (let [x (mon "Rugido" ["normal"]
               [{:slug "roar" :nome-exibicao "Rugido" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t (turno-pvp (pvp x o) :x 0)]
    (is (= :o (:aguardando-substituicao t)))
    (is (= :o (:vez t)))))

(deftest armadilha-prende-e-fantasma-escapa
  (let [x (mon "Preso" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        outro (assoc x :nome "Outro")
        o (mon "Alvo" ["normal"] [])
        base (assoc-in (pvp x o) [:volatil :x :residual] {:restante 2})
        fantasma (assoc-in base [:pokemons :x :tipos] ["ghost"])]
    (is (mechanics/preso? base :x))
    (is (not (mechanics/preso? fantasma :x)))
    (is (= :preso
           (err/error-code
            (try
              (battle/apply-pvp-turn
               base :x
               {:action :switch :collection-index 1 :pokemon outro :agora 0}
               rng-base)
              nil
              (catch clojure.lang.ExceptionInfo e e)))))
    (let [saiu (battle/apply-pvp-turn
                fantasma :x
                {:action :switch :collection-index 1 :pokemon outro :agora 0}
                rng-base)]
      (is (nil? (get-in saiu [:volatil :x :residual])))
      (is (= "Outro" (get-in saiu [:pokemons :x :nome]))))))

(deftest rochas-nao-sao-armadilha
  (let [x (assoc (mon "Pedra" ["normal"]
                      [{:slug "stealth-rock" :nome-exibicao "Pedras" :tipo "rock"
                        :poder 0 :classe :status}
                       {:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                        :poder 80 :classe :fisico}])
                 :collection-index 0)
        lider (mon "Líder" ["normal"]
                   [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                     :poder 1 :classe :fisico}])
        reserva (mon "Reserva" ["normal"]
                     [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                       :poder 1 :classe :fisico}])
        g (battle/create-gym-battle
           {:battle-id "rocks" :chat-id "c" :player-id "p" :save-id "s"
            :fence-generation 1 :gym-id "g1" :gym-nome "Ginásio"
            :team-x [x (assoc x :collection-index 1) (assoc x :collection-index 2)]
            :team-o [lider reserva reserva]})
        t1 (agir g 0 {})
        t2 (agir (assoc-in t1 [:hp :o] 1) 1 {})]
    (is (get-in t1 [:campo :o :hazards :rochas]))
    (is (not (get-in t1 [:volatil :o :residual])))
    (is (= "Reserva" (get-in t2 [:pokemons :o :nome])))
    (is (= 140 (get-in t2 [:hp :o])))
    (is (some #(re-find #"rochas" %) (get-in t2 [:last-switch :mensagens])))))

(deftest critico-so-muda-quando-o-golpe-muda
  (let [chop (move-effects/enriquecer
              {:slug "karate-chop" :poder 50 :classe :fisico :tipo "fighting"})
        comum {:poder 40 :classe :fisico :tipo "normal"}
        base {:atacante (mon "A" ["fighting"] [])
              :defensor (mon "B" ["normal"] [])}]
    (is (= 50 (:chance-critico chop)))
    (is (:critico? (dmg/resolver-ataque
                    (assoc base :golpe chop
                           :rng {:accuracy 0 :crit 49 :damage-var 0 :dodge 99}))))
    (is (not (:critico? (dmg/resolver-ataque
                         (assoc base :golpe chop
                                :rng {:accuracy 0 :crit 50 :damage-var 0 :dodge 99})))))
    (is (not (:critico? (dmg/resolver-ataque
                         (assoc base :golpe comum
                                :rng {:accuracy 0 :crit 49 :damage-var 0 :dodge 99})))))
    (is (:critico? (dmg/resolver-ataque
                    (assoc base :golpe (assoc comum :critico-garantido true)
                           :rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 99}))))))

(deftest disable-tormento-e-canto-mortal
  (let [x (mon "A" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}
                {:slug "ember" :nome-exibicao "Brasas" :tipo "fire"
                 :poder 40 :classe :especial}])
        o (mon "B" ["normal"]
               [{:slug "disable" :nome-exibicao "Desabilitar" :tipo "normal"
                 :poder 0 :classe :status}
                {:slug "torment" :nome-exibicao "Tormento" :tipo "normal"
                 :poder 0 :classe :status}
                {:slug "perish-song" :nome-exibicao "Canto Mortal" :tipo "normal"
                 :poder 0 :classe :status}])
        vivo (pvp x o)
        usou (turno-pvp vivo :x 0)
        des (turno-pvp usou :o 0)
        bloqueado (turno-pvp des :x 0)
        outro (turno-pvp des :x 1)]
    (is (re-find #"desabilitado" (:mensagem (:last-action des))))
    (is (re-find #"desabilitado" (:mensagem (:last-action bloqueado))))
    (is (not (re-find #"desabilitado" (:mensagem (:last-action outro)))))
    (let [tor (turno-pvp (turno-pvp vivo :x 0) :o 1)
          repete (turno-pvp tor :x 0)]
      (is (re-find #"tormento" (:mensagem (:last-action tor))))
      (is (re-find #"tormento" (:mensagem (:last-action repete)))))
    (let [canto (turno-pvp (turno-pvp vivo :x 0) :o 2)]
      (is (= 3 (get-in canto [:volatil :x :perecer])))
      (is (= 2 (get-in canto [:volatil :o :perecer]))))
    (let [quase (assoc-in vivo [:volatil :x :perecer] 1)
          fim (turno-pvp quase :x 0)]
      (is (zero? (get-in fim [:hp :x]))))
    (let [marcado (assoc-in vivo [:volatil :x :desabilitado] {:slug "tackle" :restante 4})
          saiu (battle/apply-pvp-turn
                marcado :x
                {:action :switch :collection-index 1
                 :pokemon (assoc x :nome "Outro") :agora 0}
                rng-base)]
      (is (nil? (get-in saiu [:volatil :x :desabilitado]))))))

(deftest sol-pula-a-carga-do-raio-solar
  (let [x (mon "Sol" ["grass"]
               [{:slug "solar-beam" :nome-exibicao "Raio Solar" :tipo "grass"
                 :poder 120 :classe :especial}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        carga (agir (batalha x o) 0 {})
        sol (agir (assoc (batalha x o) :clima {:tipo :sol :restante 5}) 0 {})]
    (is (re-find #"preparando" (:mensagem (:last-action carga))))
    (is (= 160 (get-in carga [:hp :o])))
    (is (not (re-find #"preparando" (str (:mensagem (:last-action sol))))))
    (is (< (get-in sol [:hp :o]) 160))))

(deftest raid-estado-temporario-sem-abrir-status
  (let [agora 1
        snorlax {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 80
                 :def-esp 50 :veloc 40 :tipos ["normal"] :nivel 20}
        tijolo (assoc snorlax :golpes
                      [{:slug "brick-break" :nome-exibicao "Quebra-Tijolo"
                        :tipo "fighting" :poder 80 :classe :fisico}])
        um (raid/atacar (raid-de tijolo) "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        direto (raid/calcular-dano (first (:golpes tijolo)) tijolo
                                   (raid-de tijolo) {:raid-damage-var 0})
        trovao (assoc snorlax :golpes
                      [{:slug "thunder" :nome-exibicao "Trovão" :tipo "electric"
                        :poder 110 :classe :especial}])
        errou (raid/atacar (raid-de trovao) "p" 0 agora
                           {:raid-damage-var 0 :accuracy 70})
        solar (assoc snorlax :golpes
                     [{:slug "solar-beam" :nome-exibicao "Raio Solar" :tipo "grass"
                       :poder 120 :classe :especial}])
        carga (raid/atacar (raid-de solar) "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        solta (raid/atacar (:raid carga) "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        protege (assoc snorlax :golpes
                       [{:slug "protect" :nome-exibicao "Proteção" :tipo "normal"
                         :poder 0 :classe :status}])
        prot (raid/atacar (raid-de protege) "p" 0 agora {:protecao 0 :accuracy 0})
        rosna (assoc snorlax :golpes
                     [{:slug "growl" :nome-exibicao "Rosnadura" :tipo "normal"
                       :poder 0 :classe :status}])
        sem (raid/atacar (raid-de tijolo) "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        com (raid/atacar (assoc-in (raid-de tijolo) [:chefe :status] :envenenado-grave)
                         "p" 0 agora {:raid-damage-var 0 :accuracy 0})]
    (is (= (:dano direto) (:dano um)))
    (is (not (:errou? um)))
    (is (:errou? errou))
    (is (zero? (:dano errou)))
    (is (pos? (:revide errou)))
    (is (zero? (:dano carga)))
    (is (re-find #"preparando" (:mensagem carga)))
    (is (= "solar-beam" (get-in carga [:raid :participantes "p" :volatil :carga :slug])))
    (is (pos? (:dano solta)))
    (is (nil? (get-in solta [:raid :participantes "p" :volatil :carga])))
    (is (zero? (:revide prot)))
    (is (re-find #"Proteção bloqueou" (:mensagem prot)))
    (is (= 200 (get-in prot [:raid :participantes "p" :hp])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid_raid_move"
          (raid/atacar (raid-de rosna) "p" 0 agora {})))
    (is (= 40 (- (get-in sem [:raid :hp-chefe]) (get-in com [:raid :hp-chefe]))))))

(deftest raid-edn-antigo-continua-legivel
  (let [poke {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :tipos ["normal"]}
        p {:pokemon poke :hp 200
           :party [{:pokemon poke :hp 200 :collection-index 0}]
           :active-party-idx 0
           :volatil {:carga {:slug "solar-beam"} :protecao-seguidas 1}
           :save-id "s" :fence-generation 1 :nome "A"}
        enc (#'raid-store/encode-participant-pokemon-edn p)
        cru (battle/decode-compact-edn enc)
        dec (#'raid-store/decode-participant-bundle cru 200 0)
        antigo {:__raid-party-v1 true
                :active poke
                :party [{:pokemon poke :hp 200 :collection-index 0}]
                :active-idx 0}
        velho (#'raid-store/decode-participant-bundle antigo 200 0)
        chefe {:nome "Onix" :tipos ["rock"] :status :envenenado-grave :contador 2}]
    (is (= "solar-beam" (get-in dec [:volatil :carga :slug])))
    (is (= 1 (get-in dec [:volatil :protecao-seguidas])))
    (is (nil? (:volatil velho)))
    (is (= chefe (clojure.edn/read-string (pr-str chefe))))))
