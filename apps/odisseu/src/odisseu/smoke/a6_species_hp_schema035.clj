(ns odisseu.smoke.a6-species-hp-schema035
  "Valida migration 035 só em Datomic descartável — sem produção."
  (:require [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.species-hp :as species-hp]
            [pokemon.domain.species-hp-migrate :as migrate]
            [pokemon.infrastructure.datomic.schema :as schema]))

(defn- fresh-conn []
  (let [dir (str "/tmp/a6-schema035-" (java.util.UUID/randomUUID))
        client (persistence/client {:system (str "a6-s035-" (java.util.UUID/randomUUID))
                                    :storage-dir dir})
        conn (persistence/ensure-db! client "s035")]
    {:conn conn :dir dir}))

(defn- attr-present? [db ident]
  (some? (d/pull db '[:db/ident] ident)))

(defn run
  []
  (let [{:keys [conn]} (fresh-conn)
        files-before (vec (butlast schema/migration-files))
        _ (with-redefs [schema/migration-files files-before]
            (schema/install! conn))
        db0 (d/db conn)
        has-035-before? (boolean
                         (d/pull db0 '[:odisseu.schema/migration-id]
                                 [:odisseu.schema/migration-id "pokemon/035-species-hp-formula"]))
        has-hp-base-before? (attr-present? db0 :pokemon/hp-base)
        _ (schema/install! conn)
        db1 (d/db conn)
        has-035? (boolean
                  (d/pull db1 '[:odisseu.schema/migration-id]
                          [:odisseu.schema/migration-id "pokemon/035-species-hp-formula"]))
        hp-base-meta (d/pull db1 '[:db/valueType :db/cardinality :db/unique :db/index]
                             :pokemon/hp-base)
        hp-ver-meta (d/pull db1 '[:db/valueType :db/cardinality :db/unique :db/index]
                            :pokemon/hp-formula-version)
        ;; reaplicação + “retomada” (install de novo após interrupção simulada)
        _ (schema/install! conn)
        _ (schema/install! conn)
        _ (d/transact conn {:tx-data [{:db/id "p1"
                                       :pokemon/id-pokemon "s035-1"
                                       :pokemon/key "s035-1"
                                       :pokemon/species "eevee"
                                       :pokemon/level 5
                                       :pokemon/hp 70
                                       :pokemon/hp-atual 35
                                       :pokemon/hp-base 55
                                       :pokemon/hp-formula-version species-hp/formula-version}]})
        e (d/pull (d/db conn) '[:pokemon/hp-base :pokemon/hp-formula-version :pokemon/species]
                  [:pokemon/id-pokemon "s035-1"])
        ;; legado sem attrs 035 ainda válido
        _ (d/transact conn {:tx-data [{:db/id "p2"
                                       :pokemon/id-pokemon "s035-legacy"
                                       :pokemon/key "s035-legacy"
                                       :pokemon/species "pikachu"
                                       :pokemon/level 3
                                       :pokemon/hp 40
                                       :pokemon/hp-atual 40}]})
        legado (d/pull (d/db conn) '[:pokemon/hp-base :pokemon/species]
                       [:pokemon/id-pokemon "s035-legacy"])
        plano (migrate/plano-seguro {:species "eevee" :nivel 5 :hp 70 :hp-atual 35
                                     :hp-base 55 :hp-formula-version species-hp/formula-version})
        edn-035 (persistence/read-edn-resource "migrations/pokemon/035-species-hp-formula.edn")]
    {:ok (and (not has-035-before?)
              (not has-hp-base-before?)
              has-035?
              (= :db.type/long (or (:db/ident (:db/valueType hp-base-meta))
                                   (:db/valueType hp-base-meta)))
              (= :db.cardinality/one (or (:db/ident (:db/cardinality hp-base-meta))
                                         (:db/cardinality hp-base-meta)))
              (nil? (:db/unique hp-base-meta))
              (nil? (:db/index hp-base-meta))
              (= :db.type/string (or (:db/ident (:db/valueType hp-ver-meta))
                                     (:db/valueType hp-ver-meta)))
              (= 55 (:pokemon/hp-base e))
              (= species-hp/formula-version (:pokemon/hp-formula-version e))
              (nil? (:pokemon/hp-base legado))
              (= :noop (:status plano))
              (= 2 (count edn-035)))
     :before {:migration-035? has-035-before? :hp-base-attr? has-hp-base-before?}
     :after {:migration-035? has-035?
             :hp-base hp-base-meta
             :hp-formula-version hp-ver-meta}
     :idempotent-reinstall true
     :legacy-dto-ok (nil? (:pokemon/hp-base legado))
     :attrs-roundtrip {:hp-base (:pokemon/hp-base e)
                       :hp-formula-version (:pokemon/hp-formula-version e)}
     :destructive? false
     :notes ["035 só adiciona attrs opcionais; sem retract/rename."
             "Sem índice/unique — seguro com DTOs legados ausentes dos attrs."
             "install! idempotente via :odisseu.schema/migration-id."]}))
(defn -main [& _]
  (let [report (run)]
    (println (pr-str report))
    (shutdown-agents)
    (System/exit (if (:ok report) 0 1))))
