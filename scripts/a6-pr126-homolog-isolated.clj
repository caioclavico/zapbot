;; Homologação isolada PR #126 — Datomic Local descartável.
;; Uso: clojure -M -i scripts/a6-pr126-homolog-isolated.clj
;; NÃO toca produção / WhatsApp / volumes live.
(require '[clojure.test :as t]
         '[clojure.pprint :as pp]
         '[clojure.java.io :as io]
         '[pokemon.domain.raid :as raid]
         '[pokemon.domain.egg-activity :as egg-activity]
         '[pokemon.domain.eggs :as eggs]
         '[pokemon.infrastructure.datomic.adventure-activity :as adventure-activity])

(def out-dir (or (System/getenv "A6_PR126_HOMOLOG_OUT")
                 "/tmp/a6-pr126-homolog"))
(.mkdirs (io/file out-dir))

(defn- run-ns [ns-sym]
  (require ns-sym)
  (let [r (t/run-tests ns-sym)]
    {:ns (str ns-sym)
     :test (:test r)
     :pass (:pass r)
     :fail (:fail r)
     :error (:error r)
     :ok (and (zero? (:fail r)) (zero? (:error r)))}))

(defn- scenario-raid-potion []
  (let [poke {:nome "Charizard" :species "charizard" :hp 120 :hp-atual 12
              :ataque 80 :defesa 50 :atq-esp 80 :def-esp 50 :veloc 80
              :tipos ["fogo"]
              :golpes [{:slug "ember" :nome-exibicao "Ember" :poder 40
                        :tipo "fogo" :classe :especial :precisao 100}]}
        part (#'raid/sync-active
              {:nome "Ash" :save-id "s1" :fence-generation 1 :dano 0
               :party [{:pokemon poke :hp 12 :collection-index 0}]
               :active-party-idx 0})
        agora (System/currentTimeMillis)
        raid0 {:raid-id "homolog-r1" :fase "combate" :chat-id "homolog-chat"
               :expira (+ agora 600000)
               :hp-chefe 800 :hp-max 800 :defesa 40 :def-esp 40
               :vez "ash" :ordem ["ash"] :turn-index 1 :revision 1
               :participantes {"ash" part}
               :chefe {:nome "Boss" :tipos ["normal"] :hp 800
                       :ataque 50 :defesa 40 :atq-esp 40 :def-esp 40}}
        before {:hp 12 :inventory-pocao-maxima 2}
        out (raid/usar-item raid0 "ash" "pocao-maxima" agora)
        revide-esperado (#'raid/revide-chefe 120)
        raid1 (:raid out)
        hp1 (:hp-apos-revide out)
        atk (raid/atacar raid1 "ash" 0 (+ agora 1) nil)
        part-atk (get-in atk [:raid :participantes "ash"])
        ok? (and (= 120 (:hp-depois out))
                 (= 12 (:hp-antes out))
                 (= revide-esperado (:revide out))
                 (= 24 revide-esperado)
                 (= 96 hp1)
                 (= 96 (get-in raid1 [:participantes "ash" :hp]))
                 (= 72 (:hp part-atk)) ;; 96 - revide 24 no ataque seguinte
                 (= 2 (get-in raid1 [:turn-index]))
                 (= 3 (get-in atk [:raid :turn-index])))]
    {:scenario "raid-pocao-maxima-hp-persist"
     :ok ok?
     :before before
     :after {:hp-depois (:hp-depois out)
             :revide (:revide out)
             :hp-apos-revide hp1
             :hp-next-turn-start 96
             :hp-after-next-attack (:hp part-atk)
             :turn-index-after-potion (get-in raid1 [:turn-index])
             :turn-index-after-attack (get-in atk [:raid :turn-index])
             :inventory-note "consumo ocorre no store/TX (domínio não debita inventário)"}}))

(defn- scenario-pi-capture-xor []
  (let [writer {:chat-id "homolog" :player-id "ash"}
        ;; vitória com captura pendente → nil targets
        defer (#'adventure-activity/detect-targets
               nil writer
               {:tx [{:operation-receipt/id "t1"
                      :operation-receipt/kind "battle-turn"
                      :operation-receipt/summary (pr-str {:battle-id "b1"})}]
                :result {:battle {:battle-id "b1" :kind :wild
                                  :active? false :outcome :win
                                  :aguardando-captura? true}}}
               {:operation-receipt/id "t1"
                :operation-receipt/kind "battle-turn"
                :operation-receipt/summary (pr-str {:battle-id "b1"})}
               1000)
        liberar (#'adventure-activity/detect-targets
                 nil writer
                 {:tx [{:operation-receipt/id "rel"
                        :operation-receipt/kind "encounter-release"
                        :operation-receipt/summary (pr-str {:battle-id "b1"})}]
                  :result {:liberou? true :battle-id "b1"}}
                 {:operation-receipt/id "rel"
                  :operation-receipt/kind "encounter-release"
                  :operation-receipt/summary (pr-str {:battle-id "b1"})}
                 1000)
        ovo (assoc (eggs/novo-ovo "e1" :comum 1000 "bulbasaur")
                   :state :incubating :points 40 :incubator-id "i1"
                   :points-goal 100)
        st (-> (eggs/estado-inicial)
               (assoc :incubators [{:incubator-id "i1" :kind :infinite
                                    :uses nil :bonus-points 0 :active-egg-id "e1"}]
                      :eggs [ovo]))
        hunt (egg-activity/apply-activity
              st {:event-id "hunt-1" :activity :hunt-complete :agora 2000
                  :suppress-wild-victory-pi? true})
        ok? (and (nil? defer)
                 (= :wild-victory (get-in liberar [0 :opts :activity]))
                 (= 15 (:pi-granted hunt))
                 (= 40 (get-in hunt [:summary :egg-progress 0 :points-before]))
                 (= 55 (get-in hunt [:summary :egg-progress 0 :points])))]
    {:scenario "pi-capture-xor-and-egg-progress"
     :ok ok?
     :defer-targets defer
     :liberar-activity (get-in liberar [0 :opts :activity])
     :pi-granted (:pi-granted hunt)
     :egg-progress (get-in hunt [:summary :egg-progress])}))

(defn- scenario-raid-item-defeat-pi []
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "raid-item-h"
                 :operation-receipt/kind "raid-item"
                 :operation-receipt/summary (pr-str {:derrota? true})}
        built {:tx [receipt]
               :result {:derrota? true
                        :raid {:raid-id "r-h" :fase "derrota"}
                        :rewards [{:player-id "ash" :outcome :derrota}
                                  {:player-id "misty" :outcome :derrota}]}}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)
        ok? (and (= 2 (count targets))
                 (every? #(= :raid-defeat (get-in % [:opts :activity])) targets)
                 (= #{"egg-act:raid:r-h:ash" "egg-act:raid:r-h:misty"}
                    (set (map #(get-in % [:opts :event-id]) targets))))]
    {:scenario "raid-item-defeat-pi"
     :ok ok?
     :targets (mapv (fn [t] {:player (:player-id t)
                             :activity (get-in t [:opts :activity])
                             :event-id (get-in t [:opts :event-id])})
                    targets)}))

(defn -main [& _]
  (println "=== A6 PR126 isolated homolog ===")
  (println "master tip expected: 741034c…")
  ;; defeat/homolog-fase4 usam storage local compartilhado — rodar em processos
  ;; separados via scripts/a6-pr126-homolog-run.sh (não neste mesmo JVM).
  (let [suites [(run-ns 'pokemon.tests.hotfix-pi-raid-item-a6-test)
                (run-ns 'pokemon.tests.egg-activity-a6-test)
                (run-ns 'pokemon.tests.egg-fixes-a6-test)
                (run-ns 'pokemon.tests.egg-auto-hatch-a6-test)
                (run-ns 'pokemon.tests.raid-hp-sync-p0-a6-test)
                (run-ns 'pokemon.tests.raid-auto-cadence-fase1282-test)]
        scenarios [(scenario-raid-potion)
                   (scenario-pi-capture-xor)
                   (scenario-raid-item-defeat-pi)]
        report {:generated-at (str (java.time.Instant/now))
                :storage "datomic-local disposable (per-test namespaces)"
                :suites suites
                :scenarios scenarios
                :suites-ok (every? :ok suites)
                :scenarios-ok (every? :ok scenarios)
                :ok (and (every? :ok suites) (every? :ok scenarios))}]
    (spit (str out-dir "/isolated-datomic-report.edn") (pr-str report))
    (spit (str out-dir "/isolated-datomic-report.txt")
          (with-out-str (pp/pprint report)))
    (println "wrote" (str out-dir "/isolated-datomic-report.txt"))
    (pp/pprint {:ok (:ok report)
                :suites (mapv #(select-keys % [:ns :ok :fail :error :pass :test]) suites)
                :scenarios (mapv #(select-keys % [:scenario :ok]) scenarios)})
    (System/exit (if (:ok report) 0 1))))

(-main)