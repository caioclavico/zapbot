(ns pokemon.migration.gates
  "Gates que impedem importação sob colisão, ambiuidade ou perda de campos."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.migration.match :as match]))

(defn- save-field-loss
  [save-ir]
  (vec
   (keep
    (fn [p]
      (let [missing (cond-> []
                      (str/blank? (str (:id-pokemon p))) (conj :id-pokemon)
                      (str/blank? (str (:species p))) (conj :species)
                      (nil? (:nivel p)) (conj :nivel))]
        (when (seq missing)
          {:id-pokemon (:id-pokemon p)
           :collection-index (:collection-index p)
           :missing missing})))
    (:pokemon save-ir))))

(defn analyze-ir
  "Retorna bloqueios duros + avisos para uma IR antes do import.
   Se `match-report` for nil, não exige matching resolvido (import sintético M5.0)."
  [ir match-report]
  (let [field-losses (mapcat (fn [s]
                               (map #(assoc % :slot-id (:slot-id s))
                                    (save-field-loss s)))
                             (:saves ir))
        hashes (mapv :content-hash (:saves ir))
        hash-collision? (and (seq hashes)
                             (not= (count hashes) (count (set hashes))))
        provenance-kinds (mapv #(get-in % [:provenance :kind]) (:saves ir))
        missing-prov? (some nil? provenance-kinds)
        ambig-quarantine (filterv #(contains? #{:slot-conflito-ativo-historico
                                                :save-duplicado-mesmo-slot
                                                :treinador-ativo-ausente}
                                              (:reason %))
                                  (:quarantine ir))
        match-status (:status match-report)
        hard
        (cond-> []
          (seq field-losses)
          (conj {:code :field-loss :details (vec field-losses)})
          hash-collision?
          (conj {:code :content-hash-collision :hashes hashes})
          missing-prov?
          (conj {:code :ambiguous-provenance :kinds provenance-kinds})
          (seq ambig-quarantine)
          (conj {:code :hard-quarantine :details ambig-quarantine})
          (and (some? match-report)
               (not= :resolved match-status))
          (conj {:code :match-not-resolved
                 :status match-status
                 :reason (:block-reason match-report)})
          (true? (:auto-selected? match-report))
          (conj {:code :auto-selection-forbidden}))]
    {:ok? (empty? hard)
     :hard-blocks hard
     :match-status match-status
     :field-loss-count (count field-losses)}))

(defn assert-importable!
  "Lança se a IR / matching não forem seguros para importar."
  [ir match-report]
  (let [analysis (analyze-ir ir match-report)]
    (when-not (:ok? analysis)
      (throw (err/error :import_blocked_by_gates analysis)))
    analysis))

(defn build-match-from-inventory
  [inventory]
  (match/match-inventory inventory))
