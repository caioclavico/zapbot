(ns pokemon.domain.raid
  "Raids cooperativas — regras puras (lobby Fase 2 + combate M3.6).
  Party: exatamente `max-party` = 1 combatente por treinador. Desmaio
  encerra a participação individual (sem troca automática de reserva).
  Raids legadas com party>1: dados preservados, mas novas trocas são
  bloqueadas — só o ativo combate; faint = fora. Dano aplica efetividade."
  (:require [clojure.string :as str]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.shop :as shop]
            [pokemon.domain.damage :as dmg]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mechanics :as mechanics]
            [pokemon.domain.move-effects :as move-effects]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.rng :as rng]
            [pokemon.domain.types :as types]))

;; Regra A6 vigente: 1 Pokémon por treinador. Constante nomeada para
;; joins/montagem; `avancar-party` nunca promove reservas.
(def max-party 1)

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
         (not (:capturou cap))
         (not (:liberou cap)))))

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
  "True se o combatente ativo (slot atual) ainda tem HP > 0.
  Reservas legadas NÃO contam — regra 1-mon: só o ativo participa."
  [part]
  (let [idx (long (:active-party-idx part 0))
        slots (vec (:party part))]
    (boolean
     (cond
       (seq slots)
       (pos? (long (:hp (nth slots idx (first slots)) 0)))
       :else
       (pos? (long (:hp part 0)))))))

(defn- avancar-party
  "LEGADO / NO-OP sob a regra 1-mon: nunca promove reserva.
  Sempre retorna nil (treinador fora). Slots extras persistidos não são usados."
  [_part]
  nil)

(defn- montar-party
  "Exatamente 1 slot (`max-party`). Aceita `:party` (usa só o 1º) ou `:pokemon`.
  Slots extras em `party` são ignorados (não entram no combate)."
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
  "Inscrição. Exatamente 1 combatente por treinador. Em lobby, reinscrição
   troca o Pokémon escolhido. Em combate, combatente fica travado."
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

(defn cancelar-sistema
  "Cancela lobby por política administrativa (bloqueio jogos/pokemon).
   Sem culpado; não quebra sequência."
  [raid agora]
  (when-not (= "inscricoes" (str (:fase raid)))
    (throw (err/error :raid_cannot_cancel {:fase (:fase raid)})))
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

(defn treinadores-aptos
  "Player-ids com combatente vivo, na ordem canônica."
  [raid]
  (vec (filter #(participante-vivo? (get-in raid [:participantes %]))
               (:ordem raid []))))

(defn turn-window
  "Prazo de turno: só com ≥2 aptos. Solo/último apto → nil (idle global 300s)."
  [agora aptos turn-timeout-ms]
  (let [now (long agora)
        n (count aptos)]
    (if (and (>= n 2) (pos? (long (or turn-timeout-ms 0))))
      {:turn-started-at now
       :turn-expires-at (+ now (long turn-timeout-ms))}
      {:turn-started-at now
       :turn-expires-at nil})))

(defn turn-expired?
  "True se há prazo de turno e já venceu. Nil/ausente → não aplica skip."
  [turn-expires-at agora]
  (when (some? turn-expires-at)
    (<= (long turn-expires-at) (long agora))))

(defn liberar-captura
  "Dispensa voluntária da captura pós-vitória (sem bola/frambo/tentativa)."
  [raid player-id agora]
  (when-not (captura-pendente? raid player-id agora)
    (throw (err/error :raid_capture_unavailable {:player-id player-id})))
  (let [cap (or (get-in raid [:capturas player-id]) {})
        cap' (assoc cap :liberou true :frambo-preparada false)
        raid' (assoc-in raid [:capturas player-id] cap')]
    {:raid raid'
     :liberou? true
     :chefe (:chefe raid)
     :pointer-cleared? (deve-liberar-ponteiro? raid' agora)}))

(defn pular-turno
  "Avança a vez sem revide, dano ou remoção. Skip temporário de um turno.
   Não altera idle-expires-at (caller preserva). Exige ≥2 aptos."
  [raid agora]
  (when-not (and (ativa? raid agora) (= "combate" (str (:fase raid))))
    (throw (err/error :raid_not_in_combat {:fase (:fase raid)})))
  (when-not (vez-valida? raid)
    (throw (err/error :raid_turn_invalid
                      {:vez (:vez raid) :ordem (:ordem raid)})))
  (let [aptos (treinadores-aptos raid)
        skipped (:vez raid)
        _ (when (< (count aptos) 2)
            (throw (err/error :raid_turn_skip_solo
                              {:aptos (count aptos) :vez skipped})))
        proxima (proxima-vez raid skipped)
        _ (when (or (nil? proxima) (= proxima skipped))
            (throw (err/error :raid_turn_skip_no_next
                              {:vez skipped :aptos aptos})))
        turn-index' (inc (long (:turn-index raid 0)))
        raid' (-> raid
                  (assoc :vez proxima)
                  (assoc :turn-index turn-index')
                  ;; Skip NÃO renova :expira de combate nem idle — só avança vez.
                  )]
    {:raid raid'
     :skipped-player skipped
     :next-player proxima
     :turn-index turn-index'
     :aptos aptos}))

(defn- revide-chefe
  "Contra-ataque canônico do chefe após a ação do jogador (ataque ou item).

  Regra de produto (intencional, documentada): dano = ceil(HP máximo do
  combatente / 5), mínimo 1. Não simula um golpe completo do chefe
  (stats/tipo/precisão/RNG de variação); a mesma fórmula alimenta `atacar`
  e `usar-item` para previsibilidade e idempotência do recibo.

  Não apresentar este valor como equivalente a um ataque normal do chefe
  calculado por `calcular-dano`."
  [max-hp-ativo]
  (max 1 (long (Math/ceil (/ (double (or max-hp-ativo 100)) 5)))))

(defn- aplicar-revide-part
  "Aplica revide no ativo. Desmaio → HP 0, sem promoção de reserva (1-mon)."
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
      ;; Sem avancar-party: treinador eliminado; slots legados preservados em 0 no ativo.
      (sync-active (assoc part' :party slots' :hp 0)))))

(defn- preparar-golpe-raid
  "Estado temporário no EDN do participante e do chefe. Não abre golpe de
  status novo: só dano, proteção, carga e recarga."
  [raid part golpe rng]
  (let [vol (or (:volatil part) {})
        chefe (or (:chefe raid) {})
        hp-max (long (or (:hp-max raid) (:hp-chefe raid) 1))
        hp0 (long (:hp-chefe raid 0))
        dot (if (and (= :envenenado-grave (:status chefe)) (pos? hp0))
              (mechanics/dano-veneno-grave hp-max (long (:contador chefe 1)))
              0)
        res (when (and (:residual chefe) (pos? hp0))
              (mechanics/dano-residual hp-max))
        hp1 (max 0 (- hp0 dot (long (or res 0))))
        chefe (cond-> chefe
                (pos? dot) (update :contador (fnil inc 1)))
        chefe (if (and res (<= (dec (long (get-in chefe [:residual :restante] 1))) 0))
                (dissoc chefe :residual)
                (cond-> chefe
                  res (update-in [:residual :restante] #(dec (long (or % 1))))))
        msgs (cond-> []
               (pos? dot) (conj (str "☠️ O veneno grave causou " dot " de dano!"))
               res (conj (str "🌀 A armadilha causou " res " de dano!")))
        carga (:carga vol)
        base {:hp hp1 :chefe chefe}]
    (cond
      (zero? hp1)
      (assoc base :part (assoc part :volatil vol) :dano 0 :acertos 1 :revide 0
             :mensagem (when (seq msgs) (str/join "\n" msgs)))

      (:recarga? vol)
      (assoc base :part (assoc part :volatil (dissoc vol :recarga?))
             :dano 0 :acertos 1 :revide :normal
             :mensagem (str/join "\n" (conj msgs "⏳ O Pokémon precisa recarregar e perdeu a ação!")))

      (and carga (= (:slug carga) (:slug golpe)))
      (assoc base :part (assoc part :volatil (dissoc vol :carga))
             :golpe (:golpe carga) :calcular? true
             :mensagem (when (seq msgs) (str/join "\n" msgs)))

      (= :preparacao (:familia golpe))
      (assoc base :part (assoc part :volatil
                               (assoc vol :carga {:slug (:slug golpe) :golpe golpe}))
             :dano 0 :acertos 1 :revide :normal
             :mensagem (str/join "\n" (conj msgs "✨ O Pokémon está se preparando!")))

      (= :protecao (:familia golpe))
      (let [ok? (< (rng/roll rng :protecao 100)
                   (mechanics/chance-protecao (long (:protecao-seguidas vol 0))))]
        (if ok?
          (assoc base :part (assoc part :volatil (update vol :protecao-seguidas (fnil inc 0)))
                 :dano 0 :acertos 1 :revide 0
                 :mensagem (str/join "\n" (conj msgs "🛡️ Proteção bloqueou o ataque!")))
          (assoc base :part (assoc part :volatil (assoc vol :protecao-seguidas 0))
                 :dano 0 :acertos 1 :revide :normal
                 :mensagem (str/join "\n" (conj msgs "⚠️ A proteção falhou!")))))

      :else
      (let [chance (dmg/chance-acerto (:precisao golpe) {} {} (:ignora-precisao golpe))
            errou? (and chance (< chance 100)
                       (>= (rng/roll rng :accuracy 100) chance))]
        (assoc base :part (assoc part :volatil vol)
               :calcular? (not errou?) :errou? errou?
               :dano (when errou? 0) :acertos 1
               :mensagem (when (seq msgs) (str/join "\n" msgs)))))))

(defn atacar
  "Aplica um ataque. Retorna {:raid :dano :revide :venceu? :derrota? :multiplicador
   :trocou? :golpe :variacao-pct :base-variada}.
   Desmaio do ativo → treinador fora; raid continua se houver outros vivos.
   Derrota quando nenhum participante tem combatente ativo com HP > 0.
   `trocou?` permanece sempre false na regra 1-mon (sem substituição).
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
         golpes (mapv #(move-effects/enriquecer (battle/golpe-normalizado %))
                     (or (:golpes pokemon) []))
         golpe (when (and (integer? move-index) (<= 0 move-index 3)
                          (< move-index (count golpes)))
                 (nth golpes move-index))]
     (when-not (or (golpe-dano? golpe) (= :protecao (:familia golpe)))
       (throw (err/error :invalid_raid_move {:move-index move-index})))
     (let [prep (preparar-golpe-raid raid part golpe rng)
           golpe (or (:golpe prep) golpe)
           hp-chefe-antes (long (:hp prep))
           calcular? (:calcular? prep)
           acertos (if (and calcular? (:max-acertos golpe))
                     (mechanics/rolar-acertos (long (:min-acertos golpe 1))
                                              (long (:max-acertos golpe))
                                              rng)
                     (long (:acertos prep 1)))
           calc (when calcular? (calcular-dano golpe pokemon raid rng))
           dano (if calcular?
                  (min hp-chefe-antes (* acertos (long (:dano calc))))
                  (long (:dano prep 0)))
           multiplicador (if calc (:multiplicador calc) 1)
           base (when calc (:base calc))
           autodesmaio? (boolean (and calcular?
                                      (or (:autodesmaio golpe) (get golpe "autodesmaio"))))
           hp' (max 0 (- hp-chefe-antes dano))
           venceu? (zero? hp')
           max-hp-ativo (long (or (:hp pokemon) (get pokemon "hp") 100))
           revide (cond
                    venceu? 0
                    (= 0 (:revide prep)) 0
                    :else (revide-chefe max-hp-ativo))
           part-base (or (:part prep) part)
           part-apos-dano (-> part-base
                              (update :dano #(+ (long %) dano))
                              (aplicar-revide-part revide))
           part-final (if (and autodesmaio? (not venceu?) (pos? dano))
                        (let [idx (long (:active-party-idx part-apos-dano 0))
                              slots' (assoc-in (vec (:party part-apos-dano)) [idx :hp] 0)]
                          (sync-active (assoc part-apos-dano :party slots' :hp 0)))
                        part-apos-dano)
           part-final (dissoc part-final :trocou-por-desmaio?)
           part-final (cond-> part-final
                        (and calcular? (pos? dano) (= :recarga (:familia golpe)))
                        (assoc-in [:volatil :recarga?] true))
           chefe (or (:chefe prep) (:chefe raid))
           chefe (cond-> chefe
                   (and calcular? (pos? dano) (= :residual (:familia golpe)))
                   (assoc :residual {:restante (long (:turnos-max golpe 4))})
                   (and calcular? (pos? dano)
                        (= :envenenado-grave (:status-causado golpe))
                        (not (mechanics/imune-a-status?
                              :envenenado-grave (:tipos chefe))))
                   (assoc :status :envenenado-grave :contador 1))
           eliminado? (not (participante-vivo? part-final))
           raid' (-> raid
                     (assoc :hp-chefe hp' :chefe chefe)
                     (assoc-in [:participantes player-id] part-final)
                     (update :turn-index (fnil inc 0)))
           proxima (when-not venceu? (proxima-vez raid' player-id))
           derrota? (and (not venceu?) (nil? proxima))
           raid'' (cond
                    venceu? (assoc raid'
                                   :fase "vitoria"
                                   :captura-expira (+ (long agora) duracao-captura)
                                   :vez nil)
                    derrota? (assoc raid' :fase "derrota" :vez nil)
                    :else (assoc raid'
                                 :vez proxima
                                 :expira (+ (long agora) duracao-combate)))]
       {:raid raid''
        :dano dano
        :acertos acertos
        :errou? (boolean (:errou? prep))
        :mensagem (:mensagem prep)
        :multiplicador multiplicador
        :base base
        :base-variada (:base-variada calc)
        :variacao-pct (:variacao-pct calc)
        :hp-chefe-antes hp-chefe-antes
        :revide revide
        :venceu? venceu?
        :derrota? derrota?
        :trocou? false
        :eliminado? eliminado?
        :autodesmaio? (boolean (and autodesmaio? (pos? dano)))
        :golpe golpe
        :pokemon-atacante pokemon
        :pokemon-ativo (:pokemon part-final)
        :hp-ativo (:hp part-final)}))))


(defn usar-item
  "Poção mid-raid: cura o combatente ativo no estado canônico da party,
  consome o turno e aplica o revide canônico do chefe (`revide-chefe`).
  Inventário é debitado no store. Retorno espelha campos úteis de `atacar`."
  [raid player-id item-id agora]
  (when-not (and (ativa? raid agora) (= "combate" (str (:fase raid))))
    (throw (err/error :raid_not_in_combat {:fase (:fase raid)})))
  (when-not (vez-valida? raid)
    (throw (err/error :raid_turn_invalid
                      {:vez (:vez raid)
                       :ordem (:ordem raid)
                       :player-id player-id})))
  (when-not (= player-id (:vez raid))
    (throw (err/error :raid_not_your_turn {:vez (:vez raid) :player-id player-id})))
  (let [id (shop/normalizar-item (str item-id))
        meta (shop/item id)
        fracao (:cura-hp meta)]
    (when-not fracao
      (throw (err/error :item_use_unsupported
                        {:item-id id :allowed ["pocao" "pocao-maxima"]})))
    (let [part (get-in raid [:participantes player-id])
          _ (when-not (participante-vivo? part)
              (throw (err/error :raid_participant_fainted {:player-id player-id})))
          pokemon (:pokemon part)
          heal (shop/aplicar-pocao
                {:hp (long (or (:hp pokemon) (get pokemon "hp") 0))
                 :hp-atual (long (or (:hp part)
                                     (:hp-atual pokemon)
                                     (get pokemon "hp-atual") 0))}
                fracao)
          idx (long (:active-party-idx part 0))
          slots (vec (or (:party part)
                         [(party-slot pokemon (:collection-index part 0))]))
          slot (nth slots idx)
          poke' (assoc (:pokemon slot) :hp-atual (long (:hp-depois heal)))
          slots' (assoc slots idx (assoc slot :pokemon poke' :hp (long (:hp-depois heal))))
          part-healed (sync-active (assoc part :party slots'))
          max-hp-ativo (long (or (:hp poke') (get poke' "hp") (:hp-max heal) 100))
          ;; Mesma mecânica canônica de contra-ataque de `atacar` (não fórmula paralela).
          revide (revide-chefe max-hp-ativo)
          part-final (aplicar-revide-part part-healed revide)
          eliminado? (not (participante-vivo? part-final))
          raid' (-> raid
                    (assoc-in [:participantes player-id] part-final)
                    (update :turn-index (fnil inc 0)))
          proxima (proxima-vez raid' player-id)
          derrota? (nil? proxima)
          raid'' (if derrota?
                   (assoc raid' :fase "derrota" :vez nil)
                   (assoc raid'
                          :vez proxima
                          :expira (+ (long agora) duracao-combate)))]
      {:raid raid''
       :item-id id
       :hp-antes (:hp-antes heal)
       :hp-depois (:hp-depois heal)
       :hp-max (:hp-max heal)
       :hp-apos-revide (:hp part-final)
       :revide revide
       :dano 0
       :venceu? false
       :derrota? derrota?
       :eliminado? eliminado?
       :trocou? false
       :pokemon-ativo (:pokemon part-final)
       :hp-ativo (:hp part-final)})))

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
