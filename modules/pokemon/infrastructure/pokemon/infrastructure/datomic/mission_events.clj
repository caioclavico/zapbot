(ns pokemon.infrastructure.datomic.mission-events
  "Enriquece a TX canônica antes do commit. Nenhum callback ou fila em memória."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.mission-progress :as progress]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.mission-queries :as queries])
  (:import [java.time Instant]))

;; Só o harness JVM pode substituir o relógio; HTTP não recebe esse parâmetro.
(def ^:dynamic *clock* #(Instant/now))

(defn- read-edn [s] (if s (edn/read-string s) {}))

(defn- source-event [db writer built receipt]
  (let [result (:result built)
        kind (:operation-receipt/kind receipt)
        summary (read-edn (:operation-receipt/summary receipt))
        next (or (:battle result) (:encounter result))
        bid (or (:battle-id next) (:battle-id result) (:battle-id summary))
        previous (when bid (d/pull db '[*] [:battle/id bid]))
        before (read-edn (:battle/state-edn previous))
        _ (when (and bid (#{"battle-turn" "encounter-capture" "pvp-turn"} kind))
            (let [side (some (fn [[side pid]] (when (= pid (:player-id writer)) side))
                             (:jogadores before))
                  owner? (if (= kind "pvp-turn")
                           (and side (= (:save-id writer) (get-in before [:saves side])))
                           (and (= (:player-id writer) (:player-id before))
                                (= (:save-id writer) (:save-id before))))]
              (when-not (and (= (:chat-id writer) (:chat-id before)) owner?)
                (throw (err/error :mission_event_identity_conflict {:battle-id bid})))))
        base (select-keys writer [:chat-id :player-id :save-id])
        finished? (and (:active? before) (not (:active? next)))
        winner (:outcome next)]
    (cond
      (and (= kind "encounter-capture") (:capturou? result)
           (:aguardando-captura? before))
      (assoc base :type :wild-captured :source ["wild-captured" bid]
             :values (get-in before [:pokemons :o :tipos]))

      ;; API JVM de captura direta: a ação persistente é a própria operação.
      ;; A bridge WhatsApp usa apenas encounter-capture, com battle-id estável.
      (and (= kind "capture") (:capturou? result))
      (assoc base :type :wild-captured
             :source ["direct-capture" (:operation-receipt/id receipt)]
             :values (get-in result [:registro :tipos]))

      (and (= kind "battle-turn") finished? (= winner :win)
           (= :wild (:kind next)))
      (assoc base :type :wild-defeated :source ["wild-defeated" bid])

      (and (= kind "battle-turn") finished? (= winner :win)
           (= :gym (:kind next)))
      (assoc base :type :gym-won :source ["gym-won" bid] :values [(:gym-id next)])

      (and (= kind "gym-resolve") (:venceu? result))
      (assoc base :type :gym-won
             :source ["direct-gym-resolve" (:operation-receipt/id receipt)]
             :values [(:gym-id summary)])

      (and (= kind "pvp-turn") finished? (#{:x :o} winner))
      (assoc base :type :pvp-won :source ["pvp-won" bid]
             :player-id (get-in before [:jogadores winner])
             :save-id (get-in before [:saves winner]))

      (and (= kind "gift-send") (:sent result))
      (assoc base :type :gift-sent :source ["gift-sent" (:operation-receipt/id receipt)]))))

(defn attach
  "Invocado somente pelo writer transacional com o resultado/recibo da ação.
  Evento, progresso e recibo entram no mesmo d/transact da ação original."
  [db writer built instant]
  (let [receipt (some #(when (and (map? %) (:operation-receipt/id %)) %) (:tx built))
        event (when receipt (source-event db writer built receipt))]
    (if-not event built
      (let [{:keys [chat-id player-id save-id]} event
            sk (id/save-key chat-id player-id save-id)
            pk (id/player-key chat-id player-id)
            save (d/pull db '[*] [:save/key sk])
            player (d/pull db '[*] [:player/key pk])
            key (pr-str (:source event))
            existing (d/pull db '[*] [:mission-event/key key])]
        (when-not (:save/key save) (throw (err/error :save_not_found {:save-id save-id})))
        (when-not (= save-id (:player/active-save-id player))
          (throw (err/error :stale_writer_fence {:player-id player-id :save-id save-id})))
        (if (:mission-event/key existing)
          (let [old (read-edn (:mission-event/data-edn existing))]
            (when-not (= (select-keys event [:chat-id :player-id :save-id :type :source])
                         (select-keys old [:chat-id :player-id :save-id :type :source]))
              (throw (err/error :mission_event_identity_conflict {:source (:source event)})))
            ;; O caller relê o recibo após esta exceção. Outra ID de operação
            ;; sobre a mesma origem permanece explicitamente rejeitada.
            (throw (err/error :mission_event_already_processed {:source (:source event)})))
          (let [timezone (or (not-empty (System/getenv "MISSOES_TIMEZONE")) mundo/default-timezone)
                event (merge event (catalog/periods instant timezone)
                             {:instant (str instant) :timezone timezone
                              :operation-id (:operation-receipt/id receipt)})
                ;; A master calcula o nível após registrar a vitória do treinador.
                ;; Projeta somente os db/add canônicos no save, sem executar efeitos.
                save-after (reduce (fn [s item]
                                     (if (and (vector? item) (= :db/add (first item))
                                              (= [:save/key sk] (second item)))
                                       (assoc s (nth item 2) (nth item 3)) s))
                                   save (:tx built))
                updated (progress/apply-event
                         (read-edn (:save/missoes-diarias-edn save))
                         (read-edn (:save/missoes-semanais-edn save))
                         (queries/trainer-level save-after) event)
                lookup [:save/key sk]
                ;; O CAS do ator já está na TX; vencedor PvP pode ser o outro save.
                revision-cas? (some #(and (vector? %) (= :db/cas (first %))
                                         (= lookup (second %)) (= :save/revision (nth % 2 nil)))
                                    (:tx built))
                tx (cond-> [{:mission-event/key key
                             :mission-event/save lookup
                             :mission-event/operation (or (:db/id receipt) "mission-source-receipt")
                             :mission-event/data-edn (pr-str event)}
                            [:db/cas [:player/key pk] :player/active-save-id save-id save-id]
                            [:db/cas [:player/key pk] :player/fence-generation
                             (:player/fence-generation player) (:player/fence-generation player)]]
                     (not revision-cas?)
                     (conj [:db/cas lookup :save/revision (:save/revision save) (inc (:save/revision save 0))])
                     (:daily updated)
                     (conj [:db/add lookup :save/missoes-diarias-edn (pr-str (:daily updated))])
                     (:weekly updated)
                     (conj [:db/add lookup :save/missoes-semanais-edn (pr-str (:weekly updated))]))
                summary (assoc (read-edn (:operation-receipt/summary receipt)) :mission-event key)
                summary (cond-> summary (map? (:result summary)) (assoc-in [:result :mission-event] key))]
            (-> built
                (update :tx #(into (mapv (fn [item]
                                          (if (= item receipt)
                                            (assoc item :db/id (or (:db/id receipt) "mission-source-receipt")
                                                   :operation-receipt/summary (pr-str summary)) item)) %) tx))
                (assoc-in [:result :mission-event] key))))))))
