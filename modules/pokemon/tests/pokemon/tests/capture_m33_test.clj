(ns pokemon.tests.capture-m33-test
  "M3.3 — captura, evolução, golpes com fence/idempotência."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.evolution :as evo-app]
            [pokemon.application.moves :as moves-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.evolution :as evo]
            [pokemon.domain.moves :as moves]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m33-tests")
(def ^:private storage-rel ".datomic-local/m33-tests")

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

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-balls-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 20}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-stone-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pedra-trovao" :qty 3}}))

(def ^:private magikarp
  {:nome "Magikarp" :raridade "comum"
   :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 5 :shiny? false
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private charmander
  {:nome "Charmander" :raridade "incomum"
   :hp 50 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65
   :tipos ["fire"] :nivel 16 :shiny? true :habilidade "blaze"
   :imagem "charmander.png" :imagem-shiny "charmander-s.png"
   :golpes [{:nome-exibicao "Brasas" :tipo "fire" :poder 40 :classe :especial}
            {:nome-exibicao "Arranhão" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- load-capture-fixtures
  []
  (edn/read-string
   (slurp (io/file (persistence/project-root)
                   "modules/pokemon/tests/pokemon/tests/fixtures/legacy_capture.edn"))))

(deftest equivalencia-formulas-captura
  (doseq [f (load-capture-fixtures)]
    (testing (:id f)
      (is (= (:chance-especie f) (capture/chance-captura (:pokemon f))))
      (is (= (:chance-base f)
             (capture/chance-base-pos-batalha (:pokemon f) (:status f) (:sequencia f))))
      (is (= (:chance-bola f)
             (capture/chance-com-bola (:chance-base f) (:bola f)))))))

(deftest captura-sucesso-e-falha
  (let [s (fresh-store! "cap-ok")
        chat "chat-c1"
        player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player charmander 50 nil)
    (let [ok (capture-app/attempt-capture!
              s chat player
              {:operation-id "cap-ok-1"
               :pokemon magikarp
               :bola "pokebola"
               :status nil
               :tentativas-anteriores 0
               :roll 0})
          fail (capture-app/attempt-capture!
                s chat player
                {:operation-id "cap-fail-1"
                 :pokemon magikarp
                 :bola "pokebola"
                 :status nil
                 :tentativas-anteriores 0
                 :roll 99
                 :fuga-roll 99})
          vista (collection/consultar-treinador s chat player)]
      (is (true? (:capturou? ok)))
      (is (false? (:fugiu? ok)))
      (is (false? (:capturou? fail)))
      (is (false? (:fugiu? fail)))
      (is (= 2 (count (:colecao vista))))
      (is (= 1 (:sequencia-capturas (:save vista))))
      (is (pos? (:coins (:save vista))))
      (let [karp (second (:colecao vista))]
        (is (= "Magikarp" (:nome karp)))
        (is (some? (:id-pokemon karp)))
        (is (seq (:golpes karp)))))))

(deftest captura-fuga-na-terceira
  (let [s (fresh-store! "cap-flee")
        chat "chat-c2"
        player "misty"]
    (boot! s chat player)
    (let [r (capture-app/attempt-capture!
             s chat player
             {:operation-id "cap-flee-1"
              :pokemon magikarp
              :bola "pokebola"
              :tentativas-anteriores 2
              :roll 99
              :fuga-roll 0})]
      (is (false? (:capturou? r)))
      (is (true? (:fugiu? r)))
      (is (= 0 (:sequencia-capturas (ports/get-save s chat player "a"))))
      (is (= 0 (count (ports/list-collection s chat player "a")))))))

(deftest evolucao-consome-item-preserva-shiny
  (let [s (fresh-store! "evo-1")
        chat "chat-e1"
        player "brock"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player charmander 40 nil)
    (let [r (evo-app/evolve!
             s chat player
             {:operation-id "evo-1"
              :idx 0
              :item-id "pedra-trovao"
              :dados {:nome-novo "Charmeleon"
                      :imagem "charmeleon.png"
                      :imagem-shiny "charmeleon-s.png"
                      :tipos ["fire"]
                      :habilidade "blaze"
                      :hp 70 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80}})
          poke (first (:colecao (collection/consultar-treinador s chat player)))
          inv (ports/list-inventory s chat player "a")
          pedras (some #(when (= "pedra-trovao" (:item-id %)) (:qty %)) inv)]
      (is (false? (:duplicate r)))
      (is (= "Charmeleon" (:nome poke)))
      (is (true? (:shiny poke)))
      (is (= "charmeleon-s.png" (:imagem poke)))
      (is (= 70 (:hp poke)))
      (is (= 60 (:hp-atual poke))) ;; 40 + (70-50)
      (is (= 2 pedras))
      (is (seq (:golpes poke))))))

(deftest substituicao-golpe-stab
  (let [s (fresh-store! "move-1")
        chat "chat-m1"
        player "gary"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player charmander 50 nil)
    (let [r (moves-app/learn-move!
             s chat player
             {:operation-id "move-1"
              :idx 0
              :slot 1
              :golpe {:nome-exibicao "Flamethrower" :tipo "fire" :poder 90 :classe :especial}})
          poke (first (:colecao (collection/consultar-treinador s chat player)))
          slugs (map #(get % "slug") (:golpes poke))]
      (is (false? (:duplicate r)))
      (is (= ["brasas" "flamethrower"] slugs))
      (let [code (try
                   (moves-app/learn-move!
                    s chat player
                    {:operation-id "move-bad"
                     :idx 0
                     :slot 0
                     :golpe {:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        ;; após trocar Arranhão por Flamethrower, ainda há 2 STAB;
        ;; aqui removemos Brasas e Flamethrower em sequência via slot 0+1
        ;; — Trocar Brasas por Tackle mantém Flamethrower (STAB ok).
        ;; Validamos bloqueio trocando o único STAB restante:
        (is (= :ok code)))
      (let [code2 (try
                    (moves-app/learn-move!
                     s chat player
                     {:operation-id "move-bad-2"
                      :idx 0
                      :slot 1
                      :golpe {:nome-exibicao "Growl" :tipo "normal" :poder 0 :classe :status}})
                    :ok
                    (catch Exception ex (err/error-code ex)))]
        (is (= :must_keep_stab_move code2))))))

(deftest captura-idempotente
  (let [s (fresh-store! "cap-idem")
        chat "chat-idem"
        player "red"
        req {:operation-id "cap-once"
             :pokemon magikarp
             :bola "pokebola"
             :roll 0}]
    (boot! s chat player)
    (let [r1 (capture-app/attempt-capture! s chat player req)
          r2 (capture-app/attempt-capture! s chat player req)
          inv (ports/list-inventory s chat player "a")
          balls (some #(when (= "pokebola" (:item-id %)) (:qty %)) inv)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 1 (count (ports/list-collection s chat player "a"))))
      (is (= 19 balls)))))

(deftest callback-a-apos-switch-b
  (let [s (fresh-store! "cap-stale")
        chat "chat-stale"
        player "leaf"]
    (boot! s chat player)
    (let [writer-a (writer/freeze-writer s chat player)
          _ (trainer/switch-save! s chat player "b")
          ;; seed bola no B
          _ (reward-app/admin-seed-reward!
             s {:operation-id "seed-b-balls"
                :chat-id chat :player-id player :save-id "b"
                :item {:item-id "pokebola" :qty 5}})
          code (try
                 (capture-app/attempt-capture!
                  s writer-a
                  {:operation-id "cap-stale"
                   :pokemon magikarp
                   :bola "pokebola"
                   :roll 0})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :stale_writer_fence code))
      (is (= 0 (count (ports/list-collection s chat player "a"))))
      (is (= 0 (count (ports/list-collection s chat player "b")))))))

(deftest a-b-a-preserva-colecao
  (let [s (fresh-store! "cap-aba")
        chat "chat-aba"
        player "blue"]
    (boot! s chat player)
    (capture-app/attempt-capture!
     s chat player
     {:operation-id "cap-a1" :pokemon magikarp :bola "pokebola" :roll 0})
    (trainer/switch-save! s chat player "b")
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-b2" :chat-id chat :player-id player :save-id "b"
        :item {:item-id "pokebola" :qty 5}})
    (capture-app/attempt-capture!
     s chat player
     {:operation-id "cap-b1"
      :pokemon (assoc magikarp :nome "Feebas" :shiny? true)
      :bola "pokebola" :roll 0})
    (trainer/switch-save! s chat player "a")
    (is (= 1 (count (ports/list-collection s chat player "a"))))
    (is (= "Magikarp" (:nome (first (ports/list-collection s chat player "a")))))
    (is (= 1 (count (ports/list-collection s chat player "b"))))
    (is (true? (:shiny (first (ports/list-collection s chat player "b")))))))

(deftest persistencia-apos-reabrir
  (let [db "cap-persist"
        chat "chat-p"
        player "green"]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (capture-app/attempt-capture!
       s chat player
       {:operation-id "cap-persist" :pokemon charmander :bola "pokebola" :roll 0})
      (evo-app/evolve!
       s chat player
       {:operation-id "evo-persist" :idx 0
        :dados {:nome-novo "Charmeleon" :tipos ["fire"] :habilidade "blaze"
                :hp 70 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80
                :imagem "c.png" :imagem-shiny "cs.png"}}))
    (let [s (reopen-store db)
          poke (first (ports/list-collection s chat player "a"))
          retry (capture-app/attempt-capture!
                 s chat player
                 {:operation-id "cap-persist" :pokemon charmander :bola "pokebola" :roll 0})]
      (is (true? (:duplicate retry)))
      (is (= "Charmeleon" (:nome poke)))
      (is (true? (:shiny poke)))
      (is (seq (:golpes poke)))
      (is (some? (:id-pokemon poke))))))

(deftest captura-concorrente-operation-ids-distintos
  (let [s (fresh-store! "cap-conc")
        chat "chat-conc"
        player "yellow"]
    (boot! s chat player)
    (let [futs (doall
                (for [i (range 5)]
                  (future
                    (capture-app/attempt-capture!
                     s chat player
                     {:operation-id (str "cap-c-" i)
                      :pokemon (assoc magikarp :nome (str "Karp" i)
                                      :id-pokemon (str "id-" i))
                      :bola "pokebola"
                      :roll 0}))))
          results (mapv deref futs)]
      (is (every? #(true? (:capturou? %)) results))
      (is (= 5 (count (ports/list-collection s chat player "a"))))
      (is (= 5 (:sequencia-capturas (ports/get-save s chat player "a")))))))

(deftest dominio-evolucao-desmaiado
  (let [reg {:nome "Charmander" :hp 50 :hp-atual 0 :shiny true
             :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
             :tipos ["fire"] :nivel 16 :amizade 70
             :golpes [{"slug" "brasas" "nome-exibicao" "Brasas"
                       "tipo" "fire" "poder" 40 "classe" "especial"}]}
        out (evo/aplicar-evolucao reg
                                  {:nome-novo "Charmeleon" :tipos ["fire"]
                                   :hp 70 :ataque 2 :defesa 2 :atq-esp 2 :def-esp 2 :veloc 2})]
    (is (= 0 (:hp-atual out)))
    (is (= "Charmeleon" (:nome out)))))
