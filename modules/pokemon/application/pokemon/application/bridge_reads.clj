(ns pokemon.application.bridge-reads
  "Leituras expostas pela bridge HTTP (M4.1/M4.3/M4.5).
  Sem escritas; sem operações administrativas; DTOs neutros (sem WhatsApp)."
  (:require             [pokemon.application.adventure :as adv]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.joy :as joy-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.shop :as shop-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.joy :as joy]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.xp :as xp]))

(defn- require-player!
  [store chat-id player-id]
  (or (ports/get-player store chat-id player-id)
      (throw (err/error :player_not_found
                        {:chat-id chat-id :player-id player-id}))))

(defn- resolve-save-id!
  [store chat-id player-id save-id]
  (let [slot (or save-id (ports/active-save-id store chat-id player-id))]
    (when-not slot
      (throw (err/error :no_active_save
                        {:chat-id chat-id :player-id player-id})))
    (when-not (ports/get-save store chat-id player-id slot)
      (throw (err/error :save_not_found
                        {:chat-id chat-id :player-id player-id :save-id slot})))
    slot))

(defn- pokemon-dto
  [p]
  (select-keys p [:collection-index :id-pokemon :species :nome :nickname
                  :shiny :nivel :hp :hp-atual :status :in-party :tipos
                  :raridade :xp-desde-nivel :ataque :defesa :atq-esp :def-esp
                  :veloc :amizade :imagem :imagem-shiny :altura :golpes
                  :habilidade]))

(defn- save-profile
  [save]
  (when save
    (let [base (select-keys save [:slot-id :coins :revision :trainer-xp :trainer-level
                                  :xp-atual :xp-necessario :ativo-index
                                  :vitorias-treinador :pe-ginasios :pe-raids
                                  :xp-insignias :xp-missoes :sequencia-capturas
                                  :maior-sequencia-capturas])
          por-componentes (xp/perfil-treinador save)
          xp-stored (long (:trainer-xp save 0))
          xp (max (long (:xp por-componentes 0)) xp-stored)
          prog (if (pos? (long (:xp por-componentes 0)))
                 por-componentes
                 (xp/progresso-treinador xp))]
      (merge base
             {:nivel (:nivel prog (:trainer-level save 1))
              :xp xp
              :xp-atual (:xp-atual prog (:xp-atual save 0))
              :xp-necessario (:xp-necessario prog (:xp-necessario save 5))}))))

(defn trainer
  "Perfil do treinador + save ativo (DTO neutro para apresentação no Node)."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        ativo-idx (long (:ativo-index save 0))
        ativo (when (and colecao (seq colecao) (< ativo-idx (count colecao)))
                (nth colecao ativo-idx))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player)
     :active-battle-id (:active-battle-id player)
     :gym-defense-count (:gym-defense-count player 0)
     :profile (save-profile save)
     :ativo-index ativo-idx
     :ativo (when ativo (pokemon-dto ativo))
     :collection-count (count colecao)}))

(defn collection
  "Coleção de um save (ativo se save-id omitido)."
  [store chat-id player-id save-id]
  (require-player! store chat-id player-id)
  (let [slot (resolve-save-id! store chat-id player-id save-id)
        save (ports/get-save store chat-id player-id slot)
        items (ports/list-collection store chat-id player-id slot)]
    {:chat-id chat-id
     :player-id player-id
     :save-id slot
     :fence-generation (:fence-generation (require-player! store chat-id player-id))
     :ativo-index (long (:ativo-index save 0))
     :trainer-level (:trainer-level save 1)
     :count (count items)
     :collection (mapv pokemon-dto items)}))

(defn active-save
  "Save ativo + party + inventário (snapshot de leitura)."
  [store chat-id player-id]
  (let [snap (trainer/active-save store chat-id player-id)
        save (:save snap)
        player (:player snap)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player)
     :save (save-profile save)
     :party (mapv pokemon-dto (:party snap))
     :inventory (:inventory snap)}))

(defn- encounter-pokemon-dto
  [p]
  (when p
    (-> (select-keys p [:nome :species :nickname :shiny :shiny? :nivel :hp :hp-atual
                        :raridade :tipos :ataque :defesa :atq-esp :def-esp :veloc
                        :imagem :imagem-shiny :altura :golpes :status :habilidade])
        (cond-> (:shiny? p) (assoc :shiny true)))))

(defn- area-visual
  "Cores/emoji do bioma para composição de arena no Node (sem Cassandra)."
  [area-id]
  (when-let [a (mundo/obter-area (str area-id))]
    (hunt/area-dto a)))

(defn hunt-plan
  "Plano de caça + elegibilidade (RNG tipos/surto no servidor)."
  [store chat-id player-id opts]
  (require-player! store chat-id player-id)
  (let [plano (hunt-app/plan store chat-id player-id opts)]
    ;; API sem `?` nas chaves (encoder camelCase).
    (-> plano
        (dissoc :surto? :roll-tipos :roll-surto)
        (assoc :surto (boolean (:surto? plano))
               :roll-tipos (:roll-tipos plano)
               :roll-surto (:roll-surto plano)))))

(defn active-encounter
  "Encontro ativo (batalha em curso ou aguardando captura)."
  [store chat-id player-id]
  (require-player! store chat-id player-id)
  (let [player (ports/get-player store chat-id player-id)
        battle (ports/active-battle store chat-id player-id)
        inv (when-let [slot (:active-save-id player)]
              (ports/list-inventory store chat-id player-id slot))]
    (if-not battle
      {:chat-id chat-id
       :player-id player-id
       :active-save-id (:active-save-id player)
       :fence-generation (:fence-generation player)
       :encounter nil}
      (let [chance-base (:chance-base-captura battle)
            aguardando? (boolean (:aguardando-captura? battle))]
        {:chat-id chat-id
         :player-id player-id
         :active-save-id (:active-save-id player)
         :fence-generation (:fence-generation player)
         :encounter
         {:battle-id (:battle-id battle)
          :save-id (:save-id battle)
          :kind (name (:kind battle :wild))
          :gym-id (:gym-id battle)
          :gym-nome (:gym-nome battle)
          :lider-nome (:lider-nome battle)
          :active (boolean (:active? battle))
          :aguardando-captura aguardando?
          :phase (:phase battle)
          :outcome (:outcome battle)
          :turn-index (:turn-index battle 0)
          :tentativas-captura (long (:tentativas-captura battle 0))
          :chance-base-captura chance-base
          :hp (:hp battle)
          :status (:status battle)
          :bioma (:bioma battle)
          :clima (:clima battle)
          :area (area-visual (:bioma battle))
          :somente-batalha (boolean (:somente-batalha? battle))
          :last-switch (:last-switch battle)
          :reservas-x (count (get-in battle [:reservas :x] []))
          :reservas-o (count (get-in battle [:reservas :o] []))
          :pokemon-x (encounter-pokemon-dto (get-in battle [:pokemons :x]))
          :pokemon-o (encounter-pokemon-dto (get-in battle [:pokemons :o]))
          :inventory (or inv [])}}))))

(defn- occupation-dto
  [ocup agora]
  (when ocup
    (let [mots (gym/motivacoes ocup agora)
          time (gym/time-defensor ocup agora)]
      {:gym-id (:gym-id ocup)
       :player-id (:pid ocup)
       :save-id (:save-id ocup)
       :nome (:nome ocup)
       :desde (:desde ocup)
       :revision (:revision ocup 0)
       :motivacao mots
       :motivacao-em (:motivacao-em ocup)
       :time (mapv #(-> (encounter-pokemon-dto %)
                        (assoc :motivacao-ginasio (:motivacao-ginasio %)))
                   time)})))

(defn- gym-catalog-row
  [g insignias ocup agora]
  (let [id (:id g)
        unlocked? (gym/desbloqueado? insignias id)]
    {:id id
     :nome (:nome g)
     :lider-npc (:lider g)
     :nivel (:nivel g)
     :item (:item g)
     :time-npc (:time g)
     :desbloqueado unlocked?
     :insignia (get insignias id)
     :ocupacao (when ocup (occupation-dto ocup agora))}))

(defn gyms-overview
  "Menu de ginásios: catálogo + unlock + ocupações do chat."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        insignias (or (:ginasios save) {})
        agora (long (or agora-ms (System/currentTimeMillis)))
        rows (mapv (fn [g]
                     (let [ocup (gym-app/get-occupation store chat-id (:id g))]
                       (gym-catalog-row g insignias ocup agora)))
                   gym/ginasios)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :gym-defense-count (:gym-defense-count player 0)
     :insignias insignias
     :desafio-habilitado true
     :desafio-bloqueio nil
     :gyms rows}))

(defn gym-detail
  "Detalhe de um ginásio + time defensor com motivação."
  [store chat-id player-id gym-id {:keys [agora-ms]}]
  (let [g (or (gym/obter-ginasio (str gym-id))
              (throw (err/error :unknown_gym {:gym-id gym-id})))
        overview (gyms-overview store chat-id player-id {:agora-ms agora-ms})
        row (some #(when (= (:id g) (:id %)) %) (:gyms overview))]
    (merge (select-keys overview [:chat-id :player-id :active-save-id
                                  :fence-generation :gym-defense-count
                                  :insignias :desafio-habilitado :desafio-bloqueio])
           {:gym row})))

(defn gym-team
  "Escalação de ginásio do save ativo (índices + Pokémon)."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        idxs (vec (or (:time-ginasio save) []))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        membros (mapv (fn [i]
                        (when (and colecao (<= 0 i (dec (count colecao))))
                          (assoc (pokemon-dto (nth colecao i))
                                 :collection-index i)))
                      idxs)]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :indices idxs
     :membros (vec (remove nil? membros))
     :completo? (and (= 3 (count idxs))
                     (= 3 (count (set idxs)))
                     (= 3 (count (remove nil? membros))))}))

(defn- raid-participant-dto
  [pid p viewer-id]
  (cond-> {:player-id pid
           :nome (:nome p)
           :hp (:hp p)
           :dano (:dano p 0)
           :collection-index (:collection-index p)
           :pokemon (encounter-pokemon-dto (:pokemon p))
           :voce? (= pid viewer-id)}
    (= pid viewer-id)
    (assoc :save-id (:save-id p)
           :fence-generation (:fence-generation p))))

(defn- raid-dto
  [raid player-id agora]
  (when raid
    (let [fase (str (:fase raid))
          ativa (or (raid/ativa? raid agora) (= "vitoria" fase))
          parts (:participantes raid {})
          viewer (get parts player-id)
          cap (get-in raid [:capturas player-id])]
      {:raid-id (:raid-id raid)
       :chat-id (:chat-id raid)
       :ginasio (:ginasio raid)
       :nome-ginasio (:nome-ginasio raid)
       :nivel-ginasio (:nivel-ginasio raid)
       :liga (:liga raid)
       :fase fase
       :ativa ativa
       :expira (:expira raid)
       :captura-expira (:captura-expira raid)
       :hp-chefe (:hp-chefe raid)
       :hp-max (:hp-max raid)
       :vez (:vez raid)
       :sua-vez? (= player-id (:vez raid))
       :turn-index (:turn-index raid 0)
       :ordem (:ordem raid [])
       :n-participantes (count (:ordem raid []))
       :inscrito? (boolean viewer)
       :chefe (encounter-pokemon-dto (:chefe raid))
       :participantes
       (mapv (fn [pid]
               (raid-participant-dto pid (get parts pid) player-id))
             (:ordem raid []))
       :captura-pendente
       (boolean (and player-id (raid/captura-pendente? raid player-id agora)))
       :captura (when cap
                  {:tentativas (:tentativas cap 0)
                   :capturou (boolean (:capturou cap))})
       :rewards-paid (boolean (:rewards-paid? raid))})))

(defn- raid-visivel?
  "Inscrições/combate não expirados, ou vitória ainda na janela de captura."
  [raid agora]
  (when raid
    (let [fase (str (:fase raid))]
      (or (raid/ativa? raid agora)
          (and (= "vitoria" fase)
               (some-> (:captura-expira raid)
                       (as-> t (< (long agora) (long t)))))))))

(defn active-raid
  "Raid ativa (inscrições/combate) ou janela de captura pós-vitória."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        agora (long (or agora-ms (System/currentTimeMillis)))
        raid0 (raid-app/active-raid store chat-id)
        raid (when (raid-visivel? raid0 agora) raid0)
        inv (when-let [slot (:active-save-id player)]
              (ports/list-inventory store chat-id player-id slot))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :agora agora
     :raid (raid-dto raid player-id agora)
     :inventory (or inv [])}))

(defn raid-eligible-pokemon
  "Índices da coleção aptos para a liga da raid ativa (texto !pk raide time)."
  ([store chat-id player-id]
   (raid-eligible-pokemon store chat-id player-id {}))
  ([store chat-id player-id {:keys [agora-ms]}]
   (let [player (require-player! store chat-id player-id)
         slot (:active-save-id player)
         agora (long (or agora-ms (System/currentTimeMillis)))
         raid0 (raid-app/active-raid store chat-id)
         raid (when (raid-visivel? raid0 agora) raid0)
         _ (when-not (and raid (= "inscricoes" (str (:fase raid))))
             (throw (err/error :raid_not_open {:fase (:fase raid)})))
         colecao (when slot (ports/list-collection store chat-id player-id slot))
         aptos (filterv
                (fn [p]
                  (and (pos? (long (:hp-atual p 0)))
                       (raid/pokemon-elegivel-liga? (:liga raid) p)
                       (some (fn [g]
                               (let [c (or (:classe g) (get g "classe"))
                                     poder (or (:poder g) (get g "poder") 0)]
                                 (and (contains? #{"fisico" "especial" :fisico :especial} c)
                                      (pos? (long poder)))))
                             (or (:golpes p) []))))
                (or colecao []))]
     {:chat-id chat-id
      :player-id player-id
      :liga (:liga raid)
      :raid-id (:raid-id raid)
      :aptos (mapv #(assoc (pokemon-dto %)
                           :collection-index (:collection-index %))
                   aptos)})))

(defn- bed-dto
  [bed agora]
  (let [pronto? (<= (long (:pronto-em bed 0)) (long agora))
        poke (:pokemon bed)]
    {:bed-id (:bed-id bed)
     :pronto-em (:pronto-em bed)
     :pronto pronto?
     :restante-ms (max 0 (- (long (:pronto-em bed 0)) (long agora)))
     :pokemon (pokemon-dto poke)}))

(defn joy-status
  "Enfermaria do save ativo + feridos na coleção (1-based na apresentação Node)."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        agora (long (or agora-ms (System/currentTimeMillis)))
        beds (when slot (joy-app/list-beds store chat-id player-id slot))
        colecao (when slot (ports/list-collection store chat-id player-id slot))
        feridos (filterv #(joy/ferido? %) (or colecao []))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :agora agora
     :tempo-tratamento-minutos joy/tempo-tratamento-minutos
     :enfermaria (mapv #(bed-dto % agora) (or beds []))
     :prontos (count (filter #(<= (long (:pronto-em % 0)) agora) (or beds [])))
     :em-tratamento (count (filter #(> (long (:pronto-em % 0)) agora) (or beds [])))
     :feridos (mapv #(assoc (pokemon-dto %)
                            :collection-index (:collection-index %))
                    feridos)}))

(defn- egg-dto
  [o agora]
  (let [cat (name (or (:category o) :comum))
        state (name (or (:state o) :inventory))
        due (:due-at o)
        restante (when (and due (= "incubating" state))
                   (max 0 (- (long due) (long agora))))]
    {:egg-id (:egg-id o)
     :category cat
     :state state
     :species (:species o)
     :points (:points o 0)
     :points-goal (:points-goal o 0)
     :time-ms (:time-ms o 0)
     :time-goal-ms (:time-goal-ms o 0)
     :due-at due
     :restante-ms restante
     :incubator-id (:incubator-id o)
     :save-id (:save-id o)
     :fence-generation (:fence-generation o)
     :pronto (eggs/pronto? o)}))

(defn- incubator-dto
  [i]
  {:incubator-id (:incubator-id i)
   :kind (name (or (:kind i) :permanente))
   :uses (:uses i)
   :bonus-points (:bonus-points i 0)
   :active-egg-id (:active-egg-id i)
   :livre (nil? (:active-egg-id i))})

(defn adventure-status
  "Conta Adventure Sync (ovos/incubadoras). Tempo avançado só no DTO (lazy)."
  [store chat-id player-id {:keys [agora-ms]}]
  (let [player (require-player! store chat-id player-id)
        agora (long (or agora-ms (System/currentTimeMillis)))
        _ (adv/ensure-account! store chat-id player-id)
        acc (or (adv/get-account store chat-id player-id) (eggs/estado-inicial))
        estado (eggs/avancar-tempo acc agora)
        eggs-l (:eggs estado [])]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id (:active-save-id player)
     :fence-generation (:fence-generation player 0)
     :agora agora
     :limite-inventario eggs/limite-inventario
     :inventario-count (eggs/inventariados-count estado)
     :eggs (mapv #(egg-dto % agora) eggs-l)
     :incubators (mapv incubator-dto (:incubators estado []))
     :prontos (count (filter #(contains? #{:ready-to-hatch :delivery-pending}
                                         (:state %))
                             eggs-l))
     :categorias (into {}
                       (map (fn [[k v]]
                              [(name k) {:tempo-ms (:tempo-ms v)
                                         :pontos (:pontos v)}])
                            eggs/categorias))}))

(defn shop-catalog
  "Catálogo à venda (preços canônicos) + saldo do save ativo."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        inv (when slot (ports/list-inventory store chat-id player-id slot))
        cap (when save (shop/capacidade-mochila-save save))
        occ (shop/ocupacao-mochila (or inv []))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :coins (long (:coins save 0))
     :capacidade-mochila cap
     :ocupacao-mochila occ
     :livres (when cap (max 0 (- (long cap) occ)))
     :itens (mapv (fn [it]
                    {:item-id (:item-id it)
                     :nome (:nome it)
                     :emoji (:emoji it)
                     :preco (:preco it)
                     :descricao (:descricao it)
                     :expansao (boolean (:expansao? it))})
                  (shop-app/catalog))}))

(defn inventory-bag
  "Inventário do save ativo + capacidade da mochila."
  [store chat-id player-id]
  (let [player (require-player! store chat-id player-id)
        slot (:active-save-id player)
        save (when slot (ports/get-save store chat-id player-id slot))
        inv (when slot (ports/list-inventory store chat-id player-id slot))
        cap (when save (shop/capacidade-mochila-save save))
        occ (shop/ocupacao-mochila (or inv []))
        linhas (mapv (fn [{:keys [item-id qty]}]
                       (let [meta (or (shop/item item-id) {})]
                         {:item-id item-id
                          :qty (long qty)
                          :nome (:nome meta item-id)
                          :emoji (:emoji meta "📦")}))
                     (filter #(pos? (long (:qty % 0))) (or inv [])))]
    {:chat-id chat-id
     :player-id player-id
     :active-save-id slot
     :fence-generation (:fence-generation player 0)
     :coins (long (:coins save 0))
     :capacidade-mochila cap
     :ocupacao-mochila occ
     :livres (when cap (max 0 (- (long cap) occ)))
     :expansoes-mochila-50 (long (:expansoes-mochila-50 save 0))
     :itens linhas}))
