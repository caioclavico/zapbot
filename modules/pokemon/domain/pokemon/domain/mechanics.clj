(ns pokemon.domain.mechanics
  "Famílias de efeito compartilhadas por caça, ginásio, PvP e NPC.
  O estado volátil fica na batalha em memória. Não grava save nem schema."
  (:require [pokemon.domain.rng :as rng]
            [pokemon.domain.types :as types]))

(defn tipos-de
  [pokemon]
  (set (map str (or (:tipos pokemon) (get pokemon "tipos") []))))

(defn imune-a-status?
  [status tipos]
  (let [st (keyword (if (keyword? status) (name status) (str status)))
        ts (set (map str tipos))]
    (case st
      :queimado (contains? ts "fire")
      :paralisado (contains? ts "electric")
      (:envenenado :envenenado-grave) (or (contains? ts "poison") (contains? ts "steel"))
      :congelado (contains? ts "ice")
      false)))

(defn chance-protecao
  "Primeiro uso acerta. Cada sucesso seguido divide a chance por 3."
  [seguidas]
  (if (zero? (long seguidas))
    100
    (max 1 (long (Math/round (/ 100.0 (Math/pow 3 (long seguidas))))))))

(defn rolar-acertos
  "2–5 usa a distribuição clássica (35/35/15/15). Outras faixas são uniformes."
  [min-acertos max-acertos rng]
  (let [lo (long min-acertos)
        hi (long max-acertos)]
    (if (and (= lo 2) (= hi 5))
      (let [r (rng/roll rng :multi-hit 100)]
        (cond
          (< r 35) 2
          (< r 70) 3
          (< r 85) 4
          :else 5))
      (+ lo (rng/roll rng :multi-hit (inc (- hi lo)))))))

(defn dano-veneno-grave
  [hp-max contador]
  (max 1 (long (quot (* (long hp-max) (max 1 (long contador))) 16))))

(defn dano-residual
  [hp-max]
  (max 1 (long (quot (long hp-max) 8))))

(defn- volatil [battle marca]
  (get-in battle [:volatil marca] {}))

(defn habilidade-de
  [pokemon]
  (str (or (:habilidade pokemon) (get pokemon "habilidade") "")))

(defn aterrado?
  "No chão: sem Voador e sem Levitação, salvo se um golpe o derrubou."
  [battle marca]
  (let [poke (get-in battle [:pokemons marca])]
    (boolean
     (or (get-in battle [:volatil marca :aterrado?])
         (and poke
              (not (contains? (tipos-de poke) "flying"))
              (not= "levitate" (habilidade-de poke)))))))

(defn limpar-volatil
  [battle marca]
  (let [outro (if (= marca :x) :o :x)
        vol (get-in battle [:volatil marca])
        carregava? (:carga vol)
        era-alvo? (and (:no-ar? vol) (not carregava?))]
    (cond-> (assoc-in battle [:volatil marca] {})
      carregava? (update-in [:volatil outro] dissoc :no-ar? :invulneravel?)
      era-alvo? (update-in [:volatil outro] dissoc :no-ar? :invulneravel? :carga))))

(defn preparar-acao
  "Recarga, flinch, carga e golpe de primeiro turno.
  Devolve {:battle :golpe :pular? :carregar? :mensagem :alteracoes-carga}."
  [battle marca golpe rng]
  (let [v (volatil battle marca)
        turnos (long (:turnos-no-campo v 0))
        battle (assoc-in battle [:volatil marca :turnos-no-campo] (inc turnos))
        recarga? (:recarga? v)
        recuo? (:recuo? v)
        carga (:carga v)
        golpe (or golpe {})]
    (cond
      recarga?
      {:battle (update-in battle [:volatil marca] dissoc :recarga?)
       :golpe golpe
       :pular? true
       :mensagem "⏳ O Pokémon precisa recarregar e perdeu a ação!"}

      recuo?
      {:battle (update-in battle [:volatil marca] dissoc :recuo?)
       :golpe golpe
       :pular? true
       :mensagem "💥 O adversário recuou e perdeu a ação!"}

      (and (:no-ar? v)
           (not (and carga (= (:slug carga) (:slug golpe)))))
      {:battle battle
       :golpe golpe
       :pular? true
       :mensagem "🌬️ O Pokémon está no ar e não pode agir!"}

      (and (:so-primeiro-turno golpe) (pos? turnos))
      {:battle battle
       :golpe golpe
       :pular? true
       :mensagem "⚠️ Esse golpe só funciona no primeiro turno em campo!"}

      (and (= (:slug golpe) (get-in v [:desabilitado :slug])))
      {:battle battle
       :golpe golpe
       :pular? true
       :mensagem "🚫 O golpe está desabilitado!"}

      (and (:tormento? v) (= (:slug golpe) (:ultimo-slug v)))
      {:battle battle
       :golpe golpe
       :pular? true
       :mensagem "😵 O tormento impede repetir o golpe!"}

      (and carga (= (:slug carga) (:slug golpe)))
      (let [outro (if (= marca :x) :o :x)
            queda? (= :queda (:familia (:golpe carga)))]
        {:battle (cond-> (-> battle
                             (update-in [:volatil marca] dissoc :carga :invulneravel? :no-ar?)
                             (assoc-in [:volatil marca :ultimo-slug] (:slug golpe)))
                   queda? (update-in [:volatil outro] dissoc :no-ar? :invulneravel?))
         :golpe (dissoc (:golpe carga) :alteracoes)
         :pular? false
         :mensagem nil})

      (and (= :preparacao (:familia golpe))
           (:pula-carga-no-sol golpe)
           (= :sol (get-in battle [:clima :tipo])))
      {:battle (assoc-in battle [:volatil marca :ultimo-slug] (:slug golpe))
       :golpe golpe
       :pular? false
       :mensagem nil}

      (= :preparacao (:familia golpe))
      {:battle (cond-> (assoc-in battle [:volatil marca :carga]
                                 {:slug (:slug golpe) :golpe golpe})
                 (:invulneravel golpe)
                 (assoc-in [:volatil marca :invulneravel?] true))
       :golpe golpe
       :pular? true
       :carregar? true
       :alteracoes-carga (when (= :proprio (:alvo golpe)) (:alteracoes golpe))
       :mensagem "✨ O Pokémon está se preparando!"}

      (= :queda (:familia golpe))
      (let [outro (if (= marca :x) :o :x)
            alvo (get-in battle [:pokemons outro])]
        (if (or (contains? (tipos-de alvo) "flying")
                (= "levitate" (habilidade-de alvo)))
          {:battle battle
           :golpe golpe
           :pular? true
           :mensagem "🌬️ Queda Livre não alcança quem flutua!"}
          {:battle (-> battle
                       (assoc-in [:volatil marca :carga] {:slug (:slug golpe) :golpe golpe})
                       (assoc-in [:volatil marca :no-ar?] true)
                       (assoc-in [:volatil marca :invulneravel?] true)
                       (assoc-in [:volatil outro :no-ar?] true)
                       (assoc-in [:volatil outro :invulneravel?] true))
           :golpe golpe
           :pular? true
           :carregar? true
           :mensagem "🌬️ Queda Livre levou o alvo para o alto!"}))

      :else
      {:battle (cond-> (if (= :protecao (:familia golpe))
                         battle
                         (assoc-in battle [:volatil marca :protecao-seguidas] 0))
                 (:slug golpe)
                 (assoc-in [:volatil marca :ultimo-slug] (:slug golpe)))
       :golpe golpe
       :pular? false})))

(defn marcar-invulneravel
  [battle marca]
  (assoc-in battle [:volatil marca :invulneravel?] true))

(defn tentar-protecao
  [battle marca rng]
  (let [seguidas (long (get-in battle [:volatil marca :protecao-seguidas] 0))
        chance (chance-protecao seguidas)
        ok? (< (rng/roll rng :protecao 100) chance)]
    (if ok?
      {:battle (-> battle
                   (assoc-in [:volatil marca :protecao?] true)
                   (assoc-in [:volatil marca :protecao-seguidas] (inc seguidas)))
       :sucesso? true
       :mensagem "🛡️ Proteção ativada!"}
      {:battle (assoc-in battle [:volatil marca :protecao-seguidas] 0)
       :sucesso? false
       :mensagem "⚠️ A proteção falhou!"})))

(defn protecao-bloqueia?
  [battle def-marca golpe]
  (and (get-in battle [:volatil def-marca :protecao?])
       (not (:atravessa-protecao golpe))
       (not= :proprio (:alvo golpe))))

(defn consumir-protecao
  [battle marca]
  (if (get-in battle [:volatil marca :protecao?])
    (update-in battle [:volatil marca] dissoc :protecao?)
    battle))

(defn marcar-flinch
  [battle alvo golpe acertou? alvo-ja-agiu? rng]
  (if (and acertou?
           (not alvo-ja-agiu?)
           (:flinch golpe)
           (pos? (long (:flinch golpe)))
           (< (rng/roll rng :flinch 100) (long (:flinch golpe))))
    (assoc-in battle [:volatil alvo :recuo?] true)
    battle))

(defn agendar-recarga
  [battle marca golpe]
  (if (= :recarga (:familia golpe))
    (assoc-in battle [:volatil marca :recarga?] true)
    battle))

(defn aplicar-semente
  [battle alvo fonte tipos-alvo]
  (cond
    (contains? (set (map str tipos-alvo)) "grass")
    {:battle battle :aplicou? false :imune? true}

    (get-in battle [:volatil alvo :semente])
    {:battle battle :aplicou? false}

    :else
    {:battle (assoc-in battle [:volatil alvo :semente] fonte)
     :aplicou? true
     :mensagem "🌱 Semente Sanguessuga foi plantada!"}))

(defn aplicar-residual
  [battle alvo golpe rng]
  (let [lo (long (:turnos-min golpe 4))
        hi (long (:turnos-max golpe lo))
        n (if (>= hi lo)
            (+ lo (rng/roll rng :residual-turns (inc (- hi lo))))
            lo)]
    (assoc-in battle [:volatil alvo :residual] {:restante n})))

(defn tick-semente
  [battle marca]
  (let [fonte (get-in battle [:volatil marca :semente])
        hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))]
    (if (or (nil? fonte) (zero? hp) (zero? max-hp))
      {:battle battle :mensagens []}
      (let [dano (dano-residual max-hp)
            battle (update-in battle [:hp marca] #(max 0 (- % dano)))
            fonte-hp (long (get-in battle [:hp fonte] 0))
            fonte-max (long (get-in battle [:pokemons fonte :hp] 0))
            cura (if (pos? fonte-hp) (min (- fonte-max fonte-hp) dano) 0)
            battle (if (pos? cura)
                     (update-in battle [:hp fonte] #(min fonte-max (+ % cura)))
                     battle)]
        {:battle battle
         :mensagens [(str "🌱 Semente Sanguessuga drenou " dano " HP!")]}))))

(defn tick-residual
  [battle marca]
  (let [res (get-in battle [:volatil marca :residual])
        hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))]
    (if (or (nil? res) (zero? hp))
      {:battle battle :mensagens []}
      (let [dano (dano-residual max-hp)
            restante (dec (long (:restante res 1)))
            battle (-> battle
                       (update-in [:hp marca] #(max 0 (- % dano)))
                       (assoc-in [:volatil marca :residual]
                                 (when (pos? restante) {:restante restante})))]
        {:battle battle
         :mensagens [(str "🌀 A armadilha causou " dano " de dano!")]}))))

(defn tick-veneno-grave
  [battle marca]
  (let [hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))
        n (max 1 (long (get-in battle [:volatil marca :veneno-contador] 1)))]
    (if (or (zero? hp) (zero? max-hp))
      {:battle battle :dot 0 :mensagem nil}
      (let [dot (dano-veneno-grave max-hp n)]
        {:battle (-> battle
                     (update-in [:hp marca] #(max 0 (- % dot)))
                     (assoc-in [:volatil marca :veneno-contador] (inc n)))
         :dot dot
         :mensagem (str "☠️ O veneno grave causou " dot " de dano!")}))))

(defn iniciar-veneno-grave
  [battle marca]
  (assoc-in battle [:volatil marca :veneno-contador] 1))

(defn limpar-contador-veneno
  [battle marca]
  (update-in battle [:volatil marca] dissoc :veneno-contador))

(defn limpar-status-lado
  [battle marca alcance]
  (let [battle (-> battle
                   (assoc-in [:status marca] nil)
                   (limpar-contador-veneno marca))]
    (if (= alcance :equipe)
      (update-in battle [:reservas marca]
                 (fn [rs]
                   (when rs
                     (mapv (fn [e]
                             (if (and (map? e) (:pokemon e))
                               (update e :pokemon dissoc :status)
                               (dissoc e :status)))
                           rs))))
      battle)))

(defn limpar-estagios
  [battle]
  (-> battle
      (assoc-in [:estagios :x] {})
      (assoc-in [:estagios :o] {})))

(defn limpar-semente-e-armadilha
  [battle marca]
  (update-in battle [:volatil marca] dissoc :semente :residual))

(defn chance-de-alteracao?
  [golpe rng]
  (let [chance (:chance-alteracao golpe)]
    (or (nil? chance)
        (< (rng/roll rng :chance-alt 100) (long chance)))))

(defn preso?
  "Armadilha que prende de fato. Fantasma escapa. Rocha e espinhos não prendem."
  [battle marca]
  (and (get-in battle [:volatil marca :residual])
       (not (contains? (tipos-de (get-in battle [:pokemons marca])) "ghost"))))

(defn precisao-no-clima
  "Trovoada e furacão não erram na chuva e caem a 50 no sol.
  Nevasca não erra na neve. Sem override, a precisão do golpe permanece."
  [golpe clima]
  (cond
    (and (= clima :chuva) (:precisao-chuva golpe)) (:precisao-chuva golpe)
    (and (= clima :sol) (:precisao-sol golpe)) (:precisao-sol golpe)
    (and (#{:neve :granizo} clima) (:precisao-neve golpe)) (:precisao-neve golpe)
    :else (:precisao golpe)))

(defn terreno-bloqueia-status?
  "Elétrico impede sono. Névoa impede status principal. Voador e Levitação
  não estão no chão. Terreno psíquico não entra: o combate não tem prioridade."
  [battle marca status tipos]
  (let [t (get-in battle [:terreno :tipo])
        no-chao? (if marca
                   (aterrado? battle marca)
                   (not (contains? (set (map str tipos)) "flying")))
        st (keyword (if (keyword? status) (name status) (str status)))]
    (and t no-chao?
         (or (and (= t :eletrico) (= st :adormecido))
             (and (= t :nevoa)
                  (contains? #{:queimado :envenenado :envenenado-grave
                               :paralisado :adormecido :congelado}
                             st))))))

(defn limpar-hazards
  [battle marca]
  (update-in battle [:campo marca] dissoc :hazards))

(defn- campo-de [golpe]
  (let [c (:campo golpe)]
    (cond
      (keyword? c) c
      (string? c) (keyword c)
      :else nil)))

(defn aplicar-familia-de-campo
  "Clima, terreno, entrada, disable, tormento e canto mortal.
  Devolve nil quando a família não é desta camada.
  `marca` é quem usou o golpe: o selvagem age no fim da rodada."
  [battle golpe alvo marca]
  (case (:familia golpe)
    :clima
    (let [tipo (campo-de golpe)]
      {:battle (assoc battle :clima {:tipo tipo :restante 5 :recem? (= marca :o)})
       :mensagem (case tipo
                   :sol "☀️ O sol forte tomou o campo!"
                   :chuva "🌧️ A chuva começou!"
                   :areia "🏜️ A tempestade de areia começou!"
                   :neve "❄️ A neve começou!"
                   :granizo "🌨️ O granizo começou!"
                   "✨ O clima mudou!")})

    :terreno
    (let [tipo (campo-de golpe)]
      {:battle (assoc battle :terreno {:tipo tipo :restante 5 :recem? (= marca :o)})
       :mensagem (case tipo
                   :eletrico "⚡ O terreno elétrico cobriu o campo!"
                   :grama "🌱 O terreno gramado cobriu o campo!"
                   :nevoa "🌫️ O terreno nebuloso cobriu o campo!"
                   "✨ O terreno mudou!")})

    :hazard
    (let [tipo (campo-de golpe)
          lado alvo]
      {:battle (case tipo
                 :rochas (assoc-in battle [:campo lado :hazards :rochas] true)
                 :espinhos (update-in battle [:campo lado :hazards :espinhos]
                                      #(min 3 (inc (long (or % 0)))))
                 :toxicos (update-in battle [:campo lado :hazards :toxicos]
                                     #(min 2 (inc (long (or % 0)))))
                 :teia (assoc-in battle [:campo lado :hazards :teia] true)
                 battle)
       :mensagem (case tipo
                   :rochas "🪨 Rochas afiadas cobriram o lado adversário!"
                   :espinhos "🔺 Espinhos cobriram o lado adversário!"
                   :toxicos "☠️ Espinhos tóxicos cobriram o lado adversário!"
                   :teia "🕸️ Uma teia cobriu o lado adversário!"
                   "✨ O campo mudou!")})

    :desabilitar
    (if-let [slug (get-in battle [:volatil alvo :ultimo-slug])]
      {:battle (assoc-in battle [:volatil alvo :desabilitado]
                         {:slug slug :restante 4})
       :mensagem "🚫 O último golpe foi desabilitado!"}
      {:battle battle
       :mensagem "⚠️ Não há golpe para desabilitar!"})

    :tormento
    {:battle (assoc-in battle [:volatil alvo :tormento?] true)
     :mensagem "😵 O tormento impede repetir o golpe!"}

    :canto-mortal
    {:battle (-> battle
                 (assoc-in [:volatil :x :perecer] 3)
                 (assoc-in [:volatil :o :perecer] 3))
     :mensagem "🎵 O canto mortal começou a contar!"}

    nil))

(defn aplicar-entrada
  "Rocha, espinhos, espinhos tóxicos e teia ao entrar. Não é armadilha."
  [battle marca]
  (let [haz (get-in battle [:campo marca :hazards])
        poke (get-in battle [:pokemons marca])
        tipos (tipos-de poke)
        no-chao? (aterrado? battle marca)
        max-hp (long (or (:hp poke) 0))
        hp (long (get-in battle [:hp marca] 0))]
    (if (or (nil? haz) (not (pos? hp)) (not (pos? max-hp)))
      {:battle battle :mensagens []}
      (let [mult (types/multiplicador-vs-tipos "rock" (vec tipos) nil)
            rocha (when (:rochas haz)
                    (max 1 (long (Math/round (* (/ (double max-hp) 8.0) mult)))))
            esp (when (and no-chao? (pos? (long (:espinhos haz 0))))
                  (max 1 (long (quot max-hp (case (long (:espinhos haz)) 1 8 2 6 4)))))
            tox (long (:toxicos haz 0))
            absorve? (and (pos? tox) (contains? tipos "poison"))
            tox-pega? (and (pos? tox) no-chao? (not absorve?)
                           (not (contains? tipos "steel"))
                           (nil? (get-in battle [:status marca])))
            teia? (and (:teia haz) no-chao?)
            battle (-> battle
                       (cond-> rocha (update-in [:hp marca] #(max 0 (- % rocha)))
                               esp (update-in [:hp marca] #(max 0 (- % esp)))
                               absorve? (update-in [:campo marca :hazards] dissoc :toxicos)
                               tox-pega? (assoc-in [:status marca]
                                                   (if (>= tox 2) :envenenado-grave :envenenado))
                               (and tox-pega? (>= tox 2))
                               (assoc-in [:volatil marca :veneno-contador] 1)
                               teia? (update-in [:estagios marca :veloc]
                                                #(max -6 (dec (long (or % 0)))))))]
        {:battle battle
         :mensagens (cond-> []
                      rocha (conj (str "🪨 As rochas causaram " rocha " de dano!"))
                      esp (conj (str "🔺 Os espinhos causaram " esp " de dano!"))
                      absorve? (conj "☠️ O tipo Venenoso absorveu os espinhos tóxicos!")
                      tox-pega? (conj "☠️ Os espinhos tóxicos envenenaram!")
                      teia? (conj "🕸️ A teia reduziu a Velocidade!"))}))))

(defn- tick-perecer [battle marca]
  (if-let [n (get-in battle [:volatil marca :perecer])]
    (let [n' (dec (long n))]
      (if (zero? n')
        {:battle (-> battle
                     (assoc-in [:hp marca] 0)
                     (update-in [:volatil marca] dissoc :perecer))
         :mensagens ["💀 O canto mortal derrubou o Pokémon!"]}
        {:battle (assoc-in battle [:volatil marca :perecer] n')
         :mensagens [(str "🎵 O canto mortal conta " n' "!")]}))
    {:battle battle :mensagens []}))

(defn- tick-desabilitar [battle marca]
  (if-let [d (get-in battle [:volatil marca :desabilitado])]
    (let [n (dec (long (:restante d 1)))]
      (if (pos? n)
        {:battle (assoc-in battle [:volatil marca :desabilitado] (assoc d :restante n))
         :mensagens []}
        {:battle (update-in battle [:volatil marca] dissoc :desabilitado)
         :mensagens ["✨ O golpe voltou a funcionar!"]}))
    {:battle battle :mensagens []}))

(defn- chip-clima [battle marca]
  (let [tipo (get-in battle [:clima :tipo])
        tipos (tipos-de (get-in battle [:pokemons marca]))
        hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))
        areia? (and (= tipo :areia)
                    (not (some tipos ["rock" "ground" "steel"])))
        granizo? (and (= tipo :granizo) (not (contains? tipos "ice")))
        dano (when (and (pos? hp) (pos? max-hp) (or areia? granizo?))
               (max 1 (long (quot max-hp 16))))]
    (if-not dano
      {:battle battle :mensagens []}
      {:battle (update-in battle [:hp marca] #(max 0 (- % dano)))
       :mensagens [(if areia?
                     (str "🏜️ A areia causou " dano " de dano!")
                     (str "🌨️ O granizo causou " dano " de dano!"))]})))

(defn- cura-grama [battle marca]
  (let [hp (long (get-in battle [:hp marca] 0))
        max-hp (long (get-in battle [:pokemons marca :hp] 0))]
    (if (or (not= :grama (get-in battle [:terreno :tipo]))
            (not (aterrado? battle marca))
            (not (pos? hp))
            (>= hp max-hp))
      {:battle battle :mensagens []}
      (let [cura (max 1 (long (quot max-hp 16)))]
        {:battle (update-in battle [:hp marca] #(min max-hp (+ % cura)))
         :mensagens [(str "🌱 O terreno gramado recuperou " cura " de HP!")]}))))

(defn expirar-protecao-alheia
  "A proteção dura a ação do adversário. Se ele não bater, ela se desfaz
  no fim do turno dele e não atravessa a rodada seguinte."
  [battle marca]
  (let [outro (if (= marca :x) :o :x)]
    (if (get-in battle [:volatil outro :protecao?])
      {:battle (update-in battle [:volatil outro] dissoc :protecao?)
       :mensagens ["🛡️ A proteção se desfez."]}
      {:battle battle :mensagens []})))

(defn status-tri-ataque
  "20% no total. Um dos três status é sorteado; imunidade anula o escolhido."
  [status-atual rng tipos]
  (when (and (nil? status-atual)
             (< (rng/roll rng :status-golpe 100) 20))
    (let [escolha (nth [:queimado :congelado :paralisado]
                       (rng/roll rng :tri-ataque 3))]
      (when-not (imune-a-status? escolha tipos)
        escolha))))

(defn- avancar-condicao
  "Cinco rodadas, um desconto por fechamento. Quem age no fim da rodada
  não perde a rodada em que criou o clima."
  [battle chave]
  (let [c (get battle chave)]
    (if-not (and c (pos? (long (:restante c 0))))
      {:battle battle :encerrou? false}
      (if (:recem? c)
        {:battle (update battle chave dissoc :recem?) :encerrou? false}
        (let [n (dec (long (:restante c)))]
          (if (pos? n)
            {:battle (assoc battle chave (assoc c :restante n)) :encerrou? false}
            {:battle (dissoc battle chave) :encerrou? true}))))))

(defn- tick-visao
  [battle]
  (reduce (fn [acc marca]
            (if-let [p (get-in (:battle acc) [:campo :previsto marca])]
              (let [n (dec (long (:restante p 1)))]
                (if (pos? n)
                  {:battle (assoc-in (:battle acc) [:campo :previsto marca :restante] n)
                   :mensagens (:mensagens acc)}
                  (let [dano (long (:dano p 0))
                        protegido? (get-in (:battle acc) [:volatil marca :protecao?])
                        hp (long (get-in (:battle acc) [:hp marca] 0))
                        nome (or (:nome p) "Visão do Futuro")
                        battle (cond-> (update-in (:battle acc) [:campo :previsto] dissoc marca)
                                 protegido? (update-in [:volatil marca] dissoc :protecao?)
                                 (and (not protegido?) (pos? hp))
                                 (update-in [:hp marca] #(max 0 (- % dano))))]
                    {:battle battle
                     :mensagens (conj (:mensagens acc)
                                      (if protegido?
                                        (str "🛡️ Proteção bloqueou " nome "!")
                                        (str "🔮 " nome " causou " dano " de dano!")))})))
              acc))
          {:battle battle :mensagens []}
          [:x :o]))

(defn tick-fim
  "Canto mortal e disable por lado. Clima, terreno e visão descontam uma vez,
  no fechamento da rodada, para jogador e selvagem durarem o mesmo."
  [battle marca & {:keys [fecha-rodada?]}]
  (let [per (tick-perecer battle marca)
        dis (tick-desabilitar (:battle per) marca)
        base (:battle dis)
        chip (if (and fecha-rodada? (:recem? (:clima base)))
               {:battle base :mensagens []}
               (chip-clima base marca))
        grama (if (and fecha-rodada? (:recem? (:terreno (:battle chip))))
                {:battle (:battle chip) :mensagens []}
                (cura-grama (:battle chip) marca))
        clima (if fecha-rodada?
                (avancar-condicao (:battle grama) :clima)
                {:battle (:battle grama) :encerrou? false})
        terreno (if fecha-rodada?
                  (avancar-condicao (:battle clima) :terreno)
                  {:battle (:battle clima) :encerrou? false})
        visao (if fecha-rodada?
                (tick-visao (:battle terreno))
                {:battle (:battle terreno) :mensagens []})
        prot (expirar-protecao-alheia (:battle visao) marca)
        acabou (cond-> []
                 (:encerrou? clima) (conj "🌤️ O clima voltou ao normal!")
                 (:encerrou? terreno) (conj "✨ O terreno se desfez!"))]
    {:battle (:battle prot)
     :mensagens (vec (concat (:mensagens per) (:mensagens dis)
                             (:mensagens chip) (:mensagens grama)
                             (:mensagens visao) (:mensagens prot) acabou))}))
