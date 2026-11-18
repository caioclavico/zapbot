(ns pokemon.tests.move-consistency-a6-test
  "Precisão real, armadilha, campo, nocaute, troca, crítico e estado de raid."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mechanics :as mechanics]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.types :as types]
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

(deftest neve-nao-fura-e-granizo-fura
  (let [x (mon "A" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        o (mon "B" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        base (batalha x o)
        neve (battle/aplicar-fim-de-turno (assoc base :clima {:tipo :neve :restante 5}) :x)
        gelo (battle/aplicar-fim-de-turno
              (assoc-in (assoc base :clima {:tipo :granizo :restante 5})
                        [:pokemons :x :tipos] ["ice"])
              :x)
        granizo (battle/aplicar-fim-de-turno
                 (assoc base :clima {:tipo :granizo :restante 5}) :x)
        nevasca (move-effects/enriquecer
                 {:slug "blizzard" :poder 110 :classe :especial :tipo "ice"})
        comum {:atacante x :defensor o
               :rng {:accuracy 99 :crit 99 :damage-var 0 :dodge 99}}]
    (is (= 160 (get-in (:battle neve) [:hp :x])))
    (is (= 160 (get-in (:battle gelo) [:hp :x])))
    (is (= 150 (get-in (:battle granizo) [:hp :x])))
    (is (some #(re-find #"granizo" %) (:mensagens granizo)))
    (is (:acertou? (dmg/resolver-ataque (assoc comum :golpe nevasca :clima :neve))))
    (is (:acertou? (dmg/resolver-ataque (assoc comum :golpe nevasca :clima :granizo))))
    (is (:errou? (dmg/resolver-ataque (assoc comum :golpe nevasca))))))

(deftest protecao-nao-atravessa-a-rodada-seguinte
  (let [x (mon "Mew" ["psychic"]
               [{:slug "protect" :nome-exibicao "Proteção" :tipo "normal"
                 :poder 0 :classe :status}])
        o (mon "Alvo" ["normal"]
               [{:slug "swords-dance" :nome-exibicao "Dança das Espadas"
                 :tipo "normal" :poder 0 :classe :status}
                {:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 80 :classe :fisico}])
        t1 (agir (batalha x o) 0 {:wild-move 0})
        t2 (agir t1 0 {:wild-move 1 :protecao 50 :accuracy 0 :crit 99
                      :damage-var 0 :dodge 99})]
    (is (re-find #"Proteção ativada" (:mensagem (:last-action t1))))
    (is (nil? (get-in t1 [:volatil :x :protecao?])))
    (is (re-find #"se desfez" (str (:mensagem (:last-rival-action t1))
                                   (:mensagem (:last-action t1)))))
    (is (< (get-in t2 [:hp :x]) 160))))

(deftest retomada-preserva-o-estado-temporario
  (let [x (mon "A" ["normal"] [])
        o (mon "B" ["normal"] [])
        b (-> (batalha x o)
              (assoc-in [:volatil :x :protecao?] true)
              (assoc-in [:volatil :x :protecao-seguidas] 1)
              (assoc-in [:volatil :o :veneno-contador] 2)
              (assoc-in [:volatil :o :semente] :x)
              (assoc-in [:volatil :o :residual] {:restante 3})
              (assoc-in [:volatil :x :carga] {:slug "solar-beam" :golpe {:slug "solar-beam"}})
              (assoc-in [:volatil :x :recarga?] true)
              (assoc-in [:volatil :o :desabilitado] {:slug "tackle" :restante 3})
              (assoc-in [:volatil :o :tormento?] true)
              (assoc-in [:volatil :o :ultimo-slug] "tackle")
              (assoc-in [:volatil :x :perecer] 2)
              (assoc-in [:volatil :o :perecer] 2)
              (assoc :clima {:tipo :sol :restante 4})
              (assoc :terreno {:tipo :grama :restante 4})
              (assoc-in [:campo :o :hazards] {:rochas true :espinhos 2}))
        volta (battle/decode-compact-edn (battle/encode-state-edn b))]
    (is (get-in volta [:volatil :x :protecao?]))
    (is (= 2 (get-in volta [:volatil :o :veneno-contador])))
    (is (= :x (get-in volta [:volatil :o :semente])))
    (is (= 3 (get-in volta [:volatil :o :residual :restante])))
    (is (= "solar-beam" (get-in volta [:volatil :x :carga :slug])))
    (is (get-in volta [:volatil :x :recarga?]))
    (is (= "tackle" (get-in volta [:volatil :o :desabilitado :slug])))
    (is (get-in volta [:volatil :o :tormento?]))
    (is (= 2 (get-in volta [:volatil :x :perecer])))
    (is (= :sol (get-in volta [:clima :tipo])))
    (is (= :grama (get-in volta [:terreno :tipo])))
    (is (get-in volta [:campo :o :hazards :rochas]))
    (let [so-veneno (battle/decode-compact-edn
                     (battle/encode-state-edn
                      (-> (batalha x o)
                          (assoc-in [:status :o] :envenenado-grave)
                          (assoc-in [:volatil :o :veneno-contador] 2))))
          depois (battle/aplicar-fim-de-turno so-veneno :o)]
      (is (= 3 (get-in (:battle depois) [:volatil :o :veneno-contador])))
      (is (= 140 (get-in (:battle depois) [:hp :o]))))))

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

(deftest raid-retomada-aplica-o-veneno-uma-vez
  (let [agora 1
        snorlax {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 80
                 :def-esp 50 :veloc 40 :tipos ["normal"] :nivel 20
                 :golpes [{:slug "brick-break" :nome-exibicao "Quebra-Tijolo"
                           :tipo "fighting" :poder 80 :classe :fisico}]}
        base (-> (raid-de snorlax)
                 (assoc-in [:chefe :status] :envenenado-grave)
                 (assoc-in [:chefe :contador] 2))
        direto (raid/atacar base "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        chefe (clojure.edn/read-string (pr-str (:chefe base)))
        part (get-in base [:participantes "p"])
        dec (#'raid-store/decode-participant-bundle
             (battle/decode-compact-edn
              (#'raid-store/encode-participant-pokemon-edn part))
             (:hp part) 0)
        volta (raid/atacar (-> base
                               (assoc :chefe chefe)
                               (assoc-in [:participantes "p"] (merge part dec)))
                           "p" 0 agora {:raid-damage-var 0 :accuracy 0})
        repetido (raid/atacar (:raid direto) "p" 0 (inc agora)
                              {:raid-damage-var 0 :accuracy 0})]
    (is (= (get-in direto [:raid :hp-chefe]) (get-in volta [:raid :hp-chefe])))
    (is (= 3 (get-in volta [:raid :chefe :contador])))
    (is (< (get-in repetido [:raid :hp-chefe]) (get-in direto [:raid :hp-chefe])))))

(defn- fechar-rodada
  [battle]
  (-> battle
      (battle/aplicar-fim-de-turno :x)
      :battle
      (battle/aplicar-fim-de-turno :o :fecha-rodada? true)))

(deftest clima-dura-cinco-rodadas-dos-dois-lados
  (let [base (batalha (mon "A" ["normal"] []) (mon "B" ["normal"] []))
        jogador (assoc base :clima {:tipo :granizo :restante 5})
        selvagem (assoc base :clima {:tipo :granizo :restante 5 :recem? true})
        rodadas (fn [b]
                  (loop [atual b n 0 msgs []]
                    (if (= n 6)
                      {:battle atual :msgs msgs}
                      (let [fim (fechar-rodada atual)]
                        (recur (:battle fim) (inc n)
                               (into msgs (:mensagens fim)))))))
        do-jogador (rodadas jogador)
        do-selvagem (rodadas selvagem)]
    (is (= 4 (get-in (:battle (fechar-rodada jogador)) [:clima :restante])))
    (is (= 5 (get-in (:battle (fechar-rodada selvagem)) [:clima :restante])))
    (is (nil? (get-in do-jogador [:battle :clima])))
    (is (nil? (get-in do-selvagem [:battle :clima])))
    (is (= 1 (count (filter #(re-find #"voltou ao normal" %) (:msgs do-jogador)))))
    (is (= 1 (count (filter #(re-find #"voltou ao normal" %) (:msgs do-selvagem)))))
    (is (= 5 (count (filter #(re-find #"granizo causou" %) (:msgs do-jogador)))))
    (is (= 5 (count (filter #(re-find #"granizo causou" %) (:msgs do-selvagem)))))))

(deftest neve-reforca-defesa-de-gelo-e-granizo-nao
  (let [golpe {:slug "tackle" :poder 40 :classe :fisico :tipo "normal"}
        gelo (mon "Gelo" ["ice"] [])
        comum {:golpe golpe
               :atacante (mon "A" ["normal"] []) :defensor gelo
               :rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 99}}
        seco (dmg/resolver-ataque comum)
        neve (dmg/resolver-ataque (assoc comum :golpe golpe :clima :neve))
        granizo (dmg/resolver-ataque (assoc comum :golpe golpe :clima :granizo))]
    (is (< (:dano neve) (:dano seco)))
    (is (:defesa-neve? neve))
    (is (= (:dano seco) (:dano granizo)))
    (is (not (:defesa-neve? granizo)))))

(deftest levitacao-e-derrubada
  (let [chao {:slug "earthquake" :poder 100 :classe :fisico :tipo "ground"}
        voador (mon "Ave" ["flying"] [])
        flutuante (assoc (mon "Gas" ["ghost"] []) :habilidade "levitate")
        rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 99}
        x (mon "A" ["ground"]
               [{:slug "smack-down" :nome-exibicao "Derrubada" :tipo "rock"
                 :poder 50 :classe :fisico}
                {:slug "earthquake" :nome-exibicao "Terremoto" :tipo "ground"
                 :poder 100 :classe :fisico}])
        o (mon "Ave" ["flying"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        limpo (mechanics/limpar-volatil t1 :o)]
    (is (zero? (types/multiplicador-vs-tipos "ground" ["flying"] nil)))
    (is (zero? (types/multiplicador-vs-tipos "ground" ["ghost"] "levitate")))
    (is (= 1.0 (types/multiplicador-vs-tipos "ground" ["flying"] nil true)))
    (is (= 1.0 (types/multiplicador-vs-tipos "ground" ["ghost"] "levitate" true)))
    (is (get-in t1 [:volatil :o :aterrado?]))
    (is (re-find #"no chão" (:mensagem (:last-action t1))))
    (is (:acertou? (dmg/resolver-ataque
                    {:golpe chao :atacante x :defensor voador :aterrado? true :rng rng})))
    (is (not (:acertou? (dmg/resolver-ataque
                         {:golpe chao :atacante x :defensor voador :rng rng}))))
    (is (not (get-in limpo [:volatil :o :aterrado?])))
    (let [entrada (mechanics/aplicar-entrada
                   (-> (batalha x flutuante)
                       (assoc-in [:campo :o :hazards :espinhos] 1)
                       (assoc-in [:hp :o] 160))
                   :o)
          derrubado (mechanics/aplicar-entrada
                     (-> (batalha x flutuante)
                         (assoc-in [:volatil :o :aterrado?] true)
                         (assoc-in [:campo :o :hazards :espinhos] 1))
                     :o)]
      (is (= 160 (get-in (:battle entrada) [:hp :o])))
      (is (< (get-in (:battle derrubado) [:hp :o]) 160)))))

(deftest visao-do-futuro-sai-na-terceira-rodada
  (let [x (mon "Mew" ["psychic"]
               [{:slug "future-sight" :nome-exibicao "Visão do Futuro"
                 :tipo "psychic" :poder 120 :classe :especial}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 1 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        t2 (agir t1 0 {})
        t3 (agir t2 0 {})]
    (is (re-find #"prevista" (:mensagem (:last-action t1))))
    (is (= 160 (get-in t1 [:hp :o])))
    (is (= 2 (get-in t1 [:campo :previsto :o :restante])))
    (is (= 160 (get-in t2 [:hp :o])))
    (is (= 1 (get-in t2 [:campo :previsto :o :restante])))
    (is (< (get-in t3 [:hp :o]) 160))
    (is (nil? (get-in t3 [:campo :previsto :o])))
    (is (re-find #"já está a caminho"
                 (:mensagem (:last-action (agir t1 0 {})))))))

(deftest queda-livre-e-triataque
  (let [x (mon "Ave" ["flying"]
               [{:slug "sky-drop" :nome-exibicao "Queda Livre" :tipo "flying"
                 :poder 60 :classe :fisico}])
        o (mon "Alvo" ["normal"]
               [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                 :poder 40 :classe :fisico}])
        voa (mon "Pidgey" ["flying"]
                 [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                   :poder 40 :classe :fisico}])
        t1 (agir (batalha x o) 0 {})
        t2 (agir t1 0 {})
        falha (agir (batalha x voa) 0 {})
        tri (mon "A" ["normal"]
                  [{:slug "tri-attack" :nome-exibicao "Triataque" :tipo "normal"
                    :poder 80 :classe :especial}])
        queima (agir (batalha tri o) 0 {:status-golpe 0 :tri-ataque 0})
        nada (agir (batalha tri o) 0 {:status-golpe 20 :tri-ataque 0})
        fogo (mon "Fogo" ["fire"]
                   [{:slug "tackle" :nome-exibicao "Investida" :tipo "fire"
                     :poder 1 :classe :fisico}])
        imune (agir (batalha tri fogo) 0 {:status-golpe 0 :tri-ataque 0})]
    (is (re-find #"para o alto" (:mensagem (:last-action t1))))
    (is (= 160 (get-in t1 [:hp :o])))
    (is (re-find #"não pode agir" (:mensagem (:last-rival-action t1))))
    (is (< (get-in t2 [:hp :o]) 160))
    (is (re-find #"não alcança" (:mensagem (:last-action falha))))
    (is (= :queimado (get-in queima [:status :o])))
    (is (nil? (get-in nada [:status :o])))
    (is (nil? (get-in imune [:status :o])))))
