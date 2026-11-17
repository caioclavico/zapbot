(ns pokemon.migration.recovery
  "Procedimento offline: checksums, inventário, matching, export IR (sem mutar origem)."
  (:require [clojure.java.io :as io]
            [pokemon.domain.reward :as reward]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fix]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.inventory :as inv]
            [pokemon.migration.match :as match]
            [pokemon.migration.transform :as transform])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn file-sha256
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (slurp path) StandardCharsets/UTF_8)]
    (.update digest bytes)
    (apply str (map #(format "%02x" %) (.digest digest)))))

(defn checksum-manifest
  "Gera manifesto de checksums para arquivos de dump offline (somente leitura)."
  [paths]
  {:algorithm "SHA-256"
   :files (mapv (fn [p]
                  {:path (str p)
                   :sha256 (file-sha256 p)
                   :bytes (.length (io/file p))})
                paths)
   :at (str (java.time.Instant/now))})

(defn verify-checksums!
  [manifest]
  (doseq [{:keys [path sha256]} (:files manifest)]
    (let [actual (file-sha256 path)]
      (when-not (= actual sha256)
        (throw (ex-info "checksum_mismatch"
                        {:path path :expected sha256 :actual actual})))))
  {:ok true :verified (count (:files manifest))})

(defn run-offline-discovery!
  "Inventário + matching sobre dump offline. Não importa. Não escolhe sob ambiguidade."
  [{:keys [dump-path out-dir]
    :or {out-dir "target/m5-1"}}]
  (let [out (io/file out-dir)
        _ (.mkdirs out)
        dump (export/load-edn-file dump-path)
        inventory (inv/inventory-dump dump :path dump-path)
        report (match/match-inventory inventory)
        inv-path (inv/write-inventory! inventory (str (io/file out "inventory.edn")))
        match-edn (match/write-match-report! report (str (io/file out "match.edn")))
        match-md (match/write-match-markdown! report (str (io/file out "match.md")))
        checksums (checksum-manifest [dump-path])
        _ (spit (str (io/file out "checksums.edn")) (pr-str checksums))]
    {:inventory-path inv-path
     :match-edn match-edn
     :match-md match-md
     :match-status (:status report)
     :auto-selected? false
     :suggested-mapping (:suggested-mapping report)
     :requires-human-confirmation true
     :checksums checksums
     :block-reason (:block-reason report)}))

(defn export-ir-from-fixture-dump!
  "Exporta IR a partir do dump (origem intacta). Opcionalmente exige matching resolvido."
  [{:keys [dump-path out-dir require-match-resolved?]
    :or {out-dir "target/m5-1"
         require-match-resolved? true}}]
  (let [dump (export/load-edn-file dump-path)
        export-data (export/from-fixture-dump dump)
        ir (transform/transform-export export-data)
        inventory (inv/inventory-dump dump :path dump-path)
        match-report (match/match-inventory inventory)
        _ (when require-match-resolved?
            (gates/assert-importable! ir match-report))
        ir-path (transform/write-ir! ir (str (io/file out-dir "ir.edn")))
        export-path (export/write-export! export-data (str (io/file out-dir "export.edn")))]
    {:ir-path ir-path
     :export-path export-path
     :match-status (:status match-report)
     :gates (gates/analyze-ir ir match-report)
     :source-untouched? true}))

(defn synthetic-discovery!
  "Atalho: gera fixture, grava dump, roda discovery."
  [{:keys [out-dir] :or {out-dir "target/m5-1"}}]
  (let [dump (fix/build-cassandra-dump)
        dump-path (str (io/file "fixtures/m5-0/cassandra-dump.edn"))]
    (io/make-parents dump-path)
    (spit dump-path (pr-str dump))
    (run-offline-discovery! {:dump-path dump-path :out-dir out-dir})))

(def authorization-checklist
  "Ações futuras que EXIGEM autorização expressa antes de dados reais."
  [{:id :access-oracle-vm
    :action "SSH / acesso à VM Oracle ou GCP"
    :requires "autorização ops + ticket"}
   {:id :query-prod-cassandra
    :action "SELECT / COPY em Cassandra de produção"
    :requires "autorização explícita + janela + keyspace read-only"}
   {:id :export-real-dump
    :action "Exportar dump com identidades/WhatsApp reais"
    :requires "autorização LGPD/dono + armazenamento criptografado"}
   {:id :disposable-import-real
    :action "Importar dump real em Datomic descartável"
    :requires "autorização + mapping humano confirmado + checksums"}
   {:id :cutover-prod
    :action "Habilitar POKEMON_ADAPTER=jvm / dual-write / deploy"
    :requires "fora do M5.1 — go/no-go M5.3+"}
   {:id :auto-pick-saves
    :action "Escolher automaticamente entre candidatos ambíguos"
    :requires "PROIBIDO — sempre revisão humana"}])
