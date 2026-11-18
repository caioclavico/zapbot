(ns pokemon.tests.profile-reopen-runner
  "Duas fases JVM para comprovar que o perfil sobrevive ao fechamento do processo."
  (:require [clojure.edn :as edn]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.profile-store :as profile-store]))

(defn- open-store [storage system]
  (let [client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client "core")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    {:client client :conn conn :store s}))

(defn- context [s]
  (let [p (ports/get-player s "reopen-chat" "ash")]
    {:chat-id "reopen-chat" :player-id "ash" :save-id "a"
     :fence-generation (:fence-generation p)}))

(defn- write-phase! [storage system metadata]
  (let [{:keys [conn store]} (open-store storage system)]
    (trainer/create-trainer! store {:chat-id "reopen-chat" :player-id "ash"})
    (let [ctx (context store)]
      (ports/add-to-collection! store ctx {:id-pokemon "reopen-pidgey-1" :species "pidgey"
                                           :nome "Pidgey" :nivel 5 :xp 0 :xp-desde-nivel 0
                                           :hp 20 :hp-atual 20 :amizade 70 :item "restos"})
      (let [ctx (context store)]
        (ports/add-to-collection! store ctx {:id-pokemon "reopen-pidgey-2" :species "pidgey"
                                             :nome "Pidgey" :nivel 5 :xp 0 :xp-desde-nivel 0
                                             :hp 20 :hp-atual 20 :amizade 70})
        (let [ctx (context store)
              sent (profile-store/professor-send! conn (assoc ctx :operation-id "reopen-send") 0 1000)
              confirmed (profile-store/professor-confirm! conn (assoc (context store)
                                                                        :operation-id "reopen-confirm")
                                                               (:token (:summary sent)) 1001)
              used (profile-store/professor-use! conn (assoc (context store)
                                                              :operation-id "reopen-use") 0)
              _ (ports/register-trainer-victory! store (assoc (context store)
                                                               :operation-id "reopen-victory"))
              title (profile-store/select-title! conn (assoc (context store)
                                                              :operation-id "reopen-title") 0)]
          (spit metadata (pr-str {:token (:token (:summary sent))
                                   :confirmed (:summary confirmed)
                                   :used (:summary used)
                                   :title (:summary title)}))
          (println "PROFILE_WRITE_OK"))))))

(defn- verify-phase! [storage system metadata]
  (let [{:keys [conn store]} (open-store storage system)
        saved (edn/read-string (slurp metadata))
        ctx (context store)
        collection (ports/list-collection store "reopen-chat" "ash" "a")
        inventory (ports/list-inventory store "reopen-chat" "ash" "a")
        cards (profile-store/professor-cards conn "reopen-chat" "ash" "a")
        friendship (profile-store/friendship conn "reopen-chat" "ash" "a" 0)
        titles (profile-store/titles conn "reopen-chat" "ash" "a")
        retry (profile-store/professor-confirm! conn (assoc ctx :operation-id "reopen-confirm")
                                                (:token saved) 1002)]
    (assert (= ["reopen-pidgey-2"] (mapv :id-pokemon collection)))
    (assert (= 3 (:xp-desde-nivel (first collection))))
    (assert (= 1 (:qty (first (filter #(= "restos" (:item-id %)) inventory)))))
    (assert (= {"pidgey" 0} (:cards cards)))
    (assert (= 80 (:value friendship)))
    (assert (= "doador-pokemon" (:selected titles)))
    (assert (:duplicate retry))
    (println "PROFILE_REOPEN_OK")))

(defn -main [phase storage system metadata]
  (try
    (when-not (and phase storage system metadata)
      (throw (ex-info "uso: write|verify storage system metadata" {})))
    (case phase
      "write" (write-phase! storage system metadata)
      "verify" (verify-phase! storage system metadata)
      (throw (ex-info "fase desconhecida" {:phase phase})))
    (flush)
    ;; O cliente Datomic Local mantém threads; sair explicitamente permite que
    ;; a segunda JVM abra o mesmo storage sem FileLock concorrente.
    (System/exit 0)
    (catch Throwable e
      (binding [*out* *err*]
        (println "PROFILE_REOPEN_FAILED" (ex-message e))
        (.printStackTrace e)
        (flush))
      (System/exit 1))))
