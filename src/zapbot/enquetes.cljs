(ns zapbot.enquetes
  "Uma enquete ativa por chat; votos e resultado sobrevivem a reinícios."
  (:require [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]))

(defn- normalizar-enquetes [dados]
  (into {}
        (map (fn [[chat enquete]]
               [chat {:pergunta (or (:pergunta enquete) (get enquete "pergunta"))
                      :opcoes (vec (or (:opcoes enquete) (get enquete "opcoes") []))
                      :votos (or (:votos enquete) (get enquete "votos") {})
                      :prefix (or (:prefix enquete) (get enquete "prefix") config/prefix)}]))
        (or dados {})))

(defonce ^:private enquetes (atom (normalizar-enquetes (armazenamento/obter "enquetes"))))
(armazenamento/registrar! "enquetes" enquetes normalizar-enquetes)

(defn- chat-id [message] (if (.-fromMe message) (.-to message) (.-from message)))
(defn- eleitor-id [message] (or (.-author message) (.-from message)))
(defn- salvar! [] (armazenamento/salvar! "enquetes" @enquetes))
(defn- formatar [enquete]
  (let [votos (:votos enquete)
        contagens (frequencies (vals votos))]
    (str "📊 *" (:pergunta enquete) "*\n\n"
         (str/join "\n" (map-indexed (fn [i opcao] (str (inc i) ". " opcao " — *" (get contagens (inc i) 0) "* voto(s)")) (:opcoes enquete)))
         "\n\nVote com " (:prefix enquete) "votar <número>.")))

(defn criar! [message args]
  (let [partes (map str/trim (str/split args #"\|"))
        pergunta (first partes)
        opcoes (vec (remove str/blank? (rest partes)))
        cid (chat-id message)]
    (cond
      (get @enquetes cid) "📊 Já existe uma enquete ativa. Use !enquete fechar antes de abrir outra."
      (or (str/blank? pergunta) (< (count opcoes) 2)) "❓ Use: !enquete Pergunta | Opção 1 | Opção 2 [| Opção 3]"
      (> (count opcoes) 10) "❓ Uma enquete aceita no máximo 10 opções."
      :else (let [enquete {:pergunta pergunta :opcoes opcoes :votos {} :prefix config/prefix}]
              (swap! enquetes assoc cid enquete) (salvar!)
              (str "📊 *Enquete criada!*\n\n" (formatar enquete))))))

(defn ver [message]
  (if-let [enquete (get @enquetes (chat-id message))]
    (formatar enquete)
    "📊 Não há enquete ativa neste chat. Crie com !enquete Pergunta | Opção 1 | Opção 2."))

(defn votar! [message texto]
  (let [cid (chat-id message) enquete (get @enquetes cid) n (js/parseInt (str/trim texto) 10)]
    (cond
      (nil? enquete) "📊 Não há enquete ativa neste chat."
      (or (js/isNaN n) (< n 1) (> n (count (:opcoes enquete)))) "❓ Escolha o número de uma opção válida."
      :else (do (swap! enquetes assoc-in [cid :votos (eleitor-id message)] n) (salvar!)
                (str "✅ Voto registrado em *" (nth (:opcoes enquete) (dec n)) "*.\n\n" (formatar (get @enquetes cid)))))))

(defn fechar! [message]
  (let [cid (chat-id message)]
    (if-let [enquete (get @enquetes cid)]
      (do (swap! enquetes dissoc cid) (salvar!) (str "📊 *Enquete encerrada*\n\n" (formatar enquete)))
      "📊 Não há enquete ativa neste chat.")))
