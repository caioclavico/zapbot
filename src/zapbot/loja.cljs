(ns zapbot.loja
  "Comando !loja - moedas ganhas vencendo batalhas de !pokemon, gastas em
  curas pros status (queimadura/veneno/paralisia) ou em poção de vida
  (recupera HP). Estado (moedas + inventário) por chat+jogador, mesma
  convenção de zapbot.rank; persistido via zapbot.armazenamento (chaves
  sempre string, nunca keyword - ver convenção documentada lá)."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

;; "queimadura"/"veneno" eram as chaves de compra antigas (renomeadas pra
;; "atadura"/"antidoto" - ver comentário no catálogo `itens` abaixo); sem
;; isso, inventários já persistidos com as chaves antigas ficam sem emoji/
;; nome no !loja (a chave não existe mais no catálogo novo).
(def ^:private renomeacoes-antigas {"queimadura" "atadura" "veneno" "antidoto"})

(defn- migrar-inventario [inventario]
  (reduce-kv (fn [acc chave qtd]
               (update acc (get renomeacoes-antigas chave chave) (fnil + 0) qtd))
             {}
             (or inventario {})))

(defn- migrar-chaves-antigas [dados]
  (reduce-kv (fn [acc cid contas-chat]
               (assoc acc cid
                      (reduce-kv (fn [acc2 pid conta]
                                   (assoc acc2 pid (update conta "inventario" migrar-inventario)))
                                 {}
                                 contas-chat)))
             {}
             (or dados {})))

(defonce ^:private contas (atom (migrar-chaves-antigas (armazenamento/obter "loja"))))
(armazenamento/registrar! "loja" contas migrar-chaves-antigas)

(defn- persistir! []
  (armazenamento/salvar! "loja" @contas))

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(def ^:private moedas-por-vitoria 10)

;; catálogo estático (nunca persistido, então pode usar keyword à vontade) -
;; chaves nomeadas pelo ITEM que você compra (não pelo status que ele cura),
;; então "loja comprar atadura"/"antidoto" fazem sentido de verdade
(def ^:private itens
  {"atadura"    {:nome "Atadura"           :emoji "🔥" :status :queimado   :preco 15}
   "antidoto"   {:nome "Antídoto"          :emoji "☠️" :status :envenenado :preco 15}
   "paralisia"  {:nome "Cura de Paralisia" :emoji "⚡" :status :paralisado :preco 15}
   "despertar"  {:nome "Despertar"          :emoji "💤" :status :adormecido :preco 15}
   "degelo"     {:nome "Antigelo"           :emoji "🧊" :status :congelado  :preco 15}
   "persim"     {:nome "Baya Caquic"        :emoji "💫" :status :confuso    :preco 15}
   "pocao"      {:nome "Poção de Vida"     :emoji "🧪" :cura-hp 0.4        :preco 20}
   "restos"     {:nome "Restos"            :emoji "🍱" :equipavel true :efeito :regeneracao :preco 45}
   "banda"      {:nome "Banda Musculosa"   :emoji "💪" :equipavel true :efeito :fisico :preco 40}
   "oculos"     {:nome "Óculos Sábios"     :emoji "👓" :equipavel true :efeito :especial :preco 40}
   "faixa-foco" {:nome "Faixa de Foco"     :emoji "🥋" :equipavel true :efeito :sobreviver :preco 55}})

(declare conta)

(defn dados-item [chave] (get itens chave))
(defn item-equipavel? [chave] (true? (get-in itens [chave :equipavel])))

(defn consumir-item!
  "Remove uma unidade de um item equipável do inventário."
  [cid pid chave]
  (when (and (item-equipavel? chave) (pos? (get-in (conta cid pid) ["inventario" chave] 0)))
    (swap! contas update-in [cid pid "inventario" chave] dec)
    (persistir!)
    true))

(defn devolver-item!
  "Devolve um item equipável ao inventário (ao trocar/desequipar)."
  [cid pid chave]
  (when (item-equipavel? chave)
    (swap! contas update-in [cid pid]
           (fn [c] (update-in (or c {"moedas" 0 "inventario" {}})
                              ["inventario" chave] (fnil inc 0))))
    (persistir!)
    true))

(defn- conta [cid pid]
  (get-in @contas [cid pid] {"moedas" 0 "inventario" {}}))

(defn moedas [cid pid]
  (get (conta cid pid) "moedas"))

(defn creditar!
  "Credita as moedas de vitória pro pid nesse chat (chamado ao fechar uma
  batalha de !pokemon). Retorna a quantidade creditada."
  [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c] (-> (or c {"moedas" 0 "inventario" {}})
                     (update "moedas" + moedas-por-vitoria))))
  (persistir!)
  moedas-por-vitoria)

(defn- item-por-status [status]
  (some (fn [[chave info]] (when (= (:status info) status) chave)) itens))

(defn usar-cura!
  "Se pid tiver, nesse chat, uma cura em estoque pro status dado, consome 1
  unidade e retorna true; senão não mexe em nada e retorna false."
  [cid pid status]
  (if-let [chave (item-por-status status)]
    (if (pos? (get-in (conta cid pid) ["inventario" chave] 0))
      (do (swap! contas update-in [cid pid "inventario" chave] dec)
          (persistir!)
          true)
      false)
    false))

(defn usar-pocao!
  "Se pid tiver, nesse chat, uma poção de vida em estoque, consome 1 unidade
  e retorna a fração de HP máximo que ela cura (ex.: 0.4 = 40%); senão não
  mexe em nada e retorna nil."
  [cid pid]
  (when (pos? (get-in (conta cid pid) ["inventario" "pocao"] 0))
    (swap! contas update-in [cid pid "inventario" "pocao"] dec)
    (persistir!)
    (:cura-hp (get itens "pocao"))))

(defn- formatar-item [chave {:keys [nome emoji preco]}]
  (str emoji " *" nome "* (`" chave "`) - " preco " moedas"))

(defn- formatar-inventario [inventario]
  (let [posse (filter (fn [[_ qtd]] (pos? qtd)) inventario)]
    (if (seq posse)
      (str/join ", " (map (fn [[chave qtd]] (str (get-in itens [chave :emoji]) " " qtd "x " (get-in itens [chave :nome]))) posse))
      "nenhuma ainda")))

(defn ver-loja
  "!loja - mostra o catálogo, o saldo de moedas e o inventário de curas de
  quem chamou, nesse chat."
  [message]
  (let [cid (if (.-fromMe message) (.-to message) (.-from message))
        pid (or (.-author message) (.-from message))
        c   (conta cid pid)]
    (str "🏪 *Loja do tio " config/bot-name "*\n\n"
         "💰 Suas moedas: " (get c "moedas") "\n"
         "🎒 Seu inventário: " (formatar-inventario (get c "inventario")) "\n\n"
         "*Itens à venda:*\n"
         (str/join "\n" (map (fn [[chave info]] (formatar-item chave info)) itens))
         "\n\nUse " config/prefix "loja comprar <item> (ex.: " config/prefix "loja comprar atadura).\n"
         "Ganhe moedas vencendo batalhas de " config/prefix "pokemon, cure status com " config/prefix
         "pokemon curar, recupere HP com " config/prefix "pokemon pocao e equipe itens com "
         config/prefix "pokemon equipar <nº> <item>!")))

(defn comprar
  "!loja comprar <item> - compra 1 unidade do item pro inventário de quem
  chamou, nesse chat, se tiver moedas suficientes."
  [message nome-item]
  (let [cid   (if (.-fromMe message) (.-to message) (.-from message))
        pid   (or (.-author message) (.-from message))
        chave (-> (or nome-item "") str/trim str/lower-case remover-acentos)]
    (if-let [item (get itens chave)]
      (let [saldo (moedas cid pid)]
        (if (>= saldo (:preco item))
          (do (swap! contas update-in [cid pid]
                     (fn [c] (-> (or c {"moedas" 0 "inventario" {}})
                                 (update "moedas" - (:preco item))
                                 (update-in ["inventario" chave] (fnil inc 0)))))
              (persistir!)
              (str "✅ Comprou " (:emoji item) " *" (:nome item) "*! Saldo: "
                   (- saldo (:preco item)) " moedas."))
          (str "❌ Moedas insuficientes! Você tem " saldo ", " (:nome item) " custa " (:preco item) ".")))
      (str "❓ Item \"" nome-item "\" não encontrado. Use " config/prefix "loja pra ver o catálogo."))))
