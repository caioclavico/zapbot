(ns zapbot.pokemon.loja
  "Comando !loja - moedas ganhas vencendo batalhas de !pokemon, gastas em
  curas pros status (queimadura/veneno/paralisia) ou em poção de vida
  (recupera HP). Estado (moedas + inventário) por chat+jogador, mesma
  convenção de zapbot.rank; persistido via zapbot.armazenamento (chaves
  sempre string, nunca keyword - ver convenção documentada lá)."
  (:require [promesa.core :as p]
            [zapbot.pokemon.aventuras :as aventuras]
            [clojure.string :as str]
            [zapbot.config :as config]
            [zapbot.pokemon.missoes :as missoes]
            [zapbot.pokemon.egg-rewards :as egg-rewards]
            [zapbot.pokemon.armazenamento-regras :as armazenamento-regras]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.pokemon.boundary :as boundary]
            [zapbot.pokemon.imagens :as imagens]))

(def ^:private imagem-loja (str js/__dirname "/../assets/loja-pokemon.png"))

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

(defn- migrar-economia [conta]
  (let [c (update (or conta {}) "inventario" migrar-inventario)]
    (if (contains? c "capacidade-mochila") c
        (assoc c "capacidade-mochila"
               (max 50 (reduce + 0 (vals (get c "inventario"))))))))

(defn- migrar-registro-jogador [conta]
  (if (and (map? conta)
           (= "save-bound-v1" (get conta "schema-version-loja"))
           (map? (get conta "saves")))
    (update conta "saves"
            (fn [saves]
              (reduce-kv (fn [acc sid eco] (assoc acc sid (migrar-economia eco)))
                         {} saves)))
    (migrar-economia conta)))

(defn- migrar-chaves-antigas [dados]
  (reduce-kv (fn [acc cid contas-chat]
               (assoc acc cid
                      (reduce-kv (fn [acc2 pid conta]
                                   (assoc acc2 pid (migrar-registro-jogador conta)))
                                 {}
                                 contas-chat)))
             {}
             (or dados {})))

(defonce ^:private contas (atom (migrar-chaves-antigas (armazenamento/obter "loja"))))
(armazenamento/registrar! "loja" contas migrar-chaves-antigas)

(defn- persistir! []
  (armazenamento/salvar! "loja" @contas))

(def ^:private schema-loja-save "save-bound-v1")
(defonce ^:private vinculos-save (atom {}))
;; Fila por jogador: migração e gate de transição não correm em paralelo.
(defonce ^:private filas-vinculo (atom {}))
;; Recibos locais de vínculo já confirmados neste processo (além do atom/Cassandra).
(defonce ^:private vinculos-duraveis (atom {}))

(defn- loja-vinculada-a-save? []
  (boolean (or config/save-transitions-enabled? config/canonical-trainer-enabled?)))

(defn- registro-vinculado? [reg]
  (and (map? reg)
       (= schema-loja-save (get reg "schema-version-loja"))
       (map? (get reg "saves"))))

(defn- economia-vazia []
  {"moedas" 0 "inventario" {}})

(defn- legado-plano? [reg]
  (and (map? reg) (not (registro-vinculado? reg))))

(defn- legado-com-conteudo? [reg]
  (and (legado-plano? reg)
       (or (pos? (get reg "moedas" 0))
           (seq (get reg "inventario"))
           (seq (get reg "missoes-diarias"))
           (seq (get reg "missoes-eventos"))
           (seq (get reg "adventure-eventos"))
           (pos? (get reg "expansoes-pc" 0))
           (pos? (get reg "expansoes-mochila-50" 0))
           (seq (dissoc reg "moedas" "inventario")))))

(defn- encapsular-legado [economia save-id fence-gen]
  (let [eco (-> (or economia {})
                (dissoc "schema-version-loja" "saves" "legacy-claim")
                (cond-> fence-gen (assoc "save-fence-generation" fence-gen)))]
    {"schema-version-loja" schema-loja-save
     "legacy-claim" {"save-id" save-id
                     "status" "confirmed"
                     "fence-generation" fence-gen}
     "saves" {save-id eco}}))

(defn- save-ctx-jogador [pid]
  (armazenamento/contexto-writer-jogador pid))

(defn- vinculo-ativo [cid pid]
  (get @vinculos-save [cid pid]))

(defn- registro-loja [cid pid]
  (get-in @contas [cid pid]))

(defn- registro-loja-confirmado [cid pid]
  (get-in (armazenamento/estado-confirmado "loja") [cid pid]))

(defn- save-presente-no-registro? [reg save-id]
  (and (registro-vinculado? reg) (contains? (get reg "saves") save-id)))

(defn vinculo-duravel? [cid pid save-id]
  "True quando o envelope confirmado contém o save e há recibo local.
  Após reinício o recibo é recriado ao reobservar o envelope já no Cassandra."
  (boolean
   (and (string? save-id) (not (str/blank? save-id))
        (save-presente-no-registro? (registro-loja-confirmado cid pid) save-id)
        (save-presente-no-registro? (registro-loja cid pid) save-id)
        (= save-id (get-in @vinculos-duraveis [[cid pid] :save-id])))))

(declare garantir-vinculo-duravel!)

(defn- exigir-vinculo-operacao! [cid pid]
  (when-let [ctx (save-ctx-jogador pid)]
    (when (and (string? (:transition-owner ctx))
               (not (str/blank? (:transition-owner ctx))))
      (throw (ex-info "Loja bloqueada durante reserva de transição."
                      {:code :loja-transition-reserved}))))
  (let [ctx (save-ctx-jogador pid)
        ;; Operações síncronas exigem vínculo em memória; a migração durável
        ;; roda no gate de hidratação/ativação (promise), não aqui.
        vinculo (or (vinculo-ativo cid pid)
                    (when (and ctx (vinculo-duravel? cid pid (:save-id ctx)))
                      (let [v {:save-id (:save-id ctx)
                               :fence-generation (:fence-generation ctx)}]
                        (swap! vinculos-save assoc [cid pid] v)
                        v)))]
    (when-not (and vinculo (string? (:save-id vinculo)) (not (str/blank? (:save-id vinculo))))
      (throw (ex-info "Loja sem vínculo de save ativo; operação bloqueada."
                      {:code :loja-save-binding-required})))
    (when (and ctx (not= (:save-id ctx) (:save-id vinculo)))
      (throw (ex-info "Callback de loja de outro save; efeito bloqueado."
                      {:code :loja-save-fence-conflict})))
    (when (and ctx (:fence-generation vinculo)
               (not= (:fence-generation ctx) (:fence-generation vinculo)))
      (throw (ex-info "Callback de loja com fence obsoleto; efeito bloqueado."
                      {:code :loja-save-fence-conflict})))
    vinculo))

(defn- planejar-envelope [reg save-id fence-gen]
  "Decide o próximo envelope. Não reivindica legado ambíguo (fence>1 com plano)."
  (cond
    (registro-vinculado? reg)
    (if (contains? (get reg "saves") save-id)
      {:reg reg :changed? false :claimed? false}
      {:reg (assoc-in reg ["saves" save-id] (economia-vazia))
       :changed? true :claimed? false})

    (legado-com-conteudo? reg)
    (let [claim-save (get-in reg ["legacy-claim" "save-id"])]
      (cond
        (and claim-save (not= claim-save save-id))
        (throw (ex-info "Estado legado de loja pertence a outro save; vínculo recusado."
                        {:code :loja-save-ambiguous :claimed claim-save :active save-id}))
        ;; Troca já avançou o fence sem envelope durável: dono do plano é incerto.
        (and (number? fence-gen) (> fence-gen 1))
        (throw (ex-info "Estado legado de loja ambíguo após transição; vínculo recusado."
                        {:code :loja-save-ambiguous :active save-id :fence fence-gen}))
        :else
        {:reg (encapsular-legado reg save-id fence-gen)
         :changed? true :claimed? true}))

    :else
    {:reg (encapsular-legado (or reg (economia-vazia)) save-id fence-gen)
     :changed? (not (registro-vinculado? reg))
     :claimed? true}))

(defn- enfileirar-vinculo! [cid pid trabalho]
  (let [chave [cid pid]
        anterior (get @filas-vinculo chave (p/resolved nil))
        atual (p/then anterior (fn [_] (trabalho)))]
    (swap! filas-vinculo assoc chave (p/catch atual (fn [_] nil)))
    atual))

(defn- marcar-recibo-local! [cid pid save-id fence-gen]
  (swap! vinculos-duraveis assoc [cid pid]
         {:save-id save-id :fence-generation fence-gen}))

(defn- fingerprint-economia [economia]
  (let [h (.-hash (js/require "../runtime/restore-protocol.cjs"))]
    (h (clj->js (or economia {})))))

(defn- fingerprint-confirmado [cid pid save-id]
  (fingerprint-economia
   (get-in (armazenamento/estado-confirmado "loja") [cid pid "saves" save-id])))

(defn garantir-vinculo-duravel!
  "Migra legado→envelope (idempotente), persiste e só então marca o vínculo.
  Recibo local exige save presente em estado-confirmado (não só no atom).
  Retorna promise. Com flags off é no-op resolvido."
  [cid pid save-id fence-gen]
  (if-not (and (loja-vinculada-a-save?)
               (string? save-id) (not (str/blank? save-id)))
    (p/resolved {:ready false :disabled true})
    (do
      ;; Ponteiro em memória imediato; mutação do envelope só dentro da fila.
      (swap! vinculos-save assoc [cid pid] {:save-id save-id
                                            :fence-generation fence-gen})
      (enfileirar-vinculo!
       cid pid
       (fn []
         (let [antes (registro-loja cid pid)
               {:keys [reg changed?]} (planejar-envelope antes save-id fence-gen)]
           (when changed?
             (swap! contas assoc-in [cid pid] reg))
           (cond
             (vinculo-duravel? cid pid save-id)
             (p/resolved {:ready true :idempotent true :activeSlotId save-id :durable true})

             ;; Reinício: envelope já no snapshot confirmado (hidratação) —
             ;; recria recibo local sem depender de compra nem de nova gravação.
             (and (save-presente-no-registro? (registro-loja-confirmado cid pid) save-id)
                  (save-presente-no-registro? (registro-loja cid pid) save-id))
             (do (marcar-recibo-local! cid pid save-id fence-gen)
                 (p/resolved {:ready true :idempotent true :activeSlotId save-id
                              :durable true :reobserved true}))

             :else
             ;; Sem prova durável: grava (com LateralIntent se o store canônico
             ;; estiver ativo) e só confirma com estado-confirmado.
             (let [persistir-vinculo!
                   (fn []
                     (p/let [_ (persistir!)
                             _ (armazenamento/aguardar-todas!)]
                       (let [atual (registro-loja cid pid)
                             confirmado (registro-loja-confirmado cid pid)]
                         (when-not (and (save-presente-no-registro? confirmado save-id)
                                        (save-presente-no-registro? atual save-id))
                           (throw (ex-info "Vínculo de loja não confirmado no Cassandra."
                                           {:code :loja-save-binding-unconfirmed
                                            :save-id save-id})))
                         (fingerprint-economia
                          (get-in confirmado ["saves" save-id])))))]
               (p/let [_ (if (armazenamento/loja-lateral-disponivel?)
                           (armazenamento/gravar-loja-com-intent!
                            cid pid "loja_bind"
                            {:saveId save-id :fence fence-gen :kind "bind"
                             :changed changed?}
                            persistir-vinculo!)
                           (persistir-vinculo!))]
                 (marcar-recibo-local! cid pid save-id fence-gen)
                 {:ready true
                  :idempotent (not changed?)
                  :activeSlotId save-id
                  :durable true}))))))))))

(defn vincular-save-ativo!
  "Compat: atualiza memória e dispara vínculo durável. Prefira aguardar a promise."
  [cid pid save-id fence-gen]
  (garantir-vinculo-duravel! cid pid save-id fence-gen))

(armazenamento/configurar-binder-loja-save! garantir-vinculo-duravel!)

(defn- economia-do-registro [reg save-id]
  (cond
    (registro-vinculado? reg)
    (or (get-in reg ["saves" save-id]) (economia-vazia))
    (and (map? reg) (loja-vinculada-a-save?))
    (throw (ex-info "Registro de loja legado ambíguo; vincule o save ativo antes."
                    {:code :loja-save-ambiguous}))
    :else (or reg (economia-vazia))))

(defn- gravar-economia [reg save-id fence-gen economia]
  (let [base (if (registro-vinculado? reg)
               reg
               (encapsular-legado (or reg (economia-vazia)) save-id fence-gen))
        eco (-> economia
                (dissoc "schema-version-loja" "saves")
                (cond-> fence-gen (assoc "save-fence-generation" fence-gen)))]
    (assoc-in base ["saves" save-id] eco)))

(defn- economia-legado-visivel
  "Com flags off, um único save encapsulado ainda é a economia efetiva."
  [reg]
  (cond
    (not (registro-vinculado? reg)) (or reg (economia-vazia))
    (= 1 (count (get reg "saves"))) (val (first (get reg "saves")))
    :else (throw (ex-info "Registro de loja com vários saves sem modo canônico."
                          {:code :loja-save-ambiguous}))))

(defn- alterar-economia-local! [cid pid f]
  (if-not (loja-vinculada-a-save?)
    (swap! contas update-in [cid pid]
           (fn [reg]
             (if (registro-vinculado? reg)
               (let [saves (get reg "saves")]
                 (when-not (= 1 (count saves))
                   (throw (ex-info "Registro de loja com vários saves sem modo canônico."
                                   {:code :loja-save-ambiguous})))
                 (let [[sid eco] (first saves)]
                   (assoc-in reg ["saves" sid] (f (or eco (economia-vazia))))))
               (f (or reg (economia-vazia))))))
    (let [{:keys [save-id fence-generation]} (exigir-vinculo-operacao! cid pid)]
      (swap! contas update-in [cid pid]
             (fn [reg]
               (let [eco (economia-do-registro reg save-id)
                     novo (f eco)]
                 (gravar-economia reg save-id fence-generation (or novo (economia-vazia)))))))))

(defn- alterar-conta-com-intent!
  "Begin LateralIntent → mutar atom → persistir → evidência em confirmados →
  confirm/reconcile. Bloqueia A→B enquanto o intent estiver ativo/incerto."
  [cid pid f]
  (let [{:keys [save-id fence-generation]} (exigir-vinculo-operacao! cid pid)
        reg (registro-loja cid pid)
        antes (economia-do-registro reg save-id)
        depois (or (f antes) (economia-vazia))
        esperado (fingerprint-economia depois)
        payload {:saveId save-id
                 :fence fence-generation
                 :beforeHash (fingerprint-economia antes)
                 :afterHash esperado}]
    (armazenamento/gravar-loja-com-intent!
     cid pid "loja_mut" payload
     (fn []
       (swap! contas update-in [cid pid]
              (fn [atual]
                (gravar-economia atual save-id fence-generation depois)))
       (p/let [_ (persistir!)
               _ (armazenamento/aguardar-todas!)]
         (let [fp (fingerprint-confirmado cid pid save-id)]
           (when-not (= fp esperado)
             (throw (ex-info "Efeito de loja ausente no snapshot confirmado."
                             {:code :loja-lateral-effect-absent
                              :save-id save-id})))
           fp))))))

(defn- alterar-conta! [cid pid f]
  (cond
    (not (loja-vinculada-a-save?))
    (do (alterar-economia-local! cid pid f) (persistir!))

    (armazenamento/loja-lateral-disponivel?)
    (alterar-conta-com-intent! cid pid f)

    :else
    (do (alterar-economia-local! cid pid f) (persistir!))))

(defn- trocar-conta! [cid pid nova]
  (alterar-conta! cid pid (fn [_] nova)))

(def preco-reaprender 50)
(declare conta)

;; Resolved by treinador after loading, avoiding a circular namespace dependency.
(defonce nivel-treinador (atom (fn [_ _] 1)))
(defn nivel-atual [cid pid] (@nivel-treinador cid pid))
(def ^:private vagas-por-expansao-pc 50)
(def ^:private preco-fixo-expansao-pc 200)

(defn capacidade-por-nivel [cid pid nivel]
  (let [c (conta cid pid)]
    (armazenamento-regras/capacidade-pokemon nivel (get c "expansoes-pc" 0)
                                             (get c "capacidade-pokemon" 0))))

(defn expansoes-pc [cid pid]
  (get (conta cid pid) "expansoes-pc" 0))

(defn capacidade-permanente-pokemon
  ([cid pid] (capacidade-permanente-pokemon cid pid (nivel-atual cid pid)))
  ([cid pid nivel] (capacidade-por-nivel cid pid nivel)))

(defn capacidade-pokemon [cid pid]
  (capacidade-permanente-pokemon cid pid))

(defn capacidade-pokemon-no-nivel [cid pid nivel]
  (capacidade-permanente-pokemon cid pid nivel))

(declare recompensas-pendentes texto-recompensas capacidade)

(defn notificacao-capacidade [cid pid nivel-anterior nivel-novo]
  (let [marcos (armazenamento-regras/marcos-entre nivel-anterior nivel-novo)
        linhas (for [[nivel bonus] marcos
                     :let [tipo (cond (:pokemon bonus) :pokemon :mochila :mochila)
                           antes (if (= tipo :pokemon)
                                   (capacidade-pokemon-no-nivel cid pid (max 1 (dec nivel)))
                                   (capacidade cid pid (max 1 (dec nivel))))
                           depois (if (= tipo :pokemon)
                                    (capacidade-pokemon-no-nivel cid pid nivel)
                                    (capacidade cid pid nivel))]
                     :when (not= antes depois)]
                (str "🎒 Nível " nivel ": +50 espaços de " (if (= tipo :pokemon) "Pokémon" "mochila")
                     " (" antes " → " depois ")."))]
    (when (seq linhas) (str/join "\n" linhas))))

(defn recompensas [cid pid]
  (let [pendentes (recompensas-pendentes (conta cid pid))
        texto (texto-recompensas pendentes)]
    (str "🎁 *Recompensas disponíveis*\n"
         (if (str/blank? texto) "Nenhuma recompensa pendente."
             (str texto "\nUse " config/prefix "pokemon mochila resgatar para receber.")))))

(defn preco-expansao-pc [_cid _pid]
  preco-fixo-expansao-pc)

(defn comprar-espaco-pc! [cid pid]
  ;; Saldo e expansão pertencem ao mesmo registro persistido.
  (let [resultado (volatile! nil)]
    (p/then (alterar-conta! cid pid
                            (fn [c]
                              (let [c (or c {"moedas" 0 "inventario" {}})
                                    n (get c "expansoes-pc" 0)
                                    preco preco-fixo-expansao-pc]
                                (if (< (get c "moedas" 0) preco)
                                  (do (vreset! resultado {:status :sem-moedas :preco preco}) c)
                                  (do (vreset! resultado {:status :ok :preco preco
                                                          :capacidade (armazenamento-regras/capacidade-pokemon
                                                                       (nivel-atual cid pid) (inc n)
                                                                       (get c "capacidade-pokemon" 0))})
                                      (-> c (update "moedas" - preco) (assoc "expansoes-pc" (inc n))))))))
            (fn [_] @resultado))))

(defn pagar-reaprendizado! [cid pid]
  (when (>= (get (conta cid pid) "moedas" 0) preco-reaprender)
    (alterar-conta! cid pid #(update % "moedas" - preco-reaprender))
    true))

(defn- remover-acentos [s]
  (-> s (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(def ^:private moedas-por-vitoria 10)

;; catálogo estático (nunca persistido, então pode usar keyword à vontade) -
;; chaves nomeadas pelo ITEM que você compra (não pelo status que ele cura),
;; então "loja comprar atadura"/"antidoto" fazem sentido de verdade
(def itens-evolucao-troca
  ["revestimento-metalico" "escama-dragao" "upgrade" "protetor" "pedra-rei"
   "eletrizador" "magmarizador" "tecido-ceifador" "escama-prisma"
   "chicote-doce" "sache-perfumado"])

(def ^:private nomes-itens-evolucao
  {"revestimento-metalico" ["Revestimento Metálico" "metal-coat"]
   "escama-dragao" ["Escama de Dragão" "dragon-scale"]
   "upgrade" ["Upgrade" "up-grade"]
   "protetor" ["Protetor" "protector"]
   "pedra-rei" ["Pedra do Rei" "kings-rock"]
   "eletrizador" ["Eletrizador" "electirizer"]
   "magmarizador" ["Magmarizador" "magmarizer"]
   "tecido-ceifador" ["Tecido do Ceifador" "reaper-cloth"]
   "escama-prisma" ["Escama Prisma" "prism-scale"]
   "chicote-doce" ["Chicote Doce" "whipped-dream"]
   "sache-perfumado" ["Sachê Perfumado" "sachet"]})

(defn item-evolucao-pokeapi [id] (second (get nomes-itens-evolucao id)))

(def ^:private itens-base
  {"catalisador-evolutivo" {:nome "Catalisador Evolutivo" :emoji "🧬" :exclusivo-missoes true
                              :descricao "Cumpre condições evolutivas especiais sem equivalente no WhatsApp. Use !pokemon evoluir <número> especial [destino]."}
   "cartao-presente" {:nome "Cartão de presente" :emoji "🎁" :preco 20
                       :descricao "Envie com !presente @amigo. Sorteia 3 Pokébolas (65%), 2 Grandes (25%) ou 1 Ultra (10%) para o amigo."}
   "reviver" {:nome "Reviver" :emoji "💎" :exclusivo-missoes true
              :descricao "Exclusivo das missões: revive um Pokémon desmaiado com 100% do HP e remove seu status. Use !pokemon reviver [número], fora de batalhas e caçadas."}
   "pokebola" {:nome "Pokébola" :emoji "🔴" :multiplicador-captura 1 :limite-captura 75
               :descricao "Bola normal, com até 75% de chance. Obtida em missões, bônus diário, kit inicial e nocautes PvP."}
   "grande-bola" {:nome "Grande Bola" :emoji "🔵" :multiplicador-captura 1.5 :limite-captura 88
                  :descricao "Multiplica a chance por 1,5, até 88%. Obtida em missões, bônus diário e nocautes PvP de ligas intermediárias."}
   "ultra-bola" {:nome "Ultra Bola" :emoji "🟡" :multiplicador-captura 2 :limite-captura 95
                 :descricao "Multiplica a chance por 2, até 95%. Obtida em missões, bônus diário e nocautes PvP da Liga Diamante."}
   "mochila" {:nome "Expansão de Mochila" :emoji "🎒" :preco 200 :expansao 50
              :descricao "Aumenta permanentemente a capacidade em 50 unidades. Pode comprar várias vezes; não ocupa espaço."}
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
                 :descricao "Recupera 40% do HP máximo. Use !pokemon pocao [número]; sem número, cura o ativo."}
   "pocao-maxima" {:nome "Poção Máxima" :emoji "💖" :cura-hp 1.0 :preco 60
                   :descricao "Restaura todo o HP. Use !pokemon pocao-maxima [número]; sem número, cura o ativo."}
   "fruta"      {:nome "Fruta Frambo" :emoji "🍓" :motivacao 20 :preco 10
                 :descricao "Recupera 20 pontos de motivação de um defensor no ginásio."}
   "fruta-dourada" {:nome "Fruta Frambo Dourada" :emoji "🌟" :motivacao 100 :exclusivo-diario true
                     :descricao "Recupera toda a motivação de um defensor. Prêmio da sequência diária."}
   "restos"     {:nome "Restos" :emoji "🍱" :equipavel true :efeito :regeneracao :preco 45
                 :descricao "Recupera 1/16 do HP máximo ao final de cada turno em que o Pokémon agir."}
   "banda"      {:nome "Banda Musculosa" :emoji "💪" :equipavel true :efeito :fisico :preco 40
                 :descricao "Aumenta em 15% o dano causado por golpes físicos."}
   "oculos"     {:nome "Óculos Sábios" :emoji "👓" :equipavel true :efeito :especial :preco 40
                 :descricao "Aumenta em 15% o dano causado por golpes especiais."}
   "faixa-foco" {:nome "Faixa de Foco" :emoji "🥋" :equipavel true :efeito :sobreviver :preco 55
                 :descricao "Se estiver com HP cheio, sobrevive uma vez por batalha a um golpe fatal, ficando com 1 HP."}})

(def ^:private itens
  (merge itens-base
         (into {} (map (fn [[id [nome _]]]
                         [id {:nome nome :emoji "🧬" :equipavel true :exclusivo-missoes true
                              :descricao "Equipe no Pokémon e conclua a troca exigida. O item é consumido ao evoluir."}])
                       nomes-itens-evolucao))
         (into {} (map (fn [[id pedra]]
                         [id (assoc pedra :evolucao true
                                    :descricao "Primeira vitória no ginásio correspondente ou revanche diária. Use !pokemon evoluir <número> <pedra>.")])
                       aventuras/pedras))))

(declare conta)

(defn dados-item [chave] (get itens chave))
(defn item-equipavel? [chave] (true? (get-in itens [chave :equipavel])))

(defn consumir-item!
  "Remove uma unidade de um item equipável do inventário."
  [cid pid chave]
  (when (and (item-equipavel? chave) (pos? (get-in (conta cid pid) ["inventario" chave] 0)))
    (alterar-conta! cid pid #(update-in % ["inventario" chave] dec))
    true))

(defn devolver-item!
  "Devolve um item equipável ao inventário (ao trocar/desequipar)."
  [cid pid chave]
  (when (item-equipavel? chave)
    (alterar-conta! cid pid
           (fn [c] (update-in (or c {"moedas" 0 "inventario" {}})
                              ["inventario" chave] (fnil inc 0))))
    true))

(defn- conta [cid pid]
  (if-not (loja-vinculada-a-save?)
    (economia-legado-visivel (get-in @contas [cid pid]))
    (let [vinculo (or (vinculo-ativo cid pid)
                      (when-let [ctx (save-ctx-jogador pid)]
                        (vincular-save-ativo! cid pid (:save-id ctx) (:fence-generation ctx))
                        (vinculo-ativo cid pid)))]
      (if-not vinculo
        (throw (ex-info "Loja sem vínculo de save ativo; leitura bloqueada."
                        {:code :loja-save-binding-required}))
        (do
          ;; Leitura sob writer context também respeita o fence vivo.
          (when-let [ctx (save-ctx-jogador pid)]
            (when (not= (:save-id ctx) (:save-id vinculo))
              (throw (ex-info "Callback de loja de outro save; efeito bloqueado."
                              {:code :loja-save-fence-conflict})))
            (when (and (:fence-generation vinculo)
                       (not= (:fence-generation ctx) (:fence-generation vinculo)))
              (throw (ex-info "Callback de loja com fence obsoleto; efeito bloqueado."
                              {:code :loja-save-fence-conflict}))))
          (economia-do-registro (get-in @contas [cid pid]) (:save-id vinculo)))))))

(def bolas ["pokebola" "grande-bola" "ultra-bola"])
(def ^:private ordem-recompensas
  (vec (concat bolas ["fruta" "fruta-dourada" "reviver" "catalisador-evolutivo"]
               itens-evolucao-troca (sort (keys aventuras/pedras)))))

(defn sortear-item-evolucao [] (rand-nth itens-evolucao-troca))

(defn normalizar-item [nome]
  (let [chave (-> (or nome "") str/trim str/lower-case remover-acentos
                  (str/replace #"\s+" "-"))]
    (get {"normal" "pokebola" "grande" "grande-bola" "ultra" "ultra-bola"
          "maxima" "pocao-maxima" "maxima-pocao" "pocao-maxima"} chave chave)))

(defn quantidade-item [cid pid chave]
  (get-in (conta cid pid) ["inventario" chave] 0))

(defn capacidade
  ([cid pid] (capacidade cid pid (nivel-atual cid pid)))
  ([cid pid nivel]
  (let [c (conta cid pid)]
    (armazenamento-regras/capacidade-mochila nivel
                                             (get c "expansoes-mochila-50" 0)
                                             (get c "capacidade-mochila" 50)))))

(defn ocupacao [cid pid]
  (reduce + 0 (vals (get (conta cid pid) "inventario"))))

(defn- cabe? [cid pid quantidade]
  (<= (+ (ocupacao cid pid) quantidade) (capacidade cid pid)))

(defn consumir-bola! [cid pid chave]
  (when (and (some #{chave} bolas) (pos? (quantidade-item cid pid chave)))
    (alterar-conta! cid pid #(update-in % ["inventario" chave] dec))
    true))

(defn consumir-reviver! [cid pid]
  (when (pos? (quantidade-item cid pid "reviver"))
    (alterar-conta! cid pid #(update-in % ["inventario" "reviver"] dec))
    true))

(defn consumir-catalisador! [cid pid]
  (when (pos? (quantidade-item cid pid "catalisador-evolutivo"))
    (alterar-conta! cid pid #(update-in % ["inventario" "catalisador-evolutivo"] dec))
    true))

(defn- recompensas-pendentes [c]
  ;; Compatibilidade com recompensas anteriores, que só guardavam Pokébolas.
  (update (get c "recompensas-pendentes" {}) "pokebola"
          (fnil + 0) (get c "bolas-pendentes" 0)))

(defn- guardar-recompensas [c recompensas nivel]
  (let [c (or c {"moedas" 0 "inventario" {}})
        livres (max 0 (- (armazenamento-regras/capacidade-mochila
                           nivel
                           (get c "expansoes-mochila-50" 0)
                           (get c "capacidade-mochila" 50))
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

(defn progresso-evento-recomeco [cid pid]
  (when-let [evento (aventuras/evento-recomeco (.now js/Date))]
    (let [salvo (get-in (conta cid pid) ["missoes-eventos" (:id evento)] {})]
      (assoc evento :progresso (get salvo "progresso" {})
             :premiados (set (get salvo "premiados" []))))))

(defn texto-evento-recomeco [cid pid]
  (when-let [evento (progresso-evento-recomeco cid pid)]
    (str "🌅 *" (:nome evento) "* — até "
         (.toLocaleString (js/Date. (:fim evento)) "pt-BR"
                          #js {:timeZone "America/Sao_Paulo" :dateStyle "short" :timeStyle "short" :hourCycle "h23"})
         " (São Paulo)."
         (apply str
                (for [{:keys [id nome meta recompensas]} (:objetivos evento)]
                  (str "\n• " nome ": " (get (:progresso evento) id 0) "/" meta
                       " — " (texto-recompensas recompensas)
                       (when (contains? (:premiados evento) id) " ✅ Entregue"))))
         "\nCada missão entrega os itens automaticamente uma vez por jogador neste chat."
         "\nSó ações durante o evento contam; o progresso não reinicia diariamente."
         "\nMochila cheia: os itens ficam pendentes em " config/prefix "mochila resgatar.")))

(defn registrar-evento-recomeco! [cid pid objetivo]
  (when-let [evento (aventuras/evento-recomeco (.now js/Date))]
    (when-let [missao (some #(when (= objetivo (:id %)) %) (:objetivos evento))]
      (let [aviso (volatile! nil)
            caminho ["missoes-eventos" (:id evento)]]
        ;; Progresso, marcador de entrega e inventário mudam no mesmo registro.
        (alterar-economia-local! cid pid
               (fn [c]
                 (let [c (or c {})
                       antes (get-in c (conj caminho "progresso" objetivo) 0)
                       premiados (set (get-in c (conj caminho "premiados") []))]
                   (if (or (contains? premiados objetivo) (>= antes (:meta missao)))
                     c
                     (let [atual (inc antes)
                           completo? (= atual (:meta missao))
                           novo (assoc-in c (conj caminho "progresso" objetivo) atual)]
                       (vreset! aviso
                                (str "\n🌅 Novo Recomeço — " (:nome missao) ": " atual "/" (:meta missao)
                                     (when completo?
                                       (str "\n🎁 " (texto-recompensas (:recompensas missao))
                                            ". Itens sem espaço ficam em " config/prefix "mochila resgatar."))))
                       (if completo?
                         (-> novo
                             (assoc-in (conj caminho "premiados") (vec (conj premiados objetivo)))
                             (guardar-recompensas (:recompensas missao) (nivel-atual cid pid)))
                         novo))))))
        (when @aviso (persistir!))
        @aviso))))

(defn premiar-bolas! [cid pid bola quantidade]
  (alterar-conta! cid pid #(guardar-recompensas % {bola quantidade} (nivel-atual cid pid)))
  (str "🎁 +" quantidade " " (:nome (dados-item bola)) " por nocaute(s) no PvP."
       (when (some pos? (vals (recompensas-pendentes (conta cid pid))))
         (str " Há recompensas pendentes: libere espaço e use " config/prefix "mochila resgatar."))))

(defn premiar-item-evolucao! [cid pid item]
  (alterar-conta! cid pid #(guardar-recompensas % {item 1} (nivel-atual cid pid)))
  (str "🎁 +1 " (:nome (dados-item item))
       (when (some pos? (vals (recompensas-pendentes (conta cid pid))))
         ". Mochila cheia: use !mochila resgatar após liberar espaço.")))

(defn consumir-pedra! [cid pid item]
  (when (and (contains? aventuras/pedras item) (pos? (quantidade-item cid pid item)))
    (alterar-conta! cid pid #(update-in % ["inventario" item] dec))
    true))

(defn- sortear-bola-diaria []
  (let [sorteio (rand-int 100)]
    (cond (< sorteio 10) "ultra-bola"
          (< sorteio 40) "grande-bola"
          :else "pokebola")))

(defn proxima-sequencia-diaria [ultimo dia sequencia-atual]
  (let [dia-seguinte (when ultimo
                       (let [d (js/Date. (str ultimo "T12:00:00Z"))]
                         (.setUTCDate d (inc (.getUTCDate d)))
                         (subs (.toISOString d) 0 10)))]
    (if (= dia dia-seguinte) (inc (or sequencia-atual 1)) 1)))

(defn- resgatar-bonus-diario! [cid pid]
  (let [dia (missoes/dia-atual)
        c (conta cid pid)]
    (if (= dia (get c "ultimo-bonus-diario"))
      (str "🎁 O bônus diário de hoje já foi resgatado. Volte após a meia-noite ("
           config/missoes-timezone ").")
      (let [ultimo (get c "ultimo-bonus-diario")
            sequencia (proxima-sequencia-diaria ultimo dia (get c "sequencia-bonus-diario" 1))
            quantidade (+ 3 (min 4 (dec sequencia)))
            recompensas (cond-> (frequencies (repeatedly quantidade sortear-bola-diaria))
                          (zero? (mod sequencia 7)) (assoc "fruta-dourada" 1))
            novo (-> c
                     (assoc "ultimo-bonus-diario" dia)
                     (assoc "sequencia-bonus-diario" sequencia)
                     (guardar-recompensas recompensas (nivel-atual cid pid)))]
        (trocar-conta! cid pid novo)
        (str "🎁 *Bônus diário resgatado — sequência de " sequencia " dia(s):* "
             (texto-recompensas recompensas) "!"
             (when (zero? (mod sequencia 7)) " 🌟 Você ganhou uma Fruta Frambo Dourada!")
             (when (some pos? (vals (recompensas-pendentes novo)))
               (str " Itens sem espaço ficaram pendentes: " config/prefix "mochila resgatar.")))))))

(defn resgatar-bolas! [cid pid kit?]
  (let [c (conta cid pid)
        recompensas (if kit? {"pokebola" 10} (recompensas-pendentes c))]
    (cond
      (and kit? (get c "kit-inicial-resgatado")) "🎁 Você já resgatou o kit inicial."
      (or (not (some pos? (vals recompensas))) (not (cabe? cid pid (if kit? 10 1))))
      "🎒 Sem recompensas disponíveis para resgatar ou espaço insuficiente. Libere vagas ou compre uma expansão."
      :else
      (let [novo (guardar-recompensas (if kit? (assoc c "kit-inicial-resgatado" true)
                                          (dissoc c "bolas-pendentes" "recompensas-pendentes")) recompensas (nivel-atual cid pid))
            recebidas (into {} (for [bola ordem-recompensas]
                                 [bola (- (get-in novo ["inventario" bola] 0)
                                          (get-in c ["inventario" bola] 0))]))]
        (trocar-conta! cid pid novo)
        (str "🎁 Você recebeu " (texto-recompensas recebidas) "!")))))

(defn xp-missoes [cid pid]
  (get (conta cid pid) "xp-missoes" 0))

(defn- economias-visiveis [reg]
  (if (registro-vinculado? reg)
    (vals (get reg "saves"))
    [(or reg {})]))

(defn eventos-atividade [cid]
  (for [[pid reg] (get (armazenamento/obter "loja") cid)
        eco (economias-visiveis reg)
        [_ evento] (get eco "adventure-eventos")]
    [pid evento]))

(defn confirmar-evento-atividade! [cid pid event-id]
  (alterar-conta! cid pid #(update % "adventure-eventos" dissoc event-id)))

(defn registrar-missao! [cid pid evento nivel]
  (let [dia (missoes/dia-atual)
        c (conta cid pid)
        antes (count (missoes/disponiveis (missoes/estado-do-dia c dia nivel)))
        novo (missoes/registrar-evento c dia evento nivel)
        missao (some #(when (= evento (:id %)) %) (missoes/catalogo-do-dia (missoes/estado-do-dia novo dia nivel)))
        completou? (and missao
                        (< (get-in (missoes/estado-do-dia c dia nivel) ["progresso" evento] 0) (:meta missao))
                        (>= (get-in (missoes/estado-do-dia novo dia nivel) ["progresso" evento] 0) (:meta missao)))
        novo (if (and config/adventure-enabled? completou?)
               (egg-rewards/guardar-evento novo
                 (egg-rewards/evento (str "daily:" dia ":" evento) :daily-mission (.now js/Date))) novo)]
    (trocar-conta! cid pid novo)
    (str
      (when (> (count (missoes/disponiveis (missoes/estado-do-dia novo dia nivel))) antes)
        (str "\n📋 Missão diária concluída! Resgate com " config/prefix "missoes diarias resgatar."))
      (registrar-evento-recomeco! cid pid evento))))

(defn- resgatar-missoes! [cid pid nivel]
  (let [dia (missoes/dia-atual)
        c (conta cid pid)
        estado (missoes/estado-do-dia c dia nivel)
        prontas (missoes/disponiveis estado)]
    (if (empty? prontas)
      "📋 Nenhuma missão concluída disponível para resgatar hoje."
      (let [xp (reduce + (map :xp prontas))
            recompensas (reduce (fn [r missao]
                                  (let [r (merge-with + r (missoes/recompensas-bolas missao))]
                                    (cond-> (if-let [bonus (missoes/sortear-bonus)]
                                              (update r bonus (fnil inc 0)) r)
                                      (missoes/sortear-reviver?) (update "reviver" (fnil inc 0))
                                      (< (rand-int 100) 20) (update (sortear-item-evolucao) (fnil inc 0)))))
                                {} prontas)
            novo (-> c
                     (assoc "missoes-diarias" (update estado "resgatadas" into (map :id prontas)))
                     (update "xp-missoes" (fnil + 0) xp)
                     (guardar-recompensas recompensas (nivel-atual cid pid)))]
        ;; Sem espera assíncrona: XP, sorteio, mochila e marca de resgate
        ;; são persistidos juntos, inclusive quando a mochila está cheia.
        (trocar-conta! cid pid novo)
        (str "✅ " (count prontas) " missão(ões) resgatada(s)!\n✨ +" xp " PE do treinador"
             "\n🎁 " (texto-recompensas recompensas)
             (when (some pos? (vals (recompensas-pendentes novo)))
               (str "\n🎒 Itens sem espaço ficaram pendentes: " config/prefix "mochila resgatar.")))))))

(defn ver-missoes [contexto acao nivel]
  (let [cid (:chat-id contexto)
        pid (:player-id contexto)
        dia (missoes/dia-atual)
        estado (missoes/estado-do-dia (conta cid pid) dia nivel)]
    ;; O primeiro acesso ou evento do dia fixa a faixa, sem mudar metas
    ;; depois de resgatar XP e subir de nível no mesmo dia.
    (when (not= estado (get (conta cid pid) "missoes-diarias"))
      (alterar-conta! cid pid #(assoc (or % {"moedas" 0 "inventario" {}}) "missoes-diarias" estado)))
    (if (= "resgatar" (normalizar-item acao))
      (resgatar-missoes! cid pid nivel)
      (str "📋 *Missões diárias — " dia "*\nNível de referência hoje: " (get estado "nivel" 1) "\n"
           (str/join "\n\n"
                     (for [{:keys [id nome objetivo meta xp pokebolas grandes ultras catalisadores]} (missoes/catalogo-do-dia estado)
                           :let [progresso (get-in estado ["progresso" id] 0)
                                 resgatada? (some #{id} (get estado "resgatadas"))]]
                       (str (cond resgatada? "🎁" (>= progresso meta) "✅" :else "⬜")
                            " *" nome "*: " objetivo " — " progresso "/" meta
                            "\n+" xp " PE do treinador e " pokebolas " Pokébolas, " grandes " Grandes e " ultras " Ultras"
                            (when (pos? (or catalisadores 0)) (str ", " catalisadores " Catalisador Evolutivo"))
                            (when resgatada? " (resgatada)"))))
           "\n\nCada missão: 25% de chance de +1 Grande Bola, 10% de +1 Ultra Bola; 65% sem bônus."
           " Um único sorteio de bola bônus por missão, além das Pokébolas garantidas."
           "\n💎 Chance independente de 20% de +1 Reviver por missão, exclusivo das missões."
           "\nMetas, PE e Pokébolas aumentam a cada 5 níveis; a faixa fica fixa até a próxima renovação."
           "\nResgate as concluídas com " config/prefix "missoes diarias resgatar."
           "\nRenovação à meia-noite (" config/missoes-timezone "). Resgate antes da virada!"
           "\nDesistências e fugas não contam como vitórias. PE significa Pontos de experiência do treinador; Pokémon recebem XP."))))

(defn moedas [cid pid]
  (get (conta cid pid) "moedas"))

(defn creditar!
  "Credita as moedas de vitória pro pid nesse chat (chamado ao fechar uma
  batalha de !pokemon). Retorna a quantidade creditada."
  [cid pid]
  (alterar-conta! cid pid
         (fn [c] (-> (or c {"moedas" 0 "inventario" {}})
                     (update "moedas" + moedas-por-vitoria))))
  moedas-por-vitoria)

(defn creditar-quantia!
  "Credita uma recompensa variável e retorna a quantidade adicionada."
  [cid pid quantidade]
  (alterar-conta! cid pid
         (fn [c] (-> (or c {"moedas" 0 "inventario" {}})
                     (update "moedas" + quantidade))))
  quantidade)

(defn- item-por-status [status]
  (some (fn [[chave info]] (when (= (:status info) status) chave)) itens))

(defn usar-cura!
  "Se pid tiver, nesse chat, uma cura em estoque pro status dado, consome 1
  unidade e retorna true; senão não mexe em nada e retorna false."
  [cid pid status]
  (if-let [chave (item-por-status status)]
    (if (pos? (get-in (conta cid pid) ["inventario" chave] 0))
      (do (alterar-conta! cid pid #(update-in % ["inventario" chave] dec))
          true)
      false)
    false))

(defn tem-mt? [cid pid]
  (pos? (get-in (conta cid pid) ["inventario" "mt"] 0)))

(defn consumir-mt! [cid pid]
  (when (tem-mt? cid pid)
    (alterar-conta! cid pid #(update-in % ["inventario" "mt"] dec))
    true))

(defn usar-pocao!
  "Se pid tiver, nesse chat, uma poção de vida em estoque, consome 1 unidade
  e retorna a fração de HP máximo que ela cura (ex.: 0.4 = 40%); senão não
  mexe em nada e retorna nil."
  ([cid pid] (usar-pocao! cid pid "pocao"))
  ([cid pid chave]
   (when (and (contains? #{"pocao" "pocao-maxima"} chave)
              (pos? (get-in (conta cid pid) ["inventario" chave] 0)))
     (alterar-conta! cid pid #(update-in % ["inventario" chave] dec))
     (:cura-hp (get itens chave)))))

(defn usar-fruta!
  "Consome a fruta indicada e retorna quantos pontos de motivação ela recupera."
  [cid pid chave]
  (when (and (contains? #{"fruta" "fruta-dourada"} chave)
             (pos? (quantidade-item cid pid chave)))
    (alterar-conta! cid pid #(update-in % ["inventario" chave] dec))
    (:motivacao (get itens chave))))

(defn- formatar-item [chave {:keys [nome emoji preco evolucao exclusivo-diario]}]
  (str emoji " *" nome "* (`" chave "`) - "
       (cond preco (str preco " moedas") evolucao "recompensa de ginásio"
             exclusivo-diario "recompensa da sequência diária" :else "exclusivo das missões")))

(defn- formatar-inventario [inventario]
  (let [posse (filter (fn [[_ qtd]] (pos? qtd)) inventario)]
    (if (seq posse)
      (str/join ", " (map (fn [[chave qtd]] (str (get-in itens [chave :emoji]) " " qtd "x " (get-in itens [chave :nome]))) posse))
      "nenhuma ainda")))

(defn mochila [contexto acao]
  (let [cid (:chat-id contexto)
        pid (:player-id contexto)]
    (case (normalizar-item acao)
      "kit" (resgatar-bolas! cid pid true)
      "diario" (resgatar-bonus-diario! cid pid)
      "resgatar" (resgatar-bolas! cid pid false)
      (str "🎒 *Mochila* — " (ocupacao cid pid) "/" (capacidade cid pid) " unidades\n"
           (formatar-inventario (get (conta cid pid) "inventario"))
           "\n\nCada unidade ocupa uma vaga. Itens equipados não ocupam espaço."
           "\nExpansão: +50 vagas por 200 moedas — " config/prefix "loja comprar mochila."
           "\nKit inicial: 10 Pokébolas — " config/prefix "mochila kit."
           "\nBônus diário: 3 a 7 bolas conforme a sequência; a cada 7 dias, Fruta Dourada — " config/prefix "mochila diario."
           "\nNo PvP, cada nocaute rende uma bola definida pela liga."
           "\nRecompensas pendentes: " (let [texto (texto-recompensas (recompensas-pendentes (conta cid pid)))] (if (str/blank? texto) "nenhuma" texto))
           " — " config/prefix "mochila resgatar."
           "\nGanhe PE e bolas nas missões: " config/prefix "missoes."))))

(defn detalhes
  "!loja detalhes <item> - explica o efeito e como usar um item."
  [nome-item]
  (let [chave (normalizar-item nome-item)]
    (if-let [{:keys [nome emoji preco descricao equipavel expansao]} (get itens chave)]
      (str "🔎 *Detalhes do item*\n\n"
           emoji " *" nome "* (`" chave "`)\n"
           (cond
             preco (str "💰 Preço: " preco " moedas\n")
             (some #{chave} bolas) "🎁 Não está à venda; obtida por recompensas.\n"
             :else "🎁 Exclusivo das missões; não está à venda.\n")
           "🏷️ Tipo: " (cond expansao "Melhoria permanente" equipavel "Equipável" :else "Consumível") "\n"
           "✨ Efeito: " descricao
           (when equipavel
             (str "\n\nEquipe com " config/prefix "pokemon equipar <número> " chave ".")))
      (str "❓ Item \"" nome-item "\" não encontrado. Use " config/prefix
           "loja para ver os nomes e " config/prefix "loja detalhes <item> para consultar um efeito."))))

(defn catalogo-compravel []
  ;; A mesma lista ordenada gera o texto e os comandos do menu.
  (->> itens
       (filter (fn [[_ info]] (and (:preco info)
                                   (not (:exclusivo-missoes info))
                                   (not (:exclusivo-diario info))
                                   (not (:evolucao info)))))
       (sort-by first)
       vec))

(defn opcoes-compra []
  (into {} (map-indexed (fn [i [chave _]]
                         [(str (inc i)) (str "loja comprar " chave)])
                       (catalogo-compravel))))

(defn ver-loja
  "!loja - mostra o catálogo e o saldo de moedas de quem chamou nesse chat."
  [contexto]
  (let [cid (:chat-id contexto)
        pid (:player-id contexto)
        c   (conta cid pid)
        catalogo (map-indexed (fn [i [chave info]]
                                (str (inc i) ". " (formatar-item chave info)))
                              (catalogo-compravel))]
    (str "🏪 *Loja do tio " config/bot-name "*\n\n"
         "💰 Suas moedas: " (get c "moedas") "\n\n"
         "*Catálogo de itens:*\n"
         (str/join "\n" catalogo)
         "\n\n*Serviços por comando:*\n💻 +50 vagas Pokémon — " (preco-expansao-pc cid pid) " moedas: " config/prefix "pk espaco comprar."
         "\n📚 Reaprender golpe — " preco-reaprender " moedas. Use " config/prefix "pokemon reaprender."
         "\n\n🛒 " config/prefix "pk <número> compra 1 unidade do item escolhido.\n"
         "Use " config/prefix "loja comprar <item> (ex.: " config/prefix "loja comprar atadura).\n"
         "Para saber o efeito, use " config/prefix "loja detalhes <item>.\n"
         "📖 Ajuda: " config/prefix "loja ajuda.")))

(defn ver-loja-com-imagem
  "Retorna o catálogo como legenda de uma imagem da loja; se o asset não puder
  ser processado, mantém a resposta textual para o comando continuar útil."
  [contexto]
  (let [texto (ver-loja contexto)]
    (-> (p/let [buffer (imagens/arte! imagem-loja)]
          {:media (boundary/midia "image/jpeg" buffer "loja-pokemon.jpg")
           :texto texto})
        (p/catch (fn [erro]
                   (js/console.error "Erro ao montar imagem da loja Pokémon:" erro)
                   texto)))))

(defn comprar
  "!loja comprar <item> - compra 1 unidade do item pro inventário de quem
  chamou, nesse chat, se tiver moedas suficientes."
  [contexto nome-item]
  (let [cid   (:chat-id contexto)
        pid   (:player-id contexto)
        chave (normalizar-item nome-item)]
    (if-let [item (get itens chave)]
      (let [saldo (moedas cid pid)]
        (cond
          (nil? (:preco item))
          "🎁 Esse item não está à venda; obtenha-o por recompensas ou missões."
          (some #{chave} bolas)
          (str "🎁 Pokébolas não são vendidas. Ganhe em " config/prefix "missoes, "
               config/prefix "mochila diario ou por nocautes no PvP.")
          (:evolucao item)
          "🏛️ Ganhe pedras na primeira vitória de cada ginásio e em revanches diárias: !pokemon ginasio."
          (:exclusivo-missoes item)
          (str "💎 Esse item só pode ser ganho nas missões. Veja " config/prefix "missoes.")
          (and (not (:expansao item)) (not (cabe? cid pid 1)))
          (str "🎒 Mochila cheia! Use itens ou compre mais 50 vagas com " config/prefix "loja comprar mochila (200 moedas).")
          (>= saldo (:preco item))
          (let [msg (str "✅ Comprou " (:emoji item) " *" (:nome item) "*! Saldo: "
                         (- saldo (:preco item)) " moedas.")]
            (p/then (alterar-conta! cid pid
                                    (fn [c] (cond-> (update (or c {"moedas" 0 "inventario" {}})
                                                            "moedas" - (:preco item))
                                              (:expansao item) (update "expansoes-mochila-50" (fnil inc 0))
                                              (not (:expansao item)) (update-in ["inventario" chave] (fnil inc 0)))))
                    (fn [_] msg)))
          :else
          (str "❌ Moedas insuficientes! Você tem " saldo ", " (:nome item) " custa " (:preco item) ".")))
      (str "❓ Item \"" nome-item "\" não encontrado. Use " config/prefix "loja pra ver o catálogo."))))

(defn registrar-semanal! [cid pid evento valores]
  (alterar-conta! cid pid
         #(missoes/registrar-semanal (or % {}) (missoes/semana-de (missoes/dia-atual)) evento valores)))

(defn ver-semanais [cid pid resgatar?]
  (let [semana (missoes/semana-de (missoes/dia-atual))
        [nova moedas bolas-base] (missoes/resgatar-semanais (conta cid pid) semana)
        bolas (cond-> bolas-base (and resgatar? (pos? moedas))
                (update (sortear-item-evolucao) (fnil inc 0)))]
    (when resgatar?
      ;; Progresso, marcação do resgate e saldo são persistidos juntos.
      (trocar-conta! cid pid (guardar-recompensas nova bolas (nivel-atual cid pid))))
    (let [estado (missoes/estado-semanal (conta cid pid) semana)]
      (str "📅 *Missões semanais — semana de " semana "*\n"
           (when resgatar? (str "💰 " moedas " moedas resgatadas.\n🎁 " (texto-recompensas bolas) "\n"))
           (str/join "\n" (for [{:keys [id objetivo meta moedas] :as missao} missoes/semanais]
                             (str (if (some #{id} (get estado "resgatadas")) "✅ " "🎯 ")
                                  objetivo ": " (missoes/progresso-semanal estado id) "/" meta
                                  " • " moedas " moedas • " (texto-recompensas (missoes/recompensas-bolas missao)))))
           "\nReinicia na segunda-feira (" config/missoes-timezone ")."
           "\nResgate até o fim da semana: " config/prefix "missoes semanais resgatar."))))

(defn premiar-raid! [cid pid dia]
  ;; Uma recompensa por identificador de raide, gravada junto com as moedas.
  (when (not= dia (get (conta cid pid) "raid-premiada-dia"))
    (alterar-conta! cid pid
           #(-> (or % {}) (assoc "raid-premiada-dia" dia) (update "moedas" (fnil + 0) 50)))
    50))

(defn enviar-presente! [cid pid alvo nivel]
  (cond
    (nil? alvo) (str "🎁 Use " config/prefix "presente @amigo. Compre cartões com " config/prefix "loja comprar cartao-presente.")
    (= pid alvo) "❌ Escolha um amigo para receber o presente."
    (not (pos? (quantidade-item cid pid "cartao-presente"))) "🎁 Você precisa de um cartão: !loja comprar cartao-presente."
    :else
    (let [sorteio (rand-int 100)
          recompensa (cond (< sorteio 10) {"ultra-bola" 1}
                           (< sorteio 35) {"grande-bola" 2}
                           :else {"pokebola" 3})
          dia (missoes/dia-atual)]
      (let [texto {:texto (str "🎁 Presente enviado para @" (first (str/split alvo #"@")) "!\n"
                               (texto-recompensas recompensa)
                               "\nBolas sem espaço ficam pendentes em !mochila resgatar."
                               "\n📋 Envio registrado nas missões diárias e semanais.")
                   :mentions [alvo]}]
        (p/let [_ (alterar-conta! cid pid
                                  (fn [c]
                                    (-> c
                                        (update-in ["inventario" "cartao-presente"] dec)
                                        (#(-> %
                                              (missoes/registrar-evento dia "presentes" nivel)
                                              (missoes/registrar-semanal (missoes/semana-de dia) "presentes" []))))))
                _ (alterar-conta! cid alvo
                                  #(guardar-recompensas (or % {"moedas" 0 "inventario" {}})
                                                        recompensa (nivel-atual cid alvo)))]
          texto)))))
