(ns pokemon.tests.live-storage-guard-a6-test
  "O volume live é recusado antes de existir um cliente Datomic."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [odisseu.persistence.live-guard :as guard]))

(def live "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data")

(deftest caminho-temporario-nao-abre-o-volume-live
  (is (nil? (guard/assert-external! (str (System/getProperty "java.io.tmpdir") "/a6-lab")))))

(deftest volume-live-e-recusado-antes-do-cliente
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"live_storage_refused"
                        (guard/assert-external! live)))
  (try
    (guard/assert-external! live)
    (is false)
    (catch clojure.lang.ExceptionInfo ex
      (is (false? (:client-opened? (ex-data ex)))))))

(deftest prova-gravavel-nao-libera-o-volume-live
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "a6-proof-writable")
        file (io/file dir "proof")]
    (.mkdirs dir)
    (spit file "{:exclusive true :other-writers 0 :volume \"odisseu-a6-cutover-datomic\"}")
    (.setWritable file true)
    (binding [guard/*proof-file* (.getAbsolutePath file)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"live_storage_refused"
                            (guard/assert-external! live))))))

(deftest prova-somente-leitura-fora-do-volume-libera-a-ferramenta
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "a6-proof-ro")
        file (io/file dir "proof")]
    (.mkdirs dir)
    (spit file "{:exclusive true :other-writers 0 :volume \"odisseu-a6-cutover-datomic\"}")
    (.setWritable file false false)
    (binding [guard/*proof-file* (.getAbsolutePath file)]
      (is (nil? (guard/assert-external! live))))
    (.setWritable file true false)))
