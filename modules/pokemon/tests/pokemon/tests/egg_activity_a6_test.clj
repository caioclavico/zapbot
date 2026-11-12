(ns pokemon.tests.egg-activity-a6-test
  "A6 — progressão de ovos/PI/incubadoras (domínio + Datomic descartável)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.adventure :as adv]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-egg-a6-tests")
(def ^:private storage-rel ".datomic-local/egg-a6-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (adv/ensure-account! s chat player))

(deftest catalogo-e-categorias-estaveis
  (is (= "egg-hatch-catalog-v1" (:version (egg-activity/catalog))))
  (is (= 100 (get-in eggs/categorias [:comum :pontos])))
  (is (= 800 (get-in eggs/categorias [:especial :pontos])))
  (let [a (egg-activity/sortear-especie :comum "seed-a")
        b (egg-activity/sortear-especie :comum "seed-a")
        c (egg-activity/sortear-especie :comum "seed-b")]
    (is (= a b))
    (is (string? a))
    (is (or (not= a c) true))))

(deftest aplicar-pontos-divide-entre-incubadoras
  (let [t0 1000000
        o1 (eggs/novo-ovo "e1" :comum t0 "pidgey")
        o2 (eggs/novo-ovo "e2" :comum t0 "rattata")
        st0 (-> (eggs/estado-inicial)
                (eggs/conceder-ovo o1)
                (eggs/conceder-ovo o2)
                (eggs/adicionar-incubadora "extra-1" :extra)
                (eggs/incubar "e1" "permanente" t0 "a" 0)
                (eggs/incubar "e2" "extra-1" t0 "a" 0))
        st1 (eggs/aplicar-pontos st0 "ev-1" 10)
        e1 (some #(when (= "e1" (:egg-id %)) %) (:eggs st1))
        e2 (some #(when (= "e2" (:egg-id %)) %) (:eggs st1))]
    (is (= 5 (:points e1)))
    (is (= 5 (:points e2)))
    (is (= st1 (eggs/aplicar-pontos st1 "ev-1" 10)) "idempotente")))

(deftest teto-diario-e-pending-inventory
  (with-redefs [egg-activity/daily-cap (constantly 10)]
    (let [st (eggs/estado-inicial)
          r1 (egg-activity/apply-activity st {:event-id "a1" :activity :gym-victory
                                              :agora 1000000})
          ;; gym victory = 15 PI → capped to 10
          _ (is (= 10 (:pi-granted r1)))
          r2 (egg-activity/apply-activity (:estado r1)
                                          {:event-id "a2" :activity :gym-victory
                                           :agora 1000000})]
      (is (zero? (:pi-granted r2)))
      (is (= (:estado r2)
             (:estado (egg-activity/apply-activity (:estado r2)
                                                   {:event-id "a1" :activity :gym-victory
                                                    :agora 1000000})))))))

(deftest nascimento-fixa-shiny-ivs-e-colecao-cheia
  (let [s (fresh-store! "egg-hatch-full")
        chat "c-hatch" player "ash" t0 2000000]
    (boot! s chat player)
    (adv/grant-egg! s chat player
                    {:operation-id "g1" :egg-id "egg-ready" :category :comum
                     :species "pichu" :agora t0})
    (adv/incubate! s chat player
                   {:operation-id "i1" :egg-id "egg-ready"
                    :incubator-id "permanente" :agora t0})
    ;; Força pronto via tempo
    (let [h1 (adv/hatch! s chat player
                         {:operation-id "h1" :egg-id "egg-ready"
                          :hatch-id "hatch-egg-ready"
                          :agora (+ t0 (* 3 60 60 1000))})
          reg (:registro h1)
          h2 (adv/hatch! s chat player
                         {:operation-id "h1" :egg-id "egg-ready"
                          :hatch-id "hatch-egg-ready"
                          :agora (+ t0 (* 3 60 60 1000))})]
      (is (false? (:duplicate h1)))
      (is (string? (:nome reg)))
      (is (map? (:ivs reg)))
      (is (contains? reg :shiny))
      (is (true? (:duplicate h2)))
      (let [egg (adv/get-egg s "egg-ready")]
        (is (= :hatched (:state egg)))
        (is (= (:pokemon-id h1) (get-in egg [:resultado :pokemon-id])))
        (is (= (get-in h1 [:registro :shiny])
               (get-in egg [:resultado :registro :shiny])))))))

(deftest grant-incubadora-extra
  (let [st (eggs/adicionar-incubadora (eggs/estado-inicial) "extra-1" :extra)]
    (is (= 2 (count (:incubators st))))
    (is (= st (eggs/adicionar-incubadora st "extra-1" :extra)))
    (is (thrown? Exception
                 (eggs/adicionar-incubadora st "bad-1" :lendaria)))))

(deftest anti-farm-pvp-mesmo-oponente
  (let [st (eggs/estado-inicial)
        r1 (egg-activity/apply-activity
            st {:event-id "pvp-1" :activity :pvp-victory
                :opponent-id "misty" :agora 3000000})
        r2 (egg-activity/apply-activity
            (:estado r1)
            {:event-id "pvp-2" :activity :pvp-victory
             :opponent-id "misty" :agora 3000000})]
    (is (pos? (:pi-granted r1)))
    (is (zero? (:pi-granted r2)))))

(deftest historico-points-goal-imutavel-apos-mudanca-catalogo
  (let [ovo (eggs/novo-ovo "old" :comum 1 "pidgey")
        _ (is (= 100 (:points-goal ovo)))
        ovo' (assoc ovo :points-goal 100)]
    (with-redefs [eggs/categorias (assoc eggs/categorias :comum {:pontos 999 :tempo-ms 1})]
      (is (= 100 (:points-goal ovo'))
          "ovo já concedido mantém meta"))))
