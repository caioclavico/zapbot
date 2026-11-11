(ns pokemon.tests.growth-curves-a6-test
  "Curvas oficiais PokéAPI + conversão legado residual → growth-v1."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [datomic.client.api :as d]))

(deftest six-curves-loaded-and-monotonic
  (doseq [rate growth/curve-keys]
    (testing (str rate)
      (is (= 0 (growth/total-xp-for-level rate 1)))
      (is (pos? (growth/total-xp-for-level rate 100)))
      (doseq [n (range 1 100)]
        (is (<= (growth/total-xp-for-level rate n)
                (growth/total-xp-for-level rate (inc n))))))))

(deftest official-totals-match-pokeapi-snapshot
  (let [tables (growth/tables)]
    (is (= 1000000 (growth/total-xp-for-level :medium-fast 100)))
    (is (= 800000 (growth/total-xp-for-level :fast 100)))
    (is (= 1250000 (growth/total-xp-for-level :slow 100)))
    (is (= 1059860 (growth/total-xp-for-level :medium-slow 100)))
    (is (= 600000 (growth/total-xp-for-level :erratic 100)))
    (is (= 1640000 (growth/total-xp-for-level :fluctuating 100)))
    (is (= "medium" (get-in tables [:medium-fast :pokeapi-name])))
    (is (= "slow-then-very-fast" (get-in tables [:erratic :pokeapi-name])))))

(deftest species-resolve-to-official-curves
  (is (= :medium-slow (growth/growth-rate-for-species "bulbasaur")))
  (is (= :medium-fast (growth/growth-rate-for-species "pikachu")))
  (is (= :slow (growth/growth-rate-for-species "magikarp")))
  (is (= :fast (growth/growth-rate-for-species "clefairy")))
  (is (= :erratic (growth/growth-rate-for-species "nincada")))
  (is (= :fluctuating (growth/growth-rate-for-species "wailmer")))
  (is (= :fast (growth/growth-rate-for-species "wishiwashi-solo"))
      "forma resolve para espécie-base wishiwashi (fast)"))

(deftest level-from-total-and-boundaries
  (doseq [rate [:medium-fast :erratic :fluctuating]]
    (is (= 1 (growth/pokemon-level-from-total-xp rate 0)))
    (is (= 2 (growth/pokemon-level-from-total-xp rate
                                                (growth/total-xp-for-level rate 2))))
    (is (= 50 (growth/pokemon-level-from-total-xp rate
                                                 (growth/total-xp-for-level rate 50))))
    (is (= 99 (growth/pokemon-level-from-total-xp rate
                                                 (dec (growth/total-xp-for-level rate 100)))))
    (is (= 100 (growth/pokemon-level-from-total-xp rate
                                                  (growth/total-xp-for-level rate 100))))
    (is (= 100 (growth/pokemon-level-from-total-xp rate
                                                  (+ (growth/total-xp-for-level rate 100) 99999))))))

(deftest erratic-fluctuating-piecewise-samples
  ;; Valores oficiais Pontos L50 / L68 / L99 (PokéAPI)
  (is (= 125000 (growth/total-xp-for-level :erratic 50)))
  (is (= 257834 (growth/total-xp-for-level :erratic 68)))
  (is (= 591882 (growth/total-xp-for-level :erratic 99)))
  (is (= 142500 (growth/total-xp-for-level :fluctuating 50)))
  (is (= 1640000 (growth/total-xp-for-level :fluctuating 100))))

(deftest legacy-residual-conversion-preserves-level
  (let [legado {:species "pikachu" :nivel 24 :xp-desde-nivel 4
                :hp 100 :hp-atual 100 :ataque 50 :defesa 50
                :atq-esp 50 :def-esp 50 :veloc 50}
        conv (growth/convert-legacy-residual legado)
        again (growth/convert-legacy-residual conv)]
    (is (= 24 (:nivel conv)))
    (is (= "growth-v1" (:xp-progress-version conv)))
    (is (= 4 (:xp-legacy-residual conv)))
    (is (= (:xp-total conv) (:xp-total again)) "conversão idempotente")
    (is (<= (growth/total-xp-for-level :medium-fast 24)
            (:xp-total conv)
            (dec (growth/total-xp-for-level :medium-fast 25))))
    (let [frac (/ 4.0 9.0)
          span (growth/level-span :medium-fast 24)
          expected (+ (growth/total-xp-for-level :medium-fast 24)
                      (long (Math/floor (* frac span))))]
      (is (= expected (:xp-total conv))))))

(deftest grant-multi-level-and-cap
  (let [base {:species "caterpie" :nivel 1 :xp-desde-nivel 0
              :hp 100 :hp-atual 100 :ataque 10 :defesa 10
              :atq-esp 10 :def-esp 10 :veloc 10 :amizade 70}
        ;; medium-fast L10 = 1000
        big (xp/aplicar-xp-pokemon base 1000)]
    (is (= 10 (:nivel-final big)))
    (is (= 9 (:subidas big)))
    (is (= 0 (get-in big [:registro :xp-desde-nivel])))
    (let [capped (xp/aplicar-xp-pokemon
                  (assoc (:registro big)
                         :nivel 100
                         :xp-total (growth/total-xp-for-level :medium-fast 100)
                         :xp-progress-version "growth-v1"
                         :growth-rate :medium-fast)
                  50000)]
      (is (= 100 (:nivel-final capped)))
      (is (= 0 (:subidas capped)))
      (is (= (growth/total-xp-for-level :medium-fast 100)
             (get-in capped [:registro :xp-total]))))))

(deftest evolution-keeps-progress-never-lowers-level
  (let [pre {:species "charmander" :nivel 16 :xp-desde-nivel 2
             :hp 100 :hp-atual 100 :ataque 50 :defesa 50
             :atq-esp 50 :def-esp 50 :veloc 50}
        conv (growth/convert-legacy-residual pre)
        evo (growth/after-evolution (assoc conv :species "charmeleon" :nome "Charmeleon"))]
    (is (>= (:nivel evo) 16))
    (is (>= (:xp-total evo) (growth/total-xp-for-level (:growth-rate evo) (:nivel evo))))
    (is (= "growth-v1" (:xp-progress-version evo)))))

(deftest migration-037-is-additive
  (let [client (persistence/client {:system (str "g037-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/g037-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "g037")]
    (schema/install! conn)
    (let [db (d/db conn)
          attrs #{:pokemon/xp-total :pokemon/xp-progress-version
                  :pokemon/xp-legacy-residual :pokemon/xp-legacy-nivel
                  :pokemon/growth-rate}]
      (doseq [a attrs]
        (is (some? (d/pull db '[:db/ident] a)))))))

(deftest datomic-grant-persists-growth-v1
  (let [client (persistence/client {:system (str "gxp-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gxp-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gxp")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (collection/adicionar-pokemon!
     s "c" "ash"
     {:id-pokemon "p1" :species "geodude" :nome "Geodude"
      :nivel 1 :xp-desde-nivel 0 :hp 40 :hp-atual 40
      :ataque 40 :defesa 40 :atq-esp 30 :def-esp 30 :veloc 20
      :tipos ["rock"] :amizade 70
      :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                :poder 40 :classe :fisico}]}
     20 nil)
    (collection/ganhar-xp-pokemon! s "c" "ash" 0 9)
    (let [poke (first (ports/list-collection s "c" "ash" "a"))]
      (is (= 2 (:nivel poke)))
      (is (= "growth-v1" (:xp-progress-version poke)))
      (is (some? (:xp-total poke)))
      (is (= :medium-slow (:growth-rate poke))))))

(deftest legacy-vitorias-desde-nivel-precedence
  (testing "somente vitórias"
    (let [r {:species "pikachu" :nivel 10 :vitorias-desde-nivel 2
             :hp 50 :hp-atual 50 :ataque 40 :defesa 40
             :atq-esp 40 :def-esp 40 :veloc 40}
          c (growth/convert-legacy-residual r)]
      (is (= 6 (:xp-legacy-residual c)))
      (is (= 10 (:nivel c)))
      (is (= "growth-v1" (:xp-progress-version c)))
      (let [span (growth/level-span :medium-fast 10)
            expected (+ (growth/total-xp-for-level :medium-fast 10)
                        (long (Math/floor (* (/ 6.0 9.0) span))))]
        (is (= expected (:xp-total c))))))
  (testing "xp-desde-nivel tem precedência sobre vitórias"
    (let [c (growth/convert-legacy-residual
             {:species "pikachu" :nivel 5 :xp-desde-nivel 1 :vitorias-desde-nivel 9
              :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})]
      (is (= 1 (:xp-legacy-residual c)))))
  (testing "idempotência"
    (let [c1 (growth/convert-legacy-residual
              {:species "eevee" :nivel 7 :vitorias-desde-nivel 1
               :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})
          c2 (growth/convert-legacy-residual c1)]
      (is (= (:xp-total c1) (:xp-total c2)))
      (is (= (:nivel c1) (:nivel c2))))))

(deftest legacy-residual-boundaries
  (doseq [[res expect-extra]
          [[0 0] [1 0] [4 0] [8 0] [9 1] [10 1] [18 2]]]
    (testing (str "residual=" res)
      (let [c (growth/convert-legacy-residual
               {:species "pikachu" :nivel 20 :xp-desde-nivel res
                :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})
            nivel' (:nivel c)
            rate :medium-fast]
        (is (= (+ 20 expect-extra) nivel')
            "nível sobe só pelo excesso flat-9; nunca desce")
        (is (<= (growth/total-xp-for-level rate nivel')
                (:xp-total c)
                (if (>= nivel' 100)
                  (growth/total-xp-for-level rate 100)
                  (dec (growth/total-xp-for-level rate (inc nivel'))))))
        (is (= nivel' (growth/pokemon-level-from-total-xp rate (:xp-total c)))
            "nível persistido alinhado ao total"))))
  (testing "nível 99 com residual 9 sobe a 100"
    (let [c (growth/convert-legacy-residual
             {:species "pikachu" :nivel 99 :xp-desde-nivel 9
              :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})]
      (is (= 100 (:nivel c)))
      (is (= (growth/total-xp-for-level :medium-fast 100) (:xp-total c)))
      (is (zero? (:xp-desde-nivel c)))))
  (testing "nível 100 não sobe nem acumula residual"
    (let [c (growth/convert-legacy-residual
             {:species "pikachu" :nivel 100 :xp-desde-nivel 20
              :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})]
      (is (= 100 (:nivel c)))
      (is (= (growth/total-xp-for-level :medium-fast 100) (:xp-total c)))
      (is (= 20 (:xp-legacy-residual c))))))

(deftest datomic-vitorias-only-roundtrip
  (let [client (persistence/client {:system (str "gvic-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gvic-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gvic")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (collection/adicionar-pokemon!
     s "c" "ash"
     {:id-pokemon "v1" :species "pikachu" :nome "Pikachu"
      :nivel 12 :vitorias-desde-nivel 2
      :hp 35 :hp-atual 35 :ataque 55 :defesa 40
      :atq-esp 50 :def-esp 50 :veloc 90 :tipos ["electric"] :amizade 70
      :golpes [{:slug "thunder-shock" :nome-exibicao "Thunder Shock" :tipo "electric"
                :poder 40 :classe :especial}]}
     20 nil)
    ;; 1 XP oficial força ensure-growth-v1 / conversão lazy sem subir por atividade.
    (collection/ganhar-xp-pokemon! s "c" "ash" 0 1)
    (let [poke (first (ports/list-collection s "c" "ash" "a"))
          again (xp/aplicar-xp-pokemon poke 0)]
      (is (= 12 (:nivel poke)))
      (is (= "growth-v1" (:xp-progress-version poke)))
      (is (pos? (:xp-total poke)))
      (is (= (:xp-total poke) (get-in again [:registro :xp-total]))
          "retry não duplica XP de conversão"))))

(deftest balanced-activity-grant-pacing
  (let [base {:species "pikachu" :nivel 24 :xp-desde-nivel 0
              :xp-progress-version "growth-v1" :growth-rate :medium-fast
              :xp-total (growth/total-xp-for-level :medium-fast 24)
              :hp 100 :hp-atual 100 :ataque 50 :defesa 50
              :atq-esp 50 :def-esp 50 :veloc 50 :amizade 70}
        span (growth/level-span :medium-fast 24)
        win (xp/calcular-ganho-pokemon base xp/xp-por-vitoria)
        loss (xp/calcular-ganho-pokemon base xp/xp-por-derrota)
        raid (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "lendario"})]
    (is (= (long (Math/floor (* span (/ 3.0 9.0)))) win)
        "vitória ≈ 1/3 do span (3 vitórias/nível)")
    (is (= (long (Math/floor (* span (/ 1.0 9.0)))) loss))
    (is (> raid win) "raid lendária > vitória comum")
    (is (<= raid (long (Math/floor (* span xp/pokemon-xp-grant-span-cap)))))
    (let [[after n]
          (loop [r base n 0]
            (if (or (>= (long (:nivel r)) 25) (> n 6))
              [r n]
              (recur (:registro (xp/aplicar-xp-atividade r xp/xp-por-vitoria))
                     (inc n))))]
      (is (= 25 (:nivel after)))
      (is (<= 3 n 4) "≈3–4 vitórias por nível (arredondamento do span)"))))

(deftest balance-simulation-milestones-medium-fast
  (testing "Vitórias estimadas até marcos com ritmo ~3/nível (não é regra PokéAPI)."
    (let [wins-to (fn [target]
                    (* 3 (dec (long target))))]
      (is (= 27 (wins-to 10)))
      (is (= 57 (wins-to 20)))
      (is (= 87 (wins-to 30)))
      (is (= 147 (wins-to 50)))
      (is (= 222 (wins-to 75)))
      (is (= 297 (wins-to 100))))))

(defn- mon-at
  [species nivel]
  (let [rate (growth/growth-rate-for-species species)
        total (growth/total-xp-for-level rate nivel)]
    {:species species :nome species :nivel nivel
     :xp-total total :xp-desde-nivel 0 :xp 0
     :xp-progress-version "growth-v1" :growth-rate rate
     :hp 100 :hp-atual 100 :ataque 50 :defesa 50
     :atq-esp 50 :def-esp 50 :veloc 50 :amizade 70}))

(defn- wins-to-reach
  "Simula vitórias comuns sucessivas até atingir `target` (máx 20000)."
  [species target]
  (loop [r (mon-at species 1) n 0]
    (cond
      (>= (long (:nivel r)) (long target)) n
      (> n 20000) n
      :else
      (recur (:registro (xp/aplicar-xp-atividade r xp/xp-por-vitoria
                                                 {:atividade :batalha}))
             (inc n)))))

(deftest dynamic-balance-six-curves-milestones
  (doseq [[species rate] [["pikachu" :medium-fast]
                          ["bulbasaur" :medium-slow]
                          ["magikarp" :slow]
                          ["clefairy" :fast]
                          ["nincada" :erratic]
                          ["wailmer" :fluctuating]]]
    (testing species
      (is (= rate (growth/growth-rate-for-species species)))
      (doseq [target [10 20 30 50]]
        (let [wins (wins-to-reach species target)
              lo (* 3 (dec target))
              hi (* 4 (dec target))]
          (is (<= lo wins hi)
              (str species " → L" target " wins=" wins " band=[" lo "," hi "]")))))))

(deftest dynamic-balance-high-milestones-medium-fast
  (is (<= 222 (wins-to-reach "pikachu" 75) 296))
  (is (<= 297 (wins-to-reach "pikachu" 100) 396)))

(deftest dynamic-balance-activity-variants
  (let [base (mon-at "pikachu" 30)
        win (xp/calcular-ganho-pokemon base 3 {:atividade :batalha})
        loss (xp/calcular-ganho-pokemon base 1 {:atividade :batalha})
        rare (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "raro"})
        epic (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "epico"})
        leg (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "lendario"})
        myth (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "mitico"})
        gym (xp/calcular-ganho-pokemon base 3 {:atividade :ginasio})
        pvp (xp/calcular-ganho-pokemon base 3 {:atividade :pvp})
        adv-high (xp/calcular-ganho-pokemon base 3 {:nivel-adversario 60})
        adv-low (xp/calcular-ganho-pokemon base 3 {:nivel-adversario 10})
        span (growth/level-span :medium-fast 30)
        cap (long (Math/floor (* span xp/pokemon-xp-grant-span-cap)))]
    (is (< loss win))
    (is (<= rare epic leg myth))
    (is (> gym win))
    (is (> pvp win))
    (is (> adv-high win))
    (is (< adv-low win))
    (is (<= myth cap) "anti-exploit cap")
    (let [near (mon-at "pikachu" 99)
          need (growth/level-span :medium-fast 99)
          g (xp/aplicar-xp-pokemon near need {:unidade :oficial})
          mx (xp/aplicar-xp-atividade (:registro g) 3)]
      (is (= 100 (:nivel-final g)))
      (is (= 100 (:nivel-final mx)))
      (is (zero? (:xp-oficial-aplicado mx)) "L100 não recebe XP excedente"))))

(deftest official-tables-untouched-snapshot
  (is (= 1000000 (growth/total-xp-for-level :medium-fast 100)))
  (is (= 800000 (growth/total-xp-for-level :fast 100)))
  (is (= 1250000 (growth/total-xp-for-level :slow 100)))
  (is (= 1059860 (growth/total-xp-for-level :medium-slow 100)))
  (is (= 600000 (growth/total-xp-for-level :erratic 100)))
  (is (= 1640000 (growth/total-xp-for-level :fluctuating 100))))

(deftest datomic-persists-xp-legacy-nivel-immutable
  (let [client (persistence/client {:system (str "gln-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gln-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gln")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (collection/adicionar-pokemon!
     s "c" "ash"
     {:id-pokemon "leg1" :species "pikachu" :nome "Pikachu"
      :nivel 7 :xp-desde-nivel 4
      :hp 35 :hp-atual 35 :ataque 55 :defesa 40
      :atq-esp 50 :def-esp 50 :veloc 90 :tipos ["electric"] :amizade 70
      :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                :poder 40 :classe :fisico}]}
     20 nil)
    (collection/ganhar-xp-pokemon! s "c" "ash" 0 1)
    (let [p1 (first (ports/list-collection s "c" "ash" "a"))
          need (- (growth/total-xp-for-level :medium-fast 8)
                  (long (:xp-total p1 0)))]
      (is (= 7 (:xp-legacy-nivel p1)))
      (is (= 4 (:xp-legacy-residual p1)))
      (collection/ganhar-xp-pokemon! s "c" "ash" 0 (max 1 need))
      (let [p2 (first (ports/list-collection s "c" "ash" "a"))]
        (is (= 7 (:xp-legacy-nivel p2)) "retry/grant não sobrescreve nível legado")
        (is (= 4 (:xp-legacy-residual p2)))
        (is (>= (:nivel p2) 8))))))