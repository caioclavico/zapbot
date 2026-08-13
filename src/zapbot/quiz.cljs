(ns zapbot.quiz
  "Comando !quiz - perguntas de múltipla escolha no chat.
  O histórico anti-repetição é persistido via zapbot.armazenamento (sobrevive
  a reinícios/deploys); a pergunta pendente de cada chat fica só em memória."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.rank :as rank]))

(def ^:private perguntas
  [{:pergunta "Qual é o maior planeta do sistema solar?"
    :opcoes   ["Terra" "Júpiter" "Saturno" "Marte"]
    :correta  1}
   {:pergunta "Quem pintou a Mona Lisa?"
    :opcoes   ["Michelangelo" "Rafael" "Leonardo da Vinci" "Van Gogh"]
    :correta  2}
   {:pergunta "Qual é o oceano mais profundo do mundo?"
    :opcoes   ["Atlântico" "Índico" "Ártico" "Pacífico"]
    :correta  3}
   {:pergunta "Em que ano o homem pisou na Lua pela primeira vez?"
    :opcoes   ["1965" "1969" "1972" "1958"]
    :correta  1}
   {:pergunta "Qual metal é líquido à temperatura ambiente?"
    :opcoes   ["Ferro" "Mercúrio" "Alumínio" "Chumbo"]
    :correta  1}
   {:pergunta "Quantos ossos tem o corpo humano adulto?"
    :opcoes   ["186" "206" "226" "246"]
    :correta  1}
   {:pergunta "Qual é a capital da Austrália?"
    :opcoes   ["Sydney" "Melbourne" "Canberra" "Perth"]
    :correta  2}
   {:pergunta "Quem escreveu Dom Casmurro?"
    :opcoes   ["José de Alencar" "Machado de Assis" "Monteiro Lobato" "Graciliano Ramos"]
    :correta  1}
   {:pergunta "Qual é o animal terrestre mais rápido do mundo?"
    :opcoes   ["Leão" "Guepardo" "Cavalo" "Avestruz"]
    :correta  1}
   {:pergunta "Em qual país fica o Vaticano?"
    :opcoes   ["França" "Espanha" "Itália" "Grécia"]
    :correta  2}
   {:pergunta "Qual é o elemento químico de símbolo 'O'?"
    :opcoes   ["Ouro" "Oxigênio" "Ósmio" "Óxido"]
    :correta  1}
   {:pergunta "Quantas cordas tem um violão comum?"
    :opcoes   ["4" "5" "6" "7"]
    :correta  2}
   {:pergunta "Qual é o maior deserto do mundo em área?"
    :opcoes   ["Saara" "Deserto da Arábia" "Antártida" "Gobi"]
    :correta  2}
   {:pergunta "Em que continente fica o Egito?"
    :opcoes   ["Ásia" "África" "Oriente Médio" "Europa"]
    :correta  1}
   {:pergunta "Qual é o rio mais longo do mundo?"
    :opcoes   ["Amazonas" "Nilo" "Yangtzé" "Mississippi"]
    :correta  1}
   {:pergunta "Quem foi o primeiro presidente do Brasil?"
    :opcoes   ["Getúlio Vargas" "Dom Pedro II" "Deodoro da Fonseca" "Juscelino Kubitschek"]
    :correta  2}
   {:pergunta "Qual gás os humanos precisam respirar para sobreviver?"
    :opcoes   ["Nitrogênio" "Oxigênio" "Gás carbônico" "Hidrogênio"]
    :correta  1}
   {:pergunta "Qual é a moeda oficial do Japão?"
    :opcoes   ["Yuan" "Won" "Iene" "Dólar"]
    :correta  2}
   {:pergunta "Quantos lados tem um hexágono?"
    :opcoes   ["5" "6" "7" "8"]
    :correta  1}
   {:pergunta "Quem pintou 'A Noite Estrelada'?"
    :opcoes   ["Van Gogh" "Picasso" "Monet" "Dalí"]
    :correta  0}
   {:pergunta "Qual é o maior mamífero do mundo?"
    :opcoes   ["Elefante africano" "Baleia azul" "Girafa" "Tubarão-baleia"]
    :correta  1}
   {:pergunta "Em que país fica a Torre Eiffel?"
    :opcoes   ["Itália" "Espanha" "França" "Bélgica"]
    :correta  2}
   {:pergunta "Qual é o osso mais longo do corpo humano?"
    :opcoes   ["Fêmur" "Tíbia" "Úmero" "Fíbula"]
    :correta  0}
   {:pergunta "Qual desses é um dos ingredientes principais do guacamole?"
    :opcoes   ["Manga" "Abacate" "Berinjela" "Beterraba"]
    :correta  1}])

(defonce ^:private quizzes (atom {}))

;; últimas perguntas por chat, só pra pedir à IA (e evitar no banco estático)
;; que não repita - persistido, sobrevive a reinício/deploy do bot
(defonce ^:private historico-perguntas (atom (or (armazenamento/obter "quiz-historico") {})))
(def ^:private historico-max 12)

(def ^:private letras ["a" "b" "c" "d"])

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- jogador-id [message]
  (or (.-author message) (.-from message)))

(defn- nome-de [message]
  (-> (.getContact message)
      (p/then (fn [c] (or (.-pushname c) (.-name c) (.-number c) "Alguém")))
      (p/catch (fn [_] "Alguém"))))

(defn- cabecalho []
  (str "🧩 *Quiz do tio " config/bot-name "*\n\n"))

(defn- normalizar-resposta [texto]
  (case (-> texto str/trim str/lower-case)
    ("a" "1") 0
    ("b" "2") 1
    ("c" "3") 2
    ("d" "4") 3
    nil))

(defn- mensagem-pergunta [{:keys [pergunta opcoes]}]
  (str pergunta "\n\n"
       (str/join "\n" (map #(str (str/upper-case %1) ") " %2) letras opcoes))
       "\n\n✏️ Responda com " config/prefix "quiz <letra>"))

(defn- registrar-historico! [cid pergunta-texto]
  (swap! historico-perguntas update cid
         (fn [hist] (vec (take-last historico-max (conj (vec hist) pergunta-texto)))))
  (armazenamento/salvar! "quiz-historico" @historico-perguntas))

(defn- pergunta-estatica-nao-repetida [cid]
  (let [ja-usadas (set (get @historico-perguntas cid))
        restantes (remove #(contains? ja-usadas (:pergunta %)) perguntas)]
    (rand-nth (if (seq restantes) restantes perguntas))))

(defn- prompt-pergunta [historico]
  (str "Crie UMA pergunta de quiz de conhecimentos gerais, em português do "
       "Brasil, com dificuldade variada e tema aleatório (geografia, história, "
       "ciências, cultura pop, esportes, artes etc.). Responda ESTRITAMENTE em "
       "JSON, sem markdown e sem nenhum texto antes ou depois, exatamente neste "
       "formato: {\"pergunta\": \"...\", \"opcoes\": [\"...\", \"...\", \"...\", \"...\"], \"correta\": 0}. "
       "\"opcoes\" deve ter exatamente 4 alternativas plausíveis e \"correta\" é "
       "o índice (0 a 3, contando do zero) da alternativa certa em \"opcoes\"."
       (when (seq historico)
         (str " Não repita, nem parecido, nenhuma destas perguntas já usadas: "
              (str/join " | " historico)))))

(defn- extrair-json [texto]
  (when texto
    (let [inicio (str/index-of texto "{")
          fim    (str/last-index-of texto "}")]
      (when (and inicio fim (< inicio fim))
        (subs texto inicio (inc fim))))))

(defn- parsear-pergunta-ia [texto]
  (try
    (when-let [json-str (extrair-json texto)]
      (let [obj      (js/JSON.parse json-str)
            pergunta (.-pergunta obj)
            opcoes   (js->clj (.-opcoes obj))
            correta  (.-correta obj)]
        (when (and (string? pergunta) (not (str/blank? pergunta))
                   (vector? opcoes) (= 4 (count opcoes)) (every? string? opcoes)
                   (integer? correta) (<= 0 correta 3))
          {:pergunta pergunta :opcoes opcoes :correta correta})))
    (catch :default _ nil)))

(defn- gerar-pergunta-ia [cid]
  (if (str/blank? config/gemini-api-key)
    (p/resolved nil)
    (-> (gemini/gerar-texto (prompt-pergunta (get @historico-perguntas cid)))
        (p/then parsear-pergunta-ia)
        (p/catch (fn [err]
                   (js/console.error "Erro ao gerar pergunta de quiz via IA:" err)
                   nil)))))

(defn- iniciar [message]
  (let [cid (chat-id message)]
    (p/let [pergunta-ia (gerar-pergunta-ia cid)
            pergunta    (or pergunta-ia (pergunta-estatica-nao-repetida cid))]
      (swap! quizzes assoc cid pergunta)
      (registrar-historico! cid (:pergunta pergunta))
      (str (cabecalho) (mensagem-pergunta pergunta)
           (when-not pergunta-ia
             (str "\n\n_(banco local - configure GEMINI_API_KEY pra perguntas sempre "
                  "novas via IA)_"))))))

(defn- sair [message]
  (let [cid (chat-id message)]
    (if (get @quizzes cid)
      (do (swap! quizzes dissoc cid)
          (p/resolved (str (cabecalho) "🚪 Pergunta cancelada.")))
      (p/resolved (str (cabecalho) "❓ Não tem nenhuma pergunta rolando nesse chat.")))))

(defn- responder [message resposta]
  (let [cid  (chat-id message)
        quiz (get @quizzes cid)]
    (cond
      (nil? quiz)
      (p/resolved (str (cabecalho) "❓ Não tem pergunta rolando. Digite " config/prefix
                        "quiz pra começar uma."))

      (nil? resposta)
      (p/resolved (str (cabecalho) "❓ Resposta inválida. Use " config/prefix "quiz a/b/c/d.\n\n"
                        (mensagem-pergunta quiz)))

      (= resposta (:correta quiz))
      (p/let [nome (nome-de message)]
        (swap! quizzes dissoc cid)
        (rank/pontuar! cid (jogador-id message) nome "quiz")
        (str (cabecalho) "✅ *Certíssimo, " nome "!* A resposta era *"
             (str/upper-case (nth letras (:correta quiz))) ") " (nth (:opcoes quiz) (:correta quiz)) "*\n\n"
             "Quer outra? Manda " config/prefix "quiz de novo."))

      :else
      (p/resolved (str (cabecalho) "❌ Não foi essa. Tenta de novo!\n\n" (mensagem-pergunta quiz))))))

(defn jogar
  "!quiz sem argumento inicia uma pergunta nova (ou mostra a pendente);
  !quiz <letra> responde a pergunta ativa; !quiz sair cancela a pendente."
  [message args]
  (let [args (str/trim (or args ""))]
    (cond
      (str/blank? args)
      (if-let [ativo (get @quizzes (chat-id message))]
        (p/resolved (str (cabecalho) "⏳ Já tem uma pergunta rolando:\n\n" (mensagem-pergunta ativo)))
        (iniciar message))

      (contains? #{"sair" "parar"} (str/lower-case args))
      (sair message)

      :else
      (responder message (normalizar-resposta args)))))
