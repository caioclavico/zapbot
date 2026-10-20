(ns pokemon.tests.active-a2-test
  "A2 — Pokémon ativo, evolução e aprendizado (idempotência + Datomic)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.active :as active-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.evolution :as evo-app]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.moves :as moves-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-a2-tests")
(def ^:private storage-rel ".datomic-local/a2-tests")

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
      :item {:item-id "pedra-trovao" :qty 3}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-coins-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :coins-delta 200}))

(def ^:private pikachu
  {:nome "Pikachu" :species "pikachu" :raridade "comum"
   :hp 45 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
   :tipos ["electric"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Choque do Trovão" :tipo "electric" :poder 40 :classe :especial}
            {:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(def ^:private squirtle
  {:nome "Squirtle" :species "squirtle" :raridade "comum"
   :hp 44 :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43
   :tipos ["water"] :nivel 10 :shiny? false
   :golpes [{:nome-exibicao "Jato de Água" :tipo "water" :poder 40 :classe :especial}]})

(deftest escolher-ativo-persiste-e-rejeita-invalido
  (let [db "a2-ativo"
        s (fresh-store! db)
        chat "chat-a2-1"
        player "ash"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil)
    (is (= 0 (:ativo-index (ports/get-save s chat player "a") 0)))
    (let [r (active-app/set-active!
             s chat player
             {:operation-id "set-active-1" :idx 1})]
      (is (false? (:duplicate r)))
      (is (= 1 (:ativo-index r))))
    (is (= 1 (:ativo-index (ports/get-save s chat player "a"))))
    (let [s2 (reopen-store db)]
      (is (= 1 (:ativo-index (ports/get-save s2 chat player "a")))))
    (let [code (try
                 (active-app/set-active!
                  s chat player
                  {:operation-id "set-active-bad" :idx 9})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :invalid_collection_index code)))
    (let [r2 (active-app/set-active!
              s chat player
              {:operation-id "set-active-1" :idx 1})]
      (is (true? (:duplicate r2))))))

(deftest escolher-bloqueado-com-caca-ativa
  (let [s (fresh-store! "a2-busy")
        chat "chat-a2-busy"
        player "misty"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (hunt-app/start-hunt!
     s chat player
     {:operation-id "hunt-busy-1"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 50
      :clima-id "ensolarado"
      :candidates [{:nome "Magikarp" :species "magikarp" :tipos ["water"]
                    :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
                    :nivel 5 :golpes [{:nome-exibicao "Splash" :tipo "normal"
                                      :poder 0 :classe :status}]
                    :slug-especie "magikarp"}]})
    (let [code (try
                 (active-app/set-active!
                  s chat player
                  {:operation-id "set-during-hunt" :idx 1})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :battle_busy code)))))

(deftest caca-usa-pokemon-ativo
  (let [s (fresh-store! "a2-hunt-ativo")
        chat "chat-a2-hunt"
        player "brock"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (collection/adicionar-pokemon! s chat player squirtle 44 nil)
    (active-app/set-active!
     s chat player
     {:operation-id "pick-squirtle" :idx 1})
    (let [r (hunt-app/start-hunt!
             s chat player
             {:operation-id "hunt-ativo-1"
              :area-id "praia"
              :tipos-encontro ["water"]
              :surto? false
              :poder-alvo 40
              :clima-id "ensolarado"
              :candidates [{:nome "Magikarp" :species "magikarp" :tipos ["water"]
                            :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20
                            :veloc 80 :nivel 5
                            :golpes [{:nome-exibicao "Splash" :tipo "normal"
                                      :poder 0 :classe :status}]
                            :slug-especie "magikarp"}]})
          battle (:battle r)]
      (is (= "Squirtle" (get-in battle [:pokemons :x :nome]))))))

(deftest evolucao-idempotente-consome-pedra
  (let [s (fresh-store! "a2-evo")
        chat "chat-a2-evo"
        player "surge"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (let [req {:operation-id "evo-pika-1"
               :idx 0
               :item-id "pedra-trovao"
               :dados {:nome-novo "Raichu"
                       :imagem "raichu.png"
                       :tipos ["electric"]
                       :habilidade "static"
                       :hp 60 :ataque 90 :defesa 55 :atq-esp 90 :def-esp 80 :veloc 110}}
          r1 (evo-app/evolve! s chat player req)
          r2 (evo-app/evolve! s chat player req)
          poke (first (:colecao (collection/consultar-treinador s chat player)))
          pedras (some #(when (= "pedra-trovao" (:item-id %)) (:qty %))
                       (ports/list-inventory s chat player "a"))]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= "Raichu" (:nome poke)))
      (is (= "Raichu" (:species poke)))
      (is (some? (:id-pokemon poke)))
      (is (= 2 pedras))
      (is (= 60 (:hp poke)))
      (is (= 60 (:hp-atual poke))))))

(deftest aprendizado-substituicao-e-retry
  (let [s (fresh-store! "a2-learn")
        chat "chat-a2-learn"
        player "gary"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (let [req {:operation-id "learn-thunder-1"
               :idx 0
               :slot 1
               :golpe {:nome-exibicao "Thunderbolt" :tipo "electric"
                       :poder 90 :classe :especial}}
          r1 (moves-app/learn-move! s chat player req)
          r2 (moves-app/learn-move! s chat player req)
          poke (first (:colecao (collection/consultar-treinador s chat player)))
          slugs (map #(get % "slug") (:golpes poke))]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= ["choque-do-trovao" "thunderbolt"] slugs)))
    (let [code (try
                 (moves-app/learn-move!
                  s chat player
                  {:operation-id "learn-dup"
                   :idx 0
                   :slot nil
                   :golpe {:nome-exibicao "Thunderbolt" :tipo "electric"
                           :poder 90 :classe :especial}})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :move_already_known code)))))

(deftest oferta-aceitar-e-recusar
  (let [s (fresh-store! "a2-offer")
        chat "chat-a2-offer"
        player "leaf"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player
                                   (assoc pikachu
                                          :golpes
                                          [{:nome-exibicao "A" :tipo "electric" :poder 40 :classe :especial}
                                           {:nome-exibicao "B" :tipo "normal" :poder 40 :classe :fisico}
                                           {:nome-exibicao "C" :tipo "electric" :poder 50 :classe :especial}
                                           {:nome-exibicao "D" :tipo "normal" :poder 0 :classe :status}])
                                   45 nil)
    (let [r (moves-app/offer-move!
             s chat player
             {:operation-id "offer-1"
              :idx 0
              :golpe {:nome-exibicao "Iron Tail" :tipo "steel"
                      :poder 100 :classe :fisico}})]
      (is (= :pendente (:resultado r)))
      (is (= 1 (count (moves/ofertas (:registro r))))))
    (let [r2 (moves-app/refuse-offer!
              s chat player
              {:operation-id "refuse-1" :idx 0})]
      (is (false? (:duplicate r2)))
      (is (empty? (moves/ofertas (:registro r2)))))
    (moves-app/offer-move!
     s chat player
     {:operation-id "offer-2"
      :idx 0
      :golpe {:nome-exibicao "Iron Tail" :tipo "steel"
              :poder 100 :classe :fisico}})
    (let [r3 (moves-app/learn-move!
              s chat player
              {:operation-id "accept-offer"
               :idx 0
               :slot 3
               :oferta? true
               :golpe {:nome-exibicao "Iron Tail" :tipo "steel"
                       :poder 100 :classe :fisico}})
          poke (first (:colecao (collection/consultar-treinador s chat player)))]
      (is (false? (:duplicate r3)))
      (is (empty? (moves/ofertas poke)))
      (is (some #(= "iron-tail" (get % "slug")) (:golpes poke))))))

(deftest forget-e-relearn-idempotente
  (let [s (fresh-store! "a2-forget")
        chat "chat-a2-fg"
        player "red"]
    (boot! s chat player)
    (collection/adicionar-pokemon! s chat player pikachu 45 nil)
    (let [r (moves-app/forget-move!
             s chat player
             {:operation-id "forget-1" :idx 0 :slot 1})
          poke (first (:colecao (collection/consultar-treinador s chat player)))]
      (is (= 1 (count (:golpes poke))))
      (is (some #{"investida"} (map moves/identificador (:golpes-removidos poke)))))
    (let [r2 (moves-app/forget-move!
              s chat player
              {:operation-id "forget-1" :idx 0 :slot 1})]
      (is (true? (:duplicate r2))))
    (let [r3 (moves-app/relearn-move!
              s chat player
              {:operation-id "relearn-1"
               :idx 0
               :slug "investida"
               :slot nil})
          poke (first (:colecao (collection/consultar-treinador s chat player)))
          coins (:coins (ports/get-save s chat player "a"))]
      (is (false? (:duplicate r3)))
      (is (= 2 (count (:golpes poke))))
      (is (= (- 200 moves/preco-reaprender) coins)))
    (let [r4 (moves-app/relearn-move!
              s chat player
              {:operation-id "relearn-1"
               :idx 0
               :slug "investida"
               :slot nil})
          coins (:coins (ports/get-save s chat player "a"))]
      (is (true? (:duplicate r4)))
      (is (= (- 200 moves/preco-reaprender) coins)))))

(deftest dominio-remover-preserva-stab
  (let [reg {:tipos ["electric"]
             :golpes [{"slug" "choque" "nome-exibicao" "Choque" "tipo" "electric"
                       "poder" 40 "classe" "especial"}
                      {"slug" "tackle" "nome-exibicao" "Tackle" "tipo" "normal"
                       "poder" 40 "classe" "fisico"}]
             :golpes-removidos []
             :historico-golpes {}}
        code (try
               (moves/remover-golpe reg 0)
               :ok
               (catch Exception ex (err/error-code ex)))]
    (is (= :must_keep_stab_move code))
    (is (= 1 (count (:golpes (moves/remover-golpe reg 1)))))))
