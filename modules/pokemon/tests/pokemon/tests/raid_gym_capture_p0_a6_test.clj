(ns pokemon.tests.raid-gym-capture-p0-a6-test
  "P0 auditoria: desmaio≠derrota (manual/coop), dano água→fogo, ginásio RNG, raridade slim."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.types :as types]))

(def ^:private agora 2000000)

(defn- mon
  [nome hp & {:keys [tipos poder atq def]}]
  {:nome nome :hp hp :hp-atual hp
   :ataque (or atq 80) :defesa (or def 40)
   :atq-esp (or atq 80) :def-esp (or def 40)
   :veloc 50 :tipos (or tipos ["normal"]) :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Hit" :tipo (or (first tipos) "normal")
             :poder (or poder 80) :classe :fisico}]})

(defn- agua-gun
  []
  {:nome-exibicao "Jato d'Água" :tipo "water" :poder 40 :classe :especial})

(defn- raid-com
  [partes]
  (let [base (raid/nova-raid {:raid-id "r-p0" :chat-id "c" :ginasio "fogo"
                              :nome-ginasio "Fogo" :nivel-ginasio 50 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["fire"] :defesa 80 :def-esp 80
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora agora :criador "ash"})
        r (reduce (fn [acc [pid party]]
                    (raid/entrar acc {:player-id pid :nome pid :save-id pid
                                      :fence-generation 1 :party party} agora))
                  base
                  partes)]
    (raid/iniciar r "ash" (inc agora))))

(defn- forcar-hp
  [raid pid hp]
  (let [part (get-in raid [:participantes pid])
        idx (long (:active-party-idx part 0))
        part' (-> part
                  (assoc-in [:party idx :hp] hp)
                  (assoc :hp hp))]
    (assoc-in raid [:participantes pid] part')))

(deftest agua-vs-fogo-multiplicador-2x
  (testing "Água×Fogo = 2.0 na tabela canônica"
    (is (= 2.0 (types/multiplicador-vs-tipos "water" ["fire"] nil))))
  (testing "relato poder 40: base clamp + × efetividade (sem STAB)"
    (let [golpe (agua-gun)
          poke {:ataque 50 :atq-esp 50}
          ;; def-esp do chefe = 80 → raw = floor(15+0.6*40*(50/80)) = floor(15+15)=30
          raid {:hp-chefe 1000 :defesa 80 :def-esp 80
                :chefe {:tipos ["fire"]}}
          out (raid/calcular-dano golpe poke raid {:raid-damage-var 15})]
      (is (= 2.0 (:multiplicador out)))
      (is (= 30 (:base out)))
      (is (= 100 (:variacao-pct out)))
      (is (= 60 (:dano out)))
      (testing "sem efetividade (JVM live pré-A+B+C) a fórmula devolve só a base"
        (let [legacy-base (let [raw (long (Math/floor (+ 15 (* 0.6 40 (/ 40.0 80.0)))))]
                            (max 10 (min 160 raw)))]
          (is (= 27 legacy-base))
          (is (not= 27 (:dano out))))))))

(deftest solo-reserva-terceira-opcao
  (let [party [{:pokemon (mon "A" 40) :collection-index 0}
               {:pokemon (mon "B" 40) :collection-index 1}
               {:pokemon (mon "C" 100) :collection-index 2}]
        r0 (-> (raid-com [["ash" party]]) (forcar-hp "ash" 1))
        out1 (raid/atacar r0 "ash" 0 (+ agora 10))
        _ (is (false? (:derrota? out1)))
        _ (is (true? (:trocou? out1)))
        _ (is (= "B" (get-in out1 [:raid :participantes "ash" :pokemon :nome])))
        r1 (forcar-hp (:raid out1) "ash" 1)
        ;; vez pode ter voltado ao ash (solo)
        r1 (assoc r1 :vez "ash")
        out2 (raid/atacar r1 "ash" 0 (+ agora 20))]
    (is (false? (:derrota? out2)))
    (is (true? (:trocou? out2)))
    (is (= "C" (get-in out2 [:raid :participantes "ash" :pokemon :nome])))
    (is (= "combate" (:fase (:raid out2))))))

(deftest coop-um-derrotado-outro-continua
  (let [party-ash [{:pokemon (mon "A" 30) :collection-index 0}]
        party-misty [{:pokemon (mon "M" 100) :collection-index 0}]
        r0 (-> (raid-com [["ash" party-ash] ["misty" party-misty]])
               (forcar-hp "ash" 1))
        ;; garantir vez do ash
        r0 (assoc r0 :vez "ash")
        out (raid/atacar r0 "ash" 0 (+ agora 10))]
    (is (false? (:derrota? out)))
    (is (= "combate" (:fase (:raid out))))
    (is (pos? (get-in out [:raid :participantes "misty" :hp])))
    (is (zero? (get-in out [:raid :participantes "ash" :hp])))
    (is (= "misty" (:vez (:raid out))))))

(deftest coop-todos-sem-reserva-derrota
  (let [r0 (-> (raid-com [["ash" [{:pokemon (mon "A" 30) :collection-index 0}]]
                          ["misty" [{:pokemon (mon "M" 30) :collection-index 0}]]])
               (forcar-hp "ash" 1)
               (forcar-hp "misty" 1)
               (assoc :vez "ash"))
        out1 (raid/atacar r0 "ash" 0 (+ agora 10))
        r1 (assoc (:raid out1) :vez "misty")
        out2 (raid/atacar r1 "misty" 0 (+ agora 20))]
    (is (false? (:derrota? out1)))
    (is (true? (:derrota? out2)))
    (is (= "derrota" (:fase (:raid out2))))))

(deftest slim-preserva-raridade-captura
  (let [p {:nome "Abra" :species "abra" :hp 80 :ataque 40 :defesa 40
           :atq-esp 80 :def-esp 40 :veloc 90 :tipos ["psychic"]
           :raridade "epico" :taxa-captura 45 :golpes [] :historico-golpes [1 2 3]}
        slim (battle/slim-pokemon-for-battle p)
        st (battle/create-wild-battle
            {:battle-id "b-rar" :chat-id "c" :player-id "ash" :save-id "a"
             :fence-generation 1
             :pokemon-x (assoc p :nome "Pikachu" :species "pikachu" :raridade "comum")
             :pokemon-o p
             :hp-x 80 :hp-o 80})
        encoded (battle/encode-state-edn st)
        decoded (battle/decode-compact-edn encoded)]
    (is (= "epico" (:raridade slim)))
    (is (= 45 (:taxa-captura slim)))
    (is (nil? (:historico-golpes slim)))
    (is (= "epico" (get-in st [:pokemons :o :raridade])))
    (is (= "epico" (get-in decoded [:pokemons :o :raridade]))
        "raridade sobrevive encode/decode — evita chance KO colapsar para comum")))

(deftest gym-sorteio-npc-deterministico
  (let [a (gym/sortear-especies-npc "pedra" {:npc-pick-0 0 :npc-pick-1 0 :npc-pick-2 0})
        b (gym/sortear-especies-npc "pedra" {:npc-pick-0 0 :npc-pick-1 0 :npc-pick-2 0})
        c (gym/sortear-especies-npc "pedra" {:npc-pick-0 1 :npc-pick-1 0 :npc-pick-2 0})]
    (is (= 3 (count a)))
    (is (= 3 (count (set a))))
    (is (= a b))
    (is (not= a c))
    (is (every? #(contains? (set (gym/pool-npc "pedra")) %) a))))

(deftest gym-seed-rng-estavel
  (let [s1 (gym/sortear-especies-npc "agua" (gym/seed->npc-rng "op-1"))
        s2 (gym/sortear-especies-npc "agua" (gym/seed->npc-rng "op-1"))
        s3 (gym/sortear-especies-npc "agua" (gym/seed->npc-rng "op-2"))]
    (is (= s1 s2))
    (is (not= s1 s3))))

(deftest raridade-sem-fallback-silencioso-comum
  (testing "snapshot válido"
    (let [r (capture/resolver-raridade {:raridade "lendario" :hp 100})]
      (is (= "lendario" (:raridade r)))
      (is (= :snapshot (:fonte r)))))
  (testing "legado sem :raridade mas com taxa-captura → classifica"
    (let [p {:nome "X" :hp 100 :ataque 100 :defesa 100
             :atq-esp 100 :def-esp 100 :veloc 100 :taxa-captura 5}
          r (capture/resolver-raridade (dissoc p :raridade))]
      (is (= :classificado (:fonte r)))
      (is (= "epico" (:raridade r)))
      (is (true? (:recuperada? r)))
      (is (< (capture/chance-captura p)
             (get capture/raridade-chance-base "comum")))))
  (testing "sem sinais → desconhecida usa faixa mítica (não comum)"
    (let [vazio {}
          r (capture/resolver-raridade vazio)
          c (capture/chance-captura vazio)]
      (is (= :desconhecida (:fonte r)))
      (is (= "mitico" (:raridade r)))
      (is (<= c (get capture/raridade-chance-max "mitico")))
      (is (< c (get capture/raridade-chance-base "comum"))))))

(deftest taxas-aprovadas-por-raridade
  (doseq [[rar esperada] [["raro" 28] ["epico" 14] ["lendario" 5] ["mitico" 3]]]
    (let [p {:raridade rar :hp 75 :ataque 75 :defesa 75
             :atq-esp 75 :def-esp 75 :veloc 75}
          base (capture/chance-captura p)
          lo (get capture/raridade-chance-min rar)
          hi (get capture/raridade-chance-max rar)]
      (is (= esperada (get capture/raridade-chance-base rar)))
      (is (<= lo base hi)
          (str rar " base=" base " fora de [" lo "," hi "]")))))

(deftest legado-slim-sem-raridade-recupera-na-chance-ko
  (let [legado {:nome "Mewtwo" :species "mewtwo"
                :hp 106 :ataque 110 :defesa 90 :atq-esp 154 :def-esp 90 :veloc 130
                :taxa-captura 3 :tipos ["psychic"] :golpes []}
        ;; Simula snapshot antigo: slim sem raridade no keep (só taxa/stats).
        slim-legado (select-keys legado [:nome :species :hp :ataque :defesa
                                         :atq-esp :def-esp :veloc :tipos
                                         :taxa-captura :golpes])
        chance (capture/chance-base-pos-batalha slim-legado nil 0)
        assegurado (capture/assegurar-raridade slim-legado)]
    (is (nil? (:raridade slim-legado)))
    (is (= "epico" (:raridade assegurado)))
    (is (= :classificado (:fonte (capture/resolver-raridade slim-legado))))
    (is (<= chance (get capture/raridade-chance-max "epico") 95))
    (is (< chance (get capture/raridade-chance-min "comum"))
        "não concede chance de comum a lendário/épico classificado")))

(deftest encode-decode-preserva-taxas-pos-reopen
  (doseq [rar ["raro" "epico" "lendario" "mitico"]]
    (let [o {:nome "Boss" :species "x" :raridade rar
             :hp 80 :ataque 80 :defesa 80 :atq-esp 80 :def-esp 80 :veloc 80
             :tipos ["normal"] :golpes [] :taxa-captura 20}
          x (assoc o :nome "AshMon" :raridade "comum")
          st (battle/create-wild-battle
              {:battle-id (str "re-" rar) :chat-id "c" :player-id "ash"
               :save-id "a" :fence-generation 1
               :pokemon-x x :pokemon-o o :hp-x 80 :hp-o 80})
          decoded (battle/decode-compact-edn (battle/encode-state-edn st))
          o' (get-in decoded [:pokemons :o])
          c0 (capture/chance-base-pos-batalha o nil 0)
          c1 (capture/chance-base-pos-batalha o' nil 0)]
      (is (= rar (:raridade o')))
      (is (= c0 c1) (str "taxa " rar " divergiu após reopen")))))
