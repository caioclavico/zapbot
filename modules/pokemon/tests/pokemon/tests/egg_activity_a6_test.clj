(ns pokemon.tests.egg-activity-a6-test
  "A6 — progressão de ovos/PI/incubadoras (domínio + Datomic descartável)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.adventure :as adv]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]
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
          ;; gym victory = 25 PI → capped to 10
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
    (is (= :egg_not_ready
           (try
             (adv/hatch! s chat player {:operation-id "early" :egg-id "egg-ready"
                                        :agora (+ t0 (* 3 60 60 1000))})
             (catch Exception ex (err/error-code ex)))))
    (d/transact (:conn s) {:tx-data [[:db/add [:egg/id "egg-ready"] :egg/points 100]]})
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

(deftest ovo-legado-pronto-por-tempo-volta-a-receber-pi
  (let [ovo (assoc (eggs/novo-ovo "legacy" :comum 0 "pichu")
                   :state :ready-to-hatch :incubated-at 0 :points 99)
        estado (-> (eggs/estado-inicial)
                   (assoc :eggs [ovo])
                   (assoc-in [:incubators 0 :active-egg-id] "legacy"))
        avancado (eggs/avancar-tempo estado (:time-goal-ms ovo))
        pontuado (eggs/aplicar-pontos estado "point" 1)]
    (is (= :incubating (get-in avancado [:eggs 0 :state])))
    (is (= 100 (get-in pontuado [:eggs 0 :points])))
    (is (= :ready-to-hatch (get-in pontuado [:eggs 0 :state])))
    (is (= pontuado (eggs/aplicar-pontos pontuado "point" 1)))))

(deftest pi-de-atividade-e-persistido-no-ovo
  (let [s (fresh-store! "egg-activity-points")
        chat "c-activity" player "ash" t0 2000000]
    (boot! s chat player)
    (adv/grant-egg! s chat player
                    {:operation-id "g-pi" :egg-id "egg-pi" :category :comum
                     :species "pichu" :agora t0})
    (adv/incubate! s chat player
                   {:operation-id "i-pi" :egg-id "egg-pi"
                    :incubator-id "permanente" :agora t0})
    (let [account (adv/get-account s chat player)
          {:keys [tx]} (adventure-store/build-activity-enrich-tx
                        {:writer-ctx {:chat-id chat :player-id player}
                         :account-shell (dissoc account :eggs)
                         :egg-maps (:eggs account)
                         :activity-opts {:activity :wild-victory :event-id "pi-event"
                                         :agora t0}})]
      (d/transact (:conn s) {:tx-data tx})
      (is (= 15 (:points (adv/get-egg s "egg-pi")))))))

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

(deftest pi-table-v2-valores
  (doseq [[act expect]
          [[:hunt-complete 15] [:wild-victory 15] [:wild-defeat 5]
           [:pvp-victory 20] [:gym-victory 25] [:daily-mission 30]
           [:raid-victory 30] [:raid-defeat 20]]]
    (is (= expect (get egg-activity/pi-por-atividade act)) (str act))))

(deftest primeira-vitoria-ginasio-semana-concede-extra
  (let [t0 1700000000000
        r (egg-activity/apply-activity
           (eggs/estado-inicial)
           {:event-id "gym-1" :activity :gym-victory :agora t0})
        st (:estado r)
        kinds (map :kind (:incubators st))]
    (is (= 25 (:pi-granted r)))
    (is (some #{:extra} kinds))
    (let [r2 (egg-activity/apply-activity
              st {:event-id "gym-2" :activity :gym-victory :agora t0})]
      (is (= 1 (count (filter #(= :extra (:kind %)) (:incubators (:estado r2)))))
          "só uma extra por semana"))))

(deftest sete-missoes-diarias-concedem-extra
  (let [t0 1700000000000]
    (loop [st (eggs/estado-inicial) n 0]
      (if (< n 7)
        (recur (:estado (egg-activity/apply-activity
                         st {:event-id (str "m-" n) :activity :daily-mission
                             :agora t0}))
               (inc n))
        (is (some #(= :extra (:kind %)) (:incubators st)))))))

(deftest captura-nao-zera-pi-com-suppress-wild-victory
  (let [r (egg-activity/apply-activity
           (eggs/estado-inicial)
           {:event-id "hunt-1" :activity :hunt-complete
            :suppress-wild-victory-pi? true :agora 1700000000000})]
    (is (= 15 (:pi-granted r))
        "flag só suprime :wild-victory, não a captura")))

(deftest pending-incubator-drena-quando-ha-vaga
  (let [full (-> (eggs/estado-inicial)
                 (eggs/adicionar-incubadora "e1" :extra)
                 (eggs/adicionar-incubadora "e2" :especial))
        deferred (egg-activity/try-grant-incubator full "pend-1" :extra)
        _ (is (some? (:deferred-incubator deferred)))
        _ (is (= 1 (count (:pending-incubator-grants (:estado deferred)))))
        ;; Libera um slot removendo a especial esgotada
        freed (update (:estado deferred) :incubators
                      (fn [xs] (vec (remove #(= "e2" (:incubator-id %)) xs))))
        out (egg-activity/materialize-pending-incubators freed)]
    (is (some #(= "pend-1" (:incubator-id %)) (:incubators (:estado out))))
    (is (empty? (:pending-incubator-grants (:estado out))))
    (is (= 1 (count (:delivered out))))
    ;; idempotente
    (is (= (:estado out)
           (:estado (egg-activity/materialize-pending-incubators (:estado out)))))))
