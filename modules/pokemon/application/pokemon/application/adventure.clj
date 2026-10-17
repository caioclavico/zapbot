(ns pokemon.application.adventure
  "Adventure Sync — ovos no nível da conta; hatch no save carimbado."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn get-account
  [store chat-id player-id]
  (ports/get-adventure-account store chat-id player-id))

(defn get-egg
  [store egg-id]
  (ports/get-egg store egg-id))

(defn ensure-account!
  [store chat-id player-id]
  (ports/ensure-adventure-account! store chat-id player-id))

(defn grant-egg!
  ([store chat-id player-id request]
   (grant-egg! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/grant-egg! store writer-ctx full))))

(defn incubate!
  ([store chat-id player-id request]
   (incubate! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/incubate-egg! store writer-ctx full))))

(defn hatch!
  "Entrega no save carimbado. A→B bloqueia (save mismatch);
  A→B→A funciona com writer fresco no save A (fence nova)."
  ([store chat-id player-id request]
   (hatch! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/hatch-egg! store writer-ctx full))))
