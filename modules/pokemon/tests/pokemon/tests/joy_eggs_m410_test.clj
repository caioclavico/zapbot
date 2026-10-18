(ns pokemon.tests.joy-eggs-m410-test
  "M4.10 — bridge Joy + Adventure Sync (ovos)."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.joy :as joy]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m410-tests")
(def ^:private storage-rel ".datomic-local/m410-tests")

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

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(def ^:private ferido
  {:nome "Rattata" :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20 :veloc 40
   :tipos ["normal"] :nivel 5 :raridade "comum"
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
   :hp-atual 10 :status "envenenado"})

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(deftest joy-enviar-coletar-reinicio
  (let [db "j410"
        chat "chat-j"
        t0 1000000]
    (let [s (fresh-store! db)]
      (boot! s chat "ash")
      (collection/adicionar-pokemon! s chat "ash" ferido 10 "envenenado")
      (let [st (reads/joy-status s chat "ash" {:agora-ms t0})]
        (is (= 1 (count (:feridos st))))
        (is (= 0 (count (:enfermaria st)))))
      (let [r (tx/joy-send!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "js1" :idx 0 :agora-ms t0})]
        (is (= (+ t0 joy/tempo-tratamento-ms) (:pronto-em r)))
        (is (= 0 (count (ports/list-collection s chat "ash" "a")))))
      (let [dup (tx/joy-send!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "js1" :idx 0 :agora-ms t0})]
        (is (true? (:duplicate dup)))))
    (let [s (reopen-store db)
          early (try
                  (tx/joy-collect!
                   s {:chat-id chat :player-id "ash" :save-id "a"
                      :fence-generation (fence s chat "ash")
                      :operation-id "early" :agora-ms (inc t0)})
                  (catch Exception ex (err/error-code ex)))
          ok (tx/joy-collect!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "jc1"
                 :agora-ms (+ t0 joy/tempo-tratamento-ms)})]
      (is (= :joy_nothing_ready early))
      (is (= 1 (:n ok)))
      (is (= 1 (count (ports/list-collection s chat "ash" "a"))))
      (is (nil? (:status (first (ports/list-collection s chat "ash" "a"))))))))

(deftest joy-isolamento-ab
  (let [s (fresh-store! "j410-ab")
        chat "chat-ab"
        t0 2000000]
    (boot! s chat "ash")
    (collection/adicionar-pokemon! s chat "ash" ferido 5 "queimado")
    (tx/joy-send!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "sa" :idx 0 :agora-ms t0})
    (trainer/switch-save! s chat "ash" "b")
    (is (= 0 (count (:enfermaria (reads/joy-status s chat "ash" {:agora-ms t0})))))
    (trainer/switch-save! s chat "ash" "a")
    (tx/joy-collect!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ca"
        :agora-ms (+ t0 joy/tempo-tratamento-ms)})
    (is (= 1 (count (ports/list-collection s chat "ash" "a"))))
    (is (= 0 (count (ports/list-collection s chat "ash" "b"))))))

(deftest ovos-incubar-nascer-aba-idempotente
  (let [s (fresh-store! "e410")
        chat "chat-e"
        t0 3000000
        due (+ t0 (* 2 60 60 1000))]
    (boot! s chat "ash")
    (tx/grant-egg!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "g1" :egg-id "egg-410" :category :comum
        :species "Pichu" :agora-ms t0})
    (let [st (reads/adventure-status s chat "ash" {:agora-ms t0})]
      (is (= 1 (:inventario-count st)))
      (is (= "inventory" (get-in st [:eggs 0 :state]))))
    (tx/incubate-egg!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "i1" :egg-id "egg-410" :agora-ms t0})
    (trainer/switch-save! s chat "ash" "b")
    (let [code (try
                 (tx/hatch-egg!
                  s {:chat-id chat :player-id "ash" :save-id "b"
                     :fence-generation (fence s chat "ash")
                     :operation-id "hb" :egg-id "egg-410" :agora-ms due})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :egg_save_mismatch code)))
    (trainer/switch-save! s chat "ash" "a")
    (let [h1 (tx/hatch-egg!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "ha" :egg-id "egg-410" :hatch-id "h410"
                 :agora-ms due})
          h2 (tx/hatch-egg!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "ha" :egg-id "egg-410" :hatch-id "h410"
                 :agora-ms due})]
      (is (false? (:duplicate h1)))
      (is (true? (:duplicate h2)))
      (is (= 1 (count (ports/list-collection s chat "ash" "a"))))
      (is (= 0 (count (ports/list-collection s chat "ash" "b")))))))

(deftest constantes-espelhadas
  (is (= 30 joy/tempo-tratamento-minutos))
  (is (= 9 eggs/limite-inventario))
  (is (= (* 2 60 60 1000) (get-in eggs/categorias [:comum :tempo-ms])))
  (is (= (* 6 60 60 1000) (get-in eggs/categorias [:raro :tempo-ms])))
  (is (= (* 12 60 60 1000) (get-in eggs/categorias [:epico :tempo-ms]))))

(deftest dois-jogadores-joy
  (let [s (fresh-store! "j410-2p")
        chat "chat-2p"
        t0 4000000]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" ferido 8 nil)
    (collection/adicionar-pokemon! s chat "misty" ferido 8 nil)
    (tx/joy-send!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :idx 0 :agora-ms t0})
    (tx/joy-send!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :idx 0 :agora-ms t0})
    (is (= 1 (count (:enfermaria (reads/joy-status s chat "ash" {:agora-ms t0})))))
    (is (= 1 (count (:enfermaria (reads/joy-status s chat "misty" {:agora-ms t0})))))))
