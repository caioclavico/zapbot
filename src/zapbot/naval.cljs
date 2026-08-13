(ns zapbot.naval
  "Comando !naval - batalha naval entre duas pessoas do chat.
  As frotas são posicionadas automaticamente e ao acaso: como as mensagens
  do chat são públicas, ninguém (nem o próprio dono) vê onde seus navios
  estão - só se vê água/acerto/afundou, igual ao \"tabuleiro de tiro\" do
  jogo físico. Isso evita ter que mandar o posicionamento em privado.
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

(def ^:private tam-tabuleiro 6)
(def ^:private total-casas (* tam-tabuleiro tam-tabuleiro))
(def ^:private letras-linha ["A" "B" "C" "D" "E" "F"])

(def ^:private navios-modelo
  [{:nome "Porta-aviões" :tamanho 3}
   {:nome "Cruzador"     :tamanho 2}
   {:nome "Submarino"    :tamanho 2}])

(def ^:private emoji-agua "🟦")
(def ^:private emoji-erro "⬜")
(def ^:private emoji-acerto "🟥")
(def ^:private emoji-afundado "☠️")

(defn- posicao->indice [[linha coluna]] (+ (* linha tam-tabuleiro) coluna))

(defn- dentro-do-tabuleiro? [[linha coluna]]
  (and (<= 0 linha (dec tam-tabuleiro)) (<= 0 coluna (dec tam-tabuleiro))))

(defn- posicoes-navio [[linha coluna] direcao tamanho]
  (for [i (range tamanho)]
    (if (= direcao :horizontal)
      [linha (+ coluna i)]
      [(+ linha i) coluna])))

(defn- posicionar-navio [ocupadas {:keys [tamanho]}]
  (loop []
    (let [direcao  (rand-nth [:horizontal :vertical])
          origem   [(rand-int tam-tabuleiro) (rand-int tam-tabuleiro)]
          posicoes (posicoes-navio origem direcao tamanho)
          indices  (map posicao->indice posicoes)]
      (if (and (every? dentro-do-tabuleiro? posicoes) (not-any? ocupadas indices))
        indices
        (recur)))))

(defn- criar-tabuleiro []
  (let [navios  (reduce (fn [navios modelo]
                           (let [ocupadas (set (mapcat :posicoes navios))
                                 indices  (posicionar-navio ocupadas modelo)]
                             (conj navios (assoc modelo :posicoes (set indices) :afundado? false))))
                         []
                         navios-modelo)
        celulas (reduce #(assoc %1 %2 :navio)
                         (vec (repeat total-casas :agua))
                         (mapcat :posicoes navios))]
    {:celulas celulas :navios navios}))

(defn- atacar-tabuleiro [tabuleiro indice]
  (case (nth (:celulas tabuleiro) indice)
    :navio
    (let [celulas    (assoc (:celulas tabuleiro) indice :acerto)
          navio-idx  (first (keep-indexed (fn [i n] (when (contains? (:posicoes n) indice) i))
                                           (:navios tabuleiro)))
          navio      (nth (:navios tabuleiro) navio-idx)
          afundado?  (every? #(= :acerto (nth celulas %)) (:posicoes navio))
          navios     (assoc-in (:navios tabuleiro) [navio-idx :afundado?] afundado?)]
      {:tabuleiro  (assoc tabuleiro :celulas celulas :navios navios)
       :resultado  (if afundado? :afundou :acerto)
       :navio-nome (:nome navio)})

    :agua
    {:tabuleiro (assoc tabuleiro :celulas (assoc (:celulas tabuleiro) indice :erro))
     :resultado :erro}

    ;; já era :acerto ou :erro - posição repetida
    {:tabuleiro tabuleiro :resultado :repetido}))

(defn- parse-coordenada [texto]
  (let [texto (-> texto str/trim str/upper-case)]
    (when (re-matches #"[A-F][1-6]" texto)
      (let [linha  (- (.charCodeAt texto 0) (.charCodeAt "A" 0))
            coluna (dec (js/parseInt (subs texto 1)))]
        (posicao->indice [linha coluna])))))

(defn- emoji-celula [tabuleiro indice]
  (case (nth (:celulas tabuleiro) indice)
    :erro emoji-erro
    :acerto (if (some #(and (:afundado? %) (contains? (:posicoes %) indice)) (:navios tabuleiro))
              emoji-afundado
              emoji-acerto)
    emoji-agua))

(defn- desenhar-tabuleiro [tabuleiro]
  (let [linha-cabecalho (str "    " (str/join "  " (range 1 (inc tam-tabuleiro))))
        linhas (for [l (range tam-tabuleiro)]
                 (str (nth letras-linha l) "  "
                      (str/join " " (map #(emoji-celula tabuleiro (+ (* l tam-tabuleiro) %))
                                          (range tam-tabuleiro)))))]
    (str "```\n" linha-cabecalho "\n" (str/join "\n" linhas) "\n```")))

(defn- outro [marca] (if (= marca :x) :o :x))

(defn- cabecalho []
  (str "🚢 *Batalha naval do tio " config/bot-name "*\n\n"))

(defn- mensagem-estado [{:keys [tabuleiros vez nomes]}]
  (str "🌊 Mar de " (get nomes :x) " (" (get nomes :o) " atira aqui):\n"
       (desenhar-tabuleiro (:x tabuleiros)) "\n\n"
       "🌊 Mar de " (get nomes :o) " (" (get nomes :x) " atira aqui):\n"
       (desenhar-tabuleiro (:o tabuleiros)) "\n\n"
       "Vez de " (if (= vez :x) "❌" "⭕") " (" (get nomes vez) ")"
       " - atire com " config/prefix "naval <coordenada>, ex.: " config/prefix "naval C4"))

(defn- criar-jogo [id nome]
  {:tabuleiros {:x (criar-tabuleiro)}
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
                        (desenhar-tabuleiro (get-in jogo-atual [:tabuleiros :x]))))

      jogo-atual
      (p/let [nome (nome-de message)]
        (let [jogo-novo (-> jogo-atual
                             (assoc-in [:jogadores :o] pid)
                             (assoc-in [:nomes :o] nome)
                             (assoc-in [:tabuleiros :o] (criar-tabuleiro)))]
          (swap! jogos assoc cid jogo-novo)
          (str (cabecalho) "⭕ " nome " entrou! Frotas posicionadas, batalha começando.\n\n"
               (mensagem-estado jogo-novo))))

      :else
      (p/let [nome (nome-de message)]
        (let [jogo-novo (criar-jogo pid nome)]
          (swap! jogos assoc cid jogo-novo)
          (str (cabecalho) "❌ " nome " abriu uma partida de batalha naval! Quem quiser jogar de ⭕, "
               "mande " config/prefix "naval pra entrar.\n\n"
               (desenhar-tabuleiro (get-in jogo-novo [:tabuleiros :x]))))))))

(defn- sair [message]
  (let [cid (chat-id message)]
    (if (get @jogos cid)
      (do (swap! jogos dissoc cid)
          (p/resolved (str (cabecalho) "🚪 Partida cancelada.")))
      (p/resolved (str (cabecalho) "❓ Não tem nenhuma partida rolando nesse chat.")))))

(defn- atirar-em [message indice]
  (let [cid  (chat-id message)
        pid  (jogador-id message)
        jogo (get @jogos cid)]
    (p/resolved
     (cond
       (nil? jogo)
       (str (cabecalho) "❓ Não tem partida rolando. Digite " config/prefix "naval pra abrir uma.")

       (not (contains? (:jogadores jogo) :o))
       (str (cabecalho) "⏳ Ainda falta alguém entrar de ⭕. Digite " config/prefix "naval pra entrar.")

       (not= pid (get-in jogo [:jogadores (:vez jogo)]))
       (str (cabecalho) "🚫 Não é sua vez!\n\n" (mensagem-estado jogo))

       :else
       (let [atacante (:vez jogo)
             alvo     (outro atacante)
             {:keys [tabuleiro resultado navio-nome]} (atacar-tabuleiro (get-in jogo [:tabuleiros alvo]) indice)]
         (cond
           (= resultado :repetido)
           (str (cabecalho) "🚫 Essa posição já foi atacada.\n\n" (mensagem-estado jogo))

           (and (= resultado :afundou) (every? :afundado? (:navios tabuleiro)))
           (do (swap! jogos dissoc cid)
               (rank/pontuar! cid (get-in jogo [:jogadores atacante]) (get-in jogo [:nomes atacante]) "naval")
               (str (cabecalho) (desenhar-tabuleiro tabuleiro) "\n\n🏆 "
                    (get-in jogo [:nomes atacante]) " afundou a frota inteira e venceu!"))

           :else
           (let [jogo-novo (-> jogo (assoc-in [:tabuleiros alvo] tabuleiro) (assoc :vez alvo))]
             (swap! jogos assoc cid jogo-novo)
             (str (cabecalho)
                  (case resultado
                    :acerto  "🎯 Tiro certeiro!\n\n"
                    :afundou (str "💥 Afundou o " navio-nome "!\n\n")
                    :erro    "🌊 Água! Passa a vez.\n\n")
                  (mensagem-estado jogo-novo)))))))))

(defn jogar [message args]
  (let [args   (str/trim (or args ""))
        indice (parse-coordenada args)]
    (cond
      (str/blank? args) (iniciar-ou-entrar message)
      (= (str/lower-case args) "sair") (sair message)
      (some? indice) (atirar-em message indice)
      :else (p/resolved (str (cabecalho) "❓ Use " config/prefix "naval (abrir/entrar), "
                              config/prefix "naval <coordenada, ex.: C4> (atirar) ou "
                              config/prefix "naval sair.")))))
