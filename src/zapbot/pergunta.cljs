(ns zapbot.pergunta
  "Comando !pergunta - responde perguntas livres usando a API Gemini."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            ["sharp" :as sharp]
            [zapbot.config :as config]
            [zapbot.gemini :as gemini]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

;; Antônio Abujamra (Provocações, TV Cultura) encerrava todo programa com essa
;; pergunta. Foto fornecida pelo usuário (frame de TV, sem licença livre
;; confirmada - uso pessoal/privado).
(def ^:private foto-abujamra-path
  (str js/__dirname "/../assets/abujamra.png"))

;; Uma única foto: a Promise compartilha a conversão em andamento e retém só
;; o JPEG/base64 pronto. Uma falha permite tentar novamente no próximo comando.
(defonce ^:private foto-abujamra-cache (atom nil))

(defn- preparar-foto-abujamra []
  (-> (sharp foto-abujamra-path)
      (.resize #js {:width 640 :withoutEnlargement true})
      (.jpeg #js {:quality 70})
      (.toBuffer)
      (p/then #(.toString % "base64"))))

(defn- foto-abujamra []
  (let [cache foto-abujamra-cache]
    (or @cache
        (let [foto (-> (preparar-foto-abujamra)
                       (p/catch (fn [erro]
                                  (reset! cache nil)
                                  (throw erro))))]
          (reset! cache foto)
          foto))))

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(defn- pergunta-sobre-a-vida? [pergunta]
  (-> pergunta str/trim remover-acentos str/lower-case
      (str/replace #"[?!.]+$" "")
      (= "o que e a vida")))

(defn- resposta-abujamra []
  (if (str/blank? config/gemini-api-key)
    (p/resolved nil)
    (-> (gemini/gerar-texto
         (str "Escreva uma reflexão filosófica curta (2 a 4 frases), em português, "
              "no estilo provocador e poético do apresentador Antônio Abujamra, "
              "sobre o sentido da vida. Não termine com uma pergunta."))
        (p/catch (fn [_] nil)))))

(defn- responder-sobre-a-vida [message]
  (let [legenda (fn [reflexao]
                  (str "🎭 *O tio " config/bot-name " provoca...*\n\n"
                       (or reflexao
                           "A vida é isso que passa enquanto a gente fica se perguntando o que é a vida.")
                       "\n\nMas afinal... o que é a vida?"))
        ;; Inicia as duas operações no contexto do chamador; aguarda em paralelo.
        reflexao-promessa (resposta-abujamra)
        foto-promessa (foto-abujamra)]
    (-> (p/let [[reflexao dados] (p/all [reflexao-promessa foto-promessa])
                media    (MessageMedia. "image/jpeg" dados "abujamra.jpg")
                _        (.reply message media nil #js {:caption (legenda reflexao)})]
          nil)
        (p/catch (fn [err]
                   (js/console.error "Erro ao responder sobre a vida:" err)
                   (legenda nil))))))

(defn perguntar [message pergunta]
  (cond
    (pergunta-sobre-a-vida? pergunta)
    (responder-sobre-a-vida message)

    (str/blank? config/gemini-api-key)
    (p/resolved
     (str "⚠️ Comando indisponível: configure GEMINI_API_KEY no .env "
          "(chave gratuita em https://aistudio.google.com/apikey)."))

    (str/blank? pergunta)
    (p/resolved
     (str "❓ Faça uma pergunta depois do comando, ex.: "
          config/prefix "pergunta qual a capital do Japão?"))

    :else
    (-> (gemini/gerar-texto
         (str "Responda de forma clara e objetiva, em português, à pergunta abaixo:\n\n" pergunta))
        (p/then (fn [resposta]
                  (if resposta
                    (str "🤔 *Pergunta ao tio " config/bot-name "*\n\n" resposta)
                    "❌ Não consegui pensar em uma resposta agora.")))
        (p/catch (fn [err]
                   (js/console.error "Erro ao responder pergunta:" err)
                   "❌ Não consegui pensar em uma resposta agora. Tente novamente mais tarde.")))))
