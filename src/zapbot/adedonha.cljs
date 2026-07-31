(ns zapbot.adedonha
  "Comando !adedonha (STOP) - sorteia uma letra e categorias, e avisa quando
  o tempo acabar. Não valida nem pontua respostas automaticamente; a galera
  confere e pontua na mão, como no jogo físico."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

;; sem K, W, Y - raras demais em português pro jogo ficar divertido
(def ^:private letras
  ["A" "B" "C" "D" "E" "F" "G" "H" "I" "J" "L" "M" "N" "O" "P" "Q" "R" "S" "T" "U" "V" "X" "Z"])

(def ^:private categorias
  ["Nome" "Sobrenome" "Cor" "Animal" "Objeto" "Fruta" "País" "Profissão"])

(def ^:private duracao-ms (* 60 1000))

(defonce ^:private rodadas (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- anunciar-fim! [message letra]
  (let [cid (chat-id message)]
    (when (= letra (get @rodadas cid))
      (swap! rodadas dissoc cid)
      (-> (.reply message (str "⏰ *Tempo esgotado!* A letra era *" letra "*.\n"
                                "Confiram as respostas e contem os pontos aí no grupo! 📝"))
          (p/catch (fn [err] (js/console.error "Erro ao anunciar fim da adedonha:" err)))))))

(defn- iniciar [message]
  (let [cid   (chat-id message)
        letra (rand-nth letras)]
    (swap! rodadas assoc cid letra)
    (js/setTimeout #(anunciar-fim! message letra) duracao-ms)
    (p/resolved
     (str "🔤 *Adedonha do tio " config/bot-name "*\n\n"
          "Letra: *" letra "*\n\n"
          (str/join "\n" (map #(str "• " %) categorias))
          "\n\n⏳ Vocês têm 60 segundos! Mandem as respostas aqui no grupo.\n"
          "Use " config/prefix "adedonha parar pra encerrar antes da hora."))))

(defn- parar [message]
  (let [cid (chat-id message)]
    (if (get @rodadas cid)
      (do (swap! rodadas dissoc cid)
          (p/resolved "🛑 Rodada de adedonha encerrada antes da hora."))
      (p/resolved "❓ Não tem nenhuma rodada de adedonha rolando nesse chat."))))

(defn jogar [message args]
  (let [args     (str/lower-case (str/trim (or args "")))
        parar?   (contains? #{"parar" "stop"} args)
        em-curso (get @rodadas (chat-id message))]
    (cond
      parar?   (parar message)
      em-curso (p/resolved (str "⏳ Já tem uma rodada de adedonha rolando (letra *" em-curso
                                 "*). Use " config/prefix "adedonha parar pra encerrar antes."))
      :else    (iniciar message))))
