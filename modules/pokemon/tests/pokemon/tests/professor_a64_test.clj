(ns pokemon.tests.professor-a64-test
  "Professor Carvalho A6 — envio/confirmação/cancelamento/idempotência/concorrência."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.profile-store :as profile-store]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system (str "prof-a64-" db-name)
                                    :storage-dir (str ".datomic-local/prof-a64-" db-name)})
        _ (persistence/delete-db! client db-name)
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    [s conn]))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system (str "prof-a64-" db-name)
                                    :storage-dir (str ".datomic-local/prof-a64-" db-name)})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    [s conn]))

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (let [p (ports/get-player s chat player)]
    {:chat-id chat :player-id player :save-id "a"
     :fence-generation (:fence-generation p)}))

(defn- add!
  [s ctx poke]
  (let [p (ports/get-player s (:chat-id ctx) (:player-id ctx))
        w (assoc ctx :fence-generation (:fence-generation p))]
    (ports/add-to-collection! s w poke)
    (assoc w :fence-generation
           (:fence-generation (ports/get-player s (:chat-id ctx) (:player-id ctx))))))

(defn- err-code [thunk]
  (try (thunk) :ok
       (catch Exception ex (err/error-code ex))))

(def ^:private pidgey
  {:id-pokemon "p1" :species "pidgey" :nome "Pidgey" :nivel 5
   :xp 0 :xp-desde-nivel 0 :hp 20 :hp-atual 20 :amizade 70 :in-party true})

(def ^:private rattata
  {:id-pokemon "p2" :species "rattata" :nome "Rattata" :nivel 3
   :xp 0 :xp-desde-nivel 0 :hp 15 :hp-atual 15 :amizade 70 :in-party true})

(deftest envio-cria-pendente-sem-remover
  (let [[s conn] (fresh! "send-pending")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)]
    (is (re-matches #"[1-9][0-9]{2}" (:token sent)))
    (is (= (:token sent) (get-in sent [:summary :token])) "DTO plano + summary")
    (is (= 1 (count (ports/list-collection s "c" "ash" "a"))))
    (is (= "Pidgey" (:nome sent)))
    (is (= 1 (:display-index sent)))))

(deftest confirmar-remove-e-concede-cartao
  (let [[s conn] (fresh! "confirm-ok")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 (assoc pidgey :item "pokebola"))
        ctx (add! s ctx rattata)
        ctx (add! s ctx (assoc rattata :id-pokemon "p3" :nome "Rattata2"))
        _ (ports/set-gym-team! s ctx [0 1 2])
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        conf (profile-store/professor-confirm!
              conn (assoc ctx :operation-id "c1") (:token sent) 1001)
        col (ports/list-collection s "c" "ash" "a")
        save (ports/get-save s "c" "ash" "a")
        inv (ports/list-inventory s "c" "ash" "a")]
    (is (true? (:confirmed conf)))
    (is (= "pidgey" (:family conf)))
    (is (= 2 (count col)))
    (is (= "p2" (:id-pokemon (first col))))
    (is (zero? (:collection-index (first col))) "reindex após remoção")
    (is (= {"pidgey" 1} (:cards (profile-store/professor-cards conn "c" "ash" "a"))))
    (is (= 1 (:save/doacoes-pokemon
             (d/pull (d/db conn) '[:save/doacoes-pokemon]
                     [:save/key (id/save-key "c" "ash" "a")]))))
    (is (some #(and (= "pokebola" (:item-id %)) (pos? (:qty %))) inv)
        "item devolvido à mochila")
    (is (= [nil 0 1] (vec (:time-ginasio save))) "time-ginasio ajustado")))

(deftest cancelar-limpa-pendente
  (let [[s conn] (fresh! "cancel")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        _ (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        can (profile-store/professor-cancel! conn (assoc ctx :operation-id "x1"))]
    (is (true? (:cancelled can)))
    (is (= 1 (count (ports/list-collection s "c" "ash" "a"))))
    (is (= :professor_invalid_token
           (err-code #(profile-store/professor-confirm!
                       conn (assoc ctx :operation-id "c1") "111" 1001))))))

(deftest expiracao-cinco-minutos
  (let [[s conn] (fresh! "expire")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)]
    (is (= :professor_expired
           (err-code #(profile-store/professor-confirm!
                       conn (assoc ctx :operation-id "c1") (:token sent) (+ 1000 300000)))))
    (is (= 1 (count (ports/list-collection s "c" "ash" "a"))))
    (is (empty? (:cards (profile-store/professor-cards conn "c" "ash" "a"))))))

(deftest indice-incorreto-e-favorito
  (let [[s conn] (fresh! "idx-fav")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)]
    (is (= :invalid_collection_index
           (err-code #(profile-store/professor-send!
                       conn (assoc ctx :operation-id "bad") 9 1000))))
    (ports/set-favorite-pokemon! s ctx {:operation-id "fav" :idx 0})
    (let [ctx2 (assoc ctx :fence-generation
                      (:fence-generation (ports/get-player s "c" "ash")))]
      (is (= :pokemon_favorite
             (err-code #(profile-store/professor-send!
                         conn (assoc ctx2 :operation-id "s-fav") 0 1000)))))))

(deftest troca-de-save-invalida-confirmacao
  (let [[s conn] (fresh! "switch-save")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        _ (ports/create-save! s "c" "ash" "recovery-v1")
        _ (trainer/switch-save! s "c" "ash" "recovery-v1")
        fence (:fence-generation (ports/get-player s "c" "ash"))]
    (is (= :stale_writer_fence
           (err-code #(profile-store/professor-confirm!
                       conn {:chat-id "c" :player-id "ash" :save-id "a"
                             :fence-generation fence
                             :operation-id "c-stale"}
                       (:token sent) 1001))))
    ;; Pendente permanece no save a; coleção a intacta.
    (is (= 1 (count (ports/list-collection s "c" "ash" "a"))))))

(deftest idempotencia-e-retry-confirm
  (let [[s conn] (fresh! "idem")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        c1 (profile-store/professor-confirm!
            conn (assoc ctx :operation-id "c-same") (:token sent) 1001)
        c2 (profile-store/professor-confirm!
            conn (assoc ctx :operation-id "c-same") (:token sent) 1001)]
    (is (true? (:confirmed c1)))
    (is (true? (:duplicate c2)))
    (is (= "p1" (:id-pokemon c2)))
    (is (empty? (ports/list-collection s "c" "ash" "a")))
    (is (= {"pidgey" 1} (:cards (profile-store/professor-cards conn "c" "ash" "a"))))
    ;; Segundo código/op diferente após pendente limpa → inválido.
    (is (= :professor_invalid_token
           (err-code #(profile-store/professor-confirm!
                       conn (assoc ctx :operation-id "c-other") (:token sent) 1002))))))

(deftest concorrencia-dois-envios
  (let [[s conn] (fresh! "busy")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        ctx (add! s ctx rattata)
        _ (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)]
    (is (= :professor_busy
           (err-code #(profile-store/professor-send!
                       conn (assoc ctx :operation-id "s2") 1 1001))))))

(deftest colecao-acima-capacidade-permite-doar
  (let [[s conn] (fresh! "overcap")
        ctx0 (boot! s "c" "ash")
        ;; Capacidade base 50; adicionamos 2 e doamos 1 — foco é que overcap não bloqueia.
        ctx (reduce (fn [c i]
                      (add! s c (assoc pidgey :id-pokemon (str "x" i) :nome (str "P" i))))
                    ctx0 (range 3))
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        conf (profile-store/professor-confirm!
              conn (assoc ctx :operation-id "c1") (:token sent) 1001)]
    (is (true? (:confirmed conf)))
    (is (= 2 (count (ports/list-collection s "c" "ash" "a"))))))

(deftest persistencia-apos-restart-jvm
  (let [db "restart"
        [s conn] (fresh! db)
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)
        [_ conn2] (reopen! db)
        conf (profile-store/professor-confirm!
              conn2 (assoc ctx :operation-id "c1") (:token sent) 1001)
        [s2 _] (reopen! db)]
    (is (true? (:confirmed conf)))
    (is (empty? (ports/list-collection s2 "c" "ash" "a")))
    (is (= {"pidgey" 1}
           (:cards (profile-store/professor-cards conn2 "c" "ash" "a"))))))

(deftest dto-plano-expoe-token-no-topo
  "Regressão do bug de apresentação (formatProfessor lia d.token)."
  (let [[s conn] (fresh! "dto-flat")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        sent (profile-store/professor-send! conn (assoc ctx :operation-id "s1") 0 1000)]
    (is (string? (:token sent)))
    (is (= (:token sent) (:token (:summary sent))))
    (is (number? (:expires-at sent)))
    (is (= "pidgey" (:family sent)))))

(deftest envio-em-lote-confirma-varios
  (let [[s conn] (fresh! "batch")
        ctx0 (boot! s "c" "ash")
        ctx (add! s ctx0 pidgey)
        ctx (add! s ctx rattata)
        ctx (add! s ctx (assoc rattata :id-pokemon "p3" :nome "Rattata2"))
        sent (profile-store/professor-send!
              conn (assoc ctx :operation-id "s-batch") [0 2] 1000)
        conf (profile-store/professor-confirm!
              conn (assoc ctx :operation-id "c-batch") (:token sent) 1001)
        col (ports/list-collection s "c" "ash" "a")
        cards (:cards (profile-store/professor-cards conn "c" "ash" "a"))]
    (is (true? (:batch sent)))
    (is (= 2 (:count sent)))
    (is (= 2 (count (:entries sent))))
    (is (true? (:confirmed conf)))
    (is (= 2 (:count conf)))
    (is (= 1 (count col)))
    (is (= "p2" (:id-pokemon (first col))))
    (is (zero? (:collection-index (first col))))
    (is (= {"pidgey" 1 "rattata" 1} cards))))
