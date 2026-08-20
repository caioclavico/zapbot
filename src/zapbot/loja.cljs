(ns zapbot.loja
  "Comando !loja - moedas ganhas vencendo batalhas de !pokemon, gastas em
  curas pros status (queimadura/veneno/paralisia) ou em poção de vida
  (recupera HP). Estado (moedas + inventário) por chat+jogador, mesma
  convenção de zapbot.rank; persistido via zapbot.armazenamento (chaves
  sempre string, nunca keyword - ver convenção documentada lá)."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

(defonce ^:private contas (atom (or (armazenamento/obter "loja") {})))
(armazenamento/registrar! "loja" contas)

(defn- persistir! []
  (armazenamento/salvar! "loja" @contas))

(def ^:private moedas-por-vitoria 10)

;; catálogo estático (nunca persistido, então pode usar keyword à vontade)
(def ^:private itens
  {"queimadura" {:nome "Cura de Queimadura" :emoji "🔥" :status :queimado   :preco 15}
   "veneno"     {:nome "Antídoto"           :emoji "☠️" :status :envenenado :preco 15}
   "paralisia"  {:nome "Cura de Paralisia"  :emoji "⚡" :status :paralisado :preco 15}
   "pocao"      {:nome "Poção de Vida"      :emoji "🧪" :cura-hp 0.4        :preco 20}})

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
         "\n\nUse " config/prefix "loja comprar <item> (ex.: " config/prefix "loja comprar queimadura).\n"
         "Ganhe moedas vencendo batalhas de " config/prefix "pokemon, cure status com " config/prefix
         "pokemon curar, e recupere HP com " config/prefix "pokemon pocao!")))

(defn comprar
  "!loja comprar <item> - compra 1 unidade do item pro inventário de quem
  chamou, nesse chat, se tiver moedas suficientes."
  [message nome-item]
  (let [cid   (if (.-fromMe message) (.-to message) (.-from message))
        pid   (or (.-author message) (.-from message))
        chave (str/lower-case (str/trim (or nome-item "")))]
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
