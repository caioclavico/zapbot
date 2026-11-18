(ns pokemon.tests.live-storage-guard-a6-test
  "Falha ao ler mountinfo não autoriza o cliente."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.persistence.live-guard :as guard]))

(def live "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data")

(defn- temporario []
  (str (System/getProperty "java.io.tmpdir") "/a6-lab"))

(deftest caminho-temporario-isolado-continua-non-live
  (is (= :non-live (guard/classify (temporario))))
  (is (nil? (guard/assert-external! (temporario)))))

(deftest volume-live-e-recusado-antes-do-cliente
  (is (= :live (guard/classify live)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"live_storage_refused"
                        (guard/assert-external! live))))

(deftest ioexception-no-mountinfo-nao-abre-o-cliente
  (let [opened (atom false)]
    (with-redefs [guard/read-mountinfo (fn [] (throw (java.io.IOException. "Invalid argument")))
                  persistence/open-client! (fn [& _] (reset! opened true) :aberto)]
      (is (= :unknown (guard/classify "/data/datomic-local")))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"storage_identity_unknown"
           (persistence/client {:system "lab" :storage-dir "/data/datomic-local"})))
      (is (false? @opened))
      (is (= :non-live (guard/classify (temporario)))))))

(deftest mountinfo-ausente-bloqueia-o-caminho-de-producao
  (with-redefs [guard/read-mountinfo (fn [] {:status :absent})]
    (is (= :unknown (guard/classify "/data/datomic-local")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage_identity_unknown"
                          (guard/assert-external! "/data/datomic-local")))
    (is (= :non-live (guard/classify (temporario))))))

(deftest mountinfo-malformado-bloqueia-o-caminho-de-producao
  (with-redefs [guard/read-mountinfo (fn [] {:status :ok :text "lixo sem campos"})]
    (is (= :unknown (guard/classify "/data/datomic-local")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage_identity_unknown"
                          (guard/assert-external! "/data/datomic-local")))))

(deftest prova-antiga-nao-coincide-com-o-boot-atual
  (is (false? (guard/proof-matches?
               {:exclusive true :other-writers 0
                :volume "odisseu-a6-cutover-datomic"
                :boot-id "antigo" :container-id "abc123abc123"}
               {:boot-id "novo" :container-id "abc123abc123"
                :mounts "odisseu-a6-cutover-datomic"}))))

(deftest mapa-do-chamador-nao-autoriza-o-volume-live
  (is (nil? (ns-resolve 'odisseu.persistence.live-guard '*proof-file*)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"live_storage_refused"
                        (guard/assert-external! live)))
  (is (true? (guard/proof-matches?
              {:exclusive true :other-writers 0
               :volume "odisseu-a6-cutover-datomic"
               :boot-id "mesmo" :container-id "abc123abc123"}
              {:boot-id "mesmo" :container-id "abc123abc123"
               :mounts "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data /data/datomic-local"}))))
