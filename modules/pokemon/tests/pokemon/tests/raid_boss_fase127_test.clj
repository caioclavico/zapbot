(ns pokemon.tests.raid-boss-fase127-test
  "Fase 12.7 — catálogo dinâmico de chefes, pesos e anti-repetição."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as raid-auto]
            [pokemon.domain.raid-boss :as rb]))

(deftest catalogo-carrega-e-audita
  (let [a (rb/audit-catalog)]
    (is (= 1025 (:total-cadastradas a)))
    (is (= 1025 (:total-pokemons-projeto a)))
    (is (= 571 (:elegiveis a)))
    (is (= 423 (get (:por-raridade a) "raro")))
    (is (= 34 (get (:por-raridade a) "epico")))
    (is (= 91 (get (:por-raridade a) "lendario")))
    (is (= 23 (get (:por-raridade a) "mitico")))
    (is (= 454 (get (:exclusao-por-motivo a) :raridade-baixa)))
    (is (false? (:lista-fixa? a)))
    (is (true? (:descoberta-automatica? a)))
    (is (= 9 (count (:por-geracao a))))))

(deftest nao-usa-lista-fixa-de-ginasio
  (let [fixed #{"onix" "starmie" "raichu" "vileplume" "arcanine"}
        picks (mapv (fn [i]
                      (:species
                       (rb/select-boss
                        {:rng (constantly (/ (double i) 100.0))
                         :nivel 10})))
                    (range 30))]
    (is (> (count (set picks)) 5))
    (is (seq (remove fixed picks)))))

(deftest novas-especies-entram-automaticamente
  (let [mini {:version "test"
              :total-pokemons 2
              :species
              [{:id 9998 :species "novomon" :nome "Novomon" :generation 9
                :tipos ["dragon"] :hp 100 :ataque 100 :defesa 100
                :atq-esp 100 :def-esp 100 :veloc 100
                :taxa-captura 3 :lendario-api? true :mitico-api? false
                :paradox-api? false}
               {:id 9999 :species "mitimon" :nome "Mitimon" :generation 9
                :tipos ["psychic"] :hp 90 :ataque 90 :defesa 90
                :atq-esp 120 :def-esp 120 :veloc 90
                :taxa-captura 3 :lendario-api? false :mitico-api? true
                :paradox-api? false}]}]
    (try
      (rb/reset-catalog-for-tests! mini)
      (let [a (rb/audit-catalog)
            pick (rb/select-boss {:rng (constantly 0.05) :nivel 20})]
        (is (= 2 (:total-cadastradas a)))
        (is (= 2 (:elegiveis a)))
        (is (contains? #{"novomon" "mitimon"} (:species pick)))
        (is (pos? (get-in pick [:chefe :fator-dificuldade]))))
      (finally
        (rb/reset-catalog-for-tests! nil)))))

(deftest pesos-normalizam-categorias-presentes
  (let [mini {:version "t"
              :species
              [{:id 1 :species "only-rare" :nome "Only Rare" :generation 1
                :tipos ["normal"] :hp 80 :ataque 80 :defesa 80
                :atq-esp 80 :def-esp 80 :veloc 80
                :taxa-captura 45 :lendario-api? false :mitico-api? false
                :paradox-api? false}]}]
    (try
      (rb/reset-catalog-for-tests! mini)
      (let [pick (rb/select-boss {})]
        (is (= "raro" (:categoria pick)))
        (is (= 1.0 (get-in pick [:category-weights "raro"]))))
      (finally
        (rb/reset-catalog-for-tests! nil)))))

(deftest anti-repeticao-curta-nao-permanente
  (let [blocked #{"mewtwo" "rayquaza" "dialga" "palkia"
                  "giratina" "arceus" "zacian" "zamazenta"}
        recent (atom (vec blocked))
        first-wave (atom [])
        rng (let [r (java.util.Random. 7)] (fn [] (.nextDouble r)))]
    (dotimes [_ 20]
      (let [p (rb/select-boss {:rng rng :recent-species @recent :nivel 50})]
        (swap! first-wave conj (:species p))
        (reset! recent (raid-auto/remember-boss @recent (:species p)))))
    (is (empty? (filter blocked @first-wave))
        "janela recente evita as 8 espécies")
    (let [p (rb/select-boss {:rng (constantly 0.85)
                             :recent-species []
                             :nivel 50})]
      (is (string? (:species p))))))

(deftest dificuldade-proporcional-aos-atributos
  (let [fraco (rb/materialize-boss
               {:id 10 :species "weakmon" :nome "Weak" :generation 1
                :tipos ["normal"] :hp 60 :ataque 55 :defesa 50
                :atq-esp 50 :def-esp 50 :veloc 55
                :raridade "raro" :taxa-captura 45}
               10)
        forte (rb/materialize-boss
               {:id 150 :species "mewtwo" :nome "Mewtwo" :generation 1
                :tipos ["psychic"] :hp 106 :ataque 110 :defesa 90
                :atq-esp 154 :def-esp 90 :veloc 130
                :raridade "mitico" :taxa-captura 3 :mitico-api? true}
               10)]
    (is (< (:fator-dificuldade fraco) (:fator-dificuldade forte)))
    (is (<= rb/fator-hp-min (:fator-dificuldade fraco) rb/fator-hp-max))
    (is (<= rb/fator-hp-min (:fator-dificuldade forte) rb/fator-hp-max))
    (let [raid-f (raid/nova-raid
                  {:raid-id "rf" :chat-id "c" :ginasio "pedra"
                   :liga "iniciante" :chefe fraco :agora 1000})
          raid-t (raid/nova-raid
                  {:raid-id "rt" :chat-id "c" :ginasio "pedra"
                   :liga "iniciante" :chefe forte :agora 1000})
          mon {:nome "P" :hp 100 :ataque 80 :defesa 50 :atq-esp 50
               :def-esp 50 :veloc 40 :tipos ["normal"] :nivel 8
               :golpes [{:nome-exibicao "T" :tipo "normal" :poder 80
                         :classe :fisico}]}
          join (fn [r pid]
                 (raid/entrar r {:player-id pid :nome pid :save-id "a"
                                 :fence-generation 0 :pokemon mon
                                 :collection-index 0} 1000))
          rf (-> raid-f (join "a") (join "b")
                 (raid/iniciar "a" 1000))
          rt (-> raid-t (join "a") (join "b")
                 (raid/iniciar "a" 1000))]
      (is (< (:hp-chefe rf) (:hp-chefe rt))))))

(deftest plan-spawn-usa-catalogo-dinamico
  (let [sch (raid-auto/normalize-schedule
             {:enabled true :interval-ms raid-auto/min-interval-ms
              :next-at 1000 :ginasio-index 0}
             "c" 1000)
        free (raid-auto/plan-spawn sch nil 1000 {:rng (constantly 0.01)})]
    (is (= :spawn (:acao free)))
    (is (= "pedra" (:ginasio free)))
    (is (string? (get-in free [:chefe :species])))
    (is (contains? #{"raro" "epico" "lendario" "mitico"}
                   (get-in free [:chefe :raridade])))
    (is (number? (get-in free [:chefe :fator-dificuldade])))))

(deftest simulacao-10k-em-memoria
  (let [sim (rb/simulate-distribution 10000 {:seed 42})
        n (:n sim)
        cats (:por-categoria sim)]
    (is (= 10000 n))
    (is (= 571 (:especies-distintas sim)))
    (is (false? (:especie-unica-dominante? sim)))
    (is (< 0.35 (/ (double (get cats "raro" 0)) n) 0.45))
    (is (< 0.25 (/ (double (get cats "epico" 0)) n) 0.35))
    (is (< 0.15 (/ (double (get cats "lendario" 0)) n) 0.25))
    (is (< 0.05 (/ (double (get cats "mitico" 0)) n) 0.15))
    (is (= 9 (count (:por-geracao sim))))))
