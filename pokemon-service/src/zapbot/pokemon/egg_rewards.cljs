(ns zapbot.pokemon.egg-rewards)

(def probabilidades-padrao {:capture-common 10 :pvp-epic 3 :pvp-rare 20})
(def pontos {:capture 15 :battle 10 :victory 15 :pvp-win 15 :daily-mission 30})

(defn validar-probabilidades! [config]
  (when-not (every? (fn [k] (let [n (get config k)]
                             (and (number? n) (js/Number.isFinite n) (<= 0 n 100))))
                    (keys probabilidades-padrao))
    (throw (ex-info "Probabilidades de ovos devem estar entre 0 e 100." {:code :invalid-probabilities})))
  config)

(defn categoria [tipo [primeiro segundo] config]
  (validar-probabilidades! config)
  (case tipo
    :capture (when (< primeiro (:capture-common config)) :comum)
    :pvp-win (cond (< primeiro (:pvp-epic config)) :epico
                   (< segundo (:pvp-rare config)) :raro)
    nil))

(defn evento [id tipo agora]
  {"event-id" id "kind" (name tipo) "at" agora})

(defn guardar-evento [conta e]
  (if-let [anterior (get-in conta ["adventure-eventos" (get e "event-id")])]
    (do (when-not (= (get anterior "kind") (get e "kind"))
          (throw (ex-info "Evento já usado para outra atividade." {:code :event-conflict})))
        conta)
    (assoc-in conta ["adventure-eventos" (get e "event-id")] e)))
