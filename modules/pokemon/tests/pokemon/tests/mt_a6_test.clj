(ns pokemon.tests.mt-a6-test
  "A6 — MT de Ataque: consumo atômico, idempotência, preservação e STAB."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-mt-a6-tests")
(def ^:private storage-rel ".datomic-local/mt-a6-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player coins]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (when (pos? coins)
    (reward-app/admin-seed-reward!
     s {:operation-id (str "seed-coins-" chat "-" player)
        :chat-id chat :player-id player :save-id "a"
        :coins-delta coins})))

(defn- wartortle
  []
  {:nome "Wartortle" :species "wartortle" :hp 59 :hp-atual 59
   :ataque 63 :defesa 80 :atq-esp 65 :def-esp 80 :veloc 58
   :tipos ["water"] :nivel 16 :xp 120 :shiny false :raridade "incomum"
   :golpes [{:slug "tackle" :nome-exibicao "Investida"
             :tipo "normal" :poder 40 :classe :fisico}
            {:slug "water-gun" :nome-exibicao "Jato de Água"
             :tipo "water" :poder 40 :classe :especial}
            {:slug "bite" :nome-exibicao "Mordida"
             :tipo "dark" :poder 60 :classe :fisico}
            {:slug "rapid-spin" :nome-exibicao "Pirouete"
             :tipo "normal" :poder 50 :classe :fisico}]})

(deftest catalogo-mt-liberada
  (let [mt (first (filter #(= "mt" (:item-id %)) (shop/catalogo-loja)))]
    (is (= 200 (:preco mt)))
    (is (re-find #"(?i)pk mt" (str (:descricao mt))))
    (is (not (re-find #"(?i)bloqueado" (str (:descricao mt)))))))

(deftest mt-wartortle-consome-preserva-e-idempotente
  (let [s (fresh-store! "mt-wart")
        chat "chat-mt-w"
        mon (wartortle)]
    (boot! s chat "owner" 250)
    (collection/adicionar-pokemon! s chat "owner" mon 10 nil)
    (tx/buy-item! s {:chat-id chat :player-id "owner" :save-id "a"
                     :fence-generation (fence s chat "owner")
                     :operation-id "buy-mt-w" :item-id "mt"})
    (let [antes (first (ports/list-collection s chat "owner" "a"))
          r (tx/learn-move!
             s {:chat-id chat :player-id "owner" :save-id "a"
                :fence-generation (fence s chat "owner")
                :operation-id "mt-learn-w" :idx 0 :slot 0 :consume-item-id "mt"
                :golpe {:slug "ice-beam" :nome-exibicao "Raio de Gelo"
                        :tipo "ice" :poder 90 :classe :especial}})
          retry (tx/learn-move!
                 s {:chat-id chat :player-id "owner" :save-id "a"
                    :fence-generation (fence s chat "owner")
                    :operation-id "mt-learn-w" :idx 0 :slot 0 :consume-item-id "mt"
                    :golpe {:slug "ice-beam" :nome-exibicao "Raio de Gelo"
                            :tipo "ice" :poder 90 :classe :especial}})
          depois (first (ports/list-collection s chat "owner" "a"))
          inv (ports/list-inventory s chat "owner" "a")
          mt-qty (or (:qty (first (filter #(= "mt" (:item-id %)) inv))) 0)]
      (is (false? (:duplicate r)))
      (is (true? (:duplicate retry)))
      (is (= "tackle" (moves/chave (:golpe-anterior r))))
      (is (= "ice-beam" (moves/chave (:golpe-novo r))))
      (is (zero? (:item-qty-after r)))
      (is (zero? mt-qty))
      (is (= (:id-pokemon antes) (:id-pokemon depois)))
      (is (= (:nivel antes) (:nivel depois)))
      (is (= (:hp antes) (:hp depois)))
      (is (= (:ataque antes) (:ataque depois)))
      (is (= (:defesa antes) (:defesa depois)))
      (is (= (boolean (:shiny antes)) (boolean (:shiny depois))))
      (is (= "wartortle"
             (str/lower-case (str (or (:species depois) (:nome depois))))))
      (is (= "ice-beam" (moves/chave (nth (:golpes depois) 0))))
      (is (= "water-gun" (moves/chave (nth (:golpes depois) 1))))
      (is (= "bite" (moves/chave (nth (:golpes depois) 2))))
      (is (= "rapid-spin" (moves/chave (nth (:golpes depois) 3))))
      (is (= "ice-beam" (moves/chave (:golpe-novo retry)))
          "retry devolve o mesmo recibo sem reconsumir"))))

(deftest mt-indisponivel-nao-altera-golpes
  (let [s (fresh-store! "mt-empty")
        chat "chat-mt-e"]
    (boot! s chat "ash" 0)
    (collection/adicionar-pokemon! s chat "ash" (wartortle) 10 nil)
    (let [antes (first (ports/list-collection s chat "ash" "a"))
          ex (try
               (tx/learn-move!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "mt-fail" :idx 0 :slot 2 :consume-item-id "mt"
                   :golpe {:slug "surf" :nome-exibicao "Surfar"
                           :tipo "water" :poder 90 :classe :especial}})
               (catch Exception e e))
          depois (first (ports/list-collection s chat "ash" "a"))]
      (is (= :insufficient_item (err/error-code ex)))
      (is (= (:golpes antes) (:golpes depois))))))

(deftest mt-slot-invalido-e-golpe-ja-conhecido
  (let [s (fresh-store! "mt-slot")
        chat "chat-mt-s"]
    (boot! s chat "ash" 250)
    (collection/adicionar-pokemon! s chat "ash" (wartortle) 10 nil)
    (tx/buy-item! s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-mt-s" :item-id "mt"})
    (testing "slot inválido"
      (let [ex (try
                 (tx/learn-move!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "mt-bad-slot" :idx 0 :slot 9 :consume-item-id "mt"
                     :golpe {:slug "surf" :nome-exibicao "Surfar"
                             :tipo "water" :poder 90 :classe :especial}})
                 (catch Exception e e))]
        (is (= :invalid_move_slot (err/error-code ex)))))
    (testing "golpe já conhecido"
      (let [ex (try
                 (tx/learn-move!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "mt-known" :idx 0 :slot 0 :consume-item-id "mt"
                     :golpe {:slug "bite" :nome-exibicao "Mordida"
                             :tipo "dark" :poder 60 :classe :fisico}})
                 (catch Exception e e))
            inv (ports/list-inventory s chat "ash" "a")]
        (is (= :move_already_known (err/error-code ex)))
        (is (= 1 (:qty (first (filter #(= "mt" (:item-id %)) inv)))))))))

(deftest mt-nao-remove-ultimo-stab
  (let [s (fresh-store! "mt-stab")
        chat "chat-mt-stab"
        mon (-> (wartortle)
                (assoc :golpes
                       [{:slug "water-gun" :nome-exibicao "Jato de Água"
                         :tipo "water" :poder 40 :classe :especial}
                        {:slug "tackle" :nome-exibicao "Investida"
                         :tipo "normal" :poder 40 :classe :fisico}]))]
    (boot! s chat "ash" 250)
    (collection/adicionar-pokemon! s chat "ash" mon 10 nil)
    (tx/buy-item! s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-mt-stab" :item-id "mt"})
    (let [ex (try
               (tx/learn-move!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "mt-stab-fail" :idx 0 :slot 0 :consume-item-id "mt"
                   :golpe {:slug "hyper-fang" :nome-exibicao "Hiperpresa"
                           :tipo "normal" :poder 80 :classe :fisico}})
               (catch Exception e e))
          inv (ports/list-inventory s chat "ash" "a")]
      (is (= :must_keep_stab_move (err/error-code ex)))
      (is (= 1 (:qty (first (filter #(= "mt" (:item-id %)) inv))))))))
