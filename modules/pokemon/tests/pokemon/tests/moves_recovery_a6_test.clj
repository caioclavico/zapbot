(ns pokemon.tests.moves-recovery-a6-test
  "Recuperação determinística de golpes irrecuperáveis — dry-run + apply local."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.moves-audit :as audit]
            [pokemon.domain.moves-recovery :as recovery]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.species-hp :as species-hp]))

(def ^:private wartortle-base
  {:species "wartortle" :nome "Wartortle" :nivel 16 :tipos ["water"]
   :golpes [{"slug" "tackle" "nome-exibicao" "Tackle" "tipo" "normal"
             "poder" 40 "classe" "fisico"}
            {"slug" "water-gun" "nome-exibicao" "Water Gun" "tipo" "water"
             "poder" 40 "classe" "especial"}]})

(deftest golpe-vazio-marcado-irrecuperavel
  (let [a (audit/analisar-golpe {})]
    (is (true? (:golpe-irrecuperavel? a)))
    (is (contains? (set (:problemas a)) :irrecuperavel))))

(deftest slot-vazio-recupera-stab-deterministico
  (let [reg {:species "wartortle" :nome "Wartortle" :nivel 16 :tipos ["water"]
             :golpes [{"slug" "tackle" "nome-exibicao" "Tackle" "tipo" "normal"
                       "poder" 40 "classe" "fisico"}
                      {}]
             :historico-golpes {"bubble" {"slug" "bubble"
                                          "nome-exibicao" "Bubble"
                                          "tipo" "water" "poder" 40
                                          "classe" "especial"}}}
        plan (recovery/plan-pokemon reg)
        out (recovery/recover-pokemon reg {:apply? true})]
    (is (= 1 (:n-recovered plan)))
    (is (true? (:safe-to-apply? plan)))
    (is (true? (:apply? out)))
    (is (zero? (:n-recovered (recovery/plan-pokemon (:registro out))))
        "reparo idempotente — segundo plano não vê irrecuperáveis")
    (let [g1 (nth (:golpes (:registro out)) 1)
          n (battle/golpe-normalizado g1)]
      (is (seq (:nome-exibicao n)))
      (is (= "water" (:tipo n))))))

(deftest historico-sem-hint-no-slot-usa-evo-stab
  (let [reg {:species "wartortle" :tipos ["water"] :nivel 16
             :historico-golpes {"water-gun" {"slug" "water-gun"
                                             "nome-exibicao" "Water Gun"
                                             "tipo" "water" "poder" 40
                                             "classe" "especial"}}
             :golpes [{}]
             :golpes-candidatos [{:slug "water-gun" :nome-exibicao "Water Gun"
                                  :tipo "water" :poder 40 :classe :especial}]}
        plan (recovery/plan-pokemon reg)
        slot0 (first (:slots plan))]
    (is (= :recovered (:status slot0)))
    (is (= "Water Gun" (get-in slot0 [:golpe :nome-exibicao])))))

(deftest catalogo-externo-restaura-hint
  (let [catalog {"legacy-x" {:slug "legacy-x" :nome-exibicao "Legacy X"
                             :tipo "water" :poder 55 :classe :especial}}
        ;; Slug sozinho normaliza nome=slug — audit pode marcar tipo ausente,
        ;; mas NÃO é irrecuperável (há identificação).
        reg {:species "wartortle" :tipos ["water"]
             :golpes [{"slug" "legacy-x" "classe" "especial"}]}
        a (audit/analisar-golpe (first (:golpes reg)))
        plan (recovery/plan-pokemon reg {:catalog catalog})]
    (is (false? (:golpe-irrecuperavel? a)))
    (is (zero? (:n-recovered plan))
        "só slots irrecuperáveis entram no reparo — catálogo externo é opt-in")))

(deftest slug-sozinho-nao-e-irrecuperavel
  (let [a (audit/analisar-golpe {"slug" "legacy-x"})
        plan (recovery/plan-pokemon
              {:species "wartortle" :tipos ["water"]
               :golpes [{"slug" "legacy-x"}]})]
    (is (false? (:golpe-irrecuperavel? a)))
    (is (zero? (:n-recovered plan)))))

(deftest nao-altera-golpes-validos
  (let [plan (recovery/plan-pokemon wartortle-base)
        out (recovery/recover-pokemon wartortle-base {:apply? true})]
    (is (zero? (:n-recovered plan)))
    (is (= 2 (:n-ok plan)))
    (is (= (:golpes wartortle-base)
           (:golpes (:registro out))))))

(deftest reparo-repetido-idempotente
  (let [reg {:species "squirtle" :tipos ["water"] :nivel 10
             :golpes [{} {"slug" "tackle" "nome-exibicao" "Tackle"
                          "tipo" "normal" "poder" 40 "classe" "fisico"}]}
        once (recovery/recover-pokemon reg {:apply? true})
        twice (recovery/recover-pokemon (:registro once) {:apply? true})]
    (is (= (:golpes (:registro once)) (:golpes (:registro twice))))
    (is (zero? (:n-recovered twice)))))

(deftest audit-collection-agrega-sem-pii
  (let [agg (recovery/audit-collection
             [wartortle-base
              {:species "abra" :tipos ["psychic"] :golpes [{}]}])]
    (is (= 2 (:total-pokemon agg)))
    (is (= 1 (:com-slots-invalidos agg)))
    (is (= 1 (:recuperaveis-seguros agg)))
    (is (zero? (:precisam-aprovacao agg)))))

(deftest regressao-species-hp-raid
  (is (= "species-hp-v1" species-hp/formula-version))
  (is (= 320 raid/hp-por-jogador))
  (is (= 1 raid/max-party)))
