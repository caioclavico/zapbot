(ns zapbot.loja
  "Comando !loja - moedas ganhas vencendo batalhas de !pokemon, gastas em
  curas pros status (queimadura/veneno/paralisia) ou em poção de vida
  (recupera HP). Estado (moedas + inventário) por chat+jogador, mesma
  convenção de zapbot.rank; persistido via zapbot.armazenamento (chaves
  sempre string, nunca keyword - ver convenção documentada lá)."
  (:require [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.missoes :as missoes]
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
                                   (assoc acc2 pid
                                          (let [c (update conta "inventario" migrar-inventario)]
                                            (if (contains? c "capacidade-mochila") c
                                                (assoc c "capacidade-mochila"
                                                       (max 50 (reduce + 0 (vals (get c "inventario")))))))))
                                 {}
                                 contas-chat)))
             {}
             (or dados {})))

(defonce ^:private contas (atom (migrar-chaves-antigas (armazenamento/obter "loja"))))
(armazenamento/registrar! "loja" contas migrar-chaves-antigas)

(defn- persistir! []
  (armazenamento/salvar! "loja" @contas))

(def preco-reaprender 50)

(defn pagar-reaprendizado! [cid pid]
  (when (>= (get-in @contas [cid pid "moedas"] 0) preco-reaprender)
    (swap! contas update-in [cid pid "moedas"] - preco-reaprender)
    (persistir!) true))

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(def ^:private moedas-por-vitoria 10)

;; catálogo estático (nunca persistido, então pode usar keyword à vontade) -
;; chaves nomeadas pelo ITEM que você compra (não pelo status que ele cura),
;; então "loja comprar atadura"/"antidoto" fazem sentido de verdade
(def ^:private itens
  {"reviver" {:nome "Reviver" :emoji "💎" :exclusivo-missoes true
               :descricao "Exclusivo das missões: revive um Pokémon desmaiado com 100% do HP e remove seu status. Use !pokemon reviver [número], fora de batalhas e caçadas."}
   "pokebola" {:nome "Pokébola" :emoji "🔴" :preco 5 :multiplicador-captura 1
                 :descricao "Bola normal para capturar após derrotar o selvagem. Use !pokemon capturar pokebola."}
   "grande-bola" {:nome "Grande Bola" :emoji "🔵" :preco 12 :multiplicador-captura 1.5
                    :descricao "Multiplica a chance de captura por 1,5, até 95%. Use !pokemon capturar grande-bola."}
   "ultra-bola" {:nome "Ultra Bola" :emoji "🟡" :preco 25 :multiplicador-captura 2
                  :descricao "Multiplica a chance de captura por 2, até 95%. Use !pokemon capturar ultra-bola."}
   "mochila" {:nome "Expansão de Mochila" :emoji "🎒" :preco 200 :expansao 25
                :descricao "Aumenta permanentemente a capacidade em 25 unidades. Pode comprar várias vezes; não ocupa espaço."}
   "mt" {:nome "MT de Ataque" :emoji "💿" :preco 200
          :descricao "Sorteia um novo ataque compatível com o Pokémon ativo. Use !pokemon mt para preencher uma vaga ou !pokemon mt <1-4> para substituir um ataque. Consumido apenas ao aprender."}
   "atadura"    {:nome "Atadura" :emoji "🔥" :status :queimado :preco 15
                   :descricao "Remove a queimadura do Pokémon. É consumida ao usar !pokemon curar."}
   "antidoto"   {:nome "Antídoto" :emoji "☠️" :status :envenenado :preco 15
                   :descricao "Remove o envenenamento do Pokémon. É consumido ao usar !pokemon curar."}
   "paralisia"  {:nome "Cura de Paralisia" :emoji "⚡" :status :paralisado :preco 15
                   :descricao "Remove a paralisia do Pokémon. É consumida ao usar !pokemon curar."}
   "despertar"  {:nome "Despertar" :emoji "💤" :status :adormecido :preco 15
                   :descricao "Acorda um Pokémon adormecido. É consumido ao usar !pokemon curar."}
   "degelo"     {:nome "Antigelo" :emoji "🧊" :status :congelado :preco 15
                   :descricao "Descongela o Pokémon. É consumido ao usar !pokemon curar."}
   "persim"     {:nome "Baya Caquic" :emoji "💫" :status :confuso :preco 15
                   :descricao "Remove a confusão do Pokémon. É consumida ao usar !pokemon curar."}
   "pocao"      {:nome "Poção de Vida" :emoji "🧪" :cura-hp 0.4 :preco 20
                   :descricao "Recupera 40% do HP máximo. É consumida ao usar !pokemon pocao."}
   "restos"     {:nome "Restos" :emoji "🍱" :equipavel true :efeito :regeneracao :preco 45
                   :descricao "Recupera 1/16 do HP máximo ao final de cada turno em que o Pokémon agir."}
   "banda"      {:nome "Banda Musculosa" :emoji "💪" :equipavel true :efeito :fisico :preco 40
                   :descricao "Aumenta em 15% o dano causado por golpes físicos."}
   "oculos"     {:nome "Óculos Sábios" :emoji "👓" :equipavel true :efeito :especial :preco 40
                   :descricao "Aumenta em 15% o dano causado por golpes especiais."}
   "faixa-foco" {:nome "Faixa de Foco" :emoji "🥋" :equipavel true :efeito :sobreviver :preco 55
                   :descricao "Se estiver com HP cheio, sobrevive uma vez por batalha a um golpe fatal, ficando com 1 HP."}})

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

(def bolas ["pokebola" "grande-bola" "ultra-bola"])
(def ^:private ordem-recompensas (conj bolas "reviver"))

(defn normalizar-item [nome]
  (let [chave (-> (or nome "") str/trim str/lower-case remover-acentos
                  (str/replace #"\s+" "-"))]
    (get {"normal" "pokebola" "grande" "grande-bola" "ultra" "ultra-bola"} chave chave)))

(defn quantidade-item [cid pid chave]
  (get-in (conta cid pid) ["inventario" chave] 0))

(defn capacidade [cid pid]
  (get (conta cid pid) "capacidade-mochila" 50))

(defn ocupacao [cid pid]
  (reduce + 0 (vals (get (conta cid pid) "inventario"))))

(defn- cabe? [cid pid quantidade]
  (<= (+ (ocupacao cid pid) quantidade) (capacidade cid pid)))

(defn consumir-bola! [cid pid chave]
  (when (and (some #{chave} bolas) (pos? (quantidade-item cid pid chave)))
    (swap! contas update-in [cid pid "inventario" chave] dec)
    (persistir!)
    true))

(defn consumir-reviver! [cid pid]
  (when (pos? (quantidade-item cid pid "reviver"))
    (swap! contas update-in [cid pid "inventario" "reviver"] dec)
    (persistir!)
    true))

(defn- recompensas-pendentes [c]
  ;; Compatibilidade com recompensas anteriores, que só guardavam Pokébolas.
  (update (get c "recompensas-pendentes" {}) "pokebola"
          (fnil + 0) (get c "bolas-pendentes" 0)))

(defn- guardar-recompensas [c recompensas]
  (let [c (or c {"moedas" 0 "inventario" {}})
        livres (max 0 (- (get c "capacidade-mochila" 50)
                         (reduce + 0 (vals (get c "inventario")))))
        c (-> c (assoc "recompensas-pendentes" (recompensas-pendentes c))
              (dissoc "bolas-pendentes"))]
    (first
     (reduce (fn [[c livres] bola]
               (let [qtd (get recompensas bola 0)
                     recebidas (min qtd livres)]
                 [(-> c
                      (update-in ["inventario" bola] (fnil + 0) recebidas)
                      (update-in ["recompensas-pendentes" bola] (fnil + 0) (- qtd recebidas)))
                  (- livres recebidas)]))
             [c livres] ordem-recompensas))))

(defn- texto-recompensas [recompensas]
  (str/join ", " (for [bola ordem-recompensas :let [qtd (get recompensas bola 0)] :when (pos? qtd)]
                       (str qtd "× " (:nome (dados-item bola))))))

(defn premiar-bolas! [cid pid quantidade]
  (swap! contas update-in [cid pid] guardar-recompensas {"pokebola" quantidade})
  (persistir!)
  (str "🎁 +" quantidade " Pokébola(s) por vitória."
       (when (some pos? (vals (recompensas-pendentes (conta cid pid))))
         (str " Há recompensas pendentes: libere espaço e use " config/prefix "mochila resgatar."))))

(defn resgatar-bolas! [cid pid kit?]
  (let [c (conta cid pid)
        recompensas (if kit? {"pokebola" 10} (recompensas-pendentes c))]
    (cond
      (and kit? (get c "kit-inicial-resgatado")) "🎁 Você já resgatou o kit inicial."
      (or (not (some pos? (vals recompensas))) (not (cabe? cid pid (if kit? 10 1))))
      "🎒 Sem recompensas disponíveis para resgatar ou espaço insuficiente. Libere vagas ou compre uma expansão."
      :else
      (let [novo (guardar-recompensas (if kit? (assoc c "kit-inicial-resgatado" true)
                                   (dissoc c "bolas-pendentes" "recompensas-pendentes")) recompensas)
            recebidas (into {} (for [bola ordem-recompensas]
                                 [bola (- (get-in novo ["inventario" bola] 0)
                                          (get-in c ["inventario" bola] 0))]))]
        (swap! contas assoc-in [cid pid] novo)
        (persistir!)
        (str "🎁 Você recebeu " (texto-recompensas recebidas) "!")))))

(defn xp-missoes [cid pid]
  (get (conta cid pid) "xp-missoes" 0))

(defn registrar-missao! [cid pid evento nivel]
  (let [dia (missoes/dia-atual)
        c (conta cid pid)
        antes (count (missoes/disponiveis (missoes/estado-do-dia c dia nivel)))
        novo (missoes/registrar-evento c dia evento nivel)]
    (swap! contas assoc-in [cid pid] novo)
    (persistir!)
    (when (> (count (missoes/disponiveis (missoes/estado-do-dia novo dia nivel))) antes)
      (str "\n📋 Missão diária concluída! Resgate com " config/prefix "missoes resgatar."))))

(defn- resgatar-missoes! [cid pid nivel]
  (let [dia (missoes/dia-atual)
        c (conta cid pid)
        estado (missoes/estado-do-dia c dia nivel)
        prontas (missoes/disponiveis estado)]
    (if (empty? prontas)
      "📋 Nenhuma missão concluída disponível para resgatar hoje."
      (let [xp (reduce + (map :xp prontas))
            recompensas (reduce (fn [r missao]
                                  (let [r (update r "pokebola" (fnil + 0) (:pokebolas missao))]
                                    (cond-> (if-let [bonus (missoes/sortear-bonus)]
                                              (update r bonus (fnil inc 0)) r)
                                      (missoes/sortear-reviver?) (update "reviver" (fnil inc 0)))))
                                {} prontas)
            novo (-> c
                     (assoc "missoes-diarias" (update estado "resgatadas" into (map :id prontas)))
                     (update "xp-missoes" (fnil + 0) xp)
                     (guardar-recompensas recompensas))]
        ;; Sem espera assíncrona: XP, sorteio, mochila e marca de resgate
        ;; são persistidos juntos, inclusive quando a mochila está cheia.
        (swap! contas assoc-in [cid pid] novo)
        (persistir!)
        (str "✅ " (count prontas) " missão(ões) resgatada(s)!\n✨ +" xp " XP de treinador"
             "\n🎁 " (texto-recompensas recompensas)
             (when (some pos? (vals (recompensas-pendentes novo)))
               (str "\n🎒 Itens sem espaço ficaram pendentes: " config/prefix "mochila resgatar.")))))))

(defn ver-missoes [message acao nivel]
  (let [cid (if (.-fromMe message) (.-to message) (.-from message))
        pid (or (.-author message) (.-from message))
        dia (missoes/dia-atual)
        estado (missoes/estado-do-dia (conta cid pid) dia nivel)]
    ;; O primeiro acesso ou evento do dia fixa a faixa, sem mudar metas
    ;; depois de resgatar XP e subir de nível no mesmo dia.
    (when (not= estado (get (conta cid pid) "missoes-diarias"))
      (swap! contas update-in [cid pid] #(assoc (or % {"moedas" 0 "inventario" {}}) "missoes-diarias" estado))
      (persistir!))
    (if (= "resgatar" (normalizar-item acao))
      (resgatar-missoes! cid pid nivel)
      (str "📋 *Missões diárias — " dia "*\nNível de referência hoje: " (get estado "nivel" 1) "\n"
           (str/join "\n\n"
                     (for [{:keys [id nome objetivo meta xp pokebolas]} (missoes/catalogo-do-dia estado)
                           :let [progresso (get-in estado ["progresso" id] 0)
                                 resgatada? (some #{id} (get estado "resgatadas"))]]
                       (str (cond resgatada? "🎁" (>= progresso meta) "✅" :else "⬜")
                            " *" nome "*: " objetivo " — " progresso "/" meta
                            "\n+" xp " XP de treinador e " pokebolas " Pokébolas"
                            (when resgatada? " (resgatada)"))))
           "\n\nCada missão: 25% de chance de +1 Grande Bola, 10% de +1 Ultra Bola; 65% sem bônus."
           " Um único sorteio de bola bônus por missão, além das Pokébolas garantidas."
           "\n💎 Chance independente de 20% de +1 Reviver por missão, exclusivo das missões."
           "\nMetas, XP e Pokébolas aumentam a cada 5 níveis; a faixa fica fixa até a próxima renovação."
           "\nResgate as concluídas com " config/prefix "missoes resgatar."
           "\nRenovação à meia-noite (" config/missoes-timezone "). Resgate antes da virada!"
           "\nDesistências e fugas não contam como vitórias. O XP é do treinador, não do Pokémon."))))

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

(defn creditar-quantia!
  "Credita uma recompensa variável e retorna a quantidade adicionada."
  [cid pid quantidade]
  (swap! contas update-in [cid pid]
         (fn [c] (-> (or c {"moedas" 0 "inventario" {}})
                     (update "moedas" + quantidade))))
  (persistir!)
  quantidade)

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

(defn tem-mt? [cid pid]
  (pos? (get-in (conta cid pid) ["inventario" "mt"] 0)))

(defn consumir-mt! [cid pid]
  (when (tem-mt? cid pid)
    (swap! contas update-in [cid pid "inventario" "mt"] dec)
    (persistir!)
    true))

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
  (str emoji " *" nome "* (`" chave "`) - " (if preco (str preco " moedas") "exclusivo das missões")))

(defn- formatar-inventario [inventario]
  (let [posse (filter (fn [[_ qtd]] (pos? qtd)) inventario)]
    (if (seq posse)
      (str/join ", " (map (fn [[chave qtd]] (str (get-in itens [chave :emoji]) " " qtd "x " (get-in itens [chave :nome]))) posse))
      "nenhuma ainda")))

(defn mochila [message acao]
  (let [cid (if (.-fromMe message) (.-to message) (.-from message))
        pid (or (.-author message) (.-from message))]
    (case (normalizar-item acao)
      "kit" (resgatar-bolas! cid pid true)
      "resgatar" (resgatar-bolas! cid pid false)
      (str "🎒 *Mochila* — " (ocupacao cid pid) "/" (capacidade cid pid) " unidades\n"
           (formatar-inventario (get (conta cid pid) "inventario"))
           "\n\nCada unidade ocupa uma vaga. Itens equipados não ocupam espaço."
           "\nExpansão: +25 vagas por 200 moedas — " config/prefix "loja comprar mochila."
           "\nKit inicial: 10 Pokébolas — " config/prefix "mochila kit."
           "\nGanhe 2 Pokébolas por vitória PvP e 1 por selvagem derrotado."
           "\nRecompensas pendentes: " (let [texto (texto-recompensas (recompensas-pendentes (conta cid pid)))] (if (str/blank? texto) "nenhuma" texto))
           " — " config/prefix "mochila resgatar."
           "\nGanhe XP e bolas nas missões: " config/prefix "missoes."
           "\nCompre bolas com " config/prefix "loja comprar pokebola, grande-bola ou ultra-bola."))))

(defn detalhes
  "!loja detalhes <item> - explica o efeito e como usar um item."
  [nome-item]
  (let [chave (normalizar-item nome-item)]
    (if-let [{:keys [nome emoji preco descricao equipavel expansao]} (get itens chave)]
      (str "🔎 *Detalhes do item*\n\n"
           emoji " *" nome "* (`" chave "`)\n"
           (if preco (str "💰 Preço: " preco " moedas\n") "🎁 Exclusivo das missões; não está à venda.\n")
           "🏷️ Tipo: " (cond expansao "Melhoria permanente" equipavel "Equipável" :else "Consumível") "\n"
           "✨ Efeito: " descricao
           (when equipavel
             (str "\n\nEquipe com " config/prefix "pokemon equipar <número> " chave ".")))
      (str "❓ Item \"" nome-item "\" não encontrado. Use " config/prefix
           "loja para ver os nomes e " config/prefix "loja detalhes <item> para consultar um efeito."))))

(defn ver-loja
  "!loja - mostra o catálogo, o saldo de moedas e o inventário de curas de
  quem chamou, nesse chat."
  [message]
  (let [cid (if (.-fromMe message) (.-to message) (.-from message))
        pid (or (.-author message) (.-from message))
        c   (conta cid pid)]
    (str "🏪 *Loja do tio " config/bot-name "*\n\n"
         "💰 Suas moedas: " (get c "moedas") "\n"
         "🎒 Mochila: " (ocupacao cid pid) "/" (capacidade cid pid) " — " (formatar-inventario (get c "inventario")) "\n\n"
         "Veja seus itens e kits grátis com " config/prefix "mochila.\n\n*Catálogo de itens:*\n"
         (str/join "\n" (map (fn [[chave info]] (formatar-item chave info)) itens))
         "\n📚 Reaprender golpe — " preco-reaprender " moedas. Use " config/prefix "pokemon reaprender."
         "\n\nUse " config/prefix "loja comprar <item> (ex.: " config/prefix "loja comprar atadura).\n"
         "Para saber o efeito, use " config/prefix "loja detalhes <item>.\n"
         "Ganhe moedas vencendo batalhas de " config/prefix "pokemon, cure status com " config/prefix
         "pokemon curar, recupere HP com " config/prefix "pokemon pocao e equipe itens com "
         config/prefix "pokemon equipar <nº> <item>!")))

(defn comprar
  "!loja comprar <item> - compra 1 unidade do item pro inventário de quem
  chamou, nesse chat, se tiver moedas suficientes."
  [message nome-item]
  (let [cid   (if (.-fromMe message) (.-to message) (.-from message))
        pid   (or (.-author message) (.-from message))
        chave (normalizar-item nome-item)]
    (if-let [item (get itens chave)]
      (let [saldo (moedas cid pid)]
        (cond
          (:exclusivo-missoes item)
          (str "💎 Esse item só pode ser ganho nas missões. Veja " config/prefix "missoes.")
          (and (not (:expansao item)) (not (cabe? cid pid 1)))
          (str "🎒 Mochila cheia! Use itens ou compre mais 25 vagas com " config/prefix "loja comprar mochila (200 moedas).")
          (>= saldo (:preco item))
          (do (swap! contas update-in [cid pid]
                     (fn [c] (cond-> (update (or c {"moedas" 0 "inventario" {}})
                                           "moedas" - (:preco item))
                               (:expansao item) (update "capacidade-mochila" (fnil + 50) (:expansao item))
                               (not (:expansao item)) (update-in ["inventario" chave] (fnil inc 0)))))
              (persistir!)
              (str "✅ Comprou " (:emoji item) " *" (:nome item) "*! Saldo: "
                   (- saldo (:preco item)) " moedas."))
          :else
          (str "❌ Moedas insuficientes! Você tem " saldo ", " (:nome item) " custa " (:preco item) ".")))
      (str "❓ Item \"" nome-item "\" não encontrado. Use " config/prefix "loja pra ver o catálogo."))))
