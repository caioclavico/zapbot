(ns zapbot.bloqueio
  "Comandos !bloquear/!desbloquear - admins do grupo (ou números em
  ADMIN_NUMBERS) podem desligar o bot inteiro num chat, bloquear
  comandos específicos (ex.: !naval), ou todos os jogos de uma vez
  (!bloquear jogos), sem precisar mexer no .env.
  Estado persistido via zapbot.armazenamento (sobrevive a reinicios/deploys)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [clojure.set :as set]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

;; nunca podem ser bloqueados, pra nunca travar o chat sem saída
(def ^:private protegidos #{"bloquear" "desbloquear"})

;; !bloquear jogos / !desbloquear jogos afeta todos de uma vez (inclui apelidos, ex.: stop)
(def ^:private jogos-comandos #{"bola8" "sorteio" "velha" "naval" "adedonha" "stop" "quiz" "pokemon"})

(defn- carregar-estado []
  (into {}
        (map (fn [[cid info]]
               [cid {:bot? (boolean (get info "bot?"))
                     :comandos (set (get info "comandos"))}]))
        (armazenamento/obter "bloqueio")))

(defn- estado->persistivel [estado]
  (into {}
        (map (fn [[cid info]]
               [cid {"bot?" (boolean (:bot? info))
                     "comandos" (vec (:comandos info))}]))
        estado))

(defonce ^:private estado (atom (carregar-estado)))

(defn- persistir! []
  (armazenamento/salvar! "bloqueio" (estado->persistivel @estado)))

(defn chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- autorizado-por-config? [serialized-id]
  (contains? config/admin-numbers serialized-id))

(defn- so-usuario [serialized-id]
  (first (str/split (or serialized-id "") #"@")))

(defn- participante [chat serialized-id]
  (or (first (filter #(= (.. % -id -_serialized) serialized-id) (.-participants chat)))
      ;; fallback: às vezes só um dos dois lados normaliza pro formato
      ;; "user@lid" vs "user@c.us" (rollout do LID) - compara só a parte
      ;; antes do "@" (o número/id em si) como último recurso
      (first (filter #(= (.-user (.-id %)) (so-usuario serialized-id)) (.-participants chat)))))

;; WhatsApp às vezes reporta IDs diferentes pra mesma pessoa dependendo do
;; caminho de resolução (rollout do formato @lid vs @c.us - já vimos isso
;; quebrar a comparação com ADMIN_NUMBERS, ver nota de 2026-08-12); aqui o
;; mesmo pode acontecer entre o id da mensagem e o id salvo na lista de
;; participantes do grupo, então tenta todos os ids conhecidos da pessoa
;; antes de desistir.
(defn- admin-do-grupo? [chat ids]
  (if-let [p (some #(participante chat %) ids)]
    (boolean (or (.-isAdmin p) (.-isSuperAdmin p)))
    (do (js/console.warn "bloqueio: nenhum participante do grupo bateu com os ids" (pr-str ids)
                          "- participantes no chat:" (count (.-participants chat))
                          "- chat.isGroup:" (.-isGroup chat))
        false)))

(defn- ids-da-pessoa [message]
  (let [autor-id (or (.-author message) (.-from message))]
    (-> (p/let [contato (.getContact message)]
          (let [contato-id (.. contato -id -_serialized)]
            (distinct [autor-id contato-id])))
        (p/catch (fn [_] [autor-id])))))

(defn- autorizado? [message]
  (let [autor-id (or (.-author message) (.-from message))]
    (cond
      (autorizado-por-config? autor-id)
      (p/resolved true)

      ;; sem .-author = mensagem direta (fora de grupo) - não tem "admin do
      ;; grupo" aqui; não depende de chat.isGroup, que já vimos vir
      ;; incorreto quando o modelo interno do WhatsApp Web não tem os
      ;; metadados do grupo carregados ainda
      (nil? (.-author message))
      (p/resolved false)

      :else
      (-> (p/let [chat (.getChat message)
                  ids  (ids-da-pessoa message)]
            (admin-do-grupo? chat ids))
          (p/catch (fn [err]
                     (js/console.error "bloqueio: erro ao verificar admin do grupo:" err)
                     false))))))

(defn bot-bloqueado? [cid]
  (boolean (get-in @estado [cid :bot?])))

(defn comando-bloqueado? [cid comando]
  (contains? (get-in @estado [cid :comandos] #{}) comando))

(defn- formatar-lista [cid]
  (let [{:keys [bot? comandos]} (get @estado cid)]
    (str "📋 *Status nesse chat:*\n"
         "Bot: " (if bot? "🔇 bloqueado" "🔊 ativo") "\n"
         "Comandos bloqueados: " (if (seq comandos)
                                    (str/join ", " (map #(str config/prefix %) comandos))
                                    "nenhum"))))

(defn- bloquear! [cid alvo]
  (cond
    (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] true)
        (persistir!)
        (str "🔇 Bot bloqueado nesse chat. Use " config/prefix "desbloquear para reativar."))

    (= alvo "jogos")
    (do (swap! estado update-in [cid :comandos] (fnil into #{}) jogos-comandos)
        (persistir!)
        "🔇 Todos os jogos foram bloqueados nesse chat.")

    :else
    (do (swap! estado update-in [cid :comandos] (fnil conj #{}) alvo)
        (persistir!)
        (str "🔇 " config/prefix alvo " bloqueado nesse chat."))))

(defn- desbloquear! [cid alvo]
  (cond
    (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] false)
        (persistir!)
        "🔊 Bot reativado nesse chat.")

    (= alvo "jogos")
    (do (swap! estado update-in [cid :comandos] (fnil set/difference #{}) jogos-comandos)
        (persistir!)
        "🔊 Todos os jogos foram liberados nesse chat.")

    :else
    (do (swap! estado update-in [cid :comandos] (fnil disj #{}) alvo)
        (persistir!)
        (str "🔊 " config/prefix alvo " liberado nesse chat."))))

(defn processar-comando
  "cmd é \"bloquear\" ou \"desbloquear\"; args é o resto do texto (vazio ou
  \"tudo\" = bot inteiro, \"jogos\" = todos os jogos de uma vez, \"listar\" =
  mostra o status, ou o nome de um comando)."
  [message cmd args]
  (p/let [ok? (autorizado? message)]
    (let [cid (chat-id message)
          arg (let [a (-> args str/trim str/lower-case)] (if (str/blank? a) "tudo" a))]
      (cond
        (not ok?)
        "🚫 Você precisa ser administrador do grupo para usar esse comando."

        (= arg "listar")
        (formatar-lista cid)

        (contains? protegidos arg)
        (str "🚫 " config/prefix arg " não pode ser bloqueado.")

        (= cmd "bloquear")
        (bloquear! cid arg)

        :else
        (desbloquear! cid arg)))))
