(ns zapbot.enquetes
  "Uma enquete ativa por chat; votos e resultado sobrevivem a reinícios.
   M7.2: em staging com PLATFORM_ADAPTER=datomic, persiste via bridge JVM
   (fail-closed, sem dual-write). Cache Node só atualiza após commit Datomic."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.platform-jvm :as platform]))

(defn- normalizar-enquetes [dados]
  (into {}
        (map (fn [[chat enquete]]
               [chat {:pergunta (or (:pergunta enquete) (get enquete "pergunta"))
                      :opcoes (vec (or (:opcoes enquete) (get enquete "opcoes") []))
                      :votos (or (:votos enquete) (get enquete "votos") {})
                      :prefix (or (:prefix enquete) (get enquete "prefix") config/prefix)}]))
        (or dados {})))

(defonce ^:private enquetes
  (atom (normalizar-enquetes
         (when-not (platform/enabled?)
           (armazenamento/obter "enquetes")))))

(when-not (platform/enabled?)
  (armazenamento/registrar! "enquetes" enquetes normalizar-enquetes))

(defn- chat-id [message] (if (.-fromMe message) (.-to message) (.-from message)))
(defn- eleitor-id [message] (or (.-author message) (.-from message)))

(defn- salvar-cassandra! []
  (armazenamento/salvar! "enquetes" @enquetes))

(defn- wire->enquete [wire]
  (when wire
    (let [m (js->clj wire :keywordize-keys false)]
      {:pergunta (or (get m "pergunta") (:pergunta m) "")
       :opcoes (vec (or (get m "opcoes") (:opcoes m) []))
       :votos (or (get m "votos") (:votos m) {})
       :prefix (or (get m "prefix") (:prefix m) config/prefix)})))

(defn- apply-polls-wire!
  "Atualiza cache somente com estado já commitado na bridge."
  [polls]
  (when polls
    (reset! enquetes (normalizar-enquetes (js->clj polls :keywordize-keys false)))))

(defn hidratar-datomic!
  []
  (when (platform/enabled?)
    (platform/hydrate-polls! enquetes normalizar-enquetes)))

(defn- formatar [enquete]
  (let [votos (:votos enquete)
        contagens (frequencies (vals votos))]
    (str "📊 *" (:pergunta enquete) "*\n\n"
         (str/join "\n" (map-indexed (fn [i opcao] (str (inc i) ". " opcao " — *" (get contagens (inc i) 0) "* voto(s)")) (:opcoes enquete)))
         "\n\nVote com " (:prefix enquete) "votar <número>.")))

(defn- criar-cassandra! [cid pergunta opcoes]
  (cond
    (get @enquetes cid) "📊 Já existe uma enquete ativa. Use !enquete fechar antes de abrir outra."
    (or (str/blank? pergunta) (< (count opcoes) 2)) "❓ Use: !enquete Pergunta | Opção 1 | Opção 2 [| Opção 3]"
    (> (count opcoes) 10) "❓ Uma enquete aceita no máximo 10 opções."
    :else (let [enquete {:pergunta pergunta :opcoes opcoes :votos {} :prefix config/prefix}]
            (swap! enquetes assoc cid enquete)
            (salvar-cassandra!)
            (str "📊 *Enquete criada!*\n\n" (formatar enquete)))))

(defn- criar-datomic! [cid pergunta opcoes]
  (cond
    (get @enquetes cid)
    (p/resolved "📊 Já existe uma enquete ativa. Use !enquete fechar antes de abrir outra.")

    (or (str/blank? pergunta) (< (count opcoes) 2))
    (p/resolved "❓ Use: !enquete Pergunta | Opção 1 | Opção 2 [| Opção 3]")

    (> (count opcoes) 10)
    (p/resolved "❓ Uma enquete aceita no máximo 10 opções.")

    :else
    (let [op (platform/op-id (str "poll-create:" cid))
          poll {:pergunta pergunta :opcoes opcoes :prefix config/prefix}]
      (-> (platform/create-poll! cid poll op)
          (p/then (fn [data]
                    ;; Só após commit Datomic
                    (apply-polls-wire! (.-polls data))
                    (let [enquete (or (get @enquetes cid)
                                      (wire->enquete (.-poll data)))]
                      (str "📊 *Enquete criada!*\n\n" (formatar enquete)))))
          (p/catch (fn [err]
                     (let [code (.-code err)]
                       (cond
                         (= code "operation_forbidden")
                         "📊 Já existe uma enquete ativa. Use !enquete fechar antes de abrir outra."
                         (= code "invalid_query")
                         "❓ Use: !enquete Pergunta | Opção 1 | Opção 2 [| Opção 3]"
                         :else
                         (do (js/console.error "enquetes(datomic): falha ao criar (fail-closed):" err)
                             (p/rejected err))))))))))

(defn criar! [message args]
  (let [partes (map str/trim (str/split args #"\|"))
        pergunta (first partes)
        opcoes (vec (remove str/blank? (rest partes)))
        cid (chat-id message)]
    (if (platform/enabled?)
      (criar-datomic! cid pergunta opcoes)
      (p/resolved (criar-cassandra! cid pergunta opcoes)))))

(defn ver [message]
  (p/resolved
   (if-let [enquete (get @enquetes (chat-id message))]
     (formatar enquete)
     "📊 Não há enquete ativa neste chat. Crie com !enquete Pergunta | Opção 1 | Opção 2.")))

(defn- votar-cassandra! [cid eid n]
  (let [enquete (get @enquetes cid)]
    (cond
      (nil? enquete) "📊 Não há enquete ativa neste chat."
      (or (js/isNaN n) (< n 1) (> n (count (:opcoes enquete)))) "❓ Escolha o número de uma opção válida."
      :else (do (swap! enquetes assoc-in [cid :votos eid] n)
                (salvar-cassandra!)
                (str "✅ Voto registrado em *" (nth (:opcoes enquete) (dec n)) "*.\n\n"
                     (formatar (get @enquetes cid)))))))

(defn- votar-datomic! [cid eid n]
  (let [enquete (get @enquetes cid)]
    (cond
      (nil? enquete)
      (p/resolved "📊 Não há enquete ativa neste chat.")

      (or (js/isNaN n) (< n 1) (> n (count (:opcoes enquete))))
      (p/resolved "❓ Escolha o número de uma opção válida.")

      :else
      (let [op (platform/op-id (str "poll-vote:" cid ":" eid))]
        (-> (platform/cast-vote! cid eid n op)
            (p/then (fn [data]
                      (apply-polls-wire! (.-polls data))
                      (let [atual (or (get @enquetes cid)
                                      (wire->enquete (.-poll data)))
                            escolha (nth (:opcoes atual) (dec n))]
                        (str "✅ Voto registrado em *" escolha "*.\n\n" (formatar atual)))))
            (p/catch (fn [err]
                       (let [code (.-code err)]
                         (cond
                           (= code "operation_not_found")
                           "📊 Não há enquete ativa neste chat."
                           (= code "invalid_query")
                           "❓ Escolha o número de uma opção válida."
                           :else
                           (do (js/console.error "enquetes(datomic): falha ao votar (fail-closed):" err)
                               (p/rejected err)))))))))))

(defn votar! [message texto]
  (let [cid (chat-id message)
        eid (eleitor-id message)
        n (js/parseInt (str/trim texto) 10)]
    (if (platform/enabled?)
      (votar-datomic! cid eid n)
      (p/resolved (votar-cassandra! cid eid n)))))

(defn- fechar-cassandra! [cid]
  (if-let [enquete (get @enquetes cid)]
    (do (swap! enquetes dissoc cid)
        (salvar-cassandra!)
        (str "📊 *Enquete encerrada*\n\n" (formatar enquete)))
    "📊 Não há enquete ativa neste chat."))

(defn- fechar-datomic! [cid]
  (if-not (get @enquetes cid)
    (p/resolved "📊 Não há enquete ativa neste chat.")
    (let [op (platform/op-id (str "poll-close:" cid))]
      (-> (platform/close-poll! cid op)
          (p/then (fn [data]
                    (let [closed (or (wire->enquete (.-poll data))
                                     (get @enquetes cid))]
                      (apply-polls-wire! (.-polls data))
                      (swap! enquetes dissoc cid)
                      (str "📊 *Enquete encerrada*\n\n" (formatar closed)))))
          (p/catch (fn [err]
                     (if (= "operation_not_found" (.-code err))
                       "📊 Não há enquete ativa neste chat."
                       (do (js/console.error "enquetes(datomic): falha ao fechar (fail-closed):" err)
                           (p/rejected err)))))))))

(defn fechar! [message]
  (let [cid (chat-id message)]
    (if (platform/enabled?)
      (fechar-datomic! cid)
      (p/resolved (fechar-cassandra! cid)))))
