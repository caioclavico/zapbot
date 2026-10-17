(ns pokemon.tests.gym-m36-test
  "M3.6 Parte A — concorrência determinística de ginásios."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(def ^:private system "odisseu-m36-gym")
(def ^:private storage-rel ".datomic-local/m36-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(def ^:private mon
  (fn [nome]
    {:nome nome :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
     :tipos ["normal"] :nivel 10
     :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]}))

(defn- seed-team3!
  [s chat player]
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n) 80 nil))
  (gym-app/set-gym-team! s chat player [0 1 2]))

(deftest ocupacao-concorrente-deterministica
  "Dois jogadores competem pelo stub rev=0; exatamente um ocupa;
  o perdedor não leva moedas/insígnia."
  (let [s (fresh-store! "g36-race")
        chat "chat-race"
        barrier (CyclicBarrier. 2)
        latch (CountDownLatch. 2)
        results (atom {})]
    (boot! s chat "p1")
    (boot! s chat "p2")
    (seed-team3! s chat "p1")
    (seed-team3! s chat "p2")
    (doseq [p ["p1" "p2"]]
      (.start
       (Thread.
        (fn []
          (try
            (.await barrier 5 TimeUnit/SECONDS)
            (let [r (gym-app/resolve-challenge!
                     s chat p
                     {:operation-id (str "race-" p)
                      :gym-id "pedra" :venceu? true
                      :dia "2026-10-17" :indices [0 1 2]
                      :pedra-roll 0 :nome p
                      :participacao {0 0}
                      :expected-revision 0})]
              (swap! results assoc p r))
            (catch Exception ex
              (swap! results assoc p {:error (err/error-code ex)}))
            (finally
              (.countDown latch)))))))
    (is (.await latch 15 TimeUnit/SECONDS))
    (let [rs @results
          oks (filter (fn [[_ v]] (and (map? v) (:occupied? v) (nil? (:error v)))) rs)
          errs (filter (fn [[_ v]] (:error v)) rs)
          occ (gym-app/get-occupation s chat "pedra")
          winner (:pid occ)]
      (is (= 1 (count oks)))
      (is (= 1 (count errs)))
      (is (contains? #{:gym_cas_conflict :writer_fence_conflict}
                     (:error (val (first errs)))))
      (is (contains? #{"p1" "p2"} winner))
      ;; perdedor sem recompensa de 1ª vitória
      (let [loser (first (remove #{winner} ["p1" "p2"]))
            ls (ports/get-save s chat loser "a")]
        (is (= 0 (:coins ls 0)))
        (is (empty? (:ginasios ls {})))
        (is (= 0 (:gym-defense-count (ports/get-player s chat loser)))))
      (is (= 1 (:gym-defense-count (ports/get-player s chat winner))))
      (is (= 100 (:coins (ports/get-save s chat winner "a")))))))

(deftest destronar-concorrente-mesma-revisao
  (let [s (fresh-store! "g36-dethrone")
        chat "chat-dt"]
    (boot! s chat "leader")
    (boot! s chat "a1")
    (boot! s chat "a2")
    (seed-team3! s chat "leader")
    (gym-app/resolve-challenge!
     s chat "leader"
     {:operation-id "lead" :gym-id "pedra" :venceu? true
      :dia "2026-10-10" :indices [0 1 2] :pedra-roll 0 :nome "L"
      :participacao {0 0}})
    (seed-team3! s chat "a1")
    (seed-team3! s chat "a2")
    (let [barrier (CyclicBarrier. 2)
          latch (CountDownLatch. 2)
          results (atom {})]
      (doseq [p ["a1" "a2"]]
        (.start
         (Thread.
          (fn []
            (try
              (.await barrier 5 TimeUnit/SECONDS)
              (let [r (gym-app/resolve-challenge!
                       s chat p
                       {:operation-id (str "dt-" p)
                        :gym-id "pedra" :venceu? true
                        :dia "2026-10-17" :indices [0 1 2]
                        :pedra-roll 0 :nome p
                        :participacao {0 0}
                        :expected-revision 1
                        :agora (+ (System/currentTimeMillis) 1000)})]
                (swap! results assoc p r))
              (catch Exception ex
                (swap! results assoc p {:error (err/error-code ex)}))
              (finally
                (.countDown latch)))))))
      (is (.await latch 15 TimeUnit/SECONDS))
      (let [oks (filter #(and (:occupied? (val %)) (nil? (:error (val %)))) @results)
            occ (gym-app/get-occupation s chat "pedra")]
        (is (= 1 (count oks)))
        (is (contains? #{"a1" "a2"} (:pid occ)))
        (is (= 3 (count (ports/list-collection s chat "leader" "a"))))
        (is (= 0 (:gym-defense-count (ports/get-player s chat "leader"))))))))
