(ns zapbot.sorteio
  "Comando !sorteio - sorteia um participante conhecido do chat/grupo.
  Usa os participantes vistos no histórico (zapbot.historico) em vez de
  chat.participants, que depende do serializador instável do WhatsApp Web
  (ver comentário em zapbot.historico)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.historico :as historico]))

(defn- so-numero [id]
  (first (str/split id #"@")))

(defn sortear [message]
  (let [candidatos (historico/participantes-conhecidos message)]
    (if (empty? candidatos)
      (p/resolved
       (str "❓ Ainda não conheço ninguém nesse chat pra sortear "
            "(só vejo quem mandou mensagem depois que eu liguei)."))
      (let [{:keys [id nome]} (rand-nth candidatos)
            texto (str "🎲 *Sorteio do tio " config/bot-name "*\n\n"
                       "O escolhido é... @" (so-numero id) "! 🎉")]
        (-> (p/let [_ (.reply message texto nil #js {:mentions #js [id]})]
              nil)
            (p/catch (fn [err]
                       (js/console.error "Erro ao sortear participante:" err)
                       (str "🎲 *Sorteio do tio " config/bot-name "*\n\n"
                            "O escolhido é... " nome "! 🎉"))))))))
