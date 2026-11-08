(ns pokemon.domain.raid
  "Raids cooperativas — regras puras (lobby Fase 2 + combate M3.6).
  Party: até `max-party` combatentes por jogador; desmaio troca reserva
  automaticamente. Dano de raid aplica efetividade de tipos uma vez."
  (:require [clojure.string :as str]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.rng :as rng]
            [pokemon.domain.types :as types]))

(def max-party 3)

;; Lobby Fase 2 (abertura manual cooperativa).
;; Fase 12: duração padrão 5 min (raides já abertas preservam :expira gravado).
;; Fase 12.7.1: lobby dinâmico por categoria em raids automáticas.
(def duracao-inscricoes (* 5 60 1000))
(def max-duracao-inscricoes (* 30 60 1000))
(def max-participantes 5)
;; Mínimo real para iniciar combate (manual e auto). Recomendados são só UX.
(def min-participantes 1)

(def duracao-combate (* 30 60 1000))
(def duracao-captura (* 30 60 1000))
(def intervalo-proxima (* 6 60 60 1000))
;; Proposta A (aprovada): HP por jogador 220→320; variação 85–100% na base.
(def hp-por-jogador 320)
(def dano-variacao-min-pct 85)
(def dano-variacao-span 16) ; 85..100 inclusive → roll [0,16)
(def max-tentativas-captura 3)
(def recompensa-moedas 50)
(def recompensa-pe 6)
(def recompensa-xp-pokemon 6)
;; Derrota com participação efetiva (dano > 0): 50% PE/XP da vitória, sem economia.
;; Arredondamento: quot (floor) — 50% de 6 → 3. Determinístico e estável.
(def recompensa-moedas-derrota 0)
(def recompensa-pe-derrota (quot recompensa-pe 2))
(def recompensa-xp-pokemon-derrota (quot recompensa-xp-pokemon 2))

(defn recompensas
  "Tabela canônica de liquidação. `outcome` ∈ #{:vitoria :derrota}.
  Vitória: valores integrais. Derrota: PE/XP via quot(vitória/2); moedas 0."
  [outcome]
  (case outcome
    :vitoria {:moedas recompensa-moedas
              :pe recompensa-pe
              :xp-pokemon recompensa-xp-pokemon}
    :derrota {:moedas recompensa-moedas-derrota
              :pe recompensa-pe-derrota
              :xp-pokemon recompensa-xp-pokemon-derrota}
    (throw (err/error :invalid_raid_reward_outcome {:outcome outcome}))))

(def lobby-por-categoria
  "Lobby dinâmico (auto). min-jogadores=1 em todas; recomendados só informativo."
  {"raro" {:duracao-ms (* 5 60 1000)
           :minutos 5
           :min-jogadores 1
           :recomendados 1
           :max-jogadores 5
           :estrelas "⭐"
           :label "Rara"}
   "epico" {:duracao-ms (* 10 60 1000)
            :minutos 10
            :min-jogadores 1
            :recomendados 2
            :max-jogadores 5
            :estrelas "⭐⭐⭐"
            :label "Épica"}
   "lendario" {:duracao-ms (* 20 60 1000)
               :minutos 20
               :min-jogadores 1
               :recomendados 2
               :max-jogadores 5
               :estrelas "⭐⭐⭐⭐⭐"
               :label "Lendária"}
   "mitico" {:duracao-ms (* 30 60 1000)
             :minutos 30
             :min-jogadores 1
             :recomendados 3
             :max-jogadores 5
             :estrelas "⭐⭐⭐⭐⭐⭐"
             :label "Mítica"}})

(defn lobby-params-for-categoria
  "Params de lobby para categoria (raro|epico|lendario|mitico). Fallback: raro."
  [categoria]
  (let [k (str/lower-case (str (or categoria "raro")))]
    (or (get lobby-por-categoria k)
        (get lobby-por-categoria "raro"))))

(def ligas
  "Faixas de nível por liga (espelho treinador.cljs)."
  [{:id "iniciante" :nome "Iniciante" :min 1 :max 10}
   {:id "bronze" :nome "Bronze" :min 11 :max 25}
   {:id "prata" :nome "Prata" :min 26 :max 40}
   {:id "ouro" :nome "Ouro" :min 41 :max 60}
   {:id "diamante" :nome "Diamante" :min 61 :max 100}])

(defn obter-liga
  [id]
  (some #(when (= (str id) (:id %)) %) ligas))

(defn liga-para-nivel
  "Primeira liga cuja faixa contém o nível (ginásio)."
  [nivel]
  (let [n (long (or nivel 1))]
    (or (some #(when (<= (:min %) n (:max %)) %) ligas)
        (last ligas))))

(defn max-da-raid
  [raid]
  (long (or (:max-jogadores raid) max-participantes)))

(defn min-da-raid
  "Mínimo real para iniciar (sempre ≥1). Não confundir com :recomendados (UX)."
  [raid]
  (max 1 (long (or (:min-jogadores raid) min-participantes))))

(defn recomendados-da-raid
  "Sugestão de time — nunca bloqueia início."
  [raid]
  (let [lobby (when-let [cat (or (:boss-categoria raid)
                                 (:categoria raid)
                                 (get-in raid [:chefe :raridade])
                                 (get-in raid [:chefe "raridade"]))]
                (lobby-params-for-categoria cat))]
    (long (or (:recomendados raid)
              (:recomendados lobby)
              (min-da-raid raid)))))

(defn pokemon-elegivel-liga?
  "Legado: faixa de liga era gate de entrada. A partir do hotfix de ginásio,
  qualquer nível pode entrar na raid — retorna true se há Pokémon.
  A liga do ginásio permanece só como metadado/UX de dificuldade do chefe."
  [_liga-id pokemon]
  (boolean pokemon))

(defn inscricoes-abertas?
  [raid agora]
  (and (= "inscricoes" (str (:fase raid)))
       (< (long agora) (long (:expira raid 0)))))

(defn ativa?
  [raid agora]
  (and (contains? #{"inscricoes" "combate"} (str (:fase raid)))
       (< (long agora) (long (:expira raid 0)))))

(defn lobby-ou-combate-vivo?
  "Ponteiro do chat ainda ocupa o slot (inscrições válidas ou combate)."
  [raid agora]
  (boolean
   (and raid
        (or (inscricoes-abertas? raid agora)
            (and (= "combate" (str (:fase raid)))
                 (< (long agora) (long (:expira raid 0))))))))

(defn no-ginasio?
  [raid gym-id agora]
  (and raid
       (= gym-id (:ginasio raid))
       (ativa? raid agora)))

(defn chance-captura
  [raridade]
  (get {"raro" 35 "epico" 20 "lendario" 8 "mitico" 4
        :raro 35 :epico 20 :lendario 8 :mitico 4} raridade 35))

(defn elegivel-recompensa?
  [participante]
  (pos? (long (:dano participante 0))))

(defn captura-janela-aberta?
  "Vitória ainda dentro do prazo de captura (independente do jogador)."
  [raid agora]
  (and (= "vitoria" (str (:fase raid)))
       (< (long agora) (long (:captura-expira raid 0)))))

(defn captura-pendente?
  [raid pid agora]
  (let [p (get-in raid [:participantes pid])
        cap (get-in raid [:capturas pid])]
    (and (captura-janela-aberta? raid agora)
         (elegivel-recompensa? p)
         (< (long (:tentativas cap 0)) max-tentativas-captura)
         (not (:capturou cap)))))

(defn algum-captura-pendente?
  "True se algum participante ainda pode capturar nesta janela."
  [raid agora]
  (boolean
   (some #(captura-pendente? raid % agora)
         (keys (:participantes raid {})))))

(defn slot-ativo-ocupado?
  "Ponteiro do chat ainda bloqueia nova raid de combate/lobby.
   Vitória só ocupa enquanto houver captura pendente (não trava o menu
   `!pk raid` após o jogador resolver a captura)."
  [raid agora]
  (boolean
   (and raid
        (or (lobby-ou-combate-vivo? raid agora)
            (algum-captura-pendente? raid agora)))))

(defn visivel-para?
  "O que `!pk raid` deve mostrar a este jogador.
   Vitória só aparece se *ele* ainda tem captura pendente."
  [raid agora player-id]
  (boolean
   (and raid
        (or (ativa? raid agora)
            (and player-id (captura-pendente? raid player-id agora))))))

(defn deve-liberar-ponteiro?
  "Após vitória/derrota/abandono: limpa ponteiro quando não há combate vivo
   nem captura pendente de ninguém (idempotente)."
  [raid agora]
  (boolean
   (and raid
        (not (lobby-ou-combate-vivo? raid agora))
        (not (algum-captura-pendente? raid agora)))))

(defn- golpe-dano?
  [golpe]
  (and golpe
       (contains? #{"fisico" "especial" :fisico :especial}
                  (or (:classe golpe) (get golpe "classe")))
       (pos? (long (or (:poder golpe) (get golpe "poder") 0)))))

(defn calcular-dano
  "Fórmula de raid: floor(15+0.6*poder*(atk/def)) clamp [10,160],
  depois × variação 85–100% (uma vez, RNG injetável), depois × efetividade
  de tipos (integral). Mult 0 → dano 0 (imune). Sem STAB.
  Só para golpes ofensivos (fisico/especial com poder>0) — status/fixos
  não passam por aqui (`golpe-dano?` no `atacar`).
  `rng` opcional: mapa com `:raid-damage-var` ∈ [0,16) → pct 85..100."
  ([golpe pokemon raid]
   (calcular-dano golpe pokemon raid nil))
  ([golpe pokemon raid rng]
   (let [classe (or (:classe golpe) (get golpe "classe"))
         especial? (contains? #{"especial" :especial} classe)
         ataque (long (or ((if especial? :atq-esp :ataque) pokemon)
                          (get pokemon (if especial? "atq-esp" "ataque"))
                          50))
         defesa (max 1 (long (or ((if especial? :def-esp :defesa) raid)
                                 50)))
         poder (long (or (:poder golpe) (get golpe "poder") 0))
         tipo-golpe (or (:tipo golpe) (get golpe "tipo"))
         chefe (:chefe raid)
         tipos-def (or (:tipos chefe) (get chefe "tipos") [])
         hab-def (or (:habilidade chefe) (get chefe "habilidade"))
         multiplicador (types/multiplicador-vs-tipos tipo-golpe tipos-def hab-def)
         raw (long (Math/floor (+ 15 (* 0.6 poder (/ (double ataque) defesa)))))
         base (max 10 (min 160 raw))
         var-roll (long (rng/roll (or rng {}) :raid-damage-var dano-variacao-span))
         variacao-pct (+ dano-variacao-min-pct var-roll)
         base-variada (max 1 (long (Math/round (* (double base) (/ variacao-pct 100.0)))))
         com-tipo (long (Math/round (* (double base-variada) multiplicador)))
         dano (cond
                (zero? (double multiplicador)) 0
                (pos? com-tipo) com-tipo
                :else 1)]
     {:dano (min (long (:hp-chefe raid 0)) dano)
      :multiplicador multiplicador
      :base base
      :base-variada base-variada
      :variacao-pct variacao-pct})))

(defn- media-stat
  [participantes stat]
  (let [regs (map :pokemon (vals participantes))
        n (count regs)]
    (if (zero? n)
      50.0
      (/ (double (reduce + (map #(long (or (get % stat) (get % (name stat)) 50)) regs)))
         n))))

(defn- artwork-url
  "URL estática PokeAPI (sem fetch por ataque). dex-id numérico."
  [dex-id]
  (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
       "sprites/pokemon/other/official-artwork/" dex-id ".png"))

(def ^:private chefes-ginasio
  "Templates determinísticos dos ases do catálogo A6 (última espécie do time).
   `:imagem` aponta artwork oficial com alpha — evita placeholder/caixa preta no cartão."
  {"onix" {:nome "Onix" :species "onix" :tipos ["rock" "ground"] :raridade "raro"
           :hp 70 :ataque 60 :defesa 100 :atq-esp 40 :def-esp 55 :veloc 50
           :imagem (artwork-url 95)
           :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}
                    {:nome-exibicao "Earthquake" :tipo "ground" :poder 80 :classe :fisico}]}
   "starmie" {:nome "Starmie" :species "starmie" :tipos ["water" "psychic"] :raridade "raro"
              :hp 65 :ataque 60 :defesa 70 :atq-esp 95 :def-esp 70 :veloc 100
              :imagem (artwork-url 121)
              :golpes [{:nome-exibicao "Water Gun" :tipo "water" :poder 40 :classe :especial}
                       {:nome-exibicao "Psychic" :tipo "psychic" :poder 80 :classe :especial}]}
   "raichu" {:nome "Raichu" :species "raichu" :tipos ["electric"] :raridade "raro"
             :hp 65 :ataque 80 :defesa 55 :atq-esp 90 :def-esp 70 :veloc 100
             :imagem (artwork-url 26)
             :golpes [{:nome-exibicao "Thunderbolt" :tipo "electric" :poder 80 :classe :especial}
                      {:nome-exibicao "Quick Attack" :tipo "normal" :poder 40 :classe :fisico}]}
   "vileplume" {:nome "Vileplume" :species "vileplume" :tipos ["grass" "poison"] :raridade "raro"
                :hp 80 :ataque 70 :defesa 75 :atq-esp 100 :def-esp 80 :veloc 45
                :imagem (artwork-url 45)
                :golpes [{:nome-exibicao "Petal Dance" :tipo "grass" :poder 80 :classe :especial}
                         {:nome-exibicao "Acid" :tipo "poison" :poder 40 :classe :especial}]}
   "arcanine" {:nome "Arcanine" :species "arcanine" :tipos ["fire"] :raridade "raro"
               :hp 90 :ataque 100 :defesa 75 :atq-esp 85 :def-esp 75 :veloc 90
               :imagem (artwork-url 59)
               :golpes [{:nome-exibicao "Flamethrower" :tipo "fire" :poder 80 :classe :especial}
                        {:nome-exibicao "Take Down" :tipo "normal" :poder 70 :classe :fisico}]}})

(defn boss-do-ginasio
  "Deriva liga + chefe do ginásio A6. Lança :raid_gym_unknown se id inválido."
  [ginasio-id]
  (let [g (gym/obter-ginasio (str/lower-case (str/trim (str ginasio-id))))]
    (when-not g
      (throw (err/error :raid_gym_unknown {:ginasio ginasio-id})))
    (let [species (str/lower-case (str (or (last (:time g)) "onix")))
          base (or (get chefes-ginasio species)
                   (throw (err/error :raid_boss_template_missing {:species species})))
          nivel (long (:nivel g))
          liga (liga-para-nivel nivel)
          chefe (-> (gym/escalar-nivel base nivel)
                    (assoc :species species
                           :nome (:nome base)
                           :tipos (:tipos base)
                           :raridade (:raridade base)
                           :golpes (:golpes base)
                           :hp-atual (:hp (gym/escalar-nivel base nivel)))
                    (cond-> (:imagem base) (assoc :imagem (:imagem base))))]
      {:ginasio (:id g)
       :nome-ginasio (:nome g)
       :nivel-ginasio nivel
       :liga (:id liga)
       :chefe chefe})))

(defn nova-raid
  "Abre inscrições (criação manual). Duração/limites do lobby Fase 2."
  [{:keys [raid-id chat-id ginasio nome-ginasio nivel-ginasio liga chefe agora criador
           min-jogadores max-jogadores duracao-ms]}]
  (let [agora (long agora)
        dur (long (or duracao-ms duracao-inscricoes))
        mn (long (or min-jogadores min-participantes))
        mx (long (or max-jogadores max-participantes))]
    (when-not (and (string? raid-id) (seq raid-id)
                   (string? chat-id) (seq chat-id)
                   (map? chefe) (seq (str (or (:nome chefe) (get chefe "nome") "")))
                   (pos? agora)
                   (<= 1000 dur max-duracao-inscricoes)
                   (<= 1 mn mx 6))
      (throw (err/error :raid_config_invalid
                        {:raid-id raid-id :duracao-ms dur :min mn :max mx})))
    {:raid-id raid-id
     :chat-id chat-id
     :ginasio ginasio
     :nome-ginasio (or nome-ginasio ginasio)
     :nivel-ginasio (long (or nivel-ginasio 1))
     :liga (or liga "iniciante")
     :chefe chefe
     :fase "inscricoes"
     :ordem []
     :participantes {}
     :capturas {}
     :criada-em agora
     :expira (+ agora dur)
     :min-jogadores mn
     :max-jogadores mx
     :criador criador
     :turn-index 0
     :revision 0
     :rewards-paid? false}))

(defn- assert-combatente-valido!
  [raid pokemon player-id]
  (let [hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))]
    (when-not (and pokemon (pos? hp) (some golpe-dano? (or (:golpes pokemon) [])))
      (throw (err/error :invalid_raid_pokemon {:player-id player-id})))
    ;; Sem bloqueio de nível mínimo/máximo — liga é só flavor do ginásio.
    hp))

(defn- party-slot
  [pokemon collection-index]
  (let [hp (long (or (:hp-atual pokemon) (:hp pokemon) 0))]
    {:pokemon pokemon
     :collection-index (long (or collection-index 0))
     :hp hp}))

(defn- sync-active
  "Espelha o slot ativo em :pokemon/:hp/:collection-index (compat DTO/store)."
  [part]
  (let [idx (long (:active-party-idx part 0))
        slots (vec (:party part))
        slot (nth slots idx (first slots))]
    (assoc part
           :pokemon (:pokemon slot)
           :hp (long (:hp slot 0))
           :collection-index (long (:collection-index slot 0))
           :active-party-idx idx
           :party slots)))

(defn participante-vivo?
  "True se algum membro da party (ou HP legado) ainda tem HP > 0."
  [part]
  (boolean
   (or (some #(pos? (long (:hp % 0))) (:party part))
       (pos? (long (:hp part 0))))))

(defn- avancar-party
  "Após desmaio do ativo: promove a *próxima* reserva na ordem de entrada
  (após o índice atual, com wrap). Nunca escolhe um mon arbitrário fora da
  party nem salta a ordem. Nil se esgotou."
  [part]
  (let [slots (vec (:party part))
        n (count slots)
        cur (long (:active-party-idx part 0))
        ;; Marca o ativo atual como 0 se ainda não estiver.
        slots' (if (seq slots)
                 (assoc-in slots [cur :hp] 0)
                 slots)
        next-idx (when (pos? n)
                   (first
                    (for [k (range 1 n)
                          :let [i (mod (+ cur k) n)]
                          :when (pos? (long (:hp (nth slots' i) 0)))]
                      i)))]
    (when next-idx
      (sync-active (assoc part :party slots' :active-party-idx next-idx
                          :trocou-por-desmaio? true)))))

(defn- montar-party
  "Até `max-party` slots. Aceita `:party` explícita ou um único `:pokemon`."
  [raid player-id {:keys [pokemon collection-index party]}]
  (let [slots (cond
                (seq party)
                (mapv (fn [m]
                        (let [poke (or (:pokemon m) m)
                              idx (or (:collection-index m) collection-index 0)
                              hp (assert-combatente-valido! raid poke player-id)]
                          {:pokemon poke
                           :collection-index (long idx)
                           :hp hp}))
                      (take max-party party))
                :else
                (let [hp (assert-combatente-valido! raid pokemon player-id)]
                  [(party-slot (assoc pokemon :hp-atual hp) collection-index)]))]
    (when (empty? slots)
      (throw (err/error :invalid_raid_pokemon {:player-id player-id})))
    slots))

(defn entrar
  "Inscrição. Selva até 3 combatentes (party). Em lobby, reinscrição troca a party.
   Em combate, combatente fica travado."
  [raid {:keys [player-id nome save-id fence-generation] :as opts} agora]
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (let [slots (montar-party raid player-id opts)
        part (sync-active {:nome nome
                           :save-id save-id
                           :fence-generation (long fence-generation)
                           :party slots
                           :active-party-idx 0
                           :dano 0})]
    (if (contains? (:participantes raid) player-id)
      (assoc-in raid [:participantes player-id] part)
      (do
        (when (>= (count (:ordem raid)) (max-da-raid raid))
          (throw (err/error :raid_full {:max (max-da-raid raid)})))
        (-> raid
            (update :ordem conj player-id)
            (assoc-in [:participantes player-id] part))))))

(defn sair
  "Sai durante inscrições (legado)."
  [raid player-id agora]
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_cannot_leave {:fase (:fase raid)})))
  (when-not (contains? (:participantes raid) player-id)
    (throw (err/error :not_in_raid {:player-id player-id})))
  (-> raid
      (update :ordem #(vec (remove #{player-id} %)))
      (update :participantes dissoc player-id)))

(defn sair-combate
  "Remove só o jogador em combate. Raid continua se houver vivos;
   se ninguém restar, encerra sem vitória/recompensas (:fase abandonada).
   Não exige ativa?/expira — abandono voluntário é permitido até o fim do combate."
  [raid player-id _agora]
  (when-not (= "combate" (str (:fase raid)))
    (throw (err/error :raid_cannot_leave {:fase (:fase raid)})))
  (when-not (get-in raid [:participantes player-id])
    (throw (err/error :not_in_raid {:player-id player-id})))
  (let [era-vez? (= player-id (:vez raid))
        raid' (-> raid
                  (update :ordem #(vec (remove #{player-id} %)))
                  (update :participantes dissoc player-id)
                  (update :turn-index (fnil inc 0)))
        vivos (vec (filter #(participante-vivo? (get-in raid' [:participantes %]))
                           (:ordem raid')))]
    (cond
      (empty? vivos)
      (assoc raid' :fase "abandonada" :vez nil)
      era-vez?
      (assoc raid' :vez (first vivos))
      :else raid')))

(defn vez-valida?
  "Invariante: em combate, :vez deve apontar a participante vivo na ordem."
  [raid]
  (let [vez (:vez raid)
        parts (:participantes raid {})]
    (boolean
     (and (string? vez)
          (seq vez)
          (contains? parts vez)
          (some #{vez} (:ordem raid))
          (participante-vivo? (get parts vez))))))

(defn- fator-chefe
  "Fator de dificuldade do chefe (1.0 se ausente — raids legadas)."
  [chefe]
  (let [f (or (:fator-dificuldade chefe) (get chefe "fator-dificuldade") 1.0)]
    (max 0.5 (min 2.0 (double f)))))

(defn- aplicar-inicio-combate
  [raid agora]
  (let [n (count (:ordem raid))
        parts (:participantes raid)
        fator (fator-chefe (:chefe raid))
        hp-base (long (Math/round (* (double hp-por-jogador) n fator)))
        media-def (media-stat parts :defesa)
        media-spdef (media-stat parts :def-esp)
        chefe (:chefe raid)
        boss-def (double (or (:defesa chefe) (get chefe "defesa") media-def))
        boss-spdef (double (or (:def-esp chefe) (get chefe "def-esp") media-spdef))
        ;; Mistura defesa dos jogadores com a do chefe (atributos reais influenciam).
        defesa (+ (* 0.55 media-def) (* 0.45 boss-def))
        def-esp (+ (* 0.55 media-spdef) (* 0.45 boss-spdef))
        ;; Primeira vez = primeiro da ordem com party utilizável.
        vez0 (first (filter #(participante-vivo? (get parts %)) (:ordem raid)))]
    (when-not vez0
      (throw (err/error :raid_no_eligible_turn {:ordem (:ordem raid)})))
    (assoc raid
           :fase "combate"
           :hp-chefe hp-base
           :hp-max hp-base
           :defesa defesa
           :def-esp def-esp
           :vez vez0
           :expira (+ (long agora) duracao-combate)
           :proxima (+ (long agora) intervalo-proxima)
           :turn-index 0)))

(defn iniciar
  "Início antecipado.
  Auto (`origem` = auto): qualquer inscrito — não exige criador humano.
  Manual: se há `:criador`, só ele inicia.
  Sempre exige mínimo de jogadores da raid."
  [raid player-id agora]
  (let [fase (str (:fase raid))
        auto? (= "auto" (str (:origem raid)))]
    (when-not (inscricoes-abertas? raid agora)
      (if (= "combate" fase)
        (throw (err/error :raid_already_started {:fase fase}))
        (throw (err/error :raid_not_open {:fase fase}))))
    ;; Auto: qualquer participante inscrito (criador humano irrelevante).
    (when (and (not auto?)
               (:criador raid)
               (not= player-id (:criador raid)))
      (throw (err/error :raid_not_creator {:player-id player-id})))
    (when-not (contains? (:participantes raid) player-id)
      (throw (err/error :raid_start_not_joined {:player-id player-id})))
    (when (< (count (:ordem raid)) (min-da-raid raid))
      (throw (err/error :raid_need_players
                        {:have (count (:ordem raid)) :min (min-da-raid raid)})))
    (aplicar-inicio-combate raid agora)))

(defn cancelar
  "Cancela lobby antes do combate (só criador)."
  [raid player-id agora]
  (when-not (= "inscricoes" (str (:fase raid)))
    (throw (err/error :raid_cannot_cancel {:fase (:fase raid)})))
  (when-not (inscricoes-abertas? raid agora)
    (throw (err/error :raid_not_open {:fase (:fase raid)})))
  (when (and (:criador raid) (not= player-id (:criador raid)))
    (throw (err/error :raid_not_creator {:player-id player-id})))
  (assoc raid :fase "cancelada" :expira (long agora)))

(defn expirar-inscricoes
  "Sem timer: se prazo passou em inscricoes, cancela (< min) ou inicia combate.
   Retorna {:raid :acao} onde acao é :noop|:cancelada|:combate."
  [raid agora]
  (cond
    (nil? raid)
    {:raid nil :acao :noop}

    (not= "inscricoes" (str (:fase raid)))
    {:raid raid :acao :noop}

    (< (long agora) (long (:expira raid 0)))
    {:raid raid :acao :noop}

    (< (count (:ordem raid)) (min-da-raid raid))
    {:raid (assoc raid :fase "cancelada") :acao :cancelada}

    :else
    {:raid (aplicar-inicio-combate raid agora) :acao :combate}))

(defn- proxima-vez
  [raid pid]
  (let [ordem (:ordem raid)
        vivos (set (filter #(participante-vivo? (get-in raid [:participantes %])) ordem))
        depois (concat (rest (drop-while #(not= pid %) ordem))
                       (take-while #(not= pid %) ordem)
                       [pid])]
    (first (filter vivos depois))))

(defn- aplicar-revide-part
  "Aplica revide no ativo; se desmaiar e houver reserva, troca automaticamente."
  [part revide]
  (let [idx (long (:active-party-idx part 0))
        slots (vec (or (:party part)
                       [(party-slot (:pokemon part) (:collection-index part 0))]))
        slots (if (seq (:party part)) slots
                  [(assoc (first slots) :hp (long (:hp part 0)))])
        hp0 (long (:hp (nth slots idx) 0))
        hp' (max 0 (- hp0 (long revide)))
        slots' (assoc-in slots [idx :hp] hp')
        part' (sync-active (assoc part :party slots' :active-party-idx idx
                                  :trocou-por-desmaio? false))]
    (if (pos? hp')
      part'
      (or (avancar-party part')
          (sync-active (assoc part' :party slots' :hp 0))))))

(defn atacar
  "Aplica um ataque. Retorna {:raid :dano :revide :venceu? :derrota? :multiplicador
   :trocou? :golpe :variacao-pct :base-variada}.
   Desmaio do ativo com reserva → troca, raid continua.
   Derrota só quando nenhum participante tem party utilizável.
   `rng` opcional — variação de dano sorteada **uma vez** aqui; retries HTTP
   reutilizam o receipt (não re-rolam)."
  ([raid player-id move-index agora]
   (atacar raid player-id move-index agora nil))
  ([raid player-id move-index agora rng]
   (when-not (and (ativa? raid agora) (= "combate" (str (:fase raid))))
     (throw (err/error :raid_not_in_combat {:fase (:fase raid)})))
   (when-not (vez-valida? raid)
     (throw (err/error :raid_turn_invalid
                       {:vez (:vez raid)
                        :ordem (:ordem raid)
                        :player-id player-id})))
   (when-not (= player-id (:vez raid))
     (throw (err/error :raid_not_your_turn {:vez (:vez raid) :player-id player-id})))
   (let [part (get-in raid [:participantes player-id])
         pokemon (:pokemon part)
         golpes (mapv battle/golpe-normalizado (or (:golpes pokemon) []))
         golpe (when (and (integer? move-index) (<= 0 move-index 3)
                          (< move-index (count golpes)))
                 (nth golpes move-index))]
     (when-not (golpe-dano? golpe)
       (throw (err/error :invalid_raid_move {:move-index move-index})))
     (let [hp-chefe-antes (long (:hp-chefe raid 0))
           calc (calcular-dano golpe pokemon raid rng)
           dano (:dano calc)
           multiplicador (:multiplicador calc)
           base (:base calc)
           ;; Autodesmaio (Explosion): após hit válido, HP do ativo → 0.
           autodesmaio? (boolean (or (:autodesmaio golpe) (get golpe "autodesmaio")))
           hp' (max 0 (- hp-chefe-antes dano))
           venceu? (zero? hp')
           max-hp-ativo (long (or (:hp pokemon) (get pokemon "hp") 100))
           revide (if venceu? 0 (max 1 (long (Math/ceil (/ (double max-hp-ativo) 5)))))
           part-apos-dano (-> part
                              (update :dano #(+ (long %) dano))
                              (aplicar-revide-part revide))
           part-final (if (and autodesmaio? (not venceu?) (pos? dano))
                        (or (avancar-party (assoc-in part-apos-dano
                                                     [:party (:active-party-idx part-apos-dano 0) :hp]
                                                     0))
                            (sync-active (assoc part-apos-dano :hp 0)))
                        part-apos-dano)
           trocou? (boolean (:trocou-por-desmaio? part-final))
           part-final (dissoc part-final :trocou-por-desmaio?)
           raid' (-> raid
                     (assoc :hp-chefe hp')
                     (assoc-in [:participantes player-id] part-final)
                     (update :turn-index (fnil inc 0)))
           vivos (filter #(participante-vivo? (get-in raid' [:participantes %]))
                         (:ordem raid'))
           derrota? (and (not venceu?) (empty? vivos))
           raid'' (cond
                    venceu? (assoc raid'
                                   :fase "vitoria"
                                   :captura-expira (+ (long agora) duracao-captura)
                                   :vez nil)
                    derrota? (assoc raid' :fase "derrota" :vez nil)
                    :else (assoc raid' :vez (proxima-vez raid' player-id)))]
       {:raid raid''
        :dano dano
        :multiplicador multiplicador
        :base base
        :base-variada (:base-variada calc)
        :variacao-pct (:variacao-pct calc)
        :hp-chefe-antes hp-chefe-antes
        :revide revide
        :venceu? venceu?
        :derrota? derrota?
        :trocou? trocou?
        :autodesmaio? (boolean (and autodesmaio? (pos? dano)))
        :golpe golpe
        :pokemon-atacante pokemon
        :pokemon-ativo (:pokemon part-final)
        :hp-ativo (:hp part-final)}))))

(defn assert-participant-fence!
  "Bloqueia callback com save/fence diferente do carimbo na inscrição."
  [participante writer-ctx]
  (when-not participante
    (throw (err/error :not_in_raid {:player-id (:player-id writer-ctx)})))
  (when (not= (:save-id participante) (:save-id writer-ctx))
    (throw (err/error :raid_save_mismatch
                      {:sealed (:save-id participante)
                       :writer (:save-id writer-ctx)})))
  (when (not= (long (:fence-generation participante))
              (long (:fence-generation writer-ctx)))
    (throw (err/error :stale_writer_fence
                      {:sealed (:fence-generation participante)
                       :writer (:fence-generation writer-ctx)})))
  true)

(defn participantes-elegiveis
  [raid]
  (filterv (fn [[_ p]] (elegivel-recompensa? p)) (:participantes raid)))
