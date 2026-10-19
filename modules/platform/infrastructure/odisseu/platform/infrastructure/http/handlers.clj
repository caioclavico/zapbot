(ns odisseu.platform.infrastructure.http.handlers
  "Rotas HTTP /v1/platform/* — bloqueio, admins e enquetes (sem domínio Pokémon)."
  (:require [clojure.string :as str]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [odisseu.platform.domain.enquetes :as enquetes]
            [odisseu.platform.domain.rank :as rank]
            [odisseu.platform.domain.lembretes :as lembretes]
            [odisseu.platform.domain.bugs :as bugs]
            [odisseu.platform.domain.participantes :as parts]
            [pokemon.domain.errors :as err]))

(defn- g [m & ks]
  (some #(get m %) ks))

(defn- require-str! [v label]
  (when (or (nil? v) (and (string? v) (str/blank? v)))
    (throw (err/error :invalid_query {:required [label]})))
  (str v))

(defn- require-op! [body]
  (require-str! (g body "operationId" :operationId) "operationId"))

(defn- block->wire
  [cid {:keys [bot? comandos]}]
  {"chatId" (str cid)
   "bot?" (boolean bot?)
   "comandos" (vec (sort (map str (or comandos #{}))))})

(defn- state-blocks->wire
  "Lista de entradas — chatId como valor (não chave), evita camelCase em JID."
  [st]
  (->> st
       (map (fn [[cid info]] (block->wire cid info)))
       (sort-by #(get % "chatId"))
       vec))

(defn- state-admins->wire [st]
  (->> st
       (map (fn [[cid ids]]
              {"chatId" (str cid)
               "playerIds" (vec (sort (map str ids)))}))
       (sort-by #(get % "chatId"))
       vec))

(defn- poll->wire
  [cid {:keys [pergunta opcoes votos prefix]}]
  (when (and cid pergunta)
    {"chatId" (str cid)
     "pergunta" (str pergunta)
     "opcoes" (mapv str (or opcoes []))
     "votos" (into {}
                   (map (fn [[k v]] [(str k) (long v)])
                        (or votos {})))
     "prefix" (str (or prefix "!"))}))

(defn- state-polls->wire
  [st]
  (->> st
       (map (fn [[cid poll]] (poll->wire cid poll)))
       (sort-by #(get % "chatId"))
       vec))

(defn- poll-from-body
  [body]
  {:pergunta (g body "pergunta" :pergunta)
   :opcoes (vec (or (g body "opcoes" :opcoes) []))
   :votos (or (g body "votos" :votos) {})
   :prefix (or (g body "prefix" :prefix) "!")})

(defn- wrap-poll-ex
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (let [msg (ex-message e)]
        (case msg
          "poll_already_open" (throw (err/error :operation_forbidden (ex-data e)))
          "poll_not_found" (throw (err/error :operation_not_found (ex-data e)))
          "poll_invalid" (throw (err/error :invalid_query (ex-data e)))
          "poll_invalid_choice" (throw (err/error :invalid_query (ex-data e)))
          (throw e))))))

(defn handle
  "Retorna mapa :status + :data ou lança err/error.
   nil se o path não for de platform (caller continua).
   opts opcional: :score-point! (fn [store cid pid nome jogo op]) — M8.6 durable-op."
  ([platform-store method path qs body]
   (handle platform-store method path qs body nil))
  ([platform-store method path qs body
    {:keys [score-point!]
     :or {score-point! (fn [store cid pid nome jogo op]
                         (ports/score-point! store cid pid nome jogo op))}}]
  (when (and platform-store (str/starts-with? path "/v1/platform"))
    (cond
      (= path "/v1/platform/blocks")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-block-state platform-store)]
          {:status 200
           :data (if cid
                   {"block" (block->wire cid (ports/get-chat-block platform-store cid))}
                   {"blocks" (state-blocks->wire st)})}))

      (= path "/v1/platform/blocks/bot")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              blocked? (boolean (g body "blocked" :blocked))
              op (require-op! body)
              res (ports/set-bot-blocked! platform-store cid blocked? op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "blocks" (state-blocks->wire (:state res))
                  "block" (block->wire cid (get (:state res) cid
                                                {:bot? blocked? :comandos #{}}))}}))

      (= path "/v1/platform/blocks/commands")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              action (str/lower-case (str (or (g body "action" :action) "")))
              cmds (or (g body "commands" :commands) [])
              op (require-op! body)
              _ (when-not (#{ "block" "unblock"} action)
                  (throw (err/error :invalid_query {:required ["action=block|unblock"]})))
              _ (when-not (sequential? cmds)
                  (throw (err/error :invalid_query {:required ["commands"]})))
              cmds* (mapv str cmds)
              protegidos (filter bloqueio/protegidos (map str/lower-case cmds*))
              _ (when (seq protegidos)
                  (throw (err/error :operation_forbidden {:commands protegidos})))
              res (if (= "block" action)
                    (ports/block-commands! platform-store cid cmds* op)
                    (ports/unblock-commands! platform-store cid cmds* op))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "blocks" (state-blocks->wire (:state res))
                  "block" (block->wire cid (get (:state res) cid
                                                {:bot? false :comandos #{}}))}}))

      (= path "/v1/platform/admins")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-admin-state platform-store)]
          {:status 200
           :data (if cid
                   {"chatId" cid
                    "playerIds" (vec (sort (map str (get st cid #{}))))}
                   {"admins" (state-admins->wire st)})}))

      (= path "/v1/platform/admins/remember")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              op (require-op! body)
              res (ports/remember-admin! platform-store cid pid op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "admins" (state-admins->wire (:state res))}}))

      (= path "/v1/platform/admins/forget")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              op (require-op! body)
              res (ports/forget-admin! platform-store cid pid op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "admins" (state-admins->wire (:state res))}}))

      (= path "/v1/platform/admins/known")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              ids (or (g body "playerIds" :playerIds) [])
              _ (when-not (sequential? ids)
                  (throw (err/error :invalid_query {:required ["playerIds"]})))
              known? (ports/known-admin? platform-store cid ids)]
          {:status 200
           :data {"chatId" cid "known" (boolean known?)}}))

      (= path "/v1/platform/polls")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-poll-state platform-store)]
          {:status 200
           :data (if cid
                   {"poll" (poll->wire cid (ports/get-chat-poll platform-store cid))}
                   {"polls" (state-polls->wire st)})}))

      (= path "/v1/platform/polls/create")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              op (require-op! body)
              poll (poll-from-body body)
              _ (when-not (enquetes/valid-create? (enquetes/normalize-entry poll))
                  (throw (err/error :invalid_query {:required ["pergunta" "opcoes"]})))
              res (wrap-poll-ex
                   #(ports/create-poll! platform-store cid poll op))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "poll" (poll->wire cid (:poll res))
                  "polls" (state-polls->wire (:state res))}}))

      (= path "/v1/platform/polls/vote")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              vid (require-str! (g body "voterId" :voterId) "voterId")
              choice (g body "choice" :choice)
              op (require-op! body)
              _ (when-not (number? choice)
                  (throw (err/error :invalid_query {:required ["choice"]})))
              res (wrap-poll-ex
                   #(ports/cast-vote! platform-store cid vid (long choice) op))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "poll" (poll->wire cid (:poll res))
                  "polls" (state-polls->wire (:state res))}}))

      (= path "/v1/platform/polls/close")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              op (require-op! body)
              res (wrap-poll-ex
                   #(ports/close-poll! platform-store cid op))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "closed" (boolean (:closed res))
                  "poll" (when-let [p (:poll res)]
                           (poll->wire cid p))
                  "polls" (state-polls->wire (:state res))}}))

      (= path "/v1/platform/quiz-history")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-quiz-history-state platform-store)]
          {:status 200
           :data (if cid
                   {"chatId" cid
                    "questions" (vec (ports/get-chat-quiz-history platform-store cid))}
                   {"histories"
                    (->> st
                         (map (fn [[c qs]]
                                {"chatId" (str c) "questions" (vec qs)}))
                         (sort-by #(get % "chatId"))
                         vec)})}))

      (= path "/v1/platform/quiz-history/append")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              q (require-str! (g body "question" :question) "question")
              op (require-op! body)
              res (try
                    (ports/append-quiz-question! platform-store cid q op)
                    (catch clojure.lang.ExceptionInfo e
                      (if (= "quiz_history_invalid" (ex-message e))
                        (throw (err/error :invalid_query (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "chatId" cid
                  "questions" (vec (:history res))
                  "histories"
                  (->> (:state res)
                       (map (fn [[c qs]]
                              {"chatId" (str c) "questions" (vec qs)}))
                       (sort-by #(get % "chatId"))
                       vec)}}))

      (= path "/v1/platform/rank")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-rank-state platform-store)]
          {:status 200
           :data (if cid
                   {"chatId" cid
                    "players"
                    (->> (ports/get-chat-rank platform-store cid)
                         (map (fn [[pid info]]
                                (assoc info "playerId" (str pid))))
                         (sort-by #(str (get % "playerId")))
                         vec)
                    "top" (rank/top-players
                           (ports/get-chat-rank platform-store cid) 10)}
                   {"chats"
                    (->> st
                         (map (fn [[c players]]
                                {"chatId" (str c)
                                 "players"
                                 (->> players
                                      (map (fn [[pid info]]
                                             (assoc info "playerId" (str pid))))
                                      (sort-by #(str (get % "playerId")))
                                      vec)}))
                         (sort-by #(get % "chatId"))
                         vec)})}))

      (= path "/v1/platform/rank/score")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              nome (require-str! (g body "name" :name) "name")
              jogo (require-str! (g body "game" :game) "game")
              op (require-op! body)
              res (try
                    (score-point! platform-store cid pid nome jogo op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "rank_missing_operation_id"
                        (throw (err/error :missing_operation_id (ex-data e)))
                        (case (err/error-code e)
                          (:durability_compromised :durability_maintenance
                           :durability_journal_required)
                          (throw e)
                          (throw e)))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "player" (assoc (:player res) "playerId" pid)
                  "top" (rank/top-players
                         (get (:state res) cid {}) 10)}}))

      (= path "/v1/platform/rank/penalize")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              op (require-op! body)
              res (ports/penalize-point! platform-store cid pid op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "penalized" (boolean (:penalized res))
                  "player" (when-let [p (:player res)]
                             (assoc p "playerId" pid))}}))

      (= path "/v1/platform/rank/effect")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [res (try
                    (ports/apply-rank-effect! platform-store body)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "rank_effect_invalid"
                        (throw (err/error :invalid_query (ex-data e)))
                        "rank_effect_payload_mismatch"
                        (throw (err/error :receipt_payload_mismatch (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "resultado" (boolean (:resultado res))}}))

      (= path "/v1/platform/reminders")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              rid (not-empty (g qs "id"))
              st (ports/get-reminder-state platform-store)]
          {:status 200
           :data (cond
                   rid
                   {"reminder" (when-let [r (ports/get-reminder platform-store rid)]
                                 (lembretes/->wire r))}

                   cid
                   {"chatId" cid
                    "reminders" (mapv lembretes/->wire
                                      (ports/list-reminders platform-store cid))}

                   :else
                   {"reminders"
                    (->> st vals
                         (map lembretes/->wire)
                         (sort-by #(get % "id"))
                         vec)})}))

      (= path "/v1/platform/reminders/create")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [op (require-op! body)
              rem {:id (g body "id" :id)
                   :chat (g body "chatId" :chatId "chat" :chat)
                   :texto (g body "texto" :texto "text" :text)
                   :criador (g body "criador" :criador "creator" :creator)
                   :em (or (g body "em" :em "dueAt" :dueAt) 0)}
              res (try
                    (ports/create-reminder! platform-store rem op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "reminder_invalid"
                        (throw (err/error :invalid_query (ex-data e)))
                        "reminder_already_exists"
                        (throw (err/error :operation_forbidden (ex-data e)))
                        "reminder_missing_operation_id"
                        (throw (err/error :missing_operation_id (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "reminder" (lembretes/->wire (:reminder res))}}))

      (= path "/v1/platform/reminders/cancel")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [rid (require-str! (g body "id" :id) "id")
              cid (require-str! (g body "chatId" :chatId) "chatId")
              op (require-op! body)
              res (try
                    (ports/cancel-reminder! platform-store rid cid op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "reminder_not_found"
                        (throw (err/error :operation_not_found (ex-data e)))
                        "reminder_wrong_chat"
                        (throw (err/error :operation_forbidden (ex-data e)))
                        "reminder_already_sent"
                        (throw (err/error :operation_forbidden (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "cancelled" (boolean (:cancelled res))
                  "reminder" (when-let [r (:reminder res)]
                               (lembretes/->wire r))}}))

      (= path "/v1/platform/reminders/due")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [now (long (or (some-> (g qs "now") parse-long)
                            (System/currentTimeMillis)))
              lim (long (or (some-> (g qs "limit") parse-long) 50))]
          {:status 200
           :data {"now" now
                  "reminders" (mapv lembretes/->wire
                                    (ports/due-reminders platform-store now lim))}}))

      (= path "/v1/platform/reminders/claim")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [rid (require-str! (g body "id" :id) "id")
              who (require-str! (g body "claimant" :claimant) "claimant")
              lease (long (or (g body "leaseMs" :leaseMs) lembretes/default-lease-ms))
              now (long (or (g body "now" :now) (System/currentTimeMillis)))
              res (try
                    (ports/claim-reminder! platform-store rid who lease now)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "reminder_not_found"
                        (throw (err/error :operation_not_found (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"claimed" (boolean (:claimed res))
                  "reason" (:reason res)
                  "fence" (:fence res)
                  "leaseUntil" (:lease-until res)
                  "claimant" (:claimant res)
                  "reminder" (when-let [r (:reminder res)]
                               (lembretes/->wire r))}}))

      (= path "/v1/platform/reminders/complete")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [rid (require-str! (g body "id" :id) "id")
              who (require-str! (g body "claimant" :claimant) "claimant")
              fence (long (or (g body "fence" :fence) -1))
              res (ports/complete-reminder! platform-store rid who fence)]
          {:status 200
           :data {"completed" (boolean (:completed res))
                  "duplicate" (boolean (:duplicate res))
                  "reason" (:reason res)
                  "reminder" (when-let [r (:reminder res)]
                               (lembretes/->wire r))}}))

      (= path "/v1/platform/reminders/fail")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [rid (require-str! (g body "id" :id) "id")
              who (require-str! (g body "claimant" :claimant) "claimant")
              fence (long (or (g body "fence" :fence) -1))
              err (str (or (g body "error" :error) "send_failed"))
              res (ports/fail-reminder! platform-store rid who fence err)]
          {:status 200
           :data {"failed" (boolean (:failed res))
                  "reason" (:reason res)
                  "reminder" (when-let [r (:reminder res)]
                               (lembretes/->wire r))}}))

      (= path "/v1/platform/bugs")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [bid (not-empty (g qs "id"))
              ver (or (g qs "version") "")]
          {:status 200
           :data (if bid
                   {"bug" (when-let [b (ports/get-bug platform-store bid)]
                            (bugs/->wire b))}
                   {"bugs" (mapv bugs/->wire
                                 (ports/list-bugs platform-store ver))})}))

      (= path "/v1/platform/bugs/create")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [op (require-op! body)
              bug {:id (g body "id" :id)
                   :versao (g body "versao" :versao "version" :version)
                   :chat (g body "chat" :chat "chatId" :chatId)
                   :reportado-por (g body "reportado-por" :reportado-por
                                     "reportadoPor" :reportadoPor
                                     "reportedBy" :reportedBy)
                   :autor-mensagem (g body "autor-mensagem" :autor-mensagem
                                      "autorMensagem" :autorMensagem
                                      "messageAuthor" :messageAuthor)
                   :mensagem (g body "mensagem" :mensagem "message" :message)
                   :mensagem-em (g body "mensagem-em" :mensagem-em
                                   "mensagemEm" :mensagemEm
                                   "messageAt" :messageAt)
                   :origem (g body "origem" :origem "origin" :origin)
                   :reportado-em (or (g body "reportado-em" :reportado-em
                                        "reportadoEm" :reportadoEm
                                        "reportedAt" :reportedAt)
                                     (System/currentTimeMillis))
                   :status (or (g body "status" :status) "aberto")}
              res (try
                    (ports/create-bug! platform-store bug op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "bug_invalid"
                        (throw (err/error :invalid_query (ex-data e)))
                        "bug_already_exists"
                        (throw (err/error :operation_forbidden (ex-data e)))
                        "bug_missing_operation_id"
                        (throw (err/error :missing_operation_id (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "bug" (bugs/->wire (:bug res))}}))

      (= path "/v1/platform/bugs/resolve")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [bid (require-str! (g body "id" :id) "id")
              op (require-op! body)
              at (or (g body "resolvedAt" :resolvedAt "resolvido-em" :resolvido-em)
                     (System/currentTimeMillis))
              res (try
                    (ports/resolve-bug! platform-store bid at op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "bug_not_found"
                        (throw (err/error :operation_not_found (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "bug" (bugs/->wire (:bug res))}}))

      (= path "/v1/platform/participants")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-participant-state platform-store)]
          {:status 200
           :data (if cid
                   (parts/->wire-chat cid (ports/get-chat-participants platform-store cid))
                   {"chats"
                    (->> st
                         (map (fn [[c names]] (parts/->wire-chat c names)))
                         (sort-by #(get % "chatId"))
                         vec)})}))

      (= path "/v1/platform/participants/upsert")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              nome (require-str! (g body "name" :name) "name")
              op (require-op! body)
              res (try
                    (ports/upsert-participant! platform-store cid pid nome op)
                    (catch clojure.lang.ExceptionInfo e
                      (case (ex-message e)
                        "participant_invalid"
                        (throw (err/error :invalid_query (ex-data e)))
                        "participant_missing_operation_id"
                        (throw (err/error :missing_operation_id (ex-data e)))
                        (throw e))))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "chatId" cid
                  "playerId" pid
                  "name" (:name res)}}))

      :else
      (throw (err/error :operation_not_found {:path path}))))))
