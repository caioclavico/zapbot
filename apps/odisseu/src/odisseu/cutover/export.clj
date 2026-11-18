(ns odisseu.cutover.export
  "Exportação M8.0 — snapshot + manifesto + checksums.
   Aceita somente :synthetic ou :authorized-offline-copy. Sem rede/produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.inventory :as inv]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [pokemon.migration.export :as poke-export]
            [pokemon.migration.recovery :as recovery])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn- sha256-bytes [^bytes b]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md b)
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn sha256-str [s]
  (sha256-bytes (.getBytes (str s) StandardCharsets/UTF_8)))

(defn load-dump!
  "Carrega EDN e valida meta de segurança."
  [path]
  (let [p (str path)]
    (when (re-find #"(?i)prod|oracle|odisseu-live|production" p)
      (throw (ex-info "export_path_blocked" {:path p})))
    (let [dump (with-open [r (io/reader p)]
                 (edn/read {:readers *data-readers*} (java.io.PushbackReader. r)))
          meta (:meta dump)]
      (cond
        (true? (:synthetic meta))
        dump

        (or (true? (:authorized-offline-copy meta))
            (true? (:offline-dump meta)))
        (do
          (when (or (:production meta) (get meta "production"))
            (throw (ex-info "export_production_meta_blocked" {:path p})))
          dump)

        :else
        (throw (ex-info "export_meta_required"
                        {:path p
                         :hint "Exija :meta {:synthetic true} ou {:authorized-offline-copy true}"}))))))

(defn canonicalize-modules
  "Unifica :modules e :estado_particionado → mapa modulo→particao→valor."
  [dump]
  (or (:modules dump)
      (reduce (fn [acc {:keys [modulo particao valor]}]
                (assoc-in acc [(str modulo) (str particao)] valor))
              {}
              (:estado_particionado dump))))

(defn build-export
  "Pacote de export auditável (em memória)."
  [dump {:keys [label quiescence-notes source-path]
         :or {label "m8-snapshot"
              quiescence-notes
              "Assumir writers congelados; Cassandra não oferece snapshot multi-partição atômico."}}]
  (let [modules (canonicalize-modules dump)
        inventory (inv/inventory-dump (assoc dump :modules modules))
        payload {:meta (merge (:meta dump)
                              {:cutover-milestone "M8.0"
                               :label label
                               :quiescence quiescence-notes
                               :source-path (str source-path)
                               :exported-at (str (java.time.Instant/now))})
                 :modules modules
                 :historical_snapshots (vec (or (:historical_snapshots dump)
                                                (:historical-snapshots dump)))
                 :active_provenance (or (:active_provenance dump)
                                        (:active-provenance dump))
                 :inventory inventory
                 :content-sha256 nil}
        ;; hash sem o próprio campo content-sha256
        body (pr-str (dissoc payload :content-sha256))
        hash (sha256-str body)]
    (assoc payload :content-sha256 hash)))

(defn write-export-bundle!
  "Grava export.edn + manifest.edn + checksums.edn em out-dir (descartável/privado).
   Nunca escreve sob paths de produção."
  [dump out-dir opts]
  (let [out (io/file (str out-dir))
        _ (when (re-find #"(?i)prod|oracle|live" (str out))
            (throw (ex-info "export_out_blocked" {:out out-dir})))
        _ (.mkdirs out)
        export (build-export dump opts)
        export-path (str (io/file out "export.edn"))
        _ (spit export-path (with-out-str (pprint/pprint export)))
        file-manifest (recovery/checksum-manifest [export-path])
        manifest {:milestone "M8.0"
                  :label (get-in export [:meta :label])
                  :exported-at (get-in export [:meta :exported-at])
                  :source-path (get-in export [:meta :source-path])
                  :content-sha256 (:content-sha256 export)
                  :module-keys (get-in export [:inventory :module-keys])
                  :classified (get-in export [:inventory :classified])
                  :gaps (get-in export [:inventory :gaps])
                  :quiescence (get-in export [:meta :quiescence])
                  :files (:files file-manifest)
                  :nota "Artefato sintético/offline — não colocar dados privados no Git."}
        manifest-path (str (io/file out "manifest.edn"))
        checksums-path (str (io/file out "checksums.edn"))]
    (spit manifest-path (with-out-str (pprint/pprint manifest)))
    (spit checksums-path (pr-str file-manifest))
    (recovery/verify-checksums! file-manifest)
    {:export-path export-path
     :manifest-path manifest-path
     :checksums-path checksums-path
     :content-sha256 (:content-sha256 export)
     :manifest manifest
     :export export}))

(defn export-from-path!
  [path out-dir & [opts]]
  (let [dump (load-dump! path)]
    (write-export-bundle! dump out-dir (merge {:source-path path} opts))))

(defn platform-ir-from-export
  "IR platform a partir do pacote de export (módulos string)."
  [export]
  (platform-pipe/export-modules {:meta (:meta export)
                                 :modules (:modules export)}))

(defn pokemon-export-from-dump
  "Reusa export Pokémon M5 (synthetic ou authorized)."
  [dump]
  (cond
    (get-in dump [:meta :synthetic]) (poke-export/from-fixture-dump dump)
    (or (get-in dump [:meta :authorized-offline-copy])
        (get-in dump [:meta :offline-dump]))
    (poke-export/from-authorized-offline-dump dump)
    :else (throw (ex-info "pokemon_export_meta_invalid" {}))))
