(ns pokemon.migration.export
  "Exporta dump Cassandra descartável ou fixtures → mapa de módulos.
  Sem rede: não conecta a produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pokemon.migration.fixtures :as fix]))

(defn load-edn-file
  [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- modules-from-dump
  [dump]
  (or (:modules dump)
      (let [rows (:estado_particionado dump)]
        (reduce (fn [acc {:keys [modulo particao valor]}]
                  (assoc-in acc [modulo particao] valor))
                {}
                rows))))

(defn from-fixture-dump
  "Normaliza dump sintético (ou arquivo EDN no mesmo formato) para export canônico."
  [dump]
  (when-not (get-in dump [:meta :synthetic])
    (throw (ex-info "export_requires_synthetic_meta"
                    {:hint "M5.0 só aceita dumps com :meta {:synthetic true}"})))
  {:meta (:meta dump)
   :modules (modules-from-dump dump)
   :historical_snapshots (vec (or (:historical_snapshots dump)
                                  (:historical-snapshots dump)))
   :active_provenance (or (:active_provenance dump) (:active-provenance dump))
   :group (:group dump)
   :exported-at (str (java.time.Instant/now))
   :source-kind :synthetic-fixture})

(defn from-authorized-offline-dump
  "Export a partir de dump offline autorizado (M5.3). Recusa paths/meta de produção."
  [dump]
  (when-not (or (true? (get-in dump [:meta :authorized-offline-copy]))
                (true? (get-in dump [:meta :offline-dump]))
                (true? (get-in dump [:meta "authorized-offline-copy"])))
    (throw (ex-info "export_requires_authorized_offline_meta"
                    {:hint "Exiga :meta {:authorized-offline-copy true}"})))
  (when (or (get-in dump [:meta :production]) (get-in dump [:meta "production"]))
    (throw (ex-info "export_production_meta_blocked" {})))
  {:meta (:meta dump)
   :modules (modules-from-dump dump)
   :historical_snapshots (vec (or (:historical_snapshots dump)
                                  (:historical-snapshots dump)))
   :active_provenance (or (:active_provenance dump) (:active-provenance dump))
   :group (:group dump)
   :exported-at (str (java.time.Instant/now))
   :source-kind :authorized-offline-copy})

(defn from-path
  "Lê EDN de fixtures/arquivo descartável. Recusa paths que pareçam produção."
  [path]
  (let [p (str path)]
    (when (re-find #"(?i)prod|oracle|odisseu-live|production" p)
      (throw (ex-info "export_path_blocked"
                      {:path p :reason "caminho suspeito de produção"})))
    (from-fixture-dump (load-edn-file path))))

(defn synthetic-default
  []
  (from-fixture-dump (fix/build-cassandra-dump)))

(defn write-export!
  "Grava export auditável em disco (base descartável)."
  [export out-path]
  (io/make-parents out-path)
  (spit out-path (pr-str export))
  out-path)
