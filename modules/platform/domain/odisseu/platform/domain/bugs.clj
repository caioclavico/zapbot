(ns odisseu.platform.domain.bugs
  "Shape canônico de bugs (espelha zapbot.bugs CLJS)."
  (:require [clojure.string :as str]))

(def statuses #{"aberto" "resolvido"})

(defn normalize-entry
  [info]
  (let [info (or info {})
        id (str (or (get info :id) (get info "id") ""))]
    (cond-> {:id id
             :versao (str (or (get info :versao) (get info "versao") ""))
             :chat (str (or (get info :chat) (get info "chat") ""))
             :reportado-por (str (or (get info :reportado-por) (get info "reportado-por") ""))
             :autor-mensagem (str (or (get info :autor-mensagem) (get info "autor-mensagem") ""))
             :mensagem (str (or (get info :mensagem) (get info "mensagem") ""))
             :origem (str (or (get info :origem) (get info "origem") "anterior"))
             :reportado-em (long (or (get info :reportado-em) (get info "reportado-em") 0))
             :status (str (or (get info :status) (get info "status") "aberto"))}
      (or (get info :mensagem-em) (get info "mensagem-em"))
      (assoc :mensagem-em (long (or (get info :mensagem-em) (get info "mensagem-em"))))
      (or (get info :resolvido-em) (get info "resolvido-em"))
      (assoc :resolvido-em (long (or (get info :resolvido-em) (get info "resolvido-em")))))))

(defn ->persistivel
  [{:keys [id versao chat reportado-por autor-mensagem mensagem mensagem-em
           origem reportado-em status resolvido-em]}]
  (cond-> {"id" (str id)
           "versao" (str versao)
           "chat" (str chat)
           "reportado-por" (str reportado-por)
           "autor-mensagem" (str autor-mensagem)
           "mensagem" (str mensagem)
           "origem" (str origem)
           "reportado-em" (long reportado-em)
           "status" (str status)}
    mensagem-em (assoc "mensagem-em" (long mensagem-em))
    resolvido-em (assoc "resolvido-em" (long resolvido-em))))

(defn ->wire
  [b]
  (->persistivel b))

(defn normalize-state
  [dados]
  (into {}
        (map (fn [[id info]]
               (let [e (normalize-entry (assoc (or info {}) :id (or (:id info) (get info "id") id)))]
                 [(:id e) e])))
        (or dados {})))

(defn state->persistivel
  [estado]
  (into {}
        (map (fn [[id b]] [id (->persistivel b)]))
        estado))

(defn valid-create?
  [{:keys [chat reportado-por mensagem]}]
  (and (not (str/blank? (str chat)))
       (not (str/blank? (str reportado-por)))
       (not (str/blank? (str mensagem)))))

(defn next-id
  "Próximo id numérico (string), igual ao legado Node."
  [estado]
  (str (inc (reduce max 0
                    (keep (fn [id]
                            (try (Long/parseLong (str id))
                                 (catch Exception _ nil)))
                          (keys (or estado {})))))))
