(ns pokemon.tests.species-hp-a6-test
  "Homologação local species-hp-v1 — Datomic descartável + fórmulas puras."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.evolution :as evolution]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.level-evolution :as level-evo]
            [pokemon.domain.onboarding :as onboarding]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-form-aliases :as form-aliases]
            [pokemon.domain.species-hp :as species-hp]
            [pokemon.domain.species-hp-freeze :as freeze]
            [pokemon.domain.species-hp-migrate :as migrate]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- fresh []
  (let [client (persistence/client {:system (str "a6-sph-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a6-sph-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "sph")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    s))

(deftest formula-pura-deterministica
  (testing "base baixa / alta / clamp"
    (is (= 48 (species-hp/hp-max 20 1)))   ; Magikarp ≈ 0.60 * 80
    (is (= 80 (species-hp/hp-max 80 1)))
    (is (= 176 (species-hp/hp-max 250 1))) ; Chansey clamp 2.20 * 80
    (is (= (species-hp/hp-max 160 20) (species-hp/hp-max 160 20))))
  (testing "progressão por nível"
    (let [n1 (species-hp/hp-max 78 1)
          n10 (species-hp/hp-max 78 10)
          n50 (species-hp/hp-max 78 50)]
      (is (< n1 n10 n50))))
  (testing "dados ausentes falham fechado"
    (is (thrown? Exception (species-hp/hp-max nil 5)))
    (is (thrown? Exception (species-hp/hp-max 0 5)))
    (is (thrown? Exception (species-hp/hp-max -3 5)))))

(deftest cura-e-dano-bounds
  (is (= 0 (species-hp/dano-aplicado 5 9)))
  (is (= 10 (species-hp/cura-aplicada 8 10 5)))
  (is (= 3 (species-hp/cura-aplicada 0 10 3))))

(deftest conversao-proporcional-e-faint
  (let [c (species-hp/converter-proporcional 40 100 160)]
    (is (= 160 (:hp c)))
    (is (= 64 (:hp-atual c))))
  (let [f (species-hp/converter-proporcional 0 100 160)]
    (is (= 0 (:hp-atual f)))
    (is (true? (:desmaiado? f))))
  (let [full (species-hp/converter-proporcional 100 100 160)]
    (is (= 160 (:hp-atual full)))))

(deftest conversao-idempotente
  (let [reg {:species "snorlax" :nivel 20 :hp 207 :hp-atual 100
             :hp-base 160}
        a (species-hp/converter-registro reg)
        b (species-hp/converter-registro a)]
    (is (= species-hp/formula-version (:hp-formula-version a)))
    (is (= a b))))

(deftest overrides-e-catalogo-sem-pokeapi
  (is (= 250 (species-base/base-hp "chansey")))
  (is (= 20 (species-base/base-hp "magikarp")))
  (is (= 78 (species-base/base-hp "charizard")))
  (is (true? (species-base/base-hp-known? "wartortle")))
  (is (thrown? Exception (species-base/base-hp "zzz-nao-existe-xyz"))))

(deftest form-aliases-ensaio-quarantine-20261108
  "Os 16 identificadores (22 entidades) do ensaio — aliases explícitos, sem strip."
  (let [forms ["wishiwashi-solo" "pyroar-male" "gourgeist-average" "tatsugiri-curly"
               "frillish-male" "mimikyu-disguised" "indeedee-male" "enamorus-incarnate"
               "jellicent-male" "lycanroc-midday" "zygarde-50" "pumpkaboo-average"
               "darmanitan-standard" "deoxys-normal" "meowstic-male" "minior-red-meteor"]]
    (doseq [f forms]
      (is (true? (species-base/base-hp-known? f)) f)
      (is (pos? (species-base/base-hp f)) f)
      (let [p (migrate/plano-seguro {:species f :nivel 5 :hp 80 :hp-atual 40})]
        (is (#{:convert :noop} (:status p)) (str f " status=" (:status p)))
        (is (not= :quarantine (:status p)) f))))
  (testing "formas com HP distinto NÃO aliasam ao canônico"
    (is (= 70 (species-base/base-hp "indeedee-female")))
    (is (= 54 (species-base/base-hp "zygarde-10")))
    (is (= 216 (species-base/base-hp "zygarde-complete")))
    (is (= 55 (species-base/base-hp "gourgeist-small")))
    (is (= 44 (species-base/base-hp "pumpkaboo-small")))
    (is (not= (species-base/base-hp "indeedee-male")
              (species-base/base-hp "indeedee-female"))))
  (testing "wishiwashi/pyroar/gourgeist-average resolvem"
    (is (= (species-base/base-hp "wishiwashi") (species-base/base-hp "wishiwashi-solo")))
    (is (= (species-base/base-hp "pyroar") (species-base/base-hp "pyroar-male")))
    (is (= (species-base/base-hp "gourgeist") (species-base/base-hp "gourgeist-average")))))
(deftest onboarding-starters-v1
  (let [s (first (filter #(= "bulbasaur" (:species %)) onboarding/starters))]
    (is (= species-hp/formula-version (:hp-formula-version s)))
    (is (= 45 (:hp-base s)))
    (is (= (species-hp/hp-max 45 1) (:hp s)))
    (is (= (:hp s) (:hp-atual s)))))

(deftest evolucao-proporcional-preserva-faint
  (let [reg {:species "charmander" :nome "Charmander" :nivel 16
             :hp 80 :hp-atual 40 :ataque 50 :defesa 40 :atq-esp 50 :def-esp 40 :veloc 50
             :tipos ["fire"] :amizade 70
             :golpes [{:slug "ember" :nome-exibicao "Brasa" :tipo "fire"
                       :poder 40 :classe :especial :precisao 100}]}
        prep (level-evo/prepare reg)
        out (level-evo/apply-prepared reg prep)]
    (is (= "charmeleon" (:species out)))
    (is (= species-hp/formula-version (:hp-formula-version out)))
    (is (= 58 (:hp-base out)))
    (is (= (species-hp/hp-max 58 16) (:hp out)))
    (is (pos? (:hp-atual out)))
    (is (< (:hp-atual out) (:hp out))))
  (let [faint {:species "charmander" :nome "Charmander" :nivel 16
               :hp 80 :hp-atual 0 :ataque 50 :defesa 40 :atq-esp 50 :def-esp 40 :veloc 50
               :tipos ["fire"] :amizade 70
               :golpes [{:slug "ember" :nome-exibicao "Brasa" :tipo "fire"
                         :poder 40 :classe :especial :precisao 100}]}
        out (level-evo/apply-prepared faint (level-evo/prepare faint))]
    (is (zero? (:hp-atual out)))))

(deftest level-up-v1-recalcula
  (let [reg {:nome "X" :species "charizard" :nivel 10 :xp-desde-nivel 0
             :hp (species-hp/hp-max 78 10) :hp-atual (species-hp/hp-max 78 10)
             :hp-base 78 :hp-formula-version species-hp/formula-version
             :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50 :amizade 70}
        ;; growth-v2 L10→11
        need (pokemon.domain.growth/level-span-simple 10)
        {:keys [registro subidas]} (xp/aplicar-xp-pokemon reg need)]
    (is (= 1 subidas))
    (is (= 11 (:nivel registro)))
    (is (= (species-hp/hp-max 78 11) (:hp registro)))
    (is (= (:hp registro) (:hp-atual registro)))))

(deftest leitura-nao-converte
  (let [s (fresh)
        legado {:id-pokemon "leg-1" :species "eevee" :nome "Eevee"
                :nivel 10 :hp 85 :hp-atual 40
                :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
                :tipos ["normal"] :amizade 70
                :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                          :poder 40 :classe :fisico :precisao 100}]}
        _ (collection/adicionar-pokemon! s "chat" "ash" legado 40 nil)
        lido (first (ports/list-collection s "chat" "ash" "a"))]
    (is (= 85 (:hp lido)))
    (is (= 40 (:hp-atual lido)))
    (is (nil? (:hp-formula-version lido)))))

(deftest persistencia-v1-roundtrip
  (let [s (fresh)
        hp (species-hp/hp-max 160 15)
        half (quot hp 2)
        mon {:id-pokemon "v1-1" :species "snorlax" :nome "Snorlax"
             :nivel 15 :hp hp :hp-atual half
             :hp-base 160 :hp-formula-version species-hp/formula-version
             :ataque 80 :defesa 60 :atq-esp 60 :def-esp 60 :veloc 40
             :tipos ["normal"] :amizade 70
             :golpes [{:slug "body-slam" :nome-exibicao "Body Slam" :tipo "normal"
                       :poder 85 :classe :fisico :precisao 100}]}
        _ (collection/adicionar-pokemon! s "chat" "ash" mon half nil)
        lido (first (ports/list-collection s "chat" "ash" "a"))]
    (is (= species-hp/formula-version (:hp-formula-version lido)))
    (is (= 160 (:hp-base lido)))
    (is (= hp (:hp lido)))
    (is (= half (:hp-atual lido)))))
(deftest migrate-plano-idempotente
  (let [reg {:species "wartortle" :nivel 15 :hp 101 :hp-atual 50}
        p1 (migrate/plano-registro reg)
        p2 (migrate/plano-registro (:registro p1))]
    (is (= :convert (:status p1)))
    (is (= :noop (:status p2)))
    (is (= species-hp/formula-version (get-in p1 [:depois :hp-formula-version])))))

(deftest raid-boss-hp-inalterado
  "Chefes de raid NÃO usam species-hp-v1 — permanecem em Proposta A (320/jogador)."
  (is (= 320 raid/hp-por-jogador))
  (let [base (raid/nova-raid {:raid-id "r-hp" :chat-id "c" :ginasio "pedra"
                              :nome-ginasio "Pedra" :nivel-ginasio 10 :liga "bronze"
                              :chefe {:nome "Boss" :tipos ["rock"] :defesa 80 :def-esp 80
                                      :raridade "raro" :fator-dificuldade 1.0}
                              :agora 1 :criador "ash"})
        r (raid/entrar base
                       {:player-id "ash" :nome "Ash" :save-id "a"
                        :fence-generation 1
                        :party [{:pokemon {:nome "A" :hp 100 :hp-atual 100
                                           :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40
                                           :veloc 50 :tipos ["normal"] :nivel 20
                                           :golpes [{:nome-exibicao "Hit" :tipo "normal"
                                                     :poder 80 :classe :fisico}]}
                                 :collection-index 0}]}
                       1)
        combate (raid/iniciar r "ash" 2)]
    (is (= 320 (:hp-max combate)))
    (is (= 320 (:hp-chefe combate)))))

(deftest jvm-canoniza-hp-candidato-node-suavizado
  "DTO Node com HP suavizado + hp-base oficial → JVM aplica species-hp-v1."
  (let [node-dto {:nome "Snorlax" :species "snorlax" :tipos ["normal"]
                  :hp 118 :hp-base 160 :ataque 100 :defesa 70
                  :atq-esp 70 :def-esp 70 :veloc 40 :nivel 1
                  :golpes [{:slug "tackle" :poder 40 :tipo "normal" :classe "fisico"}]}
        out (hunt/canonizar-hp-candidato node-dto)]
    (is (= 160 (:hp-base out)))
    (is (= species-hp/formula-version (:hp-formula-version out)))
    (is (= (species-hp/hp-max 160 1) (:hp out)))
    (is (= (:hp out) (:hp-atual out)))
    (is (= 100 (:ataque out)) "atk/def do Node preservados")))

(deftest jvm-canoniza-hp-via-catalogo-sem-campo-node
  "Sem hp-base no DTO, adapter enriquece pelo catálogo antes de canonizar."
  (let [dto {:nome "Gengar" :species "gengar" :tipos ["ghost" "poison"]
             :hp 68 :ataque 70 :defesa 68 :atq-esp 90 :def-esp 80 :veloc 90
             :nivel 10 :golpes [{:slug "shadow-ball" :poder 80 :tipo "ghost"
                                 :classe "especial"}]}
        base (species-base/base-hp "gengar")
        out (hunt/canonizar-hp-candidato (assoc dto :hp-base base))]
    (is (= 60 base))
    (is (= 60 (:hp-base out)))
    (is (= (species-hp/hp-max 60 10) (:hp out)))))

(deftest evo-stone-node-dto-canonizado-na-jvm
  "Evolução por pedra: DTO Node com HP escalado legado é sobrescrito na JVM."
  (let [reg {:nome "Pikachu" :species "pikachu" :nivel 20
             :hp 80 :hp-atual 40 :ataque 50 :defesa 40
             :atq-esp 50 :def-esp 40 :veloc 90 :tipos ["electric"]
             :golpes [{:slug "thunder-shock" :tipo "electric" :poder 40 :classe "especial"}]}
        ;; Node enviaria HP suavizado×1.03^19 sem formula-version.
        dados {:nome-novo "Raichu" :tipos ["electric"] :habilidade "static"
               :hp 200 :ataque 90 :defesa 55 :atq-esp 90 :def-esp 80 :veloc 110
               :hp-base 60}
        out (evolution/aplicar-evolucao reg dados)]
    (is (= (species-hp/hp-max 60 20) (:hp out)))
    (is (= species-hp/formula-version (:hp-formula-version out)))
    (is (= 60 (:hp-base out)))
    (is (pos? (:hp-atual out)))
    (is (< (:hp-atual out) (:hp out)))
    (is (= 90 (:ataque out)))))

(deftest clamp-chansey-blissey-mesma-faixa-defesa-diferencia
  "Clamp 2.20 iguala HP max; defesa suavizada ainda diferencia Blissey."
  (doseq [nv [5 10 20 30 40 50]]
    (is (= (species-hp/hp-max 250 nv) (species-hp/hp-max 255 nv))))
  (let [def-c (hunt/suavizar-stat 5)
        def-b (hunt/suavizar-stat 10)
        hp50 (species-hp/hp-max 250 50)]
    (is (< def-c def-b))
    (is (< (* hp50 def-c) (* hp50 def-b)))))
(deftest form-aliases-explicito-sem-heuristica
  (is (= "wishiwashi" (form-aliases/resolve-catalog-species "wishiwashi-solo")))
  (is (= "gourgeist-small" (form-aliases/resolve-catalog-species "gourgeist-small"))
      "forma com HP distinto não resolve para gourgeist canônico")
  (is (nil? (:canonical (form-aliases/lookup "zygarde-complete"))))
  (is (true? (form-aliases/known-form? "minior-red-meteor"))))

(deftest freeze-classification-disjoint
  (is (true? (freeze/assert-disjoint-classification)))
  (is (true? (freeze/hp-mutating-op? :battle-start)))
  (is (true? (freeze/hp-mutating-op? "raid_attack")))
  (is (false? (freeze/allowed-during-freeze? :hunt-start)))
  (is (true? (freeze/allowed-during-freeze? :collection-read)))
  (is (false? (freeze/allowed-during-freeze? :unknown-op-xyz)))
  (is (seq (:steps (freeze/freeze-plan)))))
