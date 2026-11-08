(ns odisseu.smoke.a6-species-hp-freeze-rehearsal
  "Ensaio local: maintenance-gate + classificação de ops Species-HP freeze.
  Não toca produção / WhatsApp / hold."
  (:require [pokemon.domain.species-hp-freeze :as freeze]
            [pokemon.infrastructure.http.maintenance-gate :as gate])
  (:import [java.nio.file Files]))

(defn run
  []
  (let [dir (.getAbsolutePath
             (.toFile (Files/createTempDirectory "a6-sph-freeze-" (make-array java.nio.file.attribute.FileAttribute 0))))
        g (gate/create dir)
        open0? (= :open (:phase (gate/status g)))
        acquired? (gate/acquire! g)
        drain-fut (future (gate/enter! g 5000))
        _ (Thread/sleep 80)
        blocked? (not (gate/acquire! g))
        draining? (= :draining (:phase (gate/status g)))
        ;; libera a op em voo para o drain completar
        _ (when acquired? (gate/release! g))
        result @drain-fut
        phase (:phase (gate/status g))
        g2 (gate/create dir)
        phase2 (:phase (gate/status g2))
        _ (when (= :quiescent phase2)
            (gate/resume! g2))
        open-again? (= :open (:phase (gate/status g2)))
        mutating-blocked (every? freeze/hp-mutating-op?
                                 [:battle-start :hunt-start :raid-attack :pvp-turn :gym-challenge])
        reads-ok (every? freeze/allowed-during-freeze?
                         [:collection-read :trainer-read :raid-read])
        plan (freeze/freeze-plan)]
    {:ok (and open0? acquired? blocked? draining?
              (:quiescent? result)
              (= :quiescent phase) (= :quiescent phase2)
              open-again? mutating-blocked reads-ok
              (freeze/assert-disjoint-classification)
              (pos? (count (:steps plan))))
     :gate {:acquired acquired? :blocked-during-drain blocked?
            :saw-draining draining?
            :quiescent? (:quiescent? result)
            :persisted-phase phase2 :resumed-open? open-again?}
     :ops {:mutating-blocked mutating-blocked :reads-allowed reads-ok}
     :plan-steps (count (:steps plan))
     :dir dir}))

(defn -main [& _]
  (let [report (run)]
    (println (pr-str report))
    (shutdown-agents)
    (System/exit (if (:ok report) 0 1))))
