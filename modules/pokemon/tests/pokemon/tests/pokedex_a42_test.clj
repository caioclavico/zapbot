(ns pokemon.tests.pokedex-a42-test
  "A4.2 — Pokédex, coleção, ficha, favorito e índices estáveis (A2)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.active :as active-app]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.collection :as collection]
            [pokemon.application.favorite :as fav-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-a42-pokedex-tests")
(def ^:private storage-rel ".datomic-local/a42-pokedex-tests")

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
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}}))

(def ^:private pikachu
  {:nome "Pikachu" :species "pikachu" :raridade "comum"
   :hp 45 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
   :tipos ["electric"] :nivel 20
   :golpes [{:nome-exibicao "Choque" :tipo "electric" :poder 40 :classe :especial}]})

(def ^:private squirtle
  {:nome "Squirtle" :species "squirtle" :raridade "incomum"
   :hp 44 :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43
   :tipos ["water"] :nivel 10
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 40 :classe :especial}]})

(def ^:private charizard
  {:nome "Charizard" :species "charizard" :raridade "raro"
   :hp 78 :ataque 84 :defesa 78 :atq-esp 109 :def-esp 85 :veloc 100
   :tipos ["fire" "flying"] :nivel 36 :shiny true
   :golpes [{:nome-exibicao "Lança-chamas" :tipo "fire" :poder 90 :classe :especial}]})

(deftest colecao-vazia
  (let [s (fresh-store! "a42-empty")
        chat "c-empty" player "ash"]
    (boot! s chat player)
    (let [col (reads/collection s chat player nil)
          dex (reads/pokedex s chat player nil)]
      (is (zero? (:count col)))
      (is (empty? (:collection col)))
      (is (zero? (:capturados dex)))
      (is (empty? (:entries dex))))))

(deftest colecao-varios-ordenacao-estavel-e-indices-a2
  (let [db "a42-order"
        s (fresh-store! db)
        chat "c-order" player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil)
    (collection/adicionar-pokemon! s chat player charizard 78 nil)
    (let [col (reads/collection s chat player nil)
          items (:collection col)]
      (is (= 3 (:count col)))
      (is (= [0 1 2] (mapv :collection-index items)))
      (is (= ["Pikachu" "Squirtle" "Charizard"] (mapv :nome items)))
      ;; A2: escolher índice 2 = Charizard (não posição filtrada)
      (active-app/set-active! s chat player {:operation-id "a42-ativo-2" :idx 2})
      (let [col2 (reads/collection s chat player nil)
            ficha (reads/pokemon-ficha s chat player nil 2)]
        (is (= 2 (:ativo-index col2)))
        (is (= "Charizard" (get-in ficha [:pokemon :nome])))
        (is (true? (:is-ativo ficha)))
        (is (seq (get-in ficha [:pokemon :golpes])))
        (is (= 84 (get-in ficha [:pokemon :ataque])))))
    (let [s2 (reopen-store db)
          col (reads/collection s2 chat player nil)]
      (is (= 3 (:count col)))
      (is (= 2 (:ativo-index col)))
      (is (= "Charizard" (:nome (nth (:collection col) 2)))))))

(deftest pokedex-vistas-capturadas-e-descobertas
  (let [s (fresh-store! "a42-dex")
        chat "c-dex" player "misty"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil)
    (let [dex (reads/pokedex s chat player nil)
          entries (:entries dex)]
      (is (>= (:capturados dex) 2))
      (is (>= (:vistos dex) 2))
      (is (= 1025 (:total-pokemons dex)))
      (is (some #(= "pikachu" (:species %)) entries))
      (is (some #(= "squirtle" (:species %)) entries))
      (let [pika (first (filter #(= "pikachu" (:species %)) entries))]
        (is (seq (:indices pika)))
        (is (= 1 (:numero (first (:indices pika)))))))))

(deftest favorito-persiste-e-isola-por-jogador
  (let [db "a42-fav"
        s (fresh-store! db)
        chat "c-fav"]
    (boot! s chat "ash")
    (boot! s chat "gary")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (collection/adicionar-pokemon! s chat "ash" squirtle 44 nil)
    (collection/adicionar-pokemon! s chat "gary" charizard 78 nil)
    (let [r (fav-app/set-favorite!
             s chat "ash"
             {:operation-id "fav-ash-1" :idx 1})]
      (is (false? (:duplicate r)))
      (is (= "Squirtle" (get-in r [:favorito :nome]))))
    (let [col-ash (reads/collection s chat "ash" nil)
          col-gary (reads/collection s chat "gary" nil)]
      (is (= "Squirtle" (get-in col-ash [:favorito :nome])))
      (is (nil? (:favorito col-gary)))
      ;; Isolamento: ficha do Gary não vaza coleção do Ash
      (is (= 1 (:count col-gary)))
      (is (= "Charizard" (:nome (first (:collection col-gary)))))
      (is (= 2 (:count col-ash))))
    (fav-app/set-favorite!
     s chat "ash"
     {:operation-id "fav-ash-clear" :clear? true})
    (is (nil? (:favorito (reads/collection s chat "ash" nil))))
    (let [s2 (reopen-store db)]
      (is (nil? (:favorito-id-pokemon (ports/get-save s2 chat "ash" "a")))))))

(deftest ficha-indice-invalido
  (let [s (fresh-store! "a42-ficha-bad")
        chat "c-bad" player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (let [code (try
                 (reads/pokemon-ficha s chat player nil 3)
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :invalid_collection_index code)))))

(deftest indices-nao-mudam-com-reordenacao-logica
  "Mesmo com coleção ordenada por collection-index, o nº exibido na Pokédex
   bate com escolher/evoluir (collection-index + 1)."
  (let [s (fresh-store! "a42-idx")
        chat "c-idx" player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil) ;; #1
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)  ;; #2
    (let [dex (reads/pokedex s chat player nil)
          pika (first (filter #(= "pikachu" (:species %)) (:entries dex)))
          nums (mapv :numero (:indices pika))]
      (is (= [2] nums))
      (active-app/set-active! s chat player {:operation-id "a42-idx-ativo" :idx 1})
      (is (= "Pikachu"
             (:nome (nth (ports/list-collection s chat player "a") 1)))))))
