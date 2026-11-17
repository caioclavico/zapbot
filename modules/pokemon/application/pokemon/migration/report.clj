(ns pokemon.migration.report
  "Relatório de equivalência, proveniência e quarentena (M5.0)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn build-report
  [{:keys [ir import-result equivalence backup restore switch-result]}]
  {:milestone "M5.0"
   :synthetic true
   :m5-complete? false
   :nota "M5 NÃO concluído: falta importação autorizada de dados reais."
   :ir-hash (:ir-hash ir)
   :player-key (:player-key ir)
   :provenance
   (mapv (fn [s]
           {:slot (:slot-id s)
            :snapshot-id (get-in s [:provenance :snapshot-id])
            :kind (get-in s [:provenance :kind])
            :label (get-in s [:provenance :label])
            :source-module (get-in s [:provenance :source :module])
            :pokemon-count (:pokemon-count s)
            :content-hash (:content-hash s)
            :sourceVersion-ignored
            (get-in s [:provenance :source :sourceVersion])})
         (:saves ir))
   :quarantine (:quarantine ir)
   :import import-result
   :equivalence equivalence
   :backup backup
   :restore restore
   :switch-a-b-a switch-result
   :generated-at (str (java.time.Instant/now))})

(defn write-edn!
  [report path]
  (io/make-parents path)
  (spit path (with-out-str (pprint/pprint report)))
  path)

(defn write-markdown!
  [report path]
  (io/make-parents path)
  (let [lines
        (concat
         ["# Relatório M5.0 — migração sintética"
          ""
          (str "- Gerado: `" (:generated-at report) "`")
          (str "- M5 completo? **" (:m5-complete? report) "** — " (:nota report))
          (str "- player-key: `" (:player-key report) "`")
          (str "- IR hash: `" (:ir-hash report) "`")
          ""
          "## Proveniência dos saves"
          ""]
         (mapcat
          (fn [p]
            [(str "### Slot `" (:slot p) "`")
             (str "- snapshot-id: `" (:snapshot-id p) "`")
             (str "- kind: `" (:kind p) "`")
             (str "- label: " (:label p))
             (str "- source module: `" (:source-module p) "`")
             (str "- Pokémon: " (:pokemon-count p))
             (str "- content-hash: `" (:content-hash p) "`")
             (when (:sourceVersion-ignored p)
               (str "- sourceVersion (ignorado como identidade): `"
                    (:sourceVersion-ignored p) "`"))
             ""])
          (:provenance report))
         ["## Quarentena"
          ""]
         (if (seq (:quarantine report))
           (mapv (fn [q]
                   (str "- **" (name (:reason q)) "**: "
                        (pr-str (:data q))))
                 (:quarantine report))
           ["- (vazia para bloqueios duros; órfãos/avisos podem constar)"])
         [""
          "## Equivalência"
          ""
          (str "- ok? **" (boolean (get-in report [:equivalence :ok?])) "**")
          (str "- active-save: `" (get-in report [:equivalence :active-save-id]) "`")
          ""]
         (mapcat
          (fn [s]
            [(str "### Save `" (:slot-id s) "` — ok? " (:ok? s))
             (str "- esperado/atual: " (:expected-count s) "/" (:actual-count s))
             (if (seq (:diffs s))
               (str "- diffs: " (pr-str (:diffs s)))
               "- diffs: nenhum")
             ""])
          (get-in report [:equivalence :saves] []))
         ["## Alternância A→B→A"
          ""
          (str "```")
          (pr-str (:switch-a-b-a report))
          "```"
          ""
          "## Backup / restore"
          ""
          (str "- backup: " (pr-str (:backup report)))
          (str "- restore: " (pr-str (:restore report)))
          ""
          "## Import"
          ""
          "```"]
         [(pr-str (:import report))
          "```"
          ""])]
    (spit path (str/join "\n" lines))
    path)
)
