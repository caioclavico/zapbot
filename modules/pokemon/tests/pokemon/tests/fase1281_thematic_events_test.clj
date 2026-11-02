(ns pokemon.tests.fase1281-thematic-events-test
  "Fase 12.8.1 — scheduler, missões, doces, bias caça/clima, anúncios (domínio)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pokemon.domain.event-missions :as em]
            [pokemon.domain.event-schedule :as sched]
            [pokemon.domain.events :as events]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.mission-suggest :as suggest]
            [pokemon.domain.profile :as profile])
  (:import [java.time Instant]))

(deftest catalogo-sete-eventos-com-missoes-e-doces-familia
  (is (= 7 (count events/catalog)))
  (doseq [ev events/catalog]
    (is (seq (:missoes ev)) (:id ev))
    (is (seq (:familias-doce ev)) (:id ev))
    (is (every? string? (:familias-doce ev)))
    (is (nil? (some #(re-find #"^(dragon|water|fire|electric|ghost)$" %)
                    (:familias-doce ev))))
    (is (pos? (long (:bias-tipos ev 0))))
    (is (<= (long (:bias-tipos ev)) 35)))
  (is (events/fossil-family? "kabutops"))
  (is (events/fossil-family? "omanyte"))
  (is (not (events/fossil-family? "pikachu"))))

(deftest flag-off-isola-active-events-e-bias
  (is (false? (events/thematic-events-enabled?)))
  (is (empty? (events/active-events (Instant/now))))
  (let [bioma {:tipos #{"normal"}}
        clima {:tipos #{"water"}}
        rolls (count (filter #(= #{"water"} (hunt/resolver-tipos-encontro bioma clima %))
                             (range 100)))]
    (is (= 35 rolls))))

(deftest scheduler-anti-consecutivo-e-anti-retroativo
  (let [ids (sched/eligible-catalog-ids "festival-dragoes")]
    (is (not (some #{"festival-dragoes"} ids)))
    (is (= 6 (count ids))))
  (is (sched/within-anti-retroactive? 1000 1000 sched/default-policy))
  (is (sched/within-anti-retroactive? 1000 (+ 1000 (* 60 60 1000)) sched/default-policy))
  (is (not (sched/within-anti-retroactive?
            1000 (+ 1000 (* 5 60 60 1000)) sched/default-policy)))
  (is (nil? (sched/plan-activation
             {:enabled? true :agora-ms 5000 :next-at 1000
              :last-catalog-id nil :active-occurrences [] :draw 0
              :policy (assoc sched/default-policy :anti-retroativo-grace-ms 1000)})))
  (let [plan (sched/plan-activation-for-chat
              "chat-x"
              {:enabled? true :agora-ms 2000 :next-at 1000
               :last-catalog-id "mare-misteriosa" :active-occurrences []
               :draw 0
               :policy (assoc sched/default-policy :anti-retroativo-grace-ms (* 10 60 60 1000)
                              :intervalo-minimo-ms 0)})]
    (is (some? plan))
    (is (not= "mare-misteriosa" (:catalog-id plan)))
    (is (= "active" (:status plan)))
    (is (str/starts-with? (:occurrence-id plan) "chat-x:")))
  (is (sched/conflicts-with-active? [{:status "active"}]))
  (is (not (sched/conflicts-with-active? []))))

(deftest bands-evento-clima-bioma-limitadas
  (let [ev [(assoc (events/by-id "festival-dragoes") :bias-tipos 20)]
        bands (events/combined-type-roll-bands ev)]
    (is (= 20 (:evento bands)))
    (is (= 35 (:clima bands)))
    (is (= 45 (:bioma bands)))
    (is (= 100 (+ (:evento bands) (:clima bands) (:bioma bands)))))
  (let [bioma {:tipos #{"normal"}}
        clima {:tipos #{"water"}}
        active [(assoc (events/by-id "festival-dragoes") :bias-tipos 20)]
        rolls-evt (count (filter #(= #{"dragon"}
                                     (hunt/resolver-tipos-encontro bioma clima % active))
                                 (range 100)))
        rolls-clima (count (filter #(= #{"water"}
                                       (hunt/resolver-tipos-encontro bioma clima % active))
                                   (range 100)))
        rolls-bioma (count (filter #(= #{"normal"}
                                       (hunt/resolver-tipos-encontro bioma clima % active))
                                   (range 100)))]
    (is (= 20 rolls-evt))
    (is (= 35 rolls-clima))
    (is (= 45 rolls-bioma))))

(deftest missoes-progresso-janela-e-claim-idempotente-doces
  (let [st0 {"progresso" {} "premiados" [] "candy-picks" {}}
        a1 (em/apply-action st0 "festival-dragoes"
                            {:type :wild-captured :types ["dragon"] :family "dratini"})
        a2 (em/apply-action a1 "festival-dragoes"
                            {:type :wild-captured :types ["dragon"] :family "bagon"})
        a3 (em/apply-action a2 "festival-dragoes"
                            {:type :wild-captured :types ["dragon"] :family "gible"})
        before (em/apply-action st0 "festival-dragoes"
                                {:type :wild-captured :types ["water"] :family "magikarp"})]
    (is (= 0 (get-in before ["progresso" "cap-dragao"] 0)))
    (is (= 3 (get-in a3 ["progresso" "cap-dragao"])))
    (is (em/within-window? {:status "active" :inicio-ms 0 :fim-ms 100} 50))
    (is (not (em/within-window? {:status "active" :inicio-ms 0 :fim-ms 100} 100)))
    (let [claim1 (em/build-claim "festival-dragoes" a3 (fn [_ _] 0))
          st' (:state claim1)
          claim2 (em/build-claim "festival-dragoes" st' (fn [_ _] 0))]
      (is (seq (:ids claim1)))
      (is (pos? (get (:doces claim1) "dratini" 0)))
      (is (= "dratini" (get-in (:state claim1) ["candy-picks" "cap-dragao"])))
      (is (empty? (:ids claim2)))
      (is (= {} (:doces claim2)))))
  (is (= {"dratini" 3} (events/grant-candy-map {"dragonite" 3})))
  (is (= "Doce Dratini" (profile/candy-label "dragonite"))))

(deftest snapshot-resumo-inclui-evento-tematico
  (let [occ {:occurrence-id "c:festival-dragoes:1"
             :catalog-id "festival-dragoes"
             :chat-id "c"
             :inicio-ms (- (System/currentTimeMillis) 1000)
             :fim-ms (+ (System/currentTimeMillis) (* 60 60 1000))
             :status "active"}
        snap (catalog/snapshot {} {} {} 1 (Instant/now) "America/Sao_Paulo" [occ])
        sum (:summary snap)
        ev (first (filter #(= "festival-dragoes" (:id %)) (:events snap)))]
    (is (some? ev))
    (is (= "active" (:status ev)))
    (is (string? (:remaining-label ev)))
    (is (<= (count (:event-suggested sum)) 2))
    (is (some #(= "festival-dragoes" (:id %)) (:active-events sum)))
    (is (= "event" (suggest/normalize-scope "evento")))
    (is (= "event" (suggest/normalize-scope "eventos")))))

(deftest anuncios-compactos
  (let [ev (events/by-id "festival-dragoes")
        start (events/announce-start-text ev "24 horas")
        end (events/announce-end-text ev)]
    (is (re-find #"FESTIVAL DOS DRAGÕES COMEÇOU" start))
    (is (re-find #"missoes evento" start))
    (is (re-find #"ENCERRADO" end))
    (is (not (re-find #"\+3 Doces" start)))))
