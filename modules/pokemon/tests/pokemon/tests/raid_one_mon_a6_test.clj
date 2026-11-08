(ns pokemon.tests.raid-one-mon-a6-test
  "Regra A6: exatamente 1 Pokémon por treinador — sem troca automática."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-boss :as rb]))

(def ^:private agora 2000000)

(defn- mon
  [nome hp & {:keys [tipos poder raridade]}]
  {:nome nome :hp hp :hp-atual hp :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40
   :veloc 50 :tipos (or tipos ["normal"]) :nivel 20
   :raridade (or raridade "raro")
   :golpes [{:nome-exibicao "Hit" :tipo (or (first tipos) "normal")
             :poder (or poder 80) :classe :fisico}]})

(defn- raid-combate
  [parts-by-pid]
  (let [base (raid/nova-raid {:raid-id "r1m" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r (reduce (fn [acc [pid part]]
                    (raid/entrar acc
                                 {:player-id pid :nome pid :save-id "a"
                                  :fence-generation 1
                                  :party [{:pokemon (:pokemon part)
                                           :collection-index (:collection-index part 0)}]}
                                 agora))
                  base
                  parts-by-pid)]
    (raid/iniciar r "ash" (inc agora))))

(defn- forcar-hp
  [raid pid hp]
  (let [part (get-in raid [:participantes pid])
        idx (long (:active-party-idx part 0))
        part' (-> part
                  (assoc-in [:party idx :hp] hp)
                  (assoc :hp hp))]
    (assoc-in raid [:participantes pid] part')))

(deftest max-party-e-um
  (is (= 1 raid/max-party)))

(deftest entrar-ignora-reservas-extras
  (let [base (raid/nova-raid {:raid-id "r" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["rock"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r (raid/entrar base
                       {:player-id "ash" :nome "Ash" :save-id "a"
                        :fence-generation 1
                        :party [{:pokemon (mon "A" 100) :collection-index 0}
                                {:pokemon (mon "B" 100) :collection-index 1}
                                {:pokemon (mon "C" 100) :collection-index 2}]}
                       agora)
        part (get-in r [:participantes "ash"])]
    (is (= 1 (count (:party part))))
    (is (= "A" (get-in part [:pokemon :nome])))))

(deftest faint-solo-derrota-sem-troca
  (let [r0 (-> (raid-combate [["ash" {:pokemon (mon "Solo" 50) :collection-index 0}]])
               (forcar-hp "ash" 1))
        out (raid/atacar r0 "ash" 0 (+ agora 10) {:raid-damage-var 15})]
    (is (false? (:trocou? out)))
    (is (true? (:eliminado? out)))
    (is (true? (:derrota? out)))
    (is (= "derrota" (:fase (:raid out))))))

(deftest faint-com-legado-multi-nao-promove-reserva
  "Party legada com 2 mons: faint do ativo NÃO promove B — solo derrota."
  (let [base (raid/nova-raid {:raid-id "leg" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["grass"] :defesa 40 :def-esp 40
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        ;; Força party legada >1 bypassando montar-party (assoc direto pós-entrar).
        r-ent (raid/entrar base
                           {:player-id "ash" :nome "Ash" :save-id "a"
                            :fence-generation 1
                            :pokemon (mon "A" 50) :collection-index 0}
                           agora)
        part-leg (let [p (get-in r-ent [:participantes "ash"])]
                   (assoc p :party [{:pokemon (mon "A" 1) :collection-index 0 :hp 1}
                                    {:pokemon (mon "B" 100) :collection-index 1 :hp 100}]
                          :active-party-idx 0 :hp 1 :pokemon (mon "A" 1)))
        r0 (-> (assoc-in r-ent [:participantes "ash"] part-leg)
               (raid/iniciar "ash" (inc agora))
               (forcar-hp "ash" 1))
        out (raid/atacar r0 "ash" 0 (+ agora 10) {:raid-damage-var 15})]
    (is (false? (:trocou? out)))
    (is (true? (:derrota? out)) "reserva legada não salva o treinador")
    (is (= 2 (count (get-in out [:raid :participantes "ash" :party])))
        "dados da party legada preservados")))

(deftest coop-continua-apos-faint-de-um
  (let [r0 (-> (raid-combate [["ash" {:pokemon (mon "A" 50) :collection-index 0}]
                              ["misty" {:pokemon (mon "B" 100) :collection-index 0}]])
               (forcar-hp "ash" 1))
        out (raid/atacar r0 "ash" 0 (+ agora 10) {:raid-damage-var 15})]
    (is (false? (:derrota? out)))
    (is (= "combate" (:fase (:raid out))))
    (is (= "misty" (:vez (:raid out))))
    (is (false? (raid/participante-vivo?
                 (get-in out [:raid :participantes "ash"]))))
    (is (true? (raid/participante-vivo?
                (get-in out [:raid :participantes "misty"]))))))

(deftest catalogo-manual-maior-que-templates-fixos
  (let [audit (rb/audit-catalog)]
    (is (>= (:elegiveis audit) 50) "catálogo dinâmico >> 5 templates legados")
    (is (true? (:descoberta-automatica? audit))))
  (let [a (rb/select-boss-for-ginasio "pedra" {:rng (constantly 0.1)})
        b (rb/select-boss-for-ginasio "pedra" {:rng (constantly 0.7)})]
    (is (string? (get-in a [:chefe :species])))
    (is (string? (get-in b [:chefe :species])))
    (is (contains? #{"iniciante" "bronze" "prata" "ouro" "diamante"} (:liga a)))))
