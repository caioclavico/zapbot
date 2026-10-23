(ns pokemon.tests.shop-m411-test
  "M4.11 — loja, inventário e uso de itens via bridge."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m411-tests")
(def ^:private storage-rel ".datomic-local/m411-tests")

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

(defn- boot! [s chat player coins]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (when (pos? coins)
    (reward-app/admin-seed-reward!
     s {:operation-id (str "seed-coins-" chat "-" player)
        :chat-id chat :player-id player :save-id "a"
        :coins-delta coins})))

(deftest catalogo-precos-legado
  (let [cat (into {} (map (juxt :item-id :preco) (shop/catalogo-loja)))]
    (is (= 15 (get cat "atadura")))
    (is (= 20 (get cat "pocao")))
    (is (= 60 (get cat "pocao-maxima")))
    (is (= 10 (get cat "fruta")))
    (is (= 200 (get cat "mochila")))
    (is (= 200 (get cat "mt")))
    (is (= 45 (get cat "restos")))))

(deftest compra-saldo-e-idempotencia
  (let [s (fresh-store! "s411-buy")
        chat "chat-b"]
    (boot! s chat "ash" 100)
    (let [ok (tx/buy-item!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "buy-pocao" :item-id "pocao"})
          dup (tx/buy-item!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "buy-pocao" :item-id "pocao"})
          inv (ports/list-inventory s chat "ash" "a")
          save (ports/get-save s chat "ash" "a")]
      (is (false? (:duplicate ok)))
      (is (true? (:duplicate dup)))
      (is (= 80 (:coins save)))
      (is (= 1 (:qty (first (filter #(= "pocao" (:item-id %)) inv))))))
    (let [code (try
                 (tx/buy-item!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-cara" :item-id "mochila"})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :insufficient_coins code)))))

(deftest compra-item-nao-venda
  (let [s (fresh-store! "s411-nv")
        chat "chat-nv"]
    (boot! s chat "ash" 500)
    (let [code (try
                 (tx/buy-item!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-bola" :item-id "pokebola"})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :item_not_for_sale code)))))

(deftest isolamento-ab-e-reinicio
  (let [db "s411-ab"
        chat "chat-ab"]
    (let [s (fresh-store! db)]
      (boot! s chat "ash" 50)
      (tx/buy-item!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "buy-a" :item-id "fruta"})
      (trainer/switch-save! s chat "ash" "b")
      (is (= 0 (count (:itens (reads/inventory-bag s chat "ash")))))
      (trainer/switch-save! s chat "ash" "a")
      (is (= 1 (count (:itens (reads/inventory-bag s chat "ash"))))))
    (let [s (reopen-store db)
          bag (reads/inventory-bag s chat "ash")]
      (is (= 1 (count (:itens bag))))
      (is (= "fruta" (get-in bag [:itens 0 :item-id]))))))

(deftest uso-cura-e-pocao-uma-vez
  (let [s (fresh-store! "s411-use")
        chat "chat-u"
        mon {:nome "Rattata" :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20
             :veloc 40 :tipos ["normal"] :nivel 5 :raridade "comum"
             :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]}]
    (boot! s chat "ash" 200)
    (collection/adicionar-pokemon! s chat "ash" mon 10 "envenenado")
    (tx/buy-item!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "buy-ant" :item-id "antidoto"})
    (tx/buy-item!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "buy-pot" :item-id "pocao"})
    (let [cura (tx/use-item!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "use-cura" :kind "curar" :idx 0})
          pot (tx/use-item!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "use-pot" :item-id "pocao" :idx 0})
          pot2 (tx/use-item!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "use-pot" :item-id "pocao" :idx 0})
          col (ports/list-collection s chat "ash" "a")]
      (is (= "status" (:kind cura)))
      (is (nil? (:status (first col))))
      (is (= "pocao" (:kind pot)))
      (is (= 26 (:hp-depois pot))) ;; 10 + floor(40*0.4)=26
      (is (true? (:duplicate pot2)))
      (is (= 26 (:hp-atual (first col)))))))

(deftest reviver-equipamento-sao-atomicos
  (let [s (fresh-store! "s411-revive-equip")
        chat "chat-revive"
        mon {:nome "Rattata" :hp 40 :hp-atual 0 :ataque 30 :defesa 20
             :atq-esp 20 :def-esp 20 :veloc 40 :tipos ["normal"]
             :nivel 5 :raridade "comum"}]
    (boot! s chat "ash" 100)
    (collection/adicionar-pokemon! s chat "ash" mon 10 nil)
    (ports/update-hp-status! s {:chat-id chat :player-id "ash" :save-id "a"
                                :fence-generation (fence s chat "ash")}
                               0 0 nil)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-reviver" :chat-id chat :player-id "ash"
        :save-id "a" :item {:item-id "reviver" :qty 1}})
    (let [f (fence s chat "ash")
          r (tx/use-item! s {:chat-id chat :player-id "ash" :save-id "a"
                              :fence-generation f :operation-id "use-reviver"
                              :item-id "reviver" :idx 0})
          retry (tx/use-item! s {:chat-id chat :player-id "ash" :save-id "a"
                                 :fence-generation f :operation-id "use-reviver"
                                 :item-id "reviver" :idx 0})
          poke (first (ports/list-collection s chat "ash" "a"))
          inv (ports/list-inventory s chat "ash" "a")]
      (is (= "reviver" (:kind r)))
      (is (true? (:duplicate retry)))
      (is (= 40 (:hp-atual poke)))
      (is (zero? (or (:qty (first (filter #(= "reviver" (:item-id %)) inv))) 0))))))

(deftest equipamento-troca-item-com-cas
  (let [s (fresh-store! "s411-equip")
        chat "chat-equip"
        mon {:nome "Rattata" :hp 40 :hp-atual 40 :ataque 30 :defesa 20
             :atq-esp 20 :def-esp 20 :veloc 40 :tipos ["normal"]
             :nivel 5 :raridade "comum"}]
    (boot! s chat "ash" 100)
    (collection/adicionar-pokemon! s chat "ash" mon 10 nil)
    (tx/buy-item! s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-restos" :item-id "restos"})
    (let [r (tx/use-item! s {:chat-id chat :player-id "ash" :save-id "a"
                             :fence-generation (fence s chat "ash")
                             :operation-id "equip-restos" :item-id "restos" :idx 0})
          poke (first (ports/list-collection s chat "ash" "a"))]
      (is (= "equip" (:kind r)))
      (is (= "restos" (:item poke))))))

(deftest mt-consome-item-com-a-mesma-transacao
  (let [s (fresh-store! "s411-mt")
        chat "chat-mt"
        mon {:nome "Rattata" :hp 40 :hp-atual 40 :ataque 30 :defesa 20
             :atq-esp 20 :def-esp 20 :veloc 40 :tipos ["normal"]
             :nivel 5 :raridade "comum"
             :golpes [{:slug "tackle" :nome-exibicao "Investida"
                       :tipo "normal" :poder 40 :classe :fisico}]}]
    (boot! s chat "ash" 250)
    (collection/adicionar-pokemon! s chat "ash" mon 10 nil)
    (tx/buy-item! s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "buy-mt" :item-id "mt"})
    (let [r (tx/learn-move!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "learn-mt" :idx 0 :consume-item-id "mt"
                :golpe {:slug "quick-attack" :nome-exibicao "Ataque Rápido"
                        :tipo "normal" :poder 40 :classe :fisico}})
          retry (tx/learn-move!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "learn-mt" :idx 0 :consume-item-id "mt"
                    :golpe {:slug "quick-attack" :nome-exibicao "Ataque Rápido"
                            :tipo "normal" :poder 40 :classe :fisico}})
          inv (ports/list-inventory s chat "ash" "a")]
      (is (false? (:duplicate r)))
      (is (true? (:duplicate retry)))
      (is (zero? (or (:qty (first (filter #(= "mt" (:item-id %)) inv))) 0))))))

(deftest expansao-mochila
  (let [s (fresh-store! "s411-bag")
        chat "chat-bag"]
    (boot! s chat "ash" 250)
    (let [r (tx/buy-item!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "buy-bag" :item-id "mochila"})
          save (ports/get-save s chat "ash" "a")
          shop (reads/shop-catalog s chat "ash")]
      (is (true? (:expansao r)))
      (is (= 1 (:expansoes-mochila-50 save)))
      (is (= 50 (:coins save)))
      (is (= 100 (:capacidade-mochila shop))))))
