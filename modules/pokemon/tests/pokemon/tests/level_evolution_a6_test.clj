(ns pokemon.tests.level-evolution-a6-test
  "Auditoria A6: evolução por nível (catálogo) + progresso XP + CSV + TX Datomic."
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

(defn- base-fire
  [m]
  (merge {:hp 39 :hp-atual 39 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
          :tipos ["fire"] :amizade 70
          :golpes [{:slug "scratch" :nome-exibicao "Scratch" :tipo "normal"
                    :poder 40 :classe :fisico}]}
         m))

(deftest xp-por-nivel-canonicamente-9
  (is (= 9 xp/xp-por-nivel))
  (is (= 100 xp/nivel-maximo))
  (is (= 1.03 xp/fator-crescimento-por-nivel))
  (let [{:keys [registro subidas]}
        (xp/aplicar-xp-pokemon
         {:nome "X" :nivel 1 :xp-desde-nivel 0 :hp 100 :hp-atual 100
          :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50 :amizade 70}
         9)]
    (is (= 1 subidas))
    (is (= 2 (:nivel registro)))
    (is (= 0 (:xp-desde-nivel registro)))
    "xp-desde-nivel é progresso parcial no nível, não total acumulado"))

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

(deftest leitura-progresso-nao-muta-datomic
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "ro-1" :species "charmander" :nome "Charmander"
                 :nivel 10 :xp-desde-nivel 2})
     20 nil)
    (let [antes (first (ports/list-collection s "chat" "ash" "a"))
          _ (reads/collection s "chat" "ash" "a")
          _ (xp/progresso-pokemon antes)
          depois (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= (:xp-desde-nivel antes) (:xp-desde-nivel depois)))
      (is (= (:nivel antes) (:nivel depois)))
      (is (= (:species antes) (:species depois))))))

(deftest proxima-evolucao-catalogo-lineares
  (testing "abaixo do nível"
    (is (= "Charmeleon" (:nome (level-evo/proxima {:species "charmander" :nivel 10}))))
    (is (= 16 (:nivel-min (level-evo/proxima {:species "charmander" :nivel 10}))))
    (is (nil? (level-evo/prepare (base-fire {:species "charmander" :nome "Charmander" :nivel 15})))))
  (testing "exato e acima"
    (is (some? (level-evo/prepare (base-fire {:species "charmander" :nome "Charmander" :nivel 16}))))
    (is (some? (level-evo/prepare (base-fire {:species "charmeleon" :nome "Charmeleon" :nivel 40
                                              :hp 58 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80})))))
  (testing "especiais protegidas — pedra / ramificada / troca / amizade"
    (is (nil? (level-evo/proxima {:species "charizard" :nivel 50})))
    (is (nil? (level-evo/proxima {:species "pikachu" :nivel 50})) "pedra")
    (is (nil? (level-evo/proxima {:species "eevee" :nivel 50})) "ramificada")
    (is (nil? (level-evo/proxima {:species "kadabra" :nivel 50})) "troca")
    (is (nil? (level-evo/proxima {:species "golbat" :nivel 50})) "amizade")
    (is (nil? (level-evo/prepare (base-fire {:species "pikachu" :nome "Pikachu" :nivel 99}))))
    (is (nil? (level-evo/prepare {:species "eevee" :nome "Eevee" :nivel 99
                                  :hp 55 :ataque 55 :defesa 50 :atq-esp 45 :def-esp 65 :veloc 55
                                  :tipos ["normal"] :amizade 255 :golpes []}))))
  (testing "duas etapas — magikarp→gyarados"
    (is (= "Gyarados" (:nome (level-evo/proxima {:species "magikarp" :nivel 20}))))
    (is (nil? (level-evo/proxima {:species "gyarados" :nivel 50})))))

(deftest aplicar-evolucao-preserva-identidade-e-xp
  (let [reg (base-fire {:id-pokemon "keep-me" :species "charmander" :nome "Charmander"
                        :nivel 16 :xp-desde-nivel 2 :shiny true :amizade 80})
        prepared (level-evo/prepare reg)
        out (level-evo/apply-prepared reg prepared)]
    (is (= "keep-me" (:id-pokemon out)))
    (is (= 16 (:nivel out)))
    (is (= 2 (:xp-desde-nivel out)))
    (is (true? (:shiny out)))
    (is (= "charmeleon" (:species out)))
    (is (= "Charmeleon" (:nome out)))
    (is (seq (:golpes out)))
    (is (some (fn [g]
                (let [slug (str (or (:slug g) (get g "slug")))]
                  (= "brasas" slug)))
              (:golpes out)))))

(deftest grant-xp-evolui-por-nivel-datomic
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "evo-1" :species "charmander" :nome "Charmander"
                 :nivel 15 :xp-desde-nivel 8})
     20 nil)
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 1)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= "evo-1" (:id-pokemon after)))
      (is (= 16 (:nivel after)))
      (is (= "charmeleon" (str/lower-case (str (:species after)))))
      (is (str/includes? (str/lower-case (str (:nome after))) "charmeleon"))
      (is (= 0 (long (:collection-index after 0))) "índice/ativo preservados"))))

(deftest ultrapassou-nivel-evolui-no-proximo-xp
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "late-1" :species "charmeleon" :nome "Charmeleon"
                 :nivel 40 :xp-desde-nivel 0
                 :hp 58 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80})
     20 nil)
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 1)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= "late-1" (:id-pokemon after)))
      (is (= 40 (:nivel after)))
      (is (= "charizard" (str/lower-case (str (:species after))))))))

(deftest multiplos-niveis-uma-etapa-por-evento
  "Paridade com legado: um evento de XP → no máximo uma evolução de estágio."
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "multi-1" :species "charmander" :nome "Charmander"
                 :nivel 15 :xp-desde-nivel 0})
     20 nil)
    ;; 21*9 = 189 XP → sobe muitos níveis (≥36) mas só Charmander→Charmeleon
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 189)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= "multi-1" (:id-pokemon after)))
      (is (>= (:nivel after) 36))
      (is (= "charmeleon" (str/lower-case (str (:species after))))
          "não salta Charmander→Charizard no mesmo evento")
      ;; segundo evento no mesmo nível elegível → Charizard
      (collection/ganhar-xp-pokemon! s "chat" "ash" 0 1)
      (let [final (first (ports/list-collection s "chat" "ash" "a"))]
        (is (= "multi-1" (:id-pokemon final)))
        (is (= "charizard" (str/lower-case (str (:species final)))))))))

(deftest especiais-nao-auto-evoluem-com-xp
  (let [s (fresh)]
    (doseq [[id species nome]
            [["pika" "pikachu" "Pikachu"]
             ["eev" "eevee" "Eevee"]
             ["kad" "kadabra" "Kadabra"]]]
      (collection/adicionar-pokemon!
       s "chat" "ash"
       {:id-pokemon id :species species :nome nome
        :nivel 50 :xp-desde-nivel 8 :hp 60 :hp-atual 60
        :ataque 55 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 90
        :tipos ["normal"] :amizade 255 :golpes []}
       20 nil))
    (dotimes [i 3]
      (collection/ganhar-xp-pokemon! s "chat" "ash" i 1))
    (let [col (ports/list-collection s "chat" "ash" "a")
          by-id (into {} (map (juxt :id-pokemon identity) col))]
      (is (= "pikachu" (str/lower-case (str (:species (by-id "pika"))))))
      (is (= "eevee" (str/lower-case (str (:species (by-id "eev"))))))
      (is (= "kadabra" (str/lower-case (str (:species (by-id "kad"))))))
      ;; 8+1 XP → sobe para 51, mas espécie permanece (pedra/ramificada/troca)
      (is (= 51 (:nivel (by-id "pika"))))
      (is (= 0 (:xp-desde-nivel (by-id "pika"))))
      (is (= 51 (:nivel (by-id "eev"))))
      (is (= 51 (:nivel (by-id "kad")))))))

(deftest nivel-maximo-sem-divisao-por-zero
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "max-1" :species "charizard" :nome "Charizard"
                 :nivel 100 :xp-desde-nivel 0
                 :hp 78 :ataque 84 :defesa 78 :atq-esp 109 :def-esp 85 :veloc 100})
     20 nil)
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 50)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))
          prog (xp/progresso-pokemon after)
          dto (first (:collection (reads/collection s "chat" "ash" "a")))]
      (is (= 100 (:nivel after)))
      (is (= "charizard" (str/lower-case (str (:species after)))))
      (is (true? (:nivel-maximo? prog)))
      (is (nil? (:progresso-percentual prog)))
      (is (true? (:nivel-maximo? dto))))))

(deftest magikarp-multiplos-niveis-evolui
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     {:id-pokemon "karp" :species "magikarp" :nome "Magikarp"
      :nivel 18 :xp-desde-nivel 0 :hp 20 :hp-atual 20
      :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
      :tipos ["water"] :amizade 70 :golpes []}
     20 nil)
    (collection/ganhar-xp-pokemon! s "chat" "ash" 0 20)
    (let [after (first (ports/list-collection s "chat" "ash" "a"))]
      (is (= "karp" (:id-pokemon after)))
      (is (>= (:nivel after) 20))
      (is (= "gyarados" (str/lower-case (str (:species after))))))))

(deftest team-csv-xp-e-formula-injection
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "csv-1" :species "charmeleon" :nome "=CMD()"
                 :nivel 34 :xp-desde-nivel 5
                 :hp 80 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80
                 :raridade "incomum"})
     20 nil)
    (let [csv (reads/team-csv s "chat" "ash" "a")
          body (String. (.decode (Base64/getDecoder) ^String (:base64 csv)) StandardCharsets/UTF_8)]
      (is (= "odisseu-time-pokemon.csv" (:filename csv)))
      (is (.startsWith ^String (:base64 csv) "77u/") "UTF-8 BOM")
      (is (str/includes? body "pokemon_id,numero_pokedex,nome"))
      (is (str/includes? body "xp_nivel_atual"))
      (is (str/includes? body "proxima_evolucao"))
      (is (str/includes? body "Charizard"))
      (is (str/includes? body "'=CMD()"))
      (is (re-find #"csv-1,.*,34,.*,5,9," body))
      (is (not (str/includes? body "chat")))
      (is (not (str/includes? body "@g.us"))))))

(deftest dto-colecao-expoe-xp-e-evolucao
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "dto-1" :species "ivysaur" :nome "Ivysaur"
                 :nivel 30 :xp-desde-nivel 4
                 :hp 60 :ataque 62 :defesa 63 :atq-esp 80 :def-esp 80 :veloc 60
                 :tipos ["grass" "poison"]})
     20 nil)
    (let [col (reads/collection s "chat" "ash" "a")
          p (first (:collection col))]
      (is (true? (:xp-conhecido? p)))
      (is (= 4 (:xp-nivel-atual p)))
      (is (= 9 (:xp-proximo-nivel p)))
      (is (= "Venusaur" (:proxima-evolucao p)))
      (is (= 32 (:nivel-proxima-evolucao p))))))

(deftest apresentacao-card-txt-csv-mesma-fonte
  (let [s (fresh)]
    (collection/adicionar-pokemon!
     s "chat" "ash"
     (base-fire {:id-pokemon "sync-1" :species "charmeleon" :nome "Charmeleon"
                 :nivel 34 :xp-desde-nivel 5
                 :hp 80 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80})
     20 nil)
    (let [col (reads/collection s "chat" "ash" "a")
          p (first (:collection col))
          csv (reads/team-csv s "chat" "ash" "a")
          body (String. (.decode (Base64/getDecoder) ^String (:base64 csv)) StandardCharsets/UTF_8)]
      (is (= "Charmeleon" (:nome p)))
      (is (= 34 (:nivel p)))
      (is (= 5 (:xp-nivel-atual p)))
      (is (= 9 (:xp-proximo-nivel p)))
      (is (= "Charizard" (:proxima-evolucao p)))
      (is (= 36 (:nivel-proxima-evolucao p)))
      (is (str/includes? body "sync-1"))
      (is (str/includes? body "Charmeleon"))
      (is (str/includes? body ",5,9,"))
      (is (str/includes? body "Charizard")))))
