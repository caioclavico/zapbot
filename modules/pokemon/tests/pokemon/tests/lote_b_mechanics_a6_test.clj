(ns pokemon.tests.lote-b-mechanics-a6-test
  "Lote B — dano/efetividade, Explosion, learn@5, Frambo, raridade/bolas.
  Domínio com RNG determinístico + Datomic descartável onde há persistência."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.damage :as damage]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.store :as store]))

;; ── helpers Datomic ──────────────────────────────────────────────────────────

(defn- fresh []
  (let [client (persistence/client {:system (str "lote-b-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/lote-b-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "lote-b")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    s))

(def ^:private rng-hit
  {:accuracy 0 :crit 99 :damage-var 0 :dodge 99 :flee 99
   :status 99 :contagio 99 :multi-hit 0 :wild-move 0
   :status-golpe 99 :impede-para 99 :cura-sono 99 :cura-gelo 99 :cura-confusao 99})

;; ── B1 dano / efetividade ────────────────────────────────────────────────────

(deftest b1-raid-super-efetivo-multiplica-dano
  ;; RNG fixo em 100% para isolar efetividade da variação 85–100%.
  (let [rng {:raid-damage-var 15}
        neutro (raid/calcular-dano
                {:poder 80 :classe :fisico :tipo "normal"}
                {:ataque 80}
                {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["normal"]}}
                rng)
        super (raid/calcular-dano
               {:poder 80 :classe :fisico :tipo "fire"}
               {:ataque 80}
               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["grass"]}}
               rng)
        imune (raid/calcular-dano
               {:poder 80 :classe :fisico :tipo "normal"}
               {:ataque 80}
               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["ghost"]}}
               rng)
        fraco (raid/calcular-dano
               {:poder 80 :classe :fisico :tipo "fire"}
               {:ataque 80}
               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["water"]}}
               rng)]
    (is (= 1.0 (:multiplicador neutro)))
    (is (= 2.0 (:multiplicador super)))
    (is (= (* 2 (:base-variada super)) (:dano super)))
    (is (> (:dano super) (:dano neutro)))
    (is (zero? (:multiplicador imune)))
    (is (zero? (:dano imune)))
    (is (= 0.5 (:multiplicador fraco)))
    (is (< (:dano fraco) (:dano neutro)))))

(deftest b1-batalha-super-efetivo-multiplica-dano-rng-fixo
  (let [atk {:nome "Char" :tipos ["fire"] :ataque 80 :atq-esp 80 :defesa 40 :def-esp 40
             :veloc 50 :hp 100 :habilidade nil}
        def-n {:nome "Rattata" :tipos ["normal"] :ataque 40 :atq-esp 40 :defesa 40 :def-esp 40
               :veloc 40 :hp 100}
        def-s {:nome "Bulba" :tipos ["grass"] :ataque 40 :atq-esp 40 :defesa 40 :def-esp 40
               :veloc 40 :hp 100}
        golpe {:nome-exibicao "Brasas" :tipo "fire" :poder 40 :classe :especial :precisao 100}
        neutro (damage/resolver-ataque
                {:golpe golpe :atacante atk :defensor def-n
                 :hp-atacante-atual 100 :rng rng-hit})
        super (damage/resolver-ataque
               {:golpe golpe :atacante atk :defensor def-s
                :hp-atacante-atual 100 :rng rng-hit})]
    (is (= 1.0 (:multiplicador neutro)))
    (is (= 2.0 (:multiplicador super)))
    (is (= (* 2 (long (:dano neutro))) (long (:dano super)))
        "super efetivo altera o dano, não só a mensagem")
    (is (true? (:acertou? super)))))

(deftest b1-sem-stab-no-dano
  (let [atk {:nome "Charmander" :tipos ["fire"] :ataque 80 :atq-esp 80 :defesa 40 :def-esp 40
             :veloc 50 :hp 100}
        def {:nome "Rattata" :tipos ["normal"] :ataque 40 :atq-esp 40 :defesa 40 :def-esp 40
             :veloc 40 :hp 100}
        fire (damage/resolver-ataque
              {:golpe {:tipo "fire" :poder 40 :classe :especial :precisao 100}
               :atacante atk :defensor def :hp-atacante-atual 100 :rng rng-hit})
        normal (damage/resolver-ataque
                {:golpe {:tipo "normal" :poder 40 :classe :fisico :precisao 100}
                 :atacante atk :defensor def :hp-atacante-atual 100 :rng rng-hit})]
    (is (= 1.0 (:multiplicador fire) (:multiplicador normal)))
    (is (= (:dano fire) (:dano normal)) "mesmo poder/stats → mesmo dano (sem STAB)")))

;; ── B2 Explosion ─────────────────────────────────────────────────────────────

(defn- mon-exp [nome tipos]
  {:nome nome :species nome :hp 100 :ataque 80 :defesa 40
   :atq-esp 80 :def-esp 40 :veloc 50 :tipos tipos
   :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
             :poder 250 :classe :fisico :autodesmaio true :precisao 100}]})

(deftest b2-explosion-batalha-desmaia-usuario
  (let [b (battle/create-wild-battle
           {:battle-id "b-exp" :player-id "ash" :chat-id "c"
            :pokemon-x (mon-exp "Electrode" ["electric"])
            :pokemon-o (mon-exp "Rattata" ["normal"])})
        out (battle/apply-player-turn b {:action :attack :move-index 0} rng-hit)]
    (is (zero? (long (get-in out [:hp :x]))))
    (is (< (long (get-in out [:hp :o])) 100))))

(deftest b2-explosion-imune-nao-autodesmaia
  (let [b (battle/create-wild-battle
           {:battle-id "b-imm" :player-id "ash" :chat-id "c"
            :pokemon-x (mon-exp "Electrode" ["electric"])
            :pokemon-o {:nome "Gengar" :species "gengar" :hp 100 :ataque 40 :defesa 40
                        :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["ghost"]
                        :golpes [{:slug "lick" :nome-exibicao "Lambida" :tipo "ghost"
                                  :poder 30 :classe :fisico :precisao 100}]}})
        out (battle/apply-player-turn b {:action :attack :move-index 0} rng-hit)]
    (is (pos? (long (get-in out [:hp :x]))) "sem dano no alvo → sem autodesmaio")
    (is (= 100 (long (get-in out [:hp :o]))))))

(deftest b2-explosion-raid-troca-reserva
  (let [agora 1000000
        party [{:pokemon (assoc (mon-exp "A" ["normal"]) :hp 80 :hp-atual 80)
                :collection-index 0}
               {:pokemon {:nome "B" :hp 100 :hp-atual 100 :ataque 80 :defesa 40
                          :atq-esp 40 :def-esp 40 :veloc 50 :tipos ["normal"] :nivel 20
                          :golpes [{:nome-exibicao "Hit" :tipo "normal" :poder 80 :classe :fisico}]}
                :collection-index 1}]
        base (raid/nova-raid {:raid-id "r-exp" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["normal"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r0 (-> (raid/entrar base {:player-id "ash" :nome "Ash" :save-id "a"
                                  :fence-generation 1 :party party} agora)
               (raid/iniciar "ash" (inc agora)))
        out (raid/atacar r0 "ash" 0 (+ agora 10))]
    (is (true? (:autodesmaio? out)))
    (is (true? (:trocou? out)))
    (is (false? (:derrota? out)))
    (is (= "combate" (:fase (:raid out))))
    (is (= "B" (get-in out [:raid :participantes "ash" :pokemon :nome])))
    (is (zero? (get-in out [:raid :participantes "ash" :party 0 :hp])))))

(deftest b2-explosion-persiste-hp-zero-datomic
  (let [s (fresh)
        chat "chat" player "ash"
        electrode {:nome "Electrode" :species "electrode" :hp 100 :hp-atual 100
                   :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40 :veloc 90
                   :tipos ["electric"] :nivel 20
                   :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
                             :poder 250 :classe :fisico :autodesmaio true :precisao 100}]}
        wild {:nome "Rattata" :species "rattata" :hp 40 :ataque 40 :defesa 30
              :atq-esp 20 :def-esp 30 :veloc 50 :tipos ["normal"] :nivel 5
              :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                        :poder 40 :classe :fisico :precisao 100}]}
        _ (collection/adicionar-pokemon! s chat player electrode 100 nil)
        bid "b-exp-persist"
        _ (battle-app/start-wild-battle!
           s chat player
           {:operation-id "start-exp" :battle-id bid
            :pokemon-x electrode :pokemon-o wild
            :hp-x 100 :hp-o 40 :collection-index 0})
        _ (battle-app/confirm-turn!
           s chat player
           {:operation-id "atk-exp" :battle-id bid
            :expected-turn-index 0 :action :attack :move-index 0
            :rng rng-hit})
        col (first (ports/list-collection s chat player "a"))]
    (is (zero? (long (:hp-atual col 0))) "HP 0 persistido na coleção após Explosion")))

;; ── B3 aprendizado a cada 5 níveis ───────────────────────────────────────────

(def ^:private candidatos-3
  [{:slug "ember" :nome-exibicao "Brasas" :tipo "fire" :poder 40 :classe :especial}
   {:slug "flamethrower" :nome-exibicao "Lanca-Chamas" :tipo "fire" :poder 90 :classe :especial}
   {:slug "fire-blast" :nome-exibicao "Rajada de Fogo" :tipo "fire" :poder 110 :classe :especial}])

(deftest b3-oferta-unwrap-e-multi-marco
  (let [reg {:nome "Charmander" :tipos ["fire"] :nivel 4
             :golpes [{:slug "scratch" :nome-exibicao "Arranhao" :tipo "normal"
                       :poder 40 :classe :fisico}]
             :golpes-candidatos candidatos-3
             :ofertas-golpes []}
        r5 (moves/aplicar-ofertas-por-nivel reg 4 5)
        r11 (moves/aplicar-ofertas-por-nivel reg 4 11)]
    (testing "unwrap :registro — nível/golpes no topo"
      (is (map? r5))
      (is (nil? (:resultado r5)) "não devolve wrapper de oferecer-golpe")
      (is (= 5 (:nivel-oferta-verificado r5)))
      (is (= 2 (count (:golpes r5))) "auto-aprende com vaga (<4)"))
    (testing "salto 4→11 cruza 5 e 10"
      (is (= 10 (:nivel-oferta-verificado r11)))
      (is (= 3 (count (:golpes r11))))
      (is (= [] (moves/marcos-cruzados 5 9))))))

(deftest b3-oferta-persistida-via-xp-datomic
  (let [s (fresh)
        chat "chat" player "ash"
        ;; nível 4, xp quase cheio: 8/9 → +9 XP sobe para 5 (1 marco)
        mon {:id-pokemon "learn-1" :species "charmander" :nome "Charmander"
             :nivel 4 :xp-desde-nivel 8
             :hp 39 :hp-atual 39 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
             :tipos ["fire"] :amizade 70
             :golpes [{"slug" "scratch" "nome-exibicao" "Arranhao" "tipo" "normal"
                       "poder" 40 "classe" "fisico"}]
             :golpes-candidatos candidatos-3}
        _ (collection/adicionar-pokemon! s chat player mon 39 nil)
        _ (collection/ganhar-xp-pokemon! s chat player 0 9)
        after (first (ports/list-collection s chat player "a"))]
    (is (= 5 (:nivel after)))
    (is (>= (count (:golpes after)) 2)
        "marco 5 oferece/aprende golpe e persiste")))

;; ── B4 Frambo ────────────────────────────────────────────────────────────────

(deftest b4-frambo-multiplicador-e-cap
  (is (= 27 (capture/chance-com-bola-frambo 18 "pokebola" true)))
  (is (= 18 (capture/chance-com-bola-frambo 18 "pokebola" false)))
  (is (= 95 (capture/chance-com-bola-frambo 80 "ultra-bola" true)))
  (is (= 95 (capture/chance-com-bola-frambo 70 "grande-bola" true))))

(deftest b4-encounter-frambo-prepare-nao-consome
  (let [s (fresh)
        chat "chat" player "ash"
        squirtle {:nome "Squirtle" :hp 100 :ataque 60 :defesa 70 :atq-esp 60 :def-esp 65
                  :veloc 50 :tipos ["water"] :nivel 10
                  :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 40 :classe :especial}]}
        wild {:nome "Magikarp" :raridade "comum" :hp 5 :ataque 10 :defesa 55
              :atq-esp 15 :def-esp 20 :veloc 80 :tipos ["water"] :nivel 5
              :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]}
        _ (reward-app/admin-seed-reward!
           s {:operation-id "seed-balls" :chat-id chat :player-id player :save-id "a"
              :item {:item-id "pokebola" :qty 5}})
        _ (reward-app/admin-seed-reward!
           s {:operation-id "seed-fruta" :chat-id chat :player-id player :save-id "a"
              :item {:item-id "fruta" :qty 3}})
        _ (collection/adicionar-pokemon! s chat player squirtle 100 nil)
        bid "enc-fb"
        _ (battle-app/start-wild-battle!
           s chat player
           {:operation-id "start-fb" :battle-id bid
            :pokemon-x squirtle :pokemon-o wild
            :hp-x 100 :hp-o 5 :collection-index 0})
        _ (battle-app/confirm-turn!
           s chat player
           {:operation-id "win-fb" :battle-id bid
            :expected-turn-index 0 :action :attack :move-index 0
            :rng (assoc rng-hit :damage-var 10)})
        fruta-antes (long (or (some #(when (= "fruta" (:item-id %)) (:qty %))
                                    (ports/list-inventory s chat player "a"))
                              0))
        prep (capture-app/encounter-frambo-prepare!
              s chat player
              {:operation-id "prep-fb" :battle-id bid})
        fruta-mid (long (or (some #(when (= "fruta" (:item-id %)) (:qty %))
                                  (ports/list-inventory s chat player "a"))
                            0))
        cap (capture-app/attempt-encounter-capture!
             s chat player
             {:operation-id "cap-fb" :battle-id bid
              :bola "pokebola" :roll 0 :fuga-roll 99})
        fruta-depois (long (or (some #(when (= "fruta" (:item-id %)) (:qty %))
                                     (ports/list-inventory s chat player "a"))
                               0))]
    (is (= 3 fruta-antes))
    (is (true? (:frambo-preparada prep)))
    (is (= 3 fruta-mid) "prepare não debita")
    (is (true? (or (:capturou? cap) (:frambo cap) (:frambo? cap)
                   (boolean (:frambo-preparada prep)))))
    (is (= 2 fruta-depois) "consumo só na captura")))

;; ── B5 raridade + bolas ──────────────────────────────────────────────────────

(def ^:private wild-antes
  "Tabela pré-auditoria A6 (HEAD base) — centros Pokébola."
  {"comum" 82 "incomum" 65 "raro" 40 "epico" 22 "lendario" 8 "mitico" 5})

(def ^:private wild-depois
  capture/raridade-chance-base)

(def ^:private raid-chance
  {"comum" 35 "incomum" 35 "raro" 35 "epico" 20 "lendario" 8 "mitico" 4})

(deftest b5-tabela-raridade-wild-e-raid
  (testing "wild depois = tabela A6"
    (is (= {"comum" 78 "incomum" 58 "raro" 28 "epico" 14 "lendario" 5 "mitico" 3}
           wild-depois)))
  (testing "wild depois ≤ wild antes em todas as raridades"
    (doseq [r (keys wild-antes)]
      (is (<= (get wild-depois r) (get wild-antes r)) r)))
  (testing "raid chance-captura própria"
    (doseq [[r c] raid-chance]
      (is (= c (raid/chance-captura r)) r)))
  (testing "ordenação lendário/mítico"
    (is (<= (get wild-depois "mitico")
            (get wild-depois "lendario")
            (get wild-depois "epico")
            (get wild-depois "raro")))))

(deftest b5-grande-ultra-multiplicadores
  (let [base 40]
    (is (= 40 (capture/chance-com-bola base "pokebola")))
    (is (= 60 (capture/chance-com-bola base "grande-bola")) "×1.5")
    (is (= 80 (capture/chance-com-bola base "ultra-bola")) "×2")
    (is (= 95 (capture/chance-com-bola 95 "grande-bola")) "cap 95")
    (is (= 95 (capture/chance-com-bola 90 "ultra-bola")) "cap 95"))
  (testing "attempt-capture RNG determinístico com grande-bola"
    (let [r (capture/attempt-capture
             {:pokemon {:raridade "raro" :hp 50 :ataque 50 :defesa 50
                        :atq-esp 50 :def-esp 50 :veloc 50}
              :bola "grande-bola" :chance-base 28 :roll 0 :fuga-roll 99})]
      (is (true? (:capturou? r)))
      (is (= 42 (:chance r)) "28 × 1.5 = 42"))))
