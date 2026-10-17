(ns pokemon.tests.collection-m31-test
  "M3.1 — coleção/HP/XP/economia no save ativo (dados fictícios)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m31-tests")
(def ^:private storage-rel ".datomic-local/m31-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- boot-trainer!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(def ^:private magikarp
  {:nome "Magikarp" :hp 100 :ataque 20 :defesa 40 :atq-esp 15 :def-esp 30 :veloc 50
   :tipos ["water"] :nivel 1 :shiny? false})

(def ^:private shiny-charizard
  {:nome "Charizard" :hp 150 :ataque 80 :defesa 70 :atq-esp 90 :def-esp 75 :veloc 85
   :tipos ["fire" "flying"] :nivel 36 :shiny? true})

(deftest colecao-shiny-hp-status
  (let [s (fresh-store! "c-colecao")
        chat "chat-m31-1"
        player "ash"]
    (boot-trainer! s chat player)
    (let [add (collection/adicionar-pokemon! s chat player shiny-charizard 40 :queimado)
          vista (collection/consultar-treinador s chat player)
          poke (first (:colecao vista))]
      (is (= 0 (:indice add)))
      (is (= 1 (count (:colecao vista))))
      (is (true? (:shiny poke)))
      (is (= "Charizard" (:nome poke)))
      (is (= 40 (:hp-atual poke)))
      (is (= "queimado" (:status poke)))
      (collection/atualizar-hp-status! s chat player 0 150 nil)
      (let [curado (first (:colecao (collection/consultar-treinador s chat player)))]
        (is (= 150 (:hp-atual curado)))
        (is (nil? (:status curado)))))))

(deftest xp-pokemon-e-treinador
  (let [s (fresh-store! "c-xp")
        chat "chat-m31-2"
        player "misty"]
    (boot-trainer! s chat player)
    (collection/adicionar-pokemon! s chat player magikarp 80 nil)
    (let [subiu (collection/ganhar-xp-pokemon! s chat player 0 9)
          poke (first (:colecao (collection/consultar-treinador s chat player)))]
      (is (= 2 (:nivel subiu)))
      (is (= 2 (:nivel poke)))
      (is (= 103 (:hp poke)))
      (is (= 83 (:hp-atual poke))))
    (dotimes [_ 5]
      (collection/registrar-vitoria-treinador! s chat player))
    (let [perfil (:perfil (collection/consultar-treinador s chat player))]
      (is (= 5 (:vitorias-treinador perfil)))
      (is (= 2 (:nivel perfil)))
      (is (= 0 (:xp-atual perfil))))))

(deftest moedas-inventario-idempotentes
  (let [s (fresh-store! "c-eco")
        chat "chat-m31-3"
        player "brock"]
    (boot-trainer! s chat player)
    (let [r1 (collection/creditar-moedas! s chat player 50 "op-coin-1")
          r2 (collection/creditar-moedas! s chat player 50 "op-coin-1")
          i1 (collection/ajustar-inventario! s chat player "poke-ball" 3 "op-ball-1")
          i2 (collection/ajustar-inventario! s chat player "poke-ball" 3 "op-ball-1")
          vista (collection/consultar-treinador s chat player)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 50 (:coins (:save vista))))
      (is (false? (:duplicate i1)))
      (is (true? (:duplicate i2)))
      (is (= [{:item-id "poke-ball" :qty 3}] (:inventario vista))))))

(deftest alternancia-preserva-colecoes
  (let [s (fresh-store! "c-switch")
        chat "chat-m31-4"
        player "red"]
    (boot-trainer! s chat player)
    (collection/adicionar-pokemon! s chat player shiny-charizard 100 nil)
    (collection/creditar-moedas! s chat player 10 "op-a-coins")
    (trainer/switch-save! s chat player "b")
    (collection/adicionar-pokemon! s chat player magikarp 50 nil)
    (collection/creditar-moedas! s chat player 99 "op-b-coins")
    (trainer/switch-save! s chat player "a")
    (let [a (trainer/snapshot-save s chat player "a")
          b (trainer/snapshot-save s chat player "b")
          col-a (ports/list-collection s chat player "a")
          col-b (ports/list-collection s chat player "b")]
      (is (= 10 (:coins (:save a))))
      (is (= 99 (:coins (:save b))))
      (is (= 1 (count col-a)))
      (is (= 1 (count col-b)))
      (is (true? (:shiny (first col-a))))
      (is (= "Magikarp" (:nome (first col-b)))))))

(deftest concorrencia-mutacao-vs-ativacao
  (let [s (fresh-store! "c-race")
        chat "chat-m31-5"
        player "leaf"]
    (boot-trainer! s chat player)
    (collection/adicionar-pokemon! s chat player magikarp 100 nil)
    (let [writer-a (collection/freeze-writer s chat player)
          _ (trainer/switch-save! s chat player "b")
          failed (try
                   (ports/update-hp-status! s writer-a 0 10 nil)
                   :unexpected-ok
                   (catch Exception ex
                     (err/error-code ex)))]
      (is (= :stale_writer_fence failed))
      (is (= 100 (:hp-atual (first (ports/list-collection s chat player "a")))))
      (is (empty? (ports/list-collection s chat player "b"))))))

(deftest persistencia-apos-reabrir
  (let [db "c-persist"
        chat "chat-m31-6"
        player "oak"]
    (let [s (fresh-store! db)]
      (boot-trainer! s chat player)
      (collection/adicionar-pokemon! s chat player shiny-charizard 77 :paralisado)
      (collection/creditar-moedas! s chat player 12 "op-persist")
      (collection/registrar-vitoria-treinador! s chat player)
      (trainer/switch-save! s chat player "b"))
    (let [s (reopen-store db)
          p (ports/get-player s chat player)
          col (ports/list-collection s chat player "a")
          save-a (ports/get-save s chat player "a")]
      (is (= "b" (:active-save-id p)))
      (is (= 1 (count col)))
      (is (= 77 (:hp-atual (first col))))
      (is (= "paralisado" (:status (first col))))
      (is (true? (:shiny (first col))))
      (is (= 12 (:coins save-a)))
      (is (= 1 (:vitorias-treinador save-a))))))
