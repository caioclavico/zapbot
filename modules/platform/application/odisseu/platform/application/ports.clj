(ns odisseu.platform.application.ports
  "Portas por domínio — sem KV genérico que esconda regras.")

(defprotocol BlockStore
  (get-block-state [this])
  (put-block-state! [this estado])
  (get-chat-block [this chat-id])
  (set-bot-blocked! [this chat-id blocked? operation-id])
  (block-commands! [this chat-id commands operation-id])
  (unblock-commands! [this chat-id commands operation-id]))

(defprotocol AdminStore
  (get-admin-state [this])
  (put-admin-state! [this estado])
  (remember-admin! [this chat-id player-id operation-id])
  (forget-admin! [this chat-id player-id operation-id])
  (known-admin? [this chat-id player-ids]))

(defprotocol PollStore
  (get-poll-state [this])
  (put-poll-state! [this estado])
  (get-chat-poll [this chat-id])
  (create-poll! [this chat-id poll operation-id])
  (cast-vote! [this chat-id voter-id choice operation-id])
  (close-poll! [this chat-id operation-id]))
