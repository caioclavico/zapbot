(ns odisseu.platform.infrastructure.migration.pipeline
  "Export/import offline sintético Cassandra-shape → Datomic platform (M7.0–M7.3).
   Sem dados reais; sem dual-write; sem produção."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.admins :as admins]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [odisseu.platform.domain.enquetes :as enquetes]
            [odisseu.platform.domain.quiz-historico :as quiz-hist]
            [odisseu.platform.domain.rank :as rank]
            [odisseu.platform.domain.lembretes :as lembretes]
            [odisseu.platform.infrastructure.datomic.store :as store]
            [odisseu.platform.infrastructure.migration.compare :as compare]
            [odisseu.persistence.datomic :as persistence]))

(defn load-synthetic-dump
  "Lê fixture EDN com :meta {:synthetic true}."
  [rel-path]
  (let [f (io/file (persistence/project-root) rel-path)
        dump (edn/read-string (slurp f))]
    (when-not (get-in dump [:meta :synthetic])
      (throw (ex-info "dump_not_synthetic" {:path rel-path})))
    dump))

(defn export-modules
  "Normaliza módulos bloqueio/admins/enquetes/quiz-historico do dump para IR."
  [dump]
  (let [mods (:modules dump {})]
    {:meta (:meta dump)
     :bloqueio (compare/normalize-bloqueio (get mods "bloqueio" {}))
     :admins (compare/normalize-admins (get mods "admins-conhecidos" {}))
     :enquetes (compare/normalize-enquetes (get mods "enquetes" {}))
     :quiz-historico (compare/normalize-quiz-historico (get mods "quiz-historico" {}))
     :rank (compare/normalize-rank (get mods "rank" {}))
     :lembretes (compare/normalize-lembretes (get mods "lembretes" {}))
     :inventory-keys (sort (keys mods))}))

(defn import-ir!
  "Importa IR platform (incl. lembretes) em store Datomic (bulk put).
   Importação NÃO dispara poller/execução automática."
  [platform-store ir]
  (ports/put-block-state! platform-store
                          (bloqueio/normalize-state (:bloqueio ir)))
  (ports/put-admin-state! platform-store
                          (admins/normalize-state (:admins ir)))
  (ports/put-poll-state! platform-store
                         (enquetes/normalize-state (:enquetes ir)))
  (ports/put-quiz-history-state! platform-store
                                 (quiz-hist/normalize-state (:quiz-historico ir)))
  (ports/put-rank-state! platform-store
                         (rank/normalize-state (:rank ir)))
  (ports/put-reminder-state! platform-store
                             (lembretes/normalize-state (:lembretes ir)))
  {:bloqueio (compare/normalize-bloqueio
              (bloqueio/state->persistivel (ports/get-block-state platform-store)))
   :admins (compare/normalize-admins
            (admins/state->persistivel (ports/get-admin-state platform-store)))
   :enquetes (compare/normalize-enquetes
              (enquetes/state->persistivel (ports/get-poll-state platform-store)))
   :quiz-historico (compare/normalize-quiz-historico
                    (quiz-hist/state->persistivel
                     (ports/get-quiz-history-state platform-store)))
   :rank (compare/normalize-rank
          (rank/state->persistivel (ports/get-rank-state platform-store)))
   :lembretes (compare/normalize-lembretes
               (lembretes/state->persistivel
                (ports/get-reminder-state platform-store)))})

(defn equivalence-report
  [ir imported]
  {:bloqueio {:equivalent? (compare/equivalent? (:bloqueio ir) (:bloqueio imported))
              :diff (compare/diff-maps (:bloqueio ir) (:bloqueio imported))}
   :admins {:equivalent? (compare/equivalent? (:admins ir) (:admins imported))
            :diff (compare/diff-maps (:admins ir) (:admins imported))}
   :enquetes {:equivalent? (compare/equivalent? (:enquetes ir) (:enquetes imported))
              :diff (compare/diff-maps (:enquetes ir) (:enquetes imported))}
   :quiz-historico {:equivalent? (compare/equivalent? (:quiz-historico ir)
                                                       (:quiz-historico imported))
                    :diff (compare/diff-maps (:quiz-historico ir)
                                             (:quiz-historico imported))}
   :rank {:equivalent? (compare/equivalent? (:rank ir) (:rank imported))
          :diff (compare/diff-maps (:rank ir) (:rank imported))}
   :lembretes {:equivalent? (compare/equivalent? (:lembretes ir) (:lembretes imported))
               :diff (compare/diff-maps (:lembretes ir) (:lembretes imported))}})
(defn open-disposable-store!
  [storage-rel system db-name]
  (when (re-find #"m53-real-offline|m55-real-offline|prod" (str storage-rel))
    (throw (ex-info "forbidden_storage" {:storage storage-rel})))
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        _ (persistence/delete-db! client db-name)
        conn (persistence/ensure-db! client db-name)]
    (store/make-store conn)))
