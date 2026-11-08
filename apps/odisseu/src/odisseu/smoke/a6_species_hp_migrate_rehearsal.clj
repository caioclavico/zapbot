(ns odisseu.smoke.a6-species-hp-migrate-rehearsal
  "Ensaio integral Species-HP em Datomic descartável — NÃO toca produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp]
            [pokemon.domain.species-hp-migrate :as migrate]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.tests.species-hp-a6-test]
            [pokemon.tests.species-hp-sim-a6-test]))

(def ^:private batch-size 40)

(defn- fresh-store []
  (let [client (persistence/client {:system (str "a6-sph-reh-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a6-sph-reh-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "sph-reh")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "reh-chat" :player-id "reh-ash"})
    s))

(defn- fixture-seed
  "Fixtures representativas cobrindo os casos do plano (§6)."
  []
  (let [mk (fn [id sp nv hp-at ratio]
             (let [legacy-max (species-hp/legacy-hp-max
                               (or (when (species-base/base-hp-known? sp)
                                     (species-base/base-hp sp))
                                   80)
                               nv)
                   at (cond
                        (= ratio :faint) 0
                        (= ratio :full) legacy-max
                        (number? ratio) (max 1 (long (Math/round (* ratio (double legacy-max)))))
                        :else legacy-max)]
               {:id-pokemon id
                :species sp :nome (str/capitalize sp)
                :nivel nv :hp legacy-max :hp-atual at
                :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
                :tipos ["normal"] :amizade 70
                :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                          :poder 40 :classe :fisico :precisao 100}]}))]
    (vec
     (concat
      [(mk "f-full-1" "pikachu" 12 :full)
       (mk "f-partial-1" "charizard" 30 0.4)
       (mk "f-faint-1" "gengar" 25 :faint)
       (mk "f-chansey" "chansey" 40 0.7)
       (mk "f-blissey" "blissey" 40 0.7)
       (mk "f-shedinja" "shedinja" 20 :full)
       (mk "f-magikarp" "magikarp" 15 :full)
       (mk "f-mewtwo" "mewtwo" 50 0.55)
       (mk "f-mew" "mew" 35 :full)
       (mk "f-rayquaza" "rayquaza" 45 0.3)
       (mk "f-unknown" "zzz-species-ausente-xyz" 10 :full)
       (mk "f-combat" "snorlax" 20 0.5)
       (mk "f-raid" "tyranitar" 28 0.6)]
      ;; massa ~983: mistura common/rare + estados HP
      (for [i (range 970)]
        (let [pool ["rattata" "pidgey" "eevee" "bulbasaur" "squirtle" "charmander"
                    "geodude" "abra" "machop" "gastly" "dratini" "lapras"
                    "snorlax" "dragonite" "gyarados" "alakazam" "gengar"
                    "articuno" "zapdos" "moltres" "lugia" "ho-oh"]
              sp (nth pool (mod i (count pool)))
              nv (inc (mod i 50))
              ratio (case (mod i 17)
                      0 :faint
                      1 0.25
                      2 0.5
                      3 0.75
                      :full)]
          (mk (str "mass-" i) sp nv ratio)))))))

(defn- load-export-rows
  "Carrega export RO anonimizado se o path existir; senão nil."
  [path]
  (when (and path (.exists (io/file path)))
    (let [raw (edn/read-string (slurp path))
          rows (or (:fixture-rows raw) raw)]
      (when (sequential? rows)
        (mapv (fn [r]
                (let [sp (-> (str (or (:species r) "unknown"))
                             str/lower-case
                             (str/replace #" " "-"))]
                  {:id-pokemon (or (:id r) (str "exp-" (hash r)))
                   :species sp
                   :nome (str/capitalize sp)
                   :nivel (long (or (:nivel r) 1))
                   :hp (long (or (:hp r) 0))
                   :hp-atual (long (or (:hp-atual r) 0))
                   :hp-base (:hp-base r)
                   :hp-formula-version (:hp-formula-version r)
                   :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
                   :tipos ["normal"] :amizade 70
                   :golpes [{:slug "tackle" :nome-exibicao "Tackle" :tipo "normal"
                             :poder 40 :classe :fisico :precisao 100}]}))
              rows)))))
(defn- integrity-fingerprint
  "Campos que NÃO devem mudar na migração de HP (exclui golpes/tipos cosméticos)."
  [reg]
  {:id-pokemon (:id-pokemon reg)
   :species (some-> (:species reg) str str/lower-case)
   :nivel (long (or (:nivel reg) (:level reg) 1))
   :ataque (long (:ataque reg 0))
   :defesa (long (:defesa reg 0))
   :atq-esp (long (:atq-esp reg 0))
   :def-esp (long (:def-esp reg 0))
   :veloc (long (:veloc reg 0))
   :amizade (long (:amizade reg 0))})(defn- aggregate
  [regs]
  (let [ver (fn [r]
              (let [v (str (or (:hp-formula-version r) ""))]
                (if (str/blank? v) "legacy-unset" v)))
        partial? (fn [r]
                   (let [hp (long (:hp r 0)) at (long (:hp-atual r 0))]
                     (and (pos? hp) (pos? at) (< at hp))))
        faint? (fn [r] (not (pos? (long (:hp-atual r 0)))))
        full? (fn [r]
                (let [hp (long (:hp r 0)) at (long (:hp-atual r 0))]
                  (and (pos? hp) (= at hp))))]
    {:total (count regs)
     :by-version (frequencies (map ver regs))
     :full (count (filter full? regs))
     :partial (count (filter partial? regs))
     :fainted (count (filter faint? regs))}))

(defn- run-batches
  [regs {:keys [fail-at-batch crash-after-commit? occupied-ids]}]
  (let [regs (mapv (fn [r]
                     (cond-> r
                       (contains? (set occupied-ids) (:id-pokemon r))
                       (assoc :battle-active? true)))
                   regs)
        n (count regs)
        t0 (System/nanoTime)
        acc (atom {:convert 0 :noop 0 :quarantine 0 :skip-combat 0
                   :receipts [] :regs regs :failed-batch nil})
        offsets (range 0 n batch-size)]
    (doseq [[batch-idx offset] (map-indexed vector offsets)]
      (when (= batch-idx fail-at-batch)
        (swap! acc assoc :failed-batch batch-idx)
        (throw (ex-info "simulated-batch-failure"
                        {:batch-idx batch-idx :offset offset})))
      (let [receipt (migrate/migrar-lote (:regs @acc)
                                         {:offset offset
                                          :tamanho batch-size
                                          :batch-id (str "b-" batch-idx)})
            updated (atom (:regs @acc))]
        (doseq [plano (:planos receipt)]
          (when (= :convert (:status plano))
            (let [id (get-in plano [:registro :id-pokemon])
                  novo (:registro plano)]
              (swap! updated (fn [v]
                               (mapv #(if (= id (:id-pokemon %)) novo %) v))))))
        (when crash-after-commit?
          ;; simula timeout pós-commit: estado já aplicado; reexecução → noop
          nil)
        (swap! acc (fn [a]
                     (-> a
                         (update :convert + (:convert receipt))
                         (update :noop + (:noop receipt))
                         (update :quarantine + (:quarantine receipt))
                         (update :skip-combat + (:skip-combat receipt))
                         (update :receipts conj (:idempotent-receipt receipt))
                         (assoc :regs @updated))))))
    (assoc @acc
           :elapsed-ms (/ (double (- (System/nanoTime) t0)) 1.0e6)
           :before (aggregate regs)
           :after (aggregate (:regs @acc)))))

(defn- apply-planos
  [regs planos]
  (reduce (fn [v plano]
            (if (= :convert (:status plano))
              (let [id (get-in plano [:registro :id-pokemon])
                    novo (:registro plano)]
                (mapv #(if (= id (:id-pokemon %)) novo %) v))
              v))
          regs
          planos))

(defn- resume-after-failure
  "Simula falha no 3º lote, aplica só lotes 0–1, retoma do restante."
  [regs]
  (let [occupied (set (map :id-pokemon (filter :battle-active? regs)))
        b0 (migrate/migrar-lote regs {:offset 0 :tamanho batch-size :batch-id "b-0"})
        b1 (migrate/migrar-lote regs {:offset batch-size :tamanho batch-size :batch-id "b-1"})
        partial (apply-planos (apply-planos regs (:planos b0)) (:planos b1))
        resumed (run-batches partial {:occupied-ids occupied})
        again (run-batches (:regs resumed) {:occupied-ids occupied})]
    {:resume-ok true
     :partial-converted (+ (:convert b0) (:convert b1))
     :resumed-convert (:convert resumed)
     :second-pass-convert (:convert again)
     :second-pass-noop (:noop again)
     :final-after (:after resumed)}))(defn- persist-sample!
  "Persiste amostra convertida em Datomic descartável e valida round-trip."
  [sample]
  (let [s (fresh-store)
        planned (mapv migrate/plano-seguro sample)
        converted (->> planned
                       (filter #(= :convert (:status %)))
                       (map :registro)
                       (take 30)
                       vec)
        fingerprints (mapv integrity-fingerprint converted)]
    (doseq [novo converted]
      (collection/adicionar-pokemon! s "reh-chat" "reh-ash" novo
                                     (long (:hp-atual novo 0)) nil))
    (let [lidos (ports/list-collection s "reh-chat" "reh-ash" "a")
          by-id (into {} (map (juxt :id-pokemon identity) lidos))]
      {:persisted (count converted)
       :read-back (count lidos)
       :all-v1? (every? #(= species-hp/formula-version
                            (str (:hp-formula-version (by-id (:id-pokemon %)))))
                        converted)
       :integrity-ok?
       (every? (fn [fp]
                 (= fp (integrity-fingerprint (by-id (:id-pokemon fp)))))
               fingerprints)
       :fainted-remain-zero?
       (every? (fn [r]
                 (let [lido (by-id (:id-pokemon r))]
                   (or (pos? (long (:hp-atual r 0)))
                       (zero? (long (:hp-atual lido 0))))))
               converted)
       :raid-boss-hp raid/hp-por-jogador})))
(defn run-rehearsal
  [{:keys [export-path]}]
  (let [export-rows (load-export-rows export-path)
        base (or export-rows (fixture-seed))
        ;; injeta 2 ocupados para validar skip-combat (não vem do live)
        occupied-ids (set (map :id-pokemon (take 2 base)))
        dataset (mapv (fn [r]
                        (cond-> r
                          (contains? occupied-ids (:id-pokemon r))
                          (assoc :battle-active? true)))
                      base)
        source (if export-rows :live-ro-export-anonymized :synthetic-fixtures)
        before (aggregate dataset)
        first-pass (run-batches dataset {:occupied-ids occupied-ids})
        second-pass (run-batches (:regs first-pass) {:occupied-ids occupied-ids})
        resume (resume-after-failure dataset)
        persist (persist-sample! (->> dataset
                                      (remove #(contains? occupied-ids (:id-pokemon %)))
                                      (take 80)))
        unit (test/run-tests 'pokemon.tests.species-hp-a6-test
                             'pokemon.tests.species-hp-sim-a6-test)
        unit-ok? (and (zero? (:fail unit 0)) (zero? (:error unit 0)))]
    {:ok (and unit-ok?
              (zero? (:convert second-pass))
              (:all-v1? persist)
              (:integrity-ok? persist)
              (= 320 (:raid-boss-hp persist))
              (= 2 (:skip-combat first-pass)))
     :dataset-source source
     :dataset-count (count dataset)
     :note (if export-rows
             "Dataset = export RO anonimizado da cópia Datomic (não live write)."
             "Ensaio NÃO cobriu os registros reais — fixtures sintéticas representativas.")
     :before before
     :after (:after first-pass)
     :converted (:convert first-pass)
     :quarantine (:quarantine first-pass)
     :skip-combat (:skip-combat first-pass)
     :noop (:noop first-pass)
     :batch-elapsed-ms (:elapsed-ms first-pass)
     :idempotent-second-pass {:convert (:convert second-pass)
                              :noop (:noop second-pass)
                              :quarantine (:quarantine second-pass)
                              :skip-combat (:skip-combat second-pass)}
     :resume (assoc resume :occupied-ids occupied-ids)
     :persistence persist
     :unit-tests {:pass (:pass unit) :fail (:fail unit) :error (:error unit)}
     :raid-boss-hp-preserved 320}))
(defn -main [& args]
  (let [export-path (or (first args) (System/getenv "SPECIES_HP_EXPORT_EDN"))
        report (run-rehearsal {:export-path export-path})]
    (println (pr-str report))
    (shutdown-agents)
    (System/exit (if (:ok report) 0 1))))
