(ns pokemon.tests.growth-curves-a6-test
  "flat-9-v2: progressão 9 XP/nível + conversão segura + regressão incidente #85.
   Tabelas PokéAPI permanecem só para migrar growth-v1 → flat-9."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [datomic.client.api :as d]))

(defn- mon
  "Pokémon flat-9-v2 mínimo para grants."
  [{:keys [species nivel residual]
    :or {species "pikachu" nivel 1 residual 0}}]
  {:species species :nome species :nivel nivel
   :xp-desde-nivel residual :xp residual
   :xp-total (growth/flat9-synthetic-total nivel residual)
   :xp-progress-version growth/flat9-progress-version
   :hp 100 :hp-atual 100 :ataque 50 :defesa 50
   :atq-esp 50 :def-esp 50 :veloc 50 :amizade 70})

;;; --- Tabelas PokéAPI (migração) ---

(deftest six-curves-loaded-and-monotonic
  (doseq [rate growth/curve-keys]
    (testing (str rate)
      (is (= 0 (growth/total-xp-for-level rate 1)))
      (is (pos? (growth/total-xp-for-level rate 100)))
      (doseq [n (range 1 100)]
        (is (<= (growth/total-xp-for-level rate n)
                (growth/total-xp-for-level rate (inc n))))))))

(deftest official-totals-match-pokeapi-snapshot
  (is (= 1000000 (growth/total-xp-for-level :medium-fast 100)))
  (is (= 800000 (growth/total-xp-for-level :fast 100)))
  (is (= 1250000 (growth/total-xp-for-level :slow 100)))
  (is (= 1059860 (growth/total-xp-for-level :medium-slow 100)))
  (is (= 600000 (growth/total-xp-for-level :erratic 100)))
  (is (= 1640000 (growth/total-xp-for-level :fluctuating 100))))

(deftest species-resolve-to-official-curves
  (is (= :medium-slow (growth/growth-rate-for-species "bulbasaur")))
  (is (= :medium-fast (growth/growth-rate-for-species "pikachu")))
  (is (= :slow (growth/growth-rate-for-species "magikarp")))
  (is (= :fast (growth/growth-rate-for-species "clefairy")))
  (is (= :erratic (growth/growth-rate-for-species "nincada")))
  (is (= :fluctuating (growth/growth-rate-for-species "wailmer")))
  (is (= :fast (growth/growth-rate-for-species "wishiwashi-solo"))))

;;; --- Grants flat-9 ---

(deftest flat9-grant-matrix-0-1-3-8-9-10-18
  (doseq [[resid ganho nivel-esp resid-esp subidas]
          [[0 0 20 0 0]
           [0 1 20 1 0]
           [0 3 20 3 0]
           [6 3 21 0 1]   ;; exemplo do plano
           [8 3 21 2 1]   ;; exemplo do plano
           [0 8 20 8 0]
           [0 9 21 0 1]
           [0 10 21 1 1]
           [0 18 22 0 2]
           [8 1 21 0 1]
           [5 10 21 6 1]]]
    (testing (str "resid=" resid " +" ganho)
      (let [r (mon {:nivel 20 :residual resid})
            g (xp/aplicar-xp-pokemon r ganho)]
        (is (= nivel-esp (:nivel-final g)))
        (is (= subidas (:subidas g)))
        (is (= resid-esp (get-in g [:registro :xp-desde-nivel])))
        (is (= growth/flat9-progress-version
               (get-in g [:registro :xp-progress-version])))))))

(deftest flat9-excedente-preservado-multi-level
  (let [g (xp/aplicar-xp-pokemon (mon {:nivel 1 :residual 0}) 27)]
    (is (= 4 (:nivel-final g)))
    (is (= 3 (:subidas g)))
    (is (= 0 (get-in g [:registro :xp-desde-nivel])))))

(deftest flat9-nivel-99-e-100
  (let [to100 (xp/aplicar-xp-pokemon (mon {:nivel 99 :residual 8}) 3)
        at100 (xp/aplicar-xp-pokemon (mon {:nivel 100 :residual 0}) 50)
        near (xp/aplicar-xp-pokemon (mon {:nivel 99 :residual 0}) 9)]
    (is (= 100 (:nivel-final to100)))
    (is (zero? (get-in to100 [:registro :xp-desde-nivel])))
    (is (= 100 (:nivel-final at100)))
    (is (zero? (:subidas at100)))
    (is (= 100 (:nivel-final near)))
    (is (zero? (get-in near [:registro :xp-desde-nivel])))))

(deftest flat9-activity-win-loss-no-hidden-multipliers-hunt
  (let [base (mon {:nivel 20 :residual 0})
        win (xp/calcular-ganho-pokemon base xp/xp-por-vitoria
                                       {:atividade :batalha})
        loss (xp/calcular-ganho-pokemon base xp/xp-por-derrota)
        hunt (xp/calcular-ganho-pokemon base 3 {:atividade :cacada})]
    (is (= 3 win))
    (is (= 1 loss))
    (is (= 3 hunt) "caçada comum = 3 XP flat, sem (pts/9)×span")
    (let [g (xp/aplicar-xp-atividade base 3 {:atividade :batalha})]
      (is (= 20 (:nivel-final g)))
      (is (= 3 (get-in g [:registro :xp-desde-nivel])))
      (is (zero? (:subidas g))))))

(deftest flat9-tres-vitorias-sobe-um-nivel
  (let [[after n]
        (loop [r (mon {:nivel 24 :residual 0}) n 0]
          (if (or (>= (long (:nivel r)) 25) (> n 6))
            [r n]
            (recur (:registro (xp/aplicar-xp-atividade r xp/xp-por-vitoria))
                   (inc n))))]
    (is (= 25 (:nivel after)))
    (is (= 3 n))))

(deftest flat9-activity-variants-capped
  (let [base (mon {:nivel 30 :residual 0})
        win (xp/calcular-ganho-pokemon base 3 {:atividade :batalha})
        loss (xp/calcular-ganho-pokemon base 1 {:atividade :batalha})
        rare (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "raro"})
        epic (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "epico"})
        leg (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "lendario"})
        myth (xp/calcular-ganho-pokemon base 6 {:atividade :raid :categoria-raid "mitico"})
        gym (xp/calcular-ganho-pokemon base 3 {:atividade :ginasio})
        pvp (xp/calcular-ganho-pokemon base 3 {:atividade :pvp})
        adv-high (xp/calcular-ganho-pokemon base 3 {:nivel-adversario 60})
        adv-low (xp/calcular-ganho-pokemon base 3 {:nivel-adversario 10})]
    (is (= 3 win))
    (is (= 1 loss))
    (is (< loss win))
    (is (<= rare epic leg myth))
    (is (>= gym win))
    (is (>= pvp win))
    (is (>= adv-high win))
    (is (<= adv-low win))
    (is (<= myth xp/pokemon-xp-grant-cap))))

(deftest flat9-wins-to-milestones-same-all-species
  (testing "Todas as espécies: exatamente 3 vitórias por nível"
    (doseq [species ["pikachu" "bulbasaur" "magikarp" "clefairy" "nincada" "wailmer"]]
      (doseq [target [10 20 30 50]]
        (let [wins (loop [r (mon {:species species :nivel 1 :residual 0}) n 0]
                     (cond
                       (>= (long (:nivel r)) (long target)) n
                       (> n 500) n
                       :else
                       (recur (:registro (xp/aplicar-xp-atividade r 3))
                              (inc n))))]
          (is (= (* 3 (dec target)) wins)
              (str species " → L" target)))))))

;;; --- Conversão ---

(deftest convert-growth-v1-preserves-level-fraction
  (let [rate :medium-fast
        nivel 24
        span (growth/level-span rate nivel)
        ;; ~4/9 do span
        resid-v1 (long (Math/floor (* span (/ 4.0 9.0))))
        total (+ (growth/total-xp-for-level rate nivel) resid-v1)
        v1 {:species "pikachu" :nivel nivel :xp-total total
            :xp-desde-nivel resid-v1 :xp resid-v1
            :xp-progress-version "growth-v1" :growth-rate rate
            :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1}
        flat (growth/ensure-flat9-v2 v1)]
    (is (= 24 (:nivel flat)))
    (is (= growth/flat9-progress-version (:xp-progress-version flat)))
    (let [expected (min 8 (long (Math/floor (* (/ (double resid-v1) (double span)) 9.0))))]
      (is (= expected (:xp-desde-nivel flat))))
    (is (< (:xp-desde-nivel flat) 9))
    (is (= (:nivel flat) (:nivel (growth/ensure-flat9-v2 flat))) "idempotente")))

(deftest convert-proven-legacy-0-8
  (let [legado {:species "pikachu" :nivel 24 :xp-desde-nivel 4
                :hp 100 :hp-atual 100 :ataque 50 :defesa 50
                :atq-esp 50 :def-esp 50 :veloc 50}
        flat (growth/ensure-flat9-v2 legado)]
    (is (= 24 (:nivel flat)))
    (is (= 4 (:xp-desde-nivel flat)))
    (is (= growth/flat9-progress-version (:xp-progress-version flat)))))

(deftest ambiguous-legacy-not-auto-converted
  (doseq [res [9 10 18 478 8478]]
    (testing (str "residual=" res)
      (let [r {:species "pikachu" :nivel 20 :xp-desde-nivel res :xp res
               :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1}]
        (is (growth/ambiguous-legacy? r))
        (let [c (growth/ensure-flat9-v2 r)]
          (is (nil? (:xp-progress-version c))
              "ambíguo permanece sem versão")
          (is (= 20 (:nivel c)))
          (is (= res (or (:xp-desde-nivel c) (:xp c)))))))))

(deftest ambiguous-grant-is-blocked-without-resetting-progress
  (doseq [residual [9 478 8478]]
    (let [r {:species "Staryu" :nivel 13 :xp residual :xp-desde-nivel residual
             :hp 50 :hp-atual 50 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
             :amizade 70}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"reconciliacao necessaria"
                            (xp/aplicar-xp-atividade r 3 {:atividade :batalha})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (growth/after-evolution r)))
      (is (= residual (:xp-desde-nivel r)))
      (is (= 13 (:nivel r))))))

(deftest hotfix-convert-legacy-residual-never-levels
  "Preserva hotfix #85: convert-legacy-residual (caminho growth-v1) não sobe nível."
  (doseq [res [0 1 4 8 9 10 18 8478]]
    (let [c (growth/convert-legacy-residual
             {:species "pikachu" :nivel 20 :xp-desde-nivel res
              :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})]
      (is (= 20 (:nivel c)))
      (is (= "growth-v1" (:xp-progress-version c)))
      (is (= res (:xp-legacy-residual c)))))
  (let [c (growth/convert-legacy-residual
           {:species "pikachu" :nivel 99 :xp-desde-nivel 9
            :hp 10 :hp-atual 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1})]
    (is (= 99 (:nivel c)))))

(deftest incident-85-repaired-pokemon-regression
  "Blastoise 74, Tyranitar 90, Crobat 5, Amaura 4 — conversão não altera nível.
   Staryu L13 residual anômalo — grant não pula nível."
  (doseq [[sp niv res]
          [["blastoise" 74 8478]
           ["tyranitar" 90 12000]
           ["crobat" 5 40]
           ["amaura" 4 30]
           ["Staryu" 13 478]]]
    (testing sp
      (let [r {:species sp :nivel niv :xp-desde-nivel res :xp res
               :hp 100 :hp-atual 100 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
               :amizade 70}
            ens (growth/ensure-flat9-v2 r)]
        (is (= niv (:nivel ens)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (xp/aplicar-xp-atividade r 3))
            "Ambiguo nao pode alterar XP nem nivel antes de reconciliacao")))))

(deftest evolution-keeps-flat9-progress
  (let [pre (mon {:species "charmander" :nivel 16 :residual 2})
        evo (growth/after-evolution (assoc pre :species "charmeleon" :nome "Charmeleon"))]
    (is (= 16 (:nivel evo)))
    (is (= 2 (:xp-desde-nivel evo)))
    (is (= growth/flat9-progress-version (:xp-progress-version evo)))))

(deftest unknown-xp-not-invented
  (let [p (xp/progresso-pokemon {:nivel 10})]
    (is (false? (:conhecido? p)))
    (is (nil? (:xp-nivel-atual p))))
  (let [p (xp/progresso-pokemon (mon {:nivel 10 :residual 3}))]
    (is (true? (:conhecido? p)))
    (is (= 3 (:xp-nivel-atual p)))
    (is (= 9 (:xp-proximo-nivel p)))))

(deftest stats-grow-only-on-legitimate-levelup
  (let [base (mon {:nivel 20 :residual 6})
        atk0 (:ataque base)
        no-up (xp/aplicar-xp-pokemon base 1)
        up (xp/aplicar-xp-pokemon base 3)]
    (is (zero? (:subidas no-up)))
    (is (= atk0 (get-in no-up [:registro :ataque])))
    (is (= 1 (:subidas up)))
    (is (= 21 (:nivel-final up)))
    (is (> (get-in up [:registro :ataque]) atk0))))

;;; --- Datomic ---

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

(deftest datomic-grant-persists-flat9-v2
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
      (is (= growth/flat9-progress-version (:xp-progress-version poke)))
      (is (= 0 (:xp-desde-nivel poke)))
      (is (= (growth/flat9-synthetic-total 2 0) (:xp-total poke))))))

(deftest datomic-retry-idempotent-conversion
  (let [client (persistence/client {:system (str "gvic-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/gvic-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "gvic")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "c" :player-id "ash"})
    (collection/adicionar-pokemon!
     s "c" "ash"
     {:id-pokemon "v1" :species "pikachu" :nome "Pikachu"
      :nivel 12 :xp-desde-nivel 2
      :hp 35 :hp-atual 35 :ataque 55 :defesa 40
      :atq-esp 50 :def-esp 50 :veloc 90 :tipos ["electric"] :amizade 70
      :golpes [{:slug "thunder-shock" :nome-exibicao "Thunder Shock" :tipo "electric"
                :poder 40 :classe :especial}]}
     20 nil)
    (collection/ganhar-xp-pokemon! s "c" "ash" 0 1)
    (let [poke (first (ports/list-collection s "c" "ash" "a"))
          again (xp/aplicar-xp-pokemon poke 0)]
      (is (= 12 (:nivel poke)))
      (is (= growth/flat9-progress-version (:xp-progress-version poke)))
      (is (= 3 (:xp-desde-nivel poke)))
      (is (= (:xp-total poke) (get-in again [:registro :xp-total]))
          "retry com 0 XP não duplica"))))

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
    (let [p1 (first (ports/list-collection s "c" "ash" "a"))]
      (is (= 7 (:xp-legacy-nivel p1)))
      (is (= 4 (:xp-legacy-residual p1)))
      (collection/ganhar-xp-pokemon! s "c" "ash" 0 8)
      (let [p2 (first (ports/list-collection s "c" "ash" "a"))]
        (is (= 7 (:xp-legacy-nivel p2)) "grant não sobrescreve nível legado")
        (is (= 4 (:xp-legacy-residual p2)))
        (is (= 8 (:nivel p2)))
        (is (= 4 (:xp-desde-nivel p2)))))))
