(ns a6-fase1281-pilot-festival-rehearsal
  "Ensaio descartável Etapa B — Festival dos Dragões 24h + isolamento.
   NÃO toca produção. Flag simulada via with-redefs.

   clojure -M -e '(load-file \"scripts/a6_fase1281_pilot_festival_rehearsal.clj\")' -m a6-fase1281-pilot-festival-rehearsal"
  (:require [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.thematic-events :as thematic]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.events :as events]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.thematic-event-store :as tstore])
  (:import [java.nio.file Files]))

(def piloto "120363427684548487@g.us")
(def outro "000000000000000000@g.us")
(def day-ms (* 24 60 60 1000))

(defn -main [& _]
  (let [dir (str (Files/createTempDirectory "pilot-b-" (make-array java.nio.file.attribute.FileAttribute 0)))
        client (persistence/client {:system "pilot-b" :storage-dir dir})
        conn (persistence/ensure-db! client "core")
        s (trainer/bootstrap! (store/make-store conn))
        agora 1700000000000]
    (trainer/create-trainer! s {:chat-id piloto :player-id "ash"})
    (trainer/create-trainer! s {:chat-id outro :player-id "misty"})
    (with-redefs [events/thematic-events-enabled? (constantly true)]
      (thematic/admin-schedule!
       s {:chat-id piloto :next-at agora :catalog-id "festival-dragoes"
          :enabled? true :duration-h 24 :agora-ms agora})
      (thematic/admin-schedule!
       s {:chat-id outro :next-at agora :enabled? false :agora-ms agora})
      (let [t1 (thematic/tick! s {:chat-id piloto :agora-ms agora})
            oid (:started (first (:processed t1)))
            occ (first (tstore/active-occurrences (d/db conn) piloto agora))
            t-out (thematic/tick! s {:chat-id outro :agora-ms agora})
            t-global (thematic/tick! s {:agora-ms agora})
            start-outbox (some #(when (= "thematic-event-started" (:kind %)) %)
                               (ports/list-pokemon-outbox s 20))]
        (assert oid "started")
        (assert (= "festival-dragoes" (:catalog-id occ)))
        (assert (= day-ms (- (:fim-ms occ) (:inicio-ms occ))) "24h")
        (assert (nil? (:started (first (:processed t-out)))) "outro sem start")
        (assert (empty? (tstore/active-occurrences (d/db conn) outro agora)))
        (assert (some? start-outbox) "anúncio início")
        (assert (re-find #"FESTIVAL DOS DRAGÕES" (str (:texto start-outbox))))
        ;; Restart simulado: re-tick não duplica
        (thematic/tick! s {:chat-id piloto :agora-ms agora})
        (assert (= 1 (count (filter #(= "thematic-event-started" (:kind %))
                                    (ports/list-pokemon-outbox s 50)))))
        ;; Encerramento automático em T+24h + disable-on-end
        (let [fim (:fim-ms occ)
              t-end (thematic/tick! s {:chat-id piloto :agora-ms fim})
              p (first (:processed t-end))
              end-outbox (filter #(= "thematic-event-ended" (:kind %))
                                 (ports/list-pokemon-outbox s 50))]
          (assert (= [oid] (:ended p)))
          (assert (true? (:disabled-on-end p)))
          (assert (false? (:enabled (thematic/schedule s piloto))))
          (assert (= 1 (count end-outbox)))
          (assert (empty? (tstore/active-occurrences (d/db conn) piloto fim))))
        (println "OK_PILOT_FESTIVAL_24H_ISOLATED_DISABLE_ON_END")
        (println "piloto" piloto "oid" oid)
        (println "global-processed-chats"
                 (mapv (comp :chat-id first :active) (:processed t-global)))
        (System/exit 0)))))