(ns pokemon.tests.xp-idempotency-a6-test
  "Auditoria: caminhos externos de XP usam receipts; ganhar-xp-pokemon!
  não é exposto via HTTP e só serve testes/API interna."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- fresh []
  (let [client (persistence/client {:system (str "a6-xp-idem-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a6-xp-idem-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "xp-idem")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    s))

(deftest bridge-nao-expoe-grant-xp-http
  "Varre o fonte da bridge: nenhum path /v1 concede XP sem receipt próprio."
  (let [src (slurp (io/file (persistence/project-root)
                            "modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj"))]
    (is (not (str/includes? src "ganhar-xp-pokemon!")))
    (is (not (str/includes? src "grant-pokemon-xp!")))
    (is (not (re-find #"/v1/pokemon/xp|/v1/xp/grant|grantPokemonXp" src)))))

(deftest captura-retry-nao-duplica-xp-nem-evolucao
  (let [s (fresh)
        chat "chat" player "ash"]
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-balls" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pokebola" :qty 5}})
    ;; Ativo quase no nível de evo: 15 + 8 XP; captura concede XP ao ativo
    (collection/adicionar-pokemon!
     s chat player
     {:id-pokemon "ativo-evo" :species "charmander" :nome "Charmander"
      :nivel 15 :xp-desde-nivel 8 :hp 39 :hp-atual 39
      :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
      :tipos ["fire"] :amizade 70 :golpes []}
     20 nil)
    (let [req {:operation-id "cap-xp-once"
               :pokemon {:nome "Rattata" :species "rattata" :nivel 5
                         :hp 20 :hp-atual 20 :ataque 10 :defesa 10
                         :atq-esp 10 :def-esp 10 :veloc 20
                         :tipos ["normal"] :raridade "comum"}
               :bola "pokebola"
               :roll 0}
          r1 (capture-app/attempt-capture! s chat player req)
          after1 (first (ports/list-collection s chat player "a"))
          r2 (capture-app/attempt-capture! s chat player req)
          after2 (first (ports/list-collection s chat player "a"))]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= (:id-pokemon after1) (:id-pokemon after2) "ativo-evo"))
      (is (= (:nivel after1) (:nivel after2)))
      (is (= (:xp-desde-nivel after1) (:xp-desde-nivel after2)))
      (is (= (:species after1) (:species after2)))
      (testing "XP da captura (se houve) não foi reaplicado no retry"
        (is (= (select-keys after1 [:nivel :xp-desde-nivel :species :nome])
               (select-keys after2 [:nivel :xp-desde-nivel :species :nome])))))))
