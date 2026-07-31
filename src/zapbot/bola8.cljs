(ns zapbot.bola8
  "Comando !bola8 - bola 8 mágica: gera uma imagem com a resposta dentro do
  visor da bola (SVG renderizado localmente via sharp, sem depender de foto
  externa nem de conexão para baixar imagem)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            ["sharp" :as sharp]
            [zapbot.config :as config]))

(def ^:private MessageMedia (.-MessageMedia wwjs))

(def ^:private respostas
  ["É decididamente assim" "Com certeza" "Sem dúvida" "Sim, definitivamente"
   "Pode confiar" "Ao que tudo indica, sim" "Provavelmente" "Boa perspectiva"
   "Sim" "Os sinais apontam que sim"
   "Resposta nebulosa, tente de novo" "Pergunte de novo mais tarde"
   "Melhor eu não te contar agora" "Não posso prever agora"
   "Concentre-se e pergunte de novo"
   "Não conte com isso" "Minha resposta é não" "Minhas fontes dizem que não"
   "Perspectiva não muito boa" "Muito duvidoso"])

;; Tamanhos de fonte candidatos (do maior para o menor) até o texto caber
;; dentro do visor da bola, seja qual for o tamanho da resposta sorteada.
(def ^:private tamanhos-fonte [32 27 23 19 16])
(def ^:private largura-util 185)
(def ^:private altura-util 185)
(def ^:private fator-largura-char 0.58)

(defn- escapar-xml [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- quebrar-linhas [texto max-chars]
  (reduce (fn [linhas palavra]
            (if-let [ultima (peek linhas)]
              (if (<= (+ (count ultima) 1 (count palavra)) max-chars)
                (conj (pop linhas) (str ultima " " palavra))
                (conj linhas palavra))
              (conj linhas palavra)))
          []
          (str/split (str/trim texto) #"\s+")))

(defn- layout-para-tamanho [resposta tamanho]
  (let [max-chars (int (/ largura-util (* tamanho fator-largura-char)))
        linhas    (quebrar-linhas resposta max-chars)
        maior     (apply max (map count linhas))
        altura    (* (count linhas) tamanho 1.2)]
    {:tamanho tamanho :linhas linhas :altura altura
     :cabe? (and (<= maior max-chars) (<= altura altura-util) (<= (count linhas) 4))}))

(defn- escolher-layout [resposta]
  (let [tentativas (map (partial layout-para-tamanho resposta) tamanhos-fonte)]
    (or (first (filter :cabe? tentativas)) (last tentativas))))

(defn- montar-tspans [linhas tamanho altura-total]
  (let [altura-linha (* tamanho 1.2)
        y-inicial    (- 300 (/ (- altura-total altura-linha) 2))]
    (->> linhas
         (map-indexed (fn [i linha]
                        (str "<tspan x=\"250\" y=\"" (+ y-inicial (* i altura-linha)) "\">"
                             (escapar-xml linha) "</tspan>")))
         (apply str))))

(defn- svg-bola [resposta]
  (let [{:keys [tamanho linhas altura]} (escolher-layout resposta)]
    (str
     "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"500\" height=\"500\">"
     "<defs><radialGradient id=\"g\" cx=\"35%\" cy=\"30%\" r=\"75%\">"
     "<stop offset=\"0%\" stop-color=\"#3a3a3a\"/><stop offset=\"55%\" stop-color=\"#141414\"/>"
     "<stop offset=\"100%\" stop-color=\"#000\"/></radialGradient></defs>"
     "<circle cx=\"250\" cy=\"250\" r=\"245\" fill=\"url(#g)\"/>"
     "<ellipse cx=\"170\" cy=\"140\" rx=\"65\" ry=\"38\" fill=\"#fff\" opacity=\"0.15\"/>"
     "<circle cx=\"250\" cy=\"305\" r=\"155\" fill=\"#fff\"/>"
     "<circle cx=\"250\" cy=\"305\" r=\"138\" fill=\"#182a6e\"/>"
     "<circle cx=\"250\" cy=\"300\" r=\"108\" fill=\"#f4f4f7\"/>"
     "<text font-family=\"Verdana, Arial, sans-serif\" font-weight=\"bold\" font-size=\""
     tamanho "\" fill=\"#101a4d\" text-anchor=\"middle\">" (montar-tspans linhas tamanho altura) "</text>"
     "</svg>")))

(defn- legenda [pergunta]
  (str "🎱 *Bola 8 do tio " config/bot-name "*"
       (when-not (str/blank? pergunta) (str "\n\n❓ " pergunta))))

(defn jogar [message pergunta]
  (let [resposta (rand-nth respostas)]
    (-> (p/let [buffer (-> (sharp (js/Buffer.from (svg-bola resposta))) (.png) (.toBuffer))
                media  (MessageMedia. "image/png" (.toString buffer "base64") "bola8.png")
                _      (.reply message media nil #js {:caption (legenda pergunta)})]
          nil)
        (p/catch (fn [err]
                   (js/console.error "Erro ao gerar imagem da bola 8:" err)
                   ;; se a geração/envio da imagem falhar, ainda respondemos com texto
                   (str (legenda pergunta) "\n\n👉 " resposta))))))

