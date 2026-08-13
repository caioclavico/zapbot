(ns zapbot.velha
  "Comando !velha - jogo da velha (tic-tac-toe) entre duas pessoas do chat.
  Estado guardado em memória por chat (não sobrevive a reinício do bot)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.rank :as rank]))

(defonce ^:private jogos (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- jogador-id [message]
  (or (.-author message) (.-from message)))

(defn- nome-de [message]
  (-> (.getContact message)
      (p/then (fn [c] (or (.-pushname c) (.-name c) (.-number c) "Alguém")))
      (p/catch (fn [_] "Alguém"))))

(def ^:private emoji-numero
  ["1️⃣" "2️⃣" "3️⃣" "4️⃣" "5️⃣" "6️⃣" "7️⃣" "8️⃣" "9️⃣"])

(def ^:private linhas-vitoria
  [[0 1 2] [3 4 5] [6 7 8]
   [0 3 6] [1 4 7] [2 5 8]
   [0 4 8] [2 4 6]])

(defn- celula [tabuleiro i]
  (case (nth tabuleiro i)
    :x "❌"
    :o "⭕"
    (nth emoji-numero i)))

(defn- desenhar [tabuleiro]
  (->> (range 0 9 3)
       (map (fn [linha] (str/join " " (map #(celula tabuleiro %) (range linha (+ linha 3))))))
       (str/join "\n\n")))

(defn- vencedor [tabuleiro]
  (some (fn [[a b c]]
          (let [marca (nth tabuleiro a)]
            (when (and marca (= marca (nth tabuleiro b) (nth tabuleiro c)))
              marca)))
        linhas-vitoria))

(defn- empate? [tabuleiro]
  (not-any? nil? tabuleiro))

(defn- outro [marca] (if (= marca :x) :o :x))

(defn- cabecalho []
  (str "⭕❌ *Jogo da velha do tio " config/bot-name "*\n\n"))

(defn- mensagem-estado [{:keys [tabuleiro vez nomes]}]
  (str (desenhar tabuleiro)
       "\n\nVez de " (if (= vez :x) "❌" "⭕") " (" (get nomes vez) ")"
       " - jogue com " config/prefix "velha <1-9>"))

(defn- criar-jogo [id nome]
  {:tabuleiro (vec (repeat 9 nil))
   :jogadores {:x id}
   :nomes {:x nome}
   :vez :x})

(defn- iniciar-ou-entrar [message]
  (let [cid        (chat-id message)
        pid        (jogador-id message)
        jogo-atual (get @jogos cid)]
    (cond
      (and jogo-atual (contains? (:jogadores jogo-atual) :o))
      (p/resolved (str (cabecalho) "⏳ Já tem uma partida rolando nesse chat entre "
                        (get-in jogo-atual [:nomes :x]) " e " (get-in jogo-atual [:nomes :o]) ".\n\n"
                        (mensagem-estado jogo-atual)))

      (and jogo-atual (= pid (get-in jogo-atual [:jogadores :x])))
      (p/resolved (str (cabecalho) "⏳ Você já abriu essa partida, espere alguém entrar de ⭕.\n\n"
                        (desenhar (:tabuleiro jogo-atual))))

      jogo-atual
      (p/let [nome (nome-de message)]
        (let [jogo-novo (-> jogo-atual (assoc-in [:jogadores :o] pid) (assoc-in [:nomes :o] nome))]
          (swap! jogos assoc cid jogo-novo)
          (str (cabecalho) "⭕ " nome " entrou! Partida começando.\n\n" (mensagem-estado jogo-novo))))

      :else
      (p/let [nome (nome-de message)]
        (let [jogo-novo (criar-jogo pid nome)]
          (swap! jogos assoc cid jogo-novo)
          (str (cabecalho) "❌ " nome " abriu uma partida! Quem quiser jogar de ⭕, "
               "mande " config/prefix "velha pra entrar.\n\n" (desenhar (:tabuleiro jogo-novo))))))))

(defn- sair [message]
  (let [cid (chat-id message)]
    (if (get @jogos cid)
      (do (swap! jogos dissoc cid)
          (p/resolved (str (cabecalho) "🚪 Partida cancelada.")))
      (p/resolved (str (cabecalho) "❓ Não tem nenhuma partida rolando nesse chat.")))))

(defn- jogar-em [message posicao]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (p/resolved
     (cond
       (nil? jogo)
       (str (cabecalho) "❓ Não tem partida rolando. Digite " config/prefix "velha pra abrir uma.")

       (not (contains? (:jogadores jogo) :o))
       (str (cabecalho) "⏳ Ainda falta alguém entrar de ⭕. Digite " config/prefix "velha pra entrar.")

       (not= pid (get-in jogo [:jogadores (:vez jogo)]))
       (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo))

       (some? (nth (:tabuleiro jogo) (dec posicao)))
       (str (cabecalho) "🚫 Essa casa já foi jogada.\n\n" (mensagem-estado jogo))

       :else
       (let [marca     (:vez jogo)
             tabuleiro (assoc (:tabuleiro jogo) (dec posicao) marca)
             venceu    (vencedor tabuleiro)]
         (cond
           venceu
           (do (swap! jogos dissoc cid)
               (rank/pontuar! cid (get-in jogo [:jogadores venceu]) (get-in jogo [:nomes venceu]) "velha")
               (str (cabecalho) (desenhar tabuleiro) "\n\n🏆 "
                    (get-in jogo [:nomes venceu]) " (" (if (= venceu :x) "❌" "⭕") ") venceu!"))

           (empate? tabuleiro)
           (do (swap! jogos dissoc cid)
               (str (cabecalho) (desenhar tabuleiro) "\n\n🤝 Deu velha (empate)!"))

           :else
           (let [jogo-novo (-> jogo (assoc :tabuleiro tabuleiro) (assoc :vez (outro marca)))]
             (swap! jogos assoc cid jogo-novo)
             (str (cabecalho) (mensagem-estado jogo-novo)))))))))

(defn jogar [message args]
  (let [args (str/trim (or args ""))]
    (cond
      (str/blank? args) (iniciar-ou-entrar message)
      (= (str/lower-case args) "sair") (sair message)
      (re-matches #"[1-9]" args) (jogar-em message (js/parseInt args))
      :else (p/resolved (str (cabecalho) "❓ Use " config/prefix "velha (abrir/entrar), "
                              config/prefix "velha <1-9> (jogar) ou " config/prefix "velha sair.")))))
