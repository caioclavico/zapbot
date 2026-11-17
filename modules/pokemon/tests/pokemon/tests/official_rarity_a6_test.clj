(ns pokemon.tests.official-rarity-a6-test
  "Catálogo oficial + calibrações A/B — sem ativar política global."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.rarity :as rarity]
            [pokemon.domain.raid-boss :as raid-boss]
            [pokemon.domain.gym :as gym]))

(deftest catalogo-oficial-carrega
  (let [cat (rarity/load-catalog!)]
    (is (= "official-rarity-catalog-v1" (:version cat)))
    (is (= 1025 (count (:species cat))))
    (is (pos? (:incomplete-count cat)))
    (is (map? (:by-species cat)))
    (is (some? (rarity/lookup-species "victreebel")))))

(deftest flags-lendario-mitico
  (is (= "lendario" (rarity/classificar "mewtwo" :oficial-a)))
  (is (= "mitico" (rarity/classificar "mew" :oficial-a)))
  (is (= "lendario" (rarity/classificar "mewtwo" :oficial-b)))
  (is (= "mitico" (rarity/classificar "mew" :oficial-b))))

(deftest victreebel-e-linha-bellsprout
  (testing "Victreebel não é épico nas políticas oficiais"
    (is (= "raro" (rarity/classificar "victreebel" :oficial-a)))
    (is (= "raro" (rarity/classificar "victreebel" :oficial-b))))
  (is (= "comum" (rarity/classificar "bellsprout" :oficial-a)))
  (is (= "incomum" (rarity/classificar "weepinbell" :oficial-a))))

(deftest especies-obrigatorias-perfil-a
  (doseq [[sp esperado]
          [["pidgeot" "raro"]
           ["arcanine" "incomum"]
           ["gengar" "raro"]
           ["alakazam" "raro"]
           ["machamp" "raro"]
           ["dragonite" "epico"]
           ["snorlax" "raro"]
           ["chansey" "raro"]
           ["blissey" "raro"]
           ["gyarados" "raro"]
           ["tyranitar" "epico"]
           ["metagross" "epico"]
           ["charizard" "raro"]
           ["blastoise" "raro"]
           ["venusaur" "raro"]
           ["buzzwole" "epico"]]]
    (is (= esperado (rarity/classificar sp :oficial-a))
        (str sp " → " esperado))))

(deftest epico-permanece-especial
  (let [a (rarity/audit-distribution :oficial-a)
        b (rarity/audit-distribution :oficial-b)]
    (is (<= 15 (get-in a [:por-raridade "epico"]) 40)
        "perfil A: épicos especiais mas raidáveis")
    (is (= (get-in a [:por-raridade "epico"])
           (get-in b [:por-raridade "epico"]))
        "A e B compartilham o mesmo gate de épico")
    (is (>= (:elegiveis-raid a) 400))
    (is (>= (:elegiveis-raid b) 400))))

(deftest politica-default-permanece-legado
  (is (= :legado (rarity/politica-ativa)))
  ;; Arcanine no legado é épico (poder); oficial-a é incomum.
  (let [arcanine {:species "arcanine"
                  :hp 90 :ataque 110 :defesa 80 :atq-esp 100 :def-esp 80 :veloc 95
                  :taxa-captura 75
                  :bst-raw 555}]
    ;; Stats suavizados do catálogo raid diferem — usar classify via species lookup path.
    (is (= "incomum" (rarity/classificar "arcanine" :oficial-a)))
    (is (= "epico" (capture/classificar-raridade-legado
                    {:species "arcanine"
                     :hp 90 :ataque 103 :defesa 80 :atq-esp 100 :def-esp 80 :veloc 95
                     :taxa-captura 75})))
    (is (= (capture/classificar-raridade-legado arcanine)
           (capture/classificar-raridade arcanine))
        "sem binding, classificar-raridade == legado")))

(deftest ativacao-oficial-via-binding
  (let [leg (capture/classificar-raridade {:species "arcanine"
                                           :hp 90 :ataque 103 :defesa 80
                                           :atq-esp 100 :def-esp 80 :veloc 95
                                           :taxa-captura 75})
        ofic (rarity/with-politica :oficial-a
                #(capture/classificar-raridade {:species "arcanine"
                                                :hp 90 :ataque 103 :defesa 80
                                                :atq-esp 100 :def-esp 80 :veloc 95
                                                :taxa-captura 75}))]
    (is (= "epico" leg))
    (is (= "incomum" ofic))))

(deftest raid-boss-usa-legado-por-padrao
  (let [row (some #(when (= "victreebel" (:species %)) %)
                  (:species (raid-boss/catalog)))]
    (is (some? row))
    (is (= "raro" (raid-boss/classify-rarity row)))
    (is (= "raro" (capture/classificar-raridade
                   (select-keys row [:hp :ataque :defesa :atq-esp :def-esp :veloc
                                     :taxa-captura :lendario-api? :mitico-api? :paradox-api?]))))))

(deftest ginasio-npc-exibe-raridade-canonica-da-especie
  (doseq [sp (gym/pool-npc "planta")]
    (let [ofic (rarity/classificar sp :oficial-a)
          leg (when-let [row (some #(when (= sp (:species %)) %)
                                   (:species (raid-boss/catalog)))]
                (raid-boss/classify-rarity row))]
      (is (contains? rarity/raridades-validas ofic)
          (str "oficial classifica " sp))
      (when leg
        (is (contains? rarity/raridades-validas leg))))))

(deftest simulacao-distribuicao-deterministica
  (let [a (rarity/audit-distribution :oficial-a)
        b (rarity/audit-distribution :oficial-b)]
    (is (= 1025 (:total a) (:total b)))
    (is (= (get-in a [:por-raridade "lendario"])
           (get-in b [:por-raridade "lendario"])))
    (is (= (get-in a [:por-raridade "mitico"])
           (get-in b [:por-raridade "mitico"])))))
