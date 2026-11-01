(ns pokemon.tests.trainer-profile-a64-test
  "Perfil consolidado !pk treinador — leitura A6 (saves, capacidade, economia, joy, caça)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-trainer-profile-a64")
(def ^:private storage-rel ".datomic-local/trainer-profile-a64-tests")

(defn- fresh!
  "Retorna {:store :conn}."
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      {:store s :conn conn})))

(defn- seed-n!
  "Garante save ativo = save-id (fence fresco) e adiciona n Pokémon.
   add-to-collection! sempre marca in-party=true (legado unificado)."
  [s chat player save-id n & {:keys [shiny]}]
  (when-not (= save-id (:active-save-id (ports/get-player s chat player)))
    (trainer/switch-save! s chat player save-id))
  (dotimes [i n]
    (let [p (ports/get-player s chat player)
          ctx {:chat-id chat :player-id player :save-id save-id
               :fence-generation (:fence-generation p)}]
      (ports/add-to-collection!
       s ctx
       {:id-pokemon (str "tp-" save-id "-" (System/nanoTime) "-" i)
        :species "rattata" :nome (str "Rattata-" i)
        :shiny (boolean (and shiny (zero? (mod i 10))))
        :nivel 1 :hp 10 :hp-atual 10 :xp 0 :amizade 70}))))

(deftest treinador-recem-criado
  (let [{:keys [store]} (fresh! "tp-new")
        chat "chat-new" player "ash"]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (let [tr (reads/trainer store chat player)
          saves (:saves tr)]
      (is (= "a" (:active-save-id tr)))
      (is (zero? (:collection-count tr)))
      (is (false? (:colecao-cheia tr)))
      (is (= 50 (:capacidade-pokemon tr)) "inicial Pokémon 50")
      (is (= 50 (:capacidade-mochila tr)) "mochila inicial 50")
      (is (true? (:economia-confiavel tr)))
      (is (contains? tr :coins))
      (is (= 0 (:coins tr)))
      (is (= 2 (count saves)))
      (is (some #(and (= "a" (:save-id %)) (true? (:active %))) saves))
      (is (some #(= "b" (:save-id %)) saves))
      (is (true? (get-in tr [:hunt :cooldown-ok])))
      (is (zero? (get-in tr [:joy :em-tratamento])))
      (is (true? (:party-defined tr)))
      (is (zero? (:party-count tr))))))

(deftest party-battle-resumo-heuristica
  (testing "coleção vazia = equipe 0 definida"
    (is (= {:party-defined true :party-count 0}
           (#'reads/party-battle-resumo []))))
  (testing "todos in-party (V1 unificado) = indefinida — não inventa 6"
    (is (= {:party-defined false :party-count nil}
           (#'reads/party-battle-resumo
            (mapv (fn [i] {:in-party true :nome (str i)}) (range 119))))))
  (testing "equipe parcial confiável"
    (is (= {:party-defined true :party-count 3}
           (#'reads/party-battle-resumo
            [{:in-party true} {:in-party true} {:in-party true}
             {:in-party false} {:in-party false} {:in-party false} {:in-party false}]))))
  (testing "equipe ausente (ninguém in-party) com coleção = indefinida"
    (is (= {:party-defined false :party-count nil}
           (#'reads/party-battle-resumo
            [{:in-party false} {:in-party false} {:in-party false}])))))

(deftest recovery-v1-cheio-economia-desconhecida
  (let [{:keys [store conn]} (fresh! "tp-v1")
        chat "chat-v1" player "recovery-user"
        sk (id/save-key chat player "recovery-v1")]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (ports/create-save! store chat player "recovery-v1")
    (d/transact conn {:tx-data
                      [[:db/add [:save/key sk] :save/origin "offline-import"]
                       [:db/add [:save/key sk] :save/legacy-version "unknown"]
                       [:db/add [:save/key sk] :save/display-name "recovery-v1"]
                       [:db/add [:save/key sk] :save/trainer-level 10]
                       [:db/add [:save/key sk] :save/trainer-xp 127]
                       [:db/add [:save/key sk] :save/coins 0]]})
    (trainer/switch-save! store chat player "recovery-v1")
    (seed-n! store chat player "recovery-v1" 119 :shiny true)
    (let [tr (reads/trainer store chat player)
          saves (:saves tr)
          ativo (first (filter :active saves))]
      (is (= "recovery-v1" (:active-save-id tr)))
      (is (= 119 (:collection-count tr)))
      (is (= 100 (:capacidade-pokemon tr)) "nv.10 → 100")
      (is (= 19 (:excesso-pokemon tr)))
      (is (true? (:colecao-cheia tr)))
      (is (false? (:economia-confiavel tr)))
      (is (= "unknown-pending" (:coins-status tr)))
      (is (nil? (:coins tr)) "não vaza coins=0 falso")
      (is (nil? (get-in tr [:profile :coins])))
      (is (= 119 (:collection-count ativo)))
      (is (>= (:shiny-count tr) 1))
      (is (false? (:party-defined tr)) "V1 unificado: não inventa equipe")
      (is (nil? (:party-count tr)))
      (is (= 3 (count saves))))))

(deftest equipe-parcial-quando-in-party-distingue
  (let [{:keys [store conn]} (fresh! "tp-party")
        chat "chat-party" player "surge"]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (seed-n! store chat player "a" 8)
    (let [coll (ports/list-collection store chat player "a")
          ;; marca 5 últimos como fora da equipe (pc)
          tx (mapv (fn [p]
                     [:db/add [:pokemon/id-pokemon (:id-pokemon p)] :pokemon/in-party false])
                   (drop 3 coll))]
      (d/transact conn {:tx-data tx})
      (let [tr (reads/trainer store chat player)]
        (is (= 8 (:collection-count tr)))
        (is (true? (:party-defined tr)))
        (is (= 3 (:party-count tr)) "só os 3 in-party")))))

(deftest varios-saves-resumo
  (let [{:keys [store]} (fresh! "tp-multi")
        chat "chat-m" player "brock"]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (seed-n! store chat player "a" 5)
    (seed-n! store chat player "b" 2)
    ;; volta para a como ativo para o resumo
    (trainer/switch-save! store chat player "a")
    (let [tr (reads/trainer store chat player)
          by-id (into {} (map (juxt :save-id identity) (:saves tr)))]
      (is (= 2 (:saves-count tr)))
      (is (= 5 (get-in by-id ["a" :collection-count])))
      (is (= 2 (get-in by-id ["b" :collection-count])))
      (is (true? (get-in by-id ["a" :active])))
      (is (false? (get-in by-id ["b" :active]))))))

(deftest hunt-cooldown-somente-leitura
  (let [{:keys [store conn]} (fresh! "tp-cd")
        chat "chat-cd" player "misty"
        agora 1700000000000
        sk (id/save-key chat player "a")]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (seed-n! store chat player "a" 1)
    (d/transact conn {:tx-data [[:db/add [:save/key sk] :save/ultima-cacada-at agora]]})
    (let [tr (reads/trainer store chat player {:agora-ms agora})]
      (is (false? (get-in tr [:hunt :cooldown-ok])))
      (is (pos? (get-in tr [:hunt :cooldown-restante-s]))))
    (let [later (+ agora (* 31 60 1000))
          tr2 (reads/trainer store chat player {:agora-ms later})]
      (is (true? (get-in tr2 [:hunt :cooldown-ok])))
      (is (zero? (get-in tr2 [:hunt :cooldown-restante-s]))))))

(deftest perfil-nao-muta-estado
  (let [{:keys [store]} (fresh! "tp-ro")
        chat "chat-ro" player "oak"]
    (trainer/create-trainer! store {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     store {:operation-id "seed-ro" :chat-id chat :player-id player :save-id "a"
            :coins-delta 40})
    (seed-n! store chat player "a" 1)
    (let [before (ports/get-save store chat player "a")
          fence (:fence-generation (ports/get-player store chat player))
          _ (dotimes [_ 3] (reads/trainer store chat player))
          after (ports/get-save store chat player "a")
          player-after (ports/get-player store chat player)]
      (is (= (:coins before) (:coins after) 40))
      (is (= (:revision before) (:revision after)))
      (is (= fence (:fence-generation player-after)))
      (is (= 1 (count (ports/list-collection store chat player "a")))))))
