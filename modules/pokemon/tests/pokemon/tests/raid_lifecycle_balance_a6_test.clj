(ns pokemon.tests.raid-lifecycle-balance-a6-test
  "Ciclo de vida pós-vitória, ordem de faint e diagnóstico de golpes."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.raid :as raid]))

(def ^:private agora 2000000)

(defn- mon
  [nome hp & {:keys [tipos poder golpes]}]
  {:nome nome :hp hp :hp-atual hp :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40
   :veloc 50 :tipos (or tipos ["normal"]) :nivel 20 :raridade "raro"
   :golpes (or golpes
               [{:nome-exibicao "Hit" :tipo (or (first tipos) "normal")
                 :poder (or poder 80) :classe :fisico}])})

(defn- raid-combate
  [part]
  (let [base (raid/nova-raid {:raid-id "r-life" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r (raid/entrar base {:player-id "ash" :nome "Ash" :save-id "a"
                             :fence-generation 1 :party part} agora)]
    (raid/iniciar r "ash" (inc agora))))

(defn- forcar-hp-ativo
  [raid pid hp]
  (let [part (get-in raid [:participantes pid])
        idx (long (:active-party-idx part 0))
        part' (-> part
                  (assoc-in [:party idx :hp] hp)
                  (assoc :hp hp))]
    (assoc-in raid [:participantes pid] part')))

(deftest vitoria-so-visivel-com-captura-pendente
  (let [party [{:pokemon (mon "A" 200 :poder 200) :collection-index 0}]
        r0 (raid-combate party)
        ;; força HP do chefe baixo
        r0 (assoc r0 :hp-chefe 1)
        out (raid/atacar r0 "ash" 0 (+ agora 10))
        r (:raid out)]
    (is (true? (:venceu? out)))
    (is (= "vitoria" (str (:fase r))))
    (is (true? (raid/captura-pendente? r "ash" (+ agora 10))))
    (is (true? (raid/visivel-para? r (+ agora 10) "ash")))
    (is (true? (raid/algum-captura-pendente? r (+ agora 10))))
    ;; Após capturar (marca capturou), some do menu do viewer.
    (let [r2 (assoc-in r [:capturas "ash"] {:tentativas 1 :capturou true})]
      (is (false? (raid/captura-pendente? r2 "ash" (+ agora 10))))
      (is (false? (raid/visivel-para? r2 (+ agora 10) "ash")))
      (is (true? (raid/deve-liberar-ponteiro? r2 (+ agora 10)))))
    ;; Janela expirada: libera mesmo sem captura.
    (let [depois (+ (long (:captura-expira r)) 1)]
      (is (false? (raid/visivel-para? r depois "ash")))
      (is (true? (raid/deve-liberar-ponteiro? r depois))))))

(deftest derrota-libera-ponteiro
  (let [party [{:pokemon (mon "Solo" 50) :collection-index 0}]
        r0 (-> (raid-combate party) (forcar-hp-ativo "ash" 1))
        out (raid/atacar r0 "ash" 0 (+ agora 10))
        r (:raid out)]
    (is (true? (:derrota? out)))
    (is (true? (raid/deve-liberar-ponteiro? r (+ agora 10))))
    (is (false? (raid/visivel-para? r (+ agora 10) "ash")))))

(deftest faint-escolhe-proxima-reserva-na-ordem
  (testing "party A B C: A desmaia → B (não C)"
    (let [party [{:pokemon (mon "A" 50) :collection-index 0}
                 {:pokemon (mon "B" 100) :collection-index 1}
                 {:pokemon (mon "C" 100) :collection-index 2}]
          r0 (-> (raid-combate party) (forcar-hp-ativo "ash" 1))
          out (raid/atacar r0 "ash" 0 (+ agora 10))]
      (is (true? (:trocou? out)))
      (is (= 1 (get-in out [:raid :participantes "ash" :active-party-idx])))
      (is (= "B" (get-in out [:raid :participantes "ash" :pokemon :nome])))))
  (testing "party A B C: B desmaia → C (não A)"
    (let [party [{:pokemon (mon "A" 50) :collection-index 0}
                 {:pokemon (mon "B" 50) :collection-index 1}
                 {:pokemon (mon "C" 100) :collection-index 2}]
          r0 (raid-combate party)
          ;; Marca A já desmaiado e B ativo com 1 HP
          part (get-in r0 [:participantes "ash"])
          part' (-> part
                    (assoc-in [:party 0 :hp] 0)
                    (assoc :active-party-idx 1)
                    (assoc-in [:party 1 :hp] 1)
                    (assoc :hp 1 :pokemon (get-in part [:party 1 :pokemon])))
          r1 (assoc-in r0 [:participantes "ash"] part')
          out (raid/atacar r1 "ash" 0 (+ agora 10))]
      (is (true? (:trocou? out)))
      (is (= 2 (get-in out [:raid :participantes "ash" :active-party-idx])))
      (is (= "C" (get-in out [:raid :participantes "ash" :pokemon :nome]))))))

(deftest golpe-string-keys-sem-interrogao
  (let [g (battle/golpe-normalizado
           {"nome" "Water Gun" "tipo" "water" "poder" 40 "classe" "especial"})]
    (is (= "Water Gun" (:nome-exibicao g)))
    (is (= 40 (:poder g)))
    (is (not= "?" (:nome-exibicao g)))))

(deftest golpe-status-poder-zero-valido
  (let [g (battle/golpe-normalizado
           {:nome-exibicao "Growl" :tipo "normal" :poder 0 :classe :status
            :status-causado :ataque-down})]
    (is (= "Growl" (:nome-exibicao g)))
    (is (zero? (long (:poder g))))
    (is (= :status (:classe g)))))

(deftest dano-raid-proposta-a-hp-e-variacao
  "Proposta A: HP 320/jogador; variação 85–100% antes da efetividade."
  (let [rng100 {:raid-damage-var 15}
        rng85 {:raid-damage-var 0}
        neutro (raid/calcular-dano
                {:poder 80 :classe :fisico :tipo "normal"}
                {:ataque 80}
                {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["normal"]}}
                rng100)
        super (raid/calcular-dano
               {:poder 80 :classe :fisico :tipo "water"}
               {:ataque 80}
               {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["fire"]}}
               rng100)
        super85 (raid/calcular-dano
                 {:poder 80 :classe :fisico :tipo "water"}
                 {:ataque 80}
                 {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["fire"]}}
                 rng85)
        hp-solo raid/hp-por-jogador]
    (is (= 320 hp-solo))
    (is (= 1.0 (:multiplicador neutro)))
    (is (= 2.0 (:multiplicador super)))
    (is (= (:base neutro) (:base super)))
    (is (= 100 (:variacao-pct super)))
    (is (= 85 (:variacao-pct super85)))
    (is (= (* 2 (:base-variada super)) (:dano super)))
    (is (< (:dano super85) (:dano super)))
    ;; Com HP 320, um hit SE a 100% ainda é significativo mas < ~2/3 do boss solo.
    (is (> (/ (double (:dano super)) hp-solo) 0.35))
    (is (< (/ (double (:dano super)) hp-solo) 0.70))))

(deftest dano-variacao-deterministica-por-rng
  (let [g {:poder 80 :classe :fisico :tipo "normal"}
        p {:ataque 80}
        r {:hp-chefe 1000 :defesa 50.0 :chefe {:tipos ["normal"]}}
        a (raid/calcular-dano g p r {:raid-damage-var 7})
        b (raid/calcular-dano g p r {:raid-damage-var 7})]
    (is (= 92 (:variacao-pct a)))
    (is (= (:dano a) (:dano b)))
    (is (= (:base-variada a) (:base-variada b)))))
