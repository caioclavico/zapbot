(ns pokemon.tests.level-evolution-a6-test
  "Auditoria A6: evolução por nível (catálogo) + progresso XP + CSV."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.level-evolution :as level-evo]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.util Base64]
           [java.nio.charset StandardCharsets]))

(defn- fresh []
  (let [client (persistence/client {:system (str "a6-levevo-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a6-levevo-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "levevo")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    s))

(deftest progresso-pokemon-nao-inventa-zero
  (is (false? (:conhecido? (xp/progresso-pokemon {:nivel 10}))))
  (is (nil? (:xp-nivel-atual (xp/progresso-pokemon {:nivel 10}))))
  (let [p (xp/progresso-pokemon {:nivel 10 :xp-desde-nivel 3})]
    (is (true? (:conhecido? p)))
    (is (= 3 (:xp-nivel-atual p)))
    (is (= 9 (:xp-proximo-nivel p))))
  (let [m (xp/progresso-pokemon {:nivel 100 :xp-desde-nivel 0})]
    (is (true? (:nivel-maximo? m)))
    (is (nil? (:xp-proximo-nivel m)))))

(deftest proxima-evolucao-catalogo-lineares
  (testing "abaixo do nível"
    (is (= "Charmeleon" (:nome (level-evo/proxima {:species "charmander" :nivel 10}))))
    (is (= 16 (:nivel-min (level-evo/proxima {:species "charmander" :nivel 10}))))
    (is (nil? (level-evo/prepare {:species "charmander" :nome "Charmander" :nivel 15
                                  :hp 39 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
                                  :tipos ["fire"] :golpes []}))))
  (testing "exato e acima"
    (is (some? (level-evo/prepare {:species "charmander" :nome "Charmander" :nivel 16
                                   :hp 39 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
                                   :tipos ["fire"] :golpes [] :amizade 70})))
    (is (some? (level-evo/prepare {:species "charmeleon" :nome "Charmeleon" :nivel 40
                                   :hp 58 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80
                                   :tipos ["fire"] :golpes [] :amizade 70}))))
  (testing "sem evolução / pedra / ramificada"
    (is (nil? (level-evo/proxima {:species "charizard" :nivel 50})))
    (is (nil? (level-evo/proxima {:species "pikachu" :nivel 50})))
    (is (nil? (level-evo/proxima {:species "eevee" :nivel 50}))))
  (testing "duas etapas — magikarp→gyarados"
    (is (= "Gyarados" (:nome (level-evo/proxima {:species "magikarp" :nivel 20}))))
    (is (nil? (level-evo/proxima {:species "gyarados" :nivel 50})))))

(deftest aplicar-evolucao-preserva-identidade-e-xp
  (let [reg {:id-pokemon "keep-me" :species "charmander" :nome "Charmander"
             :nivel 16 :xp-desde-nivel 2 :shiny true :amizade 80
             :hp 39 :hp-atual 30 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
             :tipos ["fire"]
             :golpes [{:slug "scratch" :nome-exibicao "Scratch" :tipo "normal"
                       :poder 40 :classe :fisico}]}
        prepared (level-evo/prepare reg)
        out (level-evo/apply-prepared reg prepared)]
    (is (= "keep-me" (:id-pokemon out)))
    (is (= 16 (:nivel out)))
    (is (= 2 (:xp-desde-nivel out)))
    (is (true? (:shiny out)))
    (is (= "charmeleon" (:species out)))
    (is (= "Charmeleon" (:nome out)))
    ;; garantir-ataque-do-tipo normaliza/acrescenta golpe do tipo (fire → Brasas)
    (is (seq (:golpes out)))
    (is (some (fn [g]
                (let [slug (str (or (:slug g) (get g "slug")))]
                  (= "brasas" slug)))
              (:golpes out)))))

(deftest grant-xp-evolui-por-nivel-e-idempotente-em-especie
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     {:id-pokemon "evo-1" :species "charmander" :nome "Charmander"
      :nivel 15 :xp-desde-nivel 8 :hp 39 :hp-atual 39
      :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
      :tipos ["fire"] :amizade 70
      :golpes [{:slug "scratch" :nome-exibicao "Scratch" :tipo "normal"
                :poder 40 :classe :fisico}]}
     20 nil)
    ;; +1 XP → sobe para 16 e deve evoluir no mesmo TX
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 1)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= "evo-1" (:id-pokemon after)))
      (is (= 16 (:nivel after)))
      (is (= "charmeleon" (str/lower-case (str (:species after)))))
      (is (str/includes? (str/lower-case (str (:nome after))) "charmeleon")))))

(deftest multiplos-niveis-de-uma-vez
  (let [reg {:species "magikarp" :nome "Magikarp" :nivel 18 :xp-desde-nivel 0
             :hp 20 :hp-atual 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
             :tipos ["water"] :amizade 70 :golpes []}
        {:keys [registro subidas]} (xp/aplicar-xp-pokemon reg 20)]
    (is (>= subidas 2))
    (is (>= (:nivel registro) 20))
    (is (some? (level-evo/prepare registro)))))

(deftest team-csv-xp-e-formula-injection
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     {:id-pokemon "csv-1" :species "charmeleon" :nome "=CMD()"
      :nivel 34 :xp-desde-nivel 5 :hp 80 :hp-atual 80
      :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80
      :tipos ["fire"] :raridade "incomum"}
     20 nil)
    (let [csv (reads/team-csv s "chat" "ash" "a")
          body (String. (.decode (Base64/getDecoder) ^String (:base64 csv)) StandardCharsets/UTF_8)]
      (is (= "odisseu-mt-pokemon.csv" (:filename csv)))
      (is (str/includes? body "pokemon_id,numero_pokedex,nome"))
      (is (str/includes? body "xp_nivel_atual"))
      (is (str/includes? body "proxima_evolucao"))
      (is (str/includes? body "Charizard"))
      (is (str/includes? body "'=CMD()"))
      (is (re-find #"csv-1,.*,34,.*,5,9," body)))))

(deftest dto-colecao-expoe-xp-e-evolucao
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     {:id-pokemon "dto-1" :species "ivysaur" :nome "Ivysaur"
      :nivel 30 :xp-desde-nivel 4 :hp 60 :hp-atual 60
      :ataque 62 :defesa 63 :atq-esp 80 :def-esp 80 :veloc 60
      :tipos ["grass" "poison"]}
     20 nil)
    (let [col (reads/collection s "chat" "ash" "a")
          p (first (:collection col))]
      (is (true? (:xp-conhecido? p)))
      (is (= 4 (:xp-nivel-atual p)))
      (is (= 9 (:xp-proximo-nivel p)))
      (is (= "Venusaur" (:proxima-evolucao p)))
      (is (= 32 (:nivel-proxima-evolucao p))))))
