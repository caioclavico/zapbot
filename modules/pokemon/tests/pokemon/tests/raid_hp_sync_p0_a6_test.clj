(ns pokemon.tests.raid-hp-sync-p0-a6-test
  "P0: dano/desmaio/troca de raid persistem :pokemon/hp-atual (Datomic descartável)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as bridge-tx]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capture :as capture]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-hp-sync-p0")
(def ^:private storage-rel ".datomic-local/raid-hp-sync-p0")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- mon
  [nome hp]
  ;; Ataque baixo: não one-shot o chefe (precisa receber revide e persistir HP).
  {:nome nome :species nome :hp hp :ataque 40 :defesa 40 :atq-esp 40 :def-esp 40
   :veloc 50 :tipos ["water"] :nivel 20 :raridade "raro"
   :imagem (str "https://example.test/sprites/" nome ".png")
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 40 :classe :especial
             :slug "water-gun"}]})

(defn- chefe
  []
  {:nome "Boss" :species "charmander" :hp 80 :ataque 40 :defesa 80 :atq-esp 40 :def-esp 80
   :veloc 30 :tipos ["fire"] :nivel 10 :raridade "raro" :fator-dificuldade 1.0
   :golpes [{:nome-exibicao "Brasas" :tipo "fire" :poder 40 :classe :especial}]})

(defn- fence
  [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(deftest slim-golpe-string-keys-sem-slug
  (testing "EDN string-key sem slug preserva nome/poder (Wartortle ? Poder 0)"
    (let [raw {"nome" "Investida" "poder" 40 "tipo" "normal" "classe" "fisico"}
          slim (battle/slim-golpe-for-battle raw)]
      (is (= "Investida" (:nome-exibicao slim)))
      (is (= 40 (:poder slim)))
      (is (= :fisico (:classe slim)))
      (is (not= "?" (:nome-exibicao slim)))))
  (testing "Wartortle legado: mistura nome-exibicao string + poder"
    (let [gs [{"nome-exibicao" "Jato d'Água" "tipo" "water" "poder" 40 "classe" "especial"}
              {"nome" "Protect" "poder" 0 "classe" "status" "tipo" "normal"}
              {:slug "bite" :nome-exibicao "Mordida" :tipo "dark" :poder 60 :classe :fisico}]
          slim (mapv battle/slim-golpe-for-battle gs)]
      (is (= "Jato d'Água" (:nome-exibicao (nth slim 0))))
      (is (= 40 (:poder (nth slim 0))))
      (is (= :especial (:classe (nth slim 0))))
      (is (= "Protect" (:nome-exibicao (nth slim 1))))
      (is (= :status (:classe (nth slim 1))))
      (is (= "Mordida" (:nome-exibicao (nth slim 2))))))
  (testing "status válido poder 0 mantém nome+classe"
    (let [slim (battle/slim-golpe-for-battle
                {"nome" "Protect" "poder" 0 "classe" "status" "tipo" "normal"})]
      (is (= "Protect" (:nome-exibicao slim)))
      (is (= :status (:classe slim)))
      (is (or (nil? (:poder slim)) (zero? (long (:poder slim 0))))))))

(deftest raid-party-hp-txs-unitario
  (let [colecao [{:id-pokemon "p1" :hp-atual 100 :status nil}
                 {:id-pokemon "p2" :hp-atual 80 :status nil}]
        party [{:pokemon {:id-pokemon "p1"} :hp 37 :collection-index 0}
               {:pokemon {:id-pokemon "p2"} :hp 0 :collection-index 1}]
        query (fn [_ _] colecao)
        txs (raid-store/raid-party-hp-txs
             nil "sk" party query
             (fn [poke hp _st _prev]
               [[:db/add [:pokemon/id-pokemon (:id-pokemon poke)]
                 :pokemon/hp-atual (long hp)]]))]
    (is (= 2 (count txs)))
    (is (some #(= 37 (last %)) txs))
    (is (some #(= 0 (last %)) txs))))

(deftest ataque-raid-persiste-hp-na-colecao
  (let [s (fresh-store! "raid-hp-atk")
        chat "c-hp"
        player "ash"
        rid "raid-hp-1"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (collection/adicionar-pokemon! s chat player (mon "Wartortle" 120) 120 nil)
    (collection/adicionar-pokemon! s chat player (mon "Blastoise" 150) 150 nil)
    (let [before (ports/list-collection s chat player "a")]
      (is (= [120 150] (mapv :hp-atual before))))
    (raid-app/open-raid!
     s chat player
     {:operation-id "open-hp" :raid-id rid :ginasio "fogo" :nome-ginasio "Fogo"
      :nivel-ginasio 20 :liga "bronze" :chefe (chefe)
      :min-jogadores 1 :agora 1000})
    (raid-app/join-raid!
     s chat player
     {:operation-id "join-hp" :raid-id rid
      :collection-index 0 :nome "Ash" :agora 1001})
    (raid-app/start-raid!
     s chat player
     {:operation-id "start-hp" :raid-id rid :agora 1002})
    ;; Um ataque: revide reduz HP do ativo no snapshot → deve gravar na coleção.
    (let [r0 (raid-app/get-raid s rid)
          _ (is (= "combate" (:fase r0)))
          out (raid-app/attack!
               s chat player
               {:operation-id "atk-hp-0" :raid-id rid
                :expected-turn-index (:turn-index r0)
                :move-index 0 :agora 1010})
          after (ports/list-collection s chat player "a")
          hp0 (:hp-atual (nth after 0))
          hp1 (:hp-atual (nth after 1))]
      (is (false? (boolean (:venceu? out))))
      (is (< hp0 120) "ativo sofreu revide persistido")
      (is (= 150 hp1) "reserva intacta até entrar")
      (is (= (get-in out [:raid :participantes player :hp]) hp0)
          "HP coleção = snapshot ativo"))))

(deftest leave-raid-combate-persiste-hp
  (let [s (fresh-store! "raid-hp-leave")
        chat "c-leave"
        player "ash"
        rid "raid-hp-leave"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (collection/adicionar-pokemon! s chat player (mon "Squirtle" 100) 100 nil)
    (raid-app/open-raid!
     s chat player
     {:operation-id "open-lv" :raid-id rid :ginasio "fogo" :nome-ginasio "Fogo"
      :nivel-ginasio 20 :liga "bronze" :chefe (chefe)
      :min-jogadores 1 :agora 2000})
    (raid-app/join-raid!
     s chat player
     {:operation-id "join-lv" :raid-id rid :collection-index 0
      :nome "Ash" :agora 2001})
    (raid-app/start-raid!
     s chat player
     {:operation-id "start-lv" :raid-id rid :agora 2002})
    (let [r0 (raid-app/get-raid s rid)]
      (raid-app/attack!
       s chat player
       {:operation-id "atk-lv-0" :raid-id rid
        :expected-turn-index (:turn-index r0)
        :move-index 0 :agora 2010}))
    (let [hp-mid (:hp-atual (first (ports/list-collection s chat player "a")))]
      (is (< hp-mid 100))
      (bridge-tx/leave-battle!
       s {:chat-id chat :player-id player :save-id "a"
          :fence-generation (fence s chat player)
          :operation-id "leave-lv" :agora-ms 2020})
      (let [hp-after (:hp-atual (first (ports/list-collection s chat player "a")))]
        (is (= hp-mid hp-after) "leave não restaura HP")
        (is (< hp-after 100))))))


(deftest vitoria-xp-nao-cura-hp-do-snapshot
  (let [s (fresh-store! "raid-hp-xp")
        chat "c-xp"
        player "ash"
        rid "raid-hp-xp"
        mon-xp (assoc (mon "Wartortle" 200)
                      :golpes [{"nome-exibicao" "Jato d'Água" "tipo" "water"
                                "poder" 40 "classe" "especial"}])]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (collection/adicionar-pokemon! s chat player mon-xp 200 nil)
    (raid-app/open-raid!
     s chat player
     {:operation-id "open-xp" :raid-id rid :ginasio "fogo" :nome-ginasio "Fogo"
      :nivel-ginasio 20 :liga "bronze" :chefe (chefe)
      :min-jogadores 1 :agora 3000})
    (raid-app/join-raid!
     s chat player
     {:operation-id "join-xp" :raid-id rid :collection-index 0
      :nome "Ash" :agora 3001})
    (raid-app/start-raid!
     s chat player
     {:operation-id "start-xp" :raid-id rid :agora 3002})
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)]
        (when (and (= "combate" (str (:fase r))) (< n 40))
          (raid-app/attack!
           s chat player
           {:operation-id (str "atk-xp-" n) :raid-id rid
            :expected-turn-index (:turn-index r)
            :move-index 0 :agora (+ 3010 n)})
          (recur (inc n)))))
    (let [r (raid-app/get-raid s rid)
          after (first (ports/list-collection s chat player "a"))
          snap-hp (get-in r [:participantes player :hp])]
      (is (= "vitoria" (str (:fase r))))
      (is (true? (:rewards-paid? r)))
      (is (= (long snap-hp) (long (:hp-atual after)))
          "HP coleção = snapshot pós-vitória/XP (não full 200)")
      (is (< (long (:hp-atual after)) 200)))))

(deftest captura-debita-alias-great-ball
  (testing "UI soma great-ball; débito canônico consome alias legado"
    (let [s (fresh-store! "ball-alias")
          chat "c-ball"
          player "ash"]
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (reward-app/admin-seed-reward!
       s {:operation-id "seed-gb" :chat-id chat :player-id player :save-id "a"
          :item {:item-id "great-ball" :qty 2}})
      (let [inv (ports/list-inventory s chat player "a")
            ids (capture/bola-alias-ids "grande-bola")
            ui-qty (reduce + 0 (map #(long (:qty % 0))
                                    (filter #(contains? ids (str (:item-id %))) inv)))]
        (is (= 2 ui-qty))
        (is (zero? (long (or (some #(when (= "grande-bola" (:item-id %)) (:qty %)) inv) 0)))))
      (capture-app/attempt-capture!
       s chat player
       {:operation-id "cap-alias"
        :pokemon {:nome "Zubat" :species "zubat" :nivel 5
                  :hp 20 :hp-atual 20 :ataque 10 :defesa 10
                  :atq-esp 10 :def-esp 10 :veloc 30
                  :tipos ["poison"] :raridade "comum"}
        :bola "grande-bola" :roll 0 :fuga-roll 99})
      (let [inv2 (ports/list-inventory s chat player "a")
            left (long (or (some #(when (= "great-ball" (:item-id %)) (:qty %)) inv2) 0))]
        (is (= 1 left) "debitou great-ball legado")))))
