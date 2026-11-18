(ns pokemon.tests.raid-audit-sim-a6-test
  "Compara a raid atual com o laço antigo (acerto sempre, um golpe, sem carga).
  Não altera o HP dos chefes."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.raid :as raid]))

(def fatores
  [["comum" 0.80]
   ["raro" 1.00]
   ["epico" 1.15]
   ["lendario" 1.40]
   ["mitico" 1.55]])

(defn- jogador
  [golpe]
  {:nome "Ace" :hp 160 :ataque 80 :atq-esp 80 :defesa 80 :def-esp 80
   :veloc 50 :tipos ["normal"] :nivel 50 :golpes [golpe]})

(defn- montar
  [n fator poke tipos]
  (let [ids (mapv #(str "p" %) (range n))
        hp (long (Math/round (* (double raid/hp-por-jogador) n fator)))]
    {:fase "combate" :expira 100000000000 :vez (first ids) :ordem ids
     :hp-chefe hp :hp-max hp
     :defesa 80.0 :def-esp 80.0 :turn-index 0
     :chefe {:tipos tipos :nome "Chefe"}
     :participantes
     (into {}
           (map (fn [id]
                  [id {:pokemon poke :hp 160
                       :party [{:pokemon poke :hp 160 :collection-index 0}]
                       :active-party-idx 0 :dano 0}])
                ids))}))

(defn- proximo
  [raid pid]
  (let [ordem (:ordem raid)
        vivos (filterv #(pos? (long (get-in raid [:participantes % :hp] 0))) ordem)]
    (when (seq vivos)
      (let [idx (.indexOf ordem pid)
            ciclo (concat (drop (inc idx) ordem) (take (inc idx) ordem))]
        (first (filter (set vivos) ciclo))))))

(defn- passo-legado
  "Um acerto, sem multiplicar acertos e sem turno de carga."
  [raid rng]
  (let [pid (:vez raid)
        part (get-in raid [:participantes pid])
        poke (:pokemon part)
        golpe (move-effects/enriquecer (first (:golpes poke)))
        calc (raid/calcular-dano golpe poke raid rng)
        dano (min (long (:hp-chefe raid)) (long (:dano calc)))
        hp' (max 0 (- (long (:hp-chefe raid)) dano))
        venceu? (zero? hp')
        revide (if venceu? 0 (max 1 (long (Math/ceil (/ 160.0 5)))))
        hp-j (max 0 (- (long (:hp part)) revide))
        slot (assoc (first (:party part)) :hp hp-j)
        raid' (-> raid
                  (assoc :hp-chefe hp')
                  (assoc-in [:participantes pid]
                            (assoc part :hp hp-j :party [slot])))
        prox (when-not venceu? (proximo raid' pid))]
    (cond
      venceu? (assoc raid' :fase "vitoria" :vez nil)
      (nil? prox) (assoc raid' :fase "derrota" :vez nil)
      :else (assoc raid' :vez prox))))

(defn- simular
  [modo raid rng]
  (loop [r raid n 0]
    (cond
      (= "vitoria" (:fase r)) {:venceu? true :turnos n}
      (= "derrota" (:fase r)) {:venceu? false :turnos n}
      (>= n 80) {:venceu? false :turnos n :estourou? true}
      :else
      (let [rng (if (number? (:accuracy rng))
                  (assoc rng :accuracy (mod (+ (:accuracy rng) n) 100))
                  rng)
            r' (if (= modo :legado)
                 (passo-legado r rng)
                 (:raid (raid/atacar r (:vez r) 0 (+ 1000 n) rng)))]
        (recur r' (inc n))))))

(defn- golpe
  [slug nome tipo poder]
  (move-effects/enriquecer
   {:slug slug :nome-exibicao nome :tipo tipo :poder poder :classe :fisico}))

(deftest formula-de-hp-permanece
  (doseq [[_ fator] fatores
          n [1 2 3]]
    (is (= (long (Math/round (* 320.0 n fator)))
           (:hp-chefe (montar n fator (jogador (golpe "tackle" "Investida" "normal" 40))
                              ["normal"]))))))

(deftest precisao-cem-empata-com-o-laco-antigo
  (let [investida (jogador (golpe "tackle" "Investida" "normal" 40))
        super (jogador (golpe "brick-break" "Quebra-Tijolo" "fighting" 75))
        rng {:raid-damage-var 15 :accuracy 99 :multi-hit 0 :protecao 0}]
    (doseq [[nome fator] fatores
            [n tipos poke] [[1 ["normal"] investida]
                            [1 ["rock"] super]
                            [2 ["normal"] investida]
                            [3 ["rock"] super]]]
      (let [raid (montar n fator poke tipos)
            antes (simular :legado raid rng)
            agora (simular :atual raid rng)]
        (println {:cenario nome :n n :tipos tipos
                  :antes antes :agora agora})
        (is (= antes agora))))))

(deftest precisao-baixa-piora-a-taxa-sem-mexer-no-chefe
  (let [poke (jogador (golpe "thunder" "Trovão" "electric" 110))
        raid (montar 1 1.0 poke ["water"])
        legado (simular :legado raid {:raid-damage-var 15 :accuracy 0})
        atual (mapv #(simular :atual raid {:raid-damage-var 15 :accuracy %})
                    (range 100))
        vitorias (count (filter :venceu? atual))]
    (println {:trovao-legado legado :trovao-vitorias-em-100 vitorias})
    (is (:venceu? legado))
    (is (pos? vitorias))
    (is (< vitorias 100))
    (is (= 320 (:hp-chefe raid)))))

(deftest varios-acertos-batem-mais-que-o-golpe-unico
  (let [poke (jogador (golpe "double-slap" "Tapa Duplo" "normal" 15))
        raid (montar 1 1.0 poke ["normal"])
        um (raid/calcular-dano (first (:golpes poke)) poke raid
                               {:raid-damage-var 15})
        agora (raid/atacar raid "p0" 0 1 {:raid-damage-var 15 :accuracy 0 :multi-hit 0})]
    (println {:golpe-unico (:dano um) :dois-acertos (:dano agora) :acertos (:acertos agora)})
    (is (= 2 (:acertos agora)))
    (is (= (* 2 (:dano um)) (:dano agora)))))

(deftest carga-e-recarga-alongam-a-raid
  (let [rng {:raid-damage-var 15 :accuracy 0}
        solar (montar 1 1.0 (jogador (golpe "solar-beam" "Raio Solar" "grass" 120)) ["water"])
        hiperespaco (montar 1 1.0 (jogador (golpe "hyper-beam" "Hiper-raio" "normal" 150)) ["normal"])
        antes-solar (simular :legado solar rng)
        agora-solar (simular :atual solar rng)
        antes-raio (simular :legado hiperespaco rng)
        agora-raio (simular :atual hiperespaco rng)]
    (println {:solar-antes antes-solar :solar-agora agora-solar
              :hiper-antes antes-raio :hiper-agora agora-raio})
    (is (:venceu? antes-solar))
    (is (or (not (:venceu? agora-solar))
            (> (:turnos agora-solar) (:turnos antes-solar))))
    (is (or (not (:venceu? agora-raio))
            (> (:turnos agora-raio) (:turnos antes-raio))))))
