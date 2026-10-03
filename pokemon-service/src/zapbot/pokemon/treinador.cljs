(ns zapbot.pokemon.treinador
  "Estado de 'treinador' de cada jogador pro !pokemon: time de pokémons
  capturados (persistido), qual está ativo pra batalhar, o cooldown de
  caçada, e o nível do treinador (contador próprio - ver nivel-jogador -
  separado de propósito do placar geral do zapbot.rank: !rank é
  compartilhado com velha/naval/quiz pra ranking geral, esse contador é
  só pra calibrar a força do pokémon selvagem sorteado na caçada).
  Convenção de persistência (ver zapbot.armazenamento): chaves sempre
  string, nunca keyword - por isso os pokémons da equipe são guardados num
  formato próprio (ver pokemon->registro/registro->pokemon), diferente do
  mapa interno (chaves keyword) que o zapbot.pokemon.core usa durante a batalha."
  (:require [clojure.string :as str]
            [zapbot.pokemon.golpes :as golpes]
            [zapbot.pokemon.loja :as loja]
            [zapbot.armazenamento :as armazenamento]))

(def ^:private nivel-maximo 100)
(def fator-crescimento-por-nivel 1.03)
(def ^:private xp-por-nivel 9)

(defn- normalizar-xp-registro
  "Converte excesso legado (ex.: 15/9) em todas as subidas cabíveis."
  [registro]
  (let [nivel (get registro "nivel" 1)
        xp (max 0 (get registro "xp-desde-nivel"
                       (* 3 (get registro "vitorias-desde-nivel" 0))))
        subidas (min (js/Math.floor (/ xp xp-por-nivel)) (- nivel-maximo nivel))
        crescer #(js/Math.round (* % fator-crescimento-por-nivel))
        subir (fn [r]
                (let [hp-antigo (get r "hp")
                      hp-novo (crescer hp-antigo)
                      incremento (- hp-novo hp-antigo)]
                  (-> r
                      (update "nivel" inc)
                      (assoc "hp" hp-novo)
                      (update "hp-atual" #(if (pos? %) (+ % incremento) 0))
                      (update "ataque" crescer) (update "defesa" crescer)
                      (update "atq-esp" crescer) (update "def-esp" crescer)
                      (update "veloc" crescer))))]
    (-> (nth (iterate subir registro) subidas)
        (assoc "xp-desde-nivel" (if (= (+ nivel subidas) nivel-maximo)
                                   0 (- xp (* subidas xp-por-nivel))))
        (dissoc "vitorias-desde-nivel"))))

(defn- unificar-colecao [conta]
  ;; Apenas acrescenta o PC ao fim: índices e registros usados por combates
  ;; permanecem exatamente iguais. Recarregar não duplica os Pokémon.
  (-> conta
      (assoc "equipe" (into (vec (get conta "equipe" [])) (get conta "pc" []))
             "pc" [] "colecao-unificada" true)))

(defn- normalizar-xp-contas [estado]
  (into {}
        (map (fn [[cid jogadores]]
               [cid (into {}
                          (map (fn [[pid conta]]
                                 [pid (-> conta
                                          (update "equipe" #(mapv normalizar-xp-registro (or % [])))
                                          (update "pc" #(mapv normalizar-xp-registro (or % [])))
                                          unificar-colecao)])
                               jogadores))])
             (or estado {}))))

(defonce ^:private contas
  (atom (normalizar-xp-contas (armazenamento/obter "treinador"))))
(armazenamento/registrar! "treinador" contas normalizar-xp-contas)
(defonce ^:private descobertas-globais
  (atom (or (armazenamento/obter "pokemon-descobertas") {})))
(armazenamento/registrar! "pokemon-descobertas" descobertas-globais)

(defn- persistir! []
  (armazenamento/salvar! "treinador" @contas))

(def ^:private conta-vazia {"equipe" [] "ativo" 0 "ultima-cacada" 0 "vitorias-treinador" 0
                            "enfermaria" [] "sequencia-capturas" 0 "pokedex" {}})

(defn- conta [cid pid]
  (get-in @contas [cid pid] conta-vazia))

(defn inicial-disponivel?
  "Contas antigas também são reconhecidas pelo histórico de Pokémon."
  [cid pid]
  (let [c (conta cid pid)]
    (not (or (get c "inicial-escolhido")
             (seq (get c "equipe")) (seq (get c "pc")) (seq (get c "enfermaria")) (seq (get c "pokedex"))
             (pos? (get c "ultima-cacada" 0)) (pos? (get c "vitorias-treinador" 0))
             (pos? (get c "doacoes-pokemon" 0))))))

(defn- golpe->registro [g]
  {"slug" (golpes/chave g) "nome-exibicao" (:nome-exibicao (golpes/traduzir g)) "tipo" (:tipo g) "poder" (:poder g) "classe" (name (:classe g))
   "alvo" (when (:alvo g) (name (:alvo g)))
   "precisao" (:precisao g) "status-causado" (some-> (:status-causado g) name)
   "chance-status" (:chance-status g) "cura" (:cura g) "dreno" (:dreno g)
   "recuo" (:recuo g) "min-acertos" (:min-acertos g) "max-acertos" (:max-acertos g)
   "alteracoes" (mapv (fn [{:keys [atributo estagios]}]
                          {"atributo" (name atributo) "estagios" estagios})
                        (:alteracoes g))})

(defn- golpe<-registro [g]
  {:slug (get g "slug") :nome-exibicao (get g "nome-exibicao") :tipo (get g "tipo") :poder (get g "poder")
   :classe (keyword (get g "classe"))
   :alvo (when (get g "alvo") (keyword (get g "alvo")))
   :precisao (get g "precisao") :status-causado (some-> (get g "status-causado") keyword)
   :chance-status (get g "chance-status") :cura (get g "cura") :dreno (get g "dreno")
   :recuo (get g "recuo") :min-acertos (get g "min-acertos") :max-acertos (get g "max-acertos")
   :alteracoes (mapv (fn [a] {:atributo (keyword (get a "atributo"))
                              :estagios (get a "estagios")})
                     (get g "alteracoes" []))})

(def ^:private versao-golpes 7)
(def ^:private versao-raridade 2)

(defn- raridade-por-registro
  "Classifica registros antigos usando a soma dos stats balanceados. A
  transformação inversa recupera aproximadamente o total original:
  balanceado = (original + 75) / 2 para cada um dos seis atributos."
  [registro]
  (let [total-balanceado (reduce + (map #(get registro % 75)
                                         ["hp" "ataque" "defesa" "atq-esp" "def-esp" "veloc"]))
        total-original   (- (* 2 total-balanceado) 450)]
    (cond (<= total-original 400) "comum"
          (<= total-original 480) "incomum"
          (<= total-original 540) "raro"
          (< total-original 570) "epico"
          :else "lendario")))

(def ^:private ataques-iniciais
  {"normal" ["Investida" 40 :fisico] "fire" ["Brasa" 40 :especial]
   "water" ["Jato de Água" 40 :especial] "electric" ["Choque do Trovão" 40 :especial]
   "grass" ["Chicote de Cipó" 45 :fisico] "ice" ["Neve em Pó" 40 :especial]
   "fighting" ["Soco Rápido" 40 :fisico] "poison" ["Ferrão Venenoso" 15 :fisico]
   "ground" ["Tapa de Lama" 20 :especial] "flying" ["Bicada" 35 :fisico]
   "psychic" ["Confusão" 50 :especial] "bug" ["Picada" 60 :fisico]
   "rock" ["Arremesso de Pedra" 50 :fisico] "ghost" ["Lambida" 30 :fisico]
   "dragon" ["Tornado" 40 :especial] "dark" ["Mordida" 60 :fisico]
   "steel" ["Garra de Metal" 50 :fisico] "fairy" ["Vento de Fada" 40 :especial]})

(defn garantir-ataque-do-tipo
  "Regra dos iniciais: pelo menos um ataque ofensivo de um dos tipos.
  Se faltar, concede um ataque básico mesmo antes do nível normal de aprendizado.
  Preserva os golpes existentes; com quatro, substitui somente o último."
  [golpes tipos]
  (let [golpes (golpes/unicos golpes)]
    (if (some #(golpes/ataque-do-tipo? % tipos) golpes)
      golpes
      (if-let [[nome poder classe] (get ataques-iniciais (first tipos))]
        (conj (vec (take 3 golpes))
              (golpes/traduzir {:nome-exibicao nome :tipo (first tipos) :poder poder :classe classe}))
        golpes))))

(defn pokemon->registro
  "Converte um pokémon (mapa interno do zapbot.pokemon.core, chaves keyword) +
  hp-atual/status pro formato persistido (chaves string) guardado na equipe."
  [pokemon hp-atual status]
  {"id-pokemon" (or (:id-pokemon pokemon) (str (random-uuid)))
   "nome" (:nome pokemon) "imagem" (:imagem pokemon) "tipos" (vec (:tipos pokemon))
   "altura" (:altura pokemon) "peso" (:peso pokemon)
   "shiny" (boolean (:shiny? pokemon)) "imagem-shiny" (:imagem-shiny pokemon)
   "habilidade" (:habilidade pokemon) "hp" (:hp pokemon) "ataque" (:ataque pokemon)
   "defesa" (:defesa pokemon) "atq-esp" (:atq-esp pokemon) "def-esp" (:def-esp pokemon)
   "veloc" (:veloc pokemon) "golpes" (mapv golpe->registro (garantir-ataque-do-tipo (:golpes pokemon) (:tipos pokemon)))
   "versao-golpes" versao-golpes "ataque-tipo-inicial" true "versao-traducao-golpes" 1
   "hp-atual" hp-atual "status" (when status (name status)) "nivel" (or (:nivel pokemon) 1)
   "raridade" (or (:raridade pokemon) "comum") "versao-raridade" versao-raridade
   "lendario-api" (boolean (:lendario-api? pokemon))
   "mitico-api" (boolean (:mitico-api? pokemon))
   "paradox-api" (boolean (:paradox-api? pokemon))
   "taxa-captura" (:taxa-captura pokemon)
   "amizade" (or (:amizade pokemon) 70)
   "item" (:item pokemon)})

(defn registro->pokemon
  "Converte um registro da equipe (chaves string) de volta pro formato
  interno do zapbot.pokemon.core (chaves keyword). Retorna [pokemon hp-atual status]."
  [registro]
  [{:nome (get registro "nome") :imagem (get registro "imagem") :tipos (vec (get registro "tipos"))
    :altura (get registro "altura") :peso (get registro "peso")
    :shiny? (get registro "shiny" false) :imagem-shiny (get registro "imagem-shiny")
    :habilidade (get registro "habilidade") :hp (get registro "hp") :ataque (get registro "ataque")
    :defesa (get registro "defesa") :atq-esp (get registro "atq-esp") :def-esp (get registro "def-esp")
    :veloc (get registro "veloc") :golpes (mapv golpe<-registro (get registro "golpes"))
    :nivel (get registro "nivel" 1)
    :raridade (if (= versao-raridade (get registro "versao-raridade"))
                (get registro "raridade" "comum")
                (raridade-por-registro registro))
    :lendario-api? (get registro "lendario-api" false)
    :mitico-api? (get registro "mitico-api" false)
    :paradox-api? (get registro "paradox-api" false)
    :taxa-captura (get registro "taxa-captura")
    :item (get registro "item")}
   (get registro "hp-atual")
   (when (get registro "status") (keyword (get registro "status")))])

(defn equipe
  "Vetor da coleção disponível (chaves string, ver pokemon->registro) do
  jogador nesse chat. Joy e defensores de ginásios ficam separados."
  [cid pid]
  (get (conta cid pid) "equipe"))

(def ligas
  [{:id "iniciante" :nome "🌱 Iniciante" :min 1 :max 10}
   {:id "bronze" :nome "🪨 Bronze" :min 11 :max 25}
   {:id "prata" :nome "🥈 Prata" :min 26 :max 40}
   {:id "ouro" :nome "🥇 Ouro" :min 41 :max 60}
   {:id "diamante" :nome "💎 Diamante" :min 61 :max 100}])

(defn obter-liga [id] (some #(when (= id (:id %)) %) ligas))
(defn elegivel? [liga registro]
  (and liga registro (<= (:min liga) (get registro "nivel" 1) (:max liga))))
(defn liga-selecionada [cid pid] (get (conta cid pid) "liga"))
(defn time-liga [cid pid id]
  (get-in (conta cid pid) ["times-liga" id] [nil nil nil]))

(defn- limpar-times [c]
  (update c "times-liga"
          (fn [times]
            (into {} (map (fn [[id slots]]
                            [id (mapv #(when (and (some? %)
                                                 (elegivel? (obter-liga id) (get (get c "equipe") %))) %) slots)])
                          times)))))

(defn- ajustar-times-remocao [c idx]
  (let [ajustar #(mapv (fn [slot]
                         (cond (nil? slot) nil (= slot idx) nil (> slot idx) (dec slot) :else slot)) %)]
    (-> c
        (update "times-liga" (fn [times] (into {} (map (fn [[id slots]] [id (ajustar slots)]) times))))
        (update "time-ginasio" ajustar))))

(defn selecionar-liga! [cid pid id]
  (when (obter-liga id)
    (swap! contas update-in [cid pid] #(assoc (or % conta-vazia) "liga" id))
    (persistir!) true))

(defn escalar! [cid pid id indices]
  (when (and (obter-liga id) (= 3 (count indices)) (= 3 (count (set indices)))
             (every? #(and (integer? %) (elegivel? (obter-liga id) (get (equipe cid pid) %))) indices))
    (swap! contas assoc-in [cid pid "times-liga" id] (vec indices))
    (persistir!) true))

(defn time-pronto? [cid pid id]
  (let [slots (time-liga cid pid id)]
    (and (= 3 (count slots)) (= 3 (count (set slots)))
         (every? #(and (some? %) (elegivel? (obter-liga id) (get (equipe cid pid) %))
                       (pos? (get (get (equipe cid pid) %) "hp-atual" 0))) slots))))

(defn corrigir-ataques-iniciais! [cid pid]
  (let [corrigir (fn [r]
                   (let [gs (mapv golpe<-registro (get r "golpes"))]
                     (assoc r "ataque-tipo-inicial" true "versao-traducao-golpes" 1
                            "golpes" (mapv golpe->registro (garantir-ataque-do-tipo gs (get r "tipos")))
                            "golpes-removidos" (mapv golpes/identificador (get r "golpes-removidos" [])))))
        eq (equipe cid pid)
        nova (mapv corrigir eq)]
    (when (not= eq nova)
      (swap! contas assoc-in [cid pid "equipe"] nova)
      (persistir!))))

(defn tem-pokemon? [cid pid]
  (pos? (count (equipe cid pid))))

(defn indice-ativo [cid pid]
  (get (conta cid pid) "ativo" 0))

(defn pokemon-no-indice [cid pid idx]
  (when-let [registro (get (equipe cid pid) idx)]
    (registro->pokemon registro)))

(defn pokemon-ativo
  "[pokemon hp-atual status] do pokémon ativo do jogador, ou nil."
  [cid pid]
  (pokemon-no-indice cid pid (indice-ativo cid pid)))

(defn definir-ativo!
  "Define o índice (0-based) ativo, se existir na equipe. Retorna true se
  definiu, false se o índice não existe."
  [cid pid idx]
  (if (contains? (vec (equipe cid pid)) idx)
    (do (swap! contas assoc-in [cid pid "ativo"] idx) (persistir!) true)
    false))

(defn garantir-id-pokemon!
  "Garante uma identidade estável ao Pokémon, inclusive em contas antigas."
  [cid pid idx]
  (when-let [registro (get (equipe cid pid) idx)]
    (if (get registro "id-pokemon")
      registro
      (let [novo (assoc registro "id-pokemon" (str (random-uuid)))]
        (swap! contas assoc-in [cid pid "equipe" idx] novo)
        (persistir!)
        novo))))

(defn favorito [cid pid]
  (get (conta cid pid) "favorito"))

(defn definir-favorito!
  "Marca o Pokémon pelo ID estável e o torna ativo se estiver saudável."
  [cid pid idx]
  (when-let [registro (garantir-id-pokemon! cid pid idx)]
    (swap! contas update-in [cid pid]
           #(cond-> (assoc % "favorito" {"id" (get registro "id-pokemon")
                                         "nome" (get registro "nome")})
              (pos? (get registro "hp-atual" 0)) (assoc "ativo" idx)))
    (persistir!)
    registro))

(defn remover-favorito! [cid pid]
  (swap! contas update-in [cid pid] dissoc "favorito")
  (persistir!))

(defn ativar-favorito-se-disponivel!
  "Ativa o favorito quando ele reaparece na equipe com HP."
  [cid pid]
  (when-let [id (get (favorito cid pid) "id")]
    (when-let [idx (first (keep-indexed
                           (fn [i registro]
                             (when (and (= id (get registro "id-pokemon"))
                                        (pos? (get registro "hp-atual" 0))) i))
                           (equipe cid pid)))]
      (swap! contas assoc-in [cid pid "ativo"] idx)
      (persistir!)
      idx)))

(defn times-prontos [cid pid]
  (get (conta cid pid) "times-prontos" {}))

(defn salvar-time-pronto!
  "Salva uma escalação nomeada por IDs; não reserva nem remove Pokémon."
  [cid pid nome indices]
  (when (and (seq nome) (= 3 (count indices)) (= 3 (count (set indices)))
             (every? #(get (equipe cid pid) %) indices))
    (let [registros (mapv #(garantir-id-pokemon! cid pid %) indices)
          ids (mapv #(get % "id-pokemon") registros)]
      (swap! contas assoc-in [cid pid "times-prontos" nome]
             {"nome" nome "pokemons" ids})
      (persistir!)
      registros)))

(defn indices-time-pronto [cid pid nome]
  (when-let [ids (get-in (times-prontos cid pid) [nome "pokemons"])]
    (let [por-id (into {} (keep-indexed (fn [idx r]
                                          (when-let [id (get r "id-pokemon")] [id idx]))
                                        (equipe cid pid)))]
      (mapv por-id ids))))

(defn excluir-time-pronto! [cid pid nome]
  (when (get (times-prontos cid pid) nome)
    (swap! contas update-in [cid pid "times-prontos"] dissoc nome)
    (persistir!)
    true))

(defn equipar-item!
  "Equipa item no Pokémon do índice informado e retorna o item anterior.
  Retorna ::inexistente se não houver Pokémon nesse índice."
  [cid pid idx item]
  (if-let [registro (get (equipe cid pid) idx)]
    (let [anterior (get registro "item")]
      (swap! contas assoc-in [cid pid "equipe" idx "item"] item)
      (persistir!)
      anterior)
    ::inexistente))

(defn consumir-item-equipado! [cid pid idx item]
  (when (= item (get-in @contas [cid pid "equipe" idx "item"]))
    (swap! contas assoc-in [cid pid "equipe" idx "item"] nil)
    (persistir!)
    true))

(defn atualizar-golpes-ativo!
  "Substitui os golpes do pokémon ativo, preservando todos os demais dados."
  [cid pid golpes]
  (let [idx (indice-ativo cid pid)]
    (when (get (equipe cid pid) idx)
      (swap! contas update-in [cid pid "equipe" idx]
             #(assoc % "golpes" (mapv golpe->registro (garantir-ataque-do-tipo golpes (get % "tipos"))) "versao-golpes" versao-golpes))
      (persistir!)
      true)))

(defn golpes-atuais?
  "Indica se o pokémon ativo já recebeu a regra atual de golpes por nível."
  [cid pid]
  (= versao-golpes (get-in @contas [cid pid "equipe" (indice-ativo cid pid) "versao-golpes"])))

(def maximo-golpes
  "Quantos golpes um pokémon pode ter ao mesmo tempo."
  4)

(defn aprender-golpe-no-indice!
  "Acrescenta um golpe ao pokémon no índice informado, se ainda houver vaga (ver
  maximo-golpes). Não mexe na lista de removidos: um golpe que o dono
  mandou remover não volta por aqui - quem escolhe o candidato já o exclui.
  Retorna true se aprendeu, nil se não havia vaga ou pokémon no índice informado."
  [cid pid idx golpe]
  (let [registro (get (equipe cid pid) idx)]
    (when (and registro (< (count (get registro "golpes")) maximo-golpes)
               (not-any? #(= (golpes/chave golpe) (golpes/chave (golpe<-registro %)))
                          (get registro "golpes")))
      (swap! contas update-in [cid pid "equipe" idx]
             #(update % "golpes" (fn [gs] (conj (vec gs) (golpe->registro golpe)))))
      (persistir!)
      true)))

(defn registrar-verificacao-golpe! [cid pid idx nivel]
  (when (get (equipe cid pid) idx)
    (swap! contas update-in [cid pid "equipe" idx "nivel-oferta-verificado"]
           #(max (or % 0) nivel))
    (persistir!)))

(defn ofertas-golpes [cid pid idx]
  (mapv golpe<-registro (get-in (equipe cid pid) [idx "ofertas-golpes"] [])))

(defn golpes-ja-oferecidos [cid pid idx]
  (let [r (get (equipe cid pid) idx)]
    (set (concat (get r "golpes-oferecidos" [])
                 (map golpes/identificador (get r "golpes-removidos" []))))))

(defn oferecer-golpe! [cid pid idx golpe]
  (let [r (get (equipe cid pid) idx)
        id (golpes/chave golpe)]
    (when (and r (not (contains? (golpes-ja-oferecidos cid pid idx) id))
               (not-any? #(= id (golpes/chave (golpe<-registro %))) (get r "golpes")))
      (let [automatico? (< (count (get r "golpes")) maximo-golpes)
            salvo (golpe->registro golpe)]
        (swap! contas update-in [cid pid "equipe" idx]
               (fn [r]
                 (-> r
                     (update "golpes-oferecidos" (fnil conj []) id)
                     (assoc-in ["historico-golpes" id] salvo)
                     (update (if automatico? "golpes" "ofertas-golpes")
                             (fn [gs] (conj (vec gs) salvo))))))
        (persistir!)
        (if automatico? :aprendido :pendente)))))

(defn recusar-oferta! [cid pid idx]
  (when (seq (ofertas-golpes cid pid idx))
    (swap! contas update-in [cid pid "equipe" idx "ofertas-golpes"] #(vec (rest %)))
    (persistir!) true))

(defn opcoes-reaprender [cid pid idx]
  (let [r (get (equipe cid pid) idx)
        conhecidos (set (map #(golpes/chave (golpe<-registro %)) (get r "golpes")))
        pendentes (set (map golpes/chave (ofertas-golpes cid pid idx)))]
    (->> (concat (keys (get r "historico-golpes"))
                 (map golpes/identificador (get r "golpes-removidos" [])))
         distinct (remove #(or (contains? conhecidos %) (contains? pendentes %))) sort vec)))

(defn golpe-memorizado [cid pid idx id]
  (some-> (get-in (equipe cid pid) [idx "historico-golpes" id]) golpe<-registro))

(defn preparar-aprendizado
  "Valida a troca sem escrever ou cobrar moedas. Índice de golpe é 0-based;
  nil ocupa uma vaga livre. Nunca elimina o último ataque do próprio tipo."
  [registro golpe slot]
  (let [atuais (mapv golpe<-registro (get registro "golpes"))
        id (golpes/chave golpe)
        novo (cond
               (and (nil? slot) (< (count atuais) maximo-golpes)) (conj atuais golpe)
               (and (integer? slot) (<= 0 slot) (< slot (count atuais))) (assoc atuais slot golpe))]
    (when (and registro novo
               (not-any? #(= id (golpes/chave %)) atuais)
               (some #(golpes/ataque-do-tipo? % (get registro "tipos")) novo))
      (let [antigo (when (some? slot) (get atuais slot))]
        (cond-> (-> registro
                    (assoc "golpes" (mapv golpe->registro novo))
                    (assoc-in ["historico-golpes" id] (golpe->registro golpe))
                    (update "golpes-removidos"
                            #(vec (remove #{id} (map golpes/identificador %)))))
          antigo (assoc-in ["historico-golpes" (golpes/chave antigo)] (golpe->registro antigo))
          antigo (update "golpes-removidos" #(vec (distinct (conj % (golpes/chave antigo))))))))))

(defn aplicar-aprendizado! [cid pid idx golpe slot oferta?]
  (let [r (get (equipe cid pid) idx)
        novo (preparar-aprendizado r golpe slot)]
    (when (and novo (or (not oferta?)
                       (= (golpes/chave golpe) (some-> (first (ofertas-golpes cid pid idx)) golpes/chave))))
      (swap! contas assoc-in [cid pid "equipe" idx]
             (if oferta? (update novo "ofertas-golpes" #(vec (rest %))) novo))
      (persistir!) true)))

(defn aprender-golpe-ativo!
  [cid pid golpe]
  (aprender-golpe-no-indice! cid pid (indice-ativo cid pid) golpe))

(defn golpes-removidos
  "Nomes dos golpes que o dono mandou remover desse pokémon. Guardados por
  NOME (e não por posição) porque a lista de golpes é regerada inteira a
  cada subida de nível - ver zapbot.pokemon.core/atualizar-golpes-por-nivel!."
  [registro]
  (set (map golpes/identificador (get registro "golpes-removidos" []))))

(defn golpes-removidos-ativo
  "golpes-removidos do pokémon ativo do jogador."
  [cid pid]
  (golpes-removidos (get (equipe cid pid) (indice-ativo cid pid))))

(defn remover-golpe-ativo!
  "Tira o golpe nessa posição (0-based) do pokémon ativo e guarda o nome dele
  na lista de removidos, pra que a regeneração por nível não o traga de
  volta. Retorna o nome do golpe removido, ou nil se a posição não existir."
  [cid pid indice]
  (let [idx      (indice-ativo cid pid)
        registro (get (equipe cid pid) idx)
        golpe    (get (vec (get registro "golpes")) indice)]
    (when (and golpe
               (some #(golpes/ataque-do-tipo? (golpe<-registro %) (get registro "tipos"))
                     (keep-indexed (fn [i g] (when (not= i indice) g)) (get registro "golpes"))))
      (let [nome (get golpe "nome-exibicao")]
        (swap! contas update-in [cid pid "equipe" idx]
               (fn [r]
                 (-> r
                     (assoc-in ["historico-golpes" (golpes/chave (golpe<-registro golpe))] golpe)
                     (update "golpes" #(let [v (vec %)]
                                         (vec (concat (subvec v 0 indice) (subvec v (inc indice))))))
                     (update "golpes-removidos" #(vec (distinct (conj (vec %) (golpes/chave (golpe<-registro golpe)))))))))
        (persistir!)
        nome))))

(defn pc [cid pid] (get (conta cid pid) "pc" []))

(defn quantidade-guardada [cid pid]
  (+ (count (equipe cid pid)) (count (pc cid pid))
     (count (get (conta cid pid) "enfermaria" []))))

(defn- guardar-registro [c registro]
  (update (or c conta-vazia) "equipe" (fnil conj []) registro))

(defn migrar-colecao!
  "Acrescenta o PC à coleção, preservando os índices inclusive em combate."
  [cid pid]
  (let [c (conta cid pid)]
    (when (or (seq (get c "pc")) (not (get c "colecao-unificada"))
              ;; A hidratação já unificou a memória; grava o legado na primeira consulta.
              (seq (get-in (armazenamento/obter "treinador") [cid pid "pc"])))
      (swap! contas update-in [cid pid] #(unificar-colecao (or % conta-vazia)))
      (persistir!))))

(defn receber-registro! [cid pid registro]
  (swap! contas update-in [cid pid] guardar-registro registro)
  (persistir!)
  {:destino :equipe :indice (dec (count (equipe cid pid)))})

(declare colecao-shiny!)

(def xp-por-cartao-professor 3)

(defn cartoes-professor [cid pid]
  (get (conta cid pid) "cartoes-professor" {}))

(defn transferencia-professor [cid pid]
  (get (conta cid pid) "transferencia-professor"))

(defn cancelar-transferencia-professor! [cid pid]
  (swap! contas update-in [cid pid] dissoc "transferencia-professor")
  (persistir!))

(defn preparar-transferencia-professor! [cid pid registro familia agora]
  (when (and (seq familia) (get registro "id-pokemon")
             (some #{registro} (equipe cid pid))
             (not= (get registro "id-pokemon") (get (favorito cid pid) "id")))
    (let [anterior (get (transferencia-professor cid pid) "token")
          sorteado (+ 100 (rand-int 900))
          codigo (str (if (= (str sorteado) anterior)
                        (+ 100 (mod (inc (- sorteado 100)) 900)) sorteado))
          pendente {"token" codigo "registro" registro
                    "familia" familia "expira" (+ agora (* 5 60 1000))}]
      (swap! contas assoc-in [cid pid "transferencia-professor"] pendente)
      (persistir!)
      pendente)))

(defn confirmar-transferencia-professor! [cid pid token agora]
  (let [resultado (volatile! {:status :invalida})]
    (swap! contas update-in [cid pid]
           (fn [c]
             (let [pendente (get c "transferencia-professor")
                   registro (get pendente "registro")
                   id (get registro "id-pokemon")
                   eq (vec (get c "equipe" []))
                   idx (first (keep-indexed #(when (= id (get %2 "id-pokemon")) %1) eq))
                   familia (get pendente "familia")]
               (if (and pendente (= token (get pendente "token"))
                        (< agora (get pendente "expira" 0)) (some? idx)
                        (= registro (get eq idx))
                        (not= id (get-in c ["favorito" "id"])))
                 (let [ativo (get c "ativo" 0)]
                   (vreset! resultado {:status :ok :registro registro :familia familia})
                   (-> (ajustar-times-remocao c idx)
                       (assoc "equipe" (vec (concat (subvec eq 0 idx) (subvec eq (inc idx))))
                              "ativo" (cond (= ativo idx) 0 (> ativo idx) (dec ativo) :else ativo)
                              "inicial-escolhido" true)
                       (update-in ["cartoes-professor" familia] (fnil inc 0))
                       (dissoc "transferencia-professor")))
                 c))))
    (when (= :ok (:status @resultado))
      (colecao-shiny! cid pid [(:registro @resultado)])
      (persistir!))
    @resultado))

(defn usar-cartao-professor! [cid pid idx registro familia]
  ;; Cartão e XP são alterados no mesmo registro persistido, sem intervalo assíncrono.
  (let [resultado (volatile! {:status :alterado})]
    (swap! contas update-in [cid pid]
           (fn [c]
             (cond
               (or (nil? registro) (not= registro (get-in c ["equipe" idx]))) c
               (>= (get registro "nivel" 1) nivel-maximo)
               (do (vreset! resultado {:status :nivel-maximo}) c)
               (not (pos? (get-in c ["cartoes-professor" familia] 0)))
               (do (vreset! resultado {:status :sem-cartoes}) c)
               :else
               (let [novo (normalizar-xp-registro
                           (-> registro
                               (update "xp-desde-nivel" (fnil + 0) xp-por-cartao-professor)
                               (update "amizade" #(min 255 (+ (or % 70) 10)))))
                     subiu? (> (get novo "nivel") (get registro "nivel" 1))]
                 (vreset! resultado {:status :ok :nome (get novo "nome")
                                     :nivel (get novo "nivel") :subiu? subiu?})
                 (-> c
                     (assoc-in ["equipe" idx] novo)
                     (update-in ["cartoes-professor" familia] dec)
                     limpar-times)))))
    (when (= :ok (:status @resultado)) (persistir!))
    @resultado))

(defn receber-retorno-ginasio! [cid pid registro xp]
  (receber-registro! cid pid
                    (if (pos? xp)
                      (normalizar-xp-registro
                       (-> (normalizar-xp-registro registro)
                           (update "amizade" #(min 255 (+ (or % 70) 10)))
                           (update "xp-desde-nivel" (fnil + 0) xp)))
                      registro)))

(defn adicionar-pokemon!
  "Acrescenta um pokémon (mapa interno do zapbot.pokemon.core + hp-atual/status)
  na coleção do jogador. Retorna destino e índice."
  [cid pid pokemon hp-atual status]
  (receber-registro! cid pid (pokemon->registro pokemon hp-atual status)))

(defn receber-inicial!
  "Valida novamente após a consulta à API e registra a escolha junto com o Pokémon."
  [cid pid pokemon]
  (when (inicial-disponivel? cid pid)
    (swap! contas update-in [cid pid]
           (fn [c] (-> (or c conta-vazia)
                       (assoc "inicial-escolhido" true)
                       (update "equipe" conj (pokemon->registro pokemon (:hp pokemon) nil)))))
    (persistir!)
    true))

(defn receber-doacao!
  "Acrescenta um registro JÁ no formato persistido (ver pokemon->registro)
  direto na equipe do destinatário, preservando nível/hp-atual/status como
  estavam - usado por !pokemon doar (não reseta o pokémon doado)."
  [cid pid registro]
  (let [destino (receber-registro! cid pid registro)]
    (colecao-shiny! cid pid [registro])
    (persistir!)
    destino))

(defn remover-pokemon!
  "Remove o pokémon no índice (0-based) da equipe do jogador, ajustando o
  índice ativo se necessário (cai pro primeiro pokémon restante se o
  removido era o ativo). Retorna true se removeu, false se o índice não existe."
  [cid pid idx]
  (let [eq (vec (equipe cid pid))]
    (if (< -1 idx (count eq))
      (let [eq-nova     (vec (concat (subvec eq 0 idx) (subvec eq (inc idx))))
            ativo-atual (indice-ativo cid pid)
            ativo-novo  (cond
                          (empty? eq-nova)       0
                          (< idx ativo-atual)    (dec ativo-atual)
                          (= idx ativo-atual)    0
                          :else                  (min ativo-atual (dec (count eq-nova))))]
        (colecao-shiny! cid pid [(get eq idx)])
        (swap! contas update-in [cid pid] #(ajustar-times-remocao (assoc % "equipe" eq-nova "ativo" ativo-novo "inicial-escolhido" true) idx))
        (persistir!)
        true)
      false)))

;; A enfermaria guarda o registro completo fora da equipe enquanto o Pokémon
;; está sendo tratado. Assim ele não pode ser escolhido nem usado em batalha
;; antes de a Enfermeira Joy terminar o atendimento, inclusive após reiniciar
;; o bot (o horário de retorno também é persistido).
(def tempo-tratamento-minutos 30)
(def ^:private tempo-tratamento-ms (* tempo-tratamento-minutos 60 1000))

(defn recolher-curados!
  "Move para a equipe os Pokémon cujo tratamento já terminou, restaurando HP
  e removendo qualquer status. Retorna os registros que voltaram agora."
  [cid pid]
  (let [agora      (js/Date.now)
        conta-atual (conta cid pid)
        enfermaria (get conta-atual "enfermaria" [])
        prontos    (filter #(<= (get % "pronto-em" 0) agora) enfermaria)]
    (when (seq prontos)
      (let [em-tratamento (vec (remove #(<= (get % "pronto-em" 0) agora) enfermaria))
            curados       (mapv #(-> (get % "pokemon")
                                     (assoc "hp-atual" (get-in % ["pokemon" "hp"]))
                                     (assoc "status" nil)) prontos)
            equipe-atual  (vec (get conta-atual "equipe" []))]
        (swap! contas update-in [cid pid]
               (fn [c]
                 (let [c (or c conta-vazia)
                       c (reduce guardar-registro c curados)
                       equipe-nova (get c "equipe")]
                   (assoc c "enfermaria" em-tratamento
                            "equipe" equipe-nova
                            ;; se a equipe estava vazia, o primeiro que voltou
                            ;; deve poder ser usado imediatamente.
                            "ativo" (if (empty? equipe-atual) 0 (get c "ativo" 0))))))
        (persistir!)
        (ativar-favorito-se-disponivel! cid pid)
        curados))))

(defn enviar-ferido-para-enfermaria!
  "Envia o Pokémon da equipe no índice 0-based informado, desde que esteja
  ferido. Retorna seu registro; retorna nil se o índice não existe ou se ele
  já está saudável."
  [cid pid idx]
  (let [conta-atual (conta cid pid)
        equipe-atual (vec (get conta-atual "equipe" []))
        registro (get equipe-atual idx)]
    (when (and registro
               (or (< (get registro "hp-atual" 0) (get registro "hp" 0))
                   (some? (get registro "status"))))
      (let [agora (js/Date.now)
            equipe-nova (vec (concat (subvec equipe-atual 0 idx)
                                     (subvec equipe-atual (inc idx))))
            entrada {"pokemon" registro "pronto-em" (+ agora tempo-tratamento-ms)}]
        (swap! contas update-in [cid pid]
               (fn [c]
                 (let [c (ajustar-times-remocao (or c conta-vazia) idx)]
                   (assoc c "equipe" equipe-nova "inicial-escolhido" true
                            "enfermaria" (conj (vec (get c "enfermaria" [])) entrada)
                            "ativo" (if (empty? equipe-nova) 0
                                        (let [ativo (get c "ativo" 0)]
                                          (cond
                                            (< idx ativo) (dec ativo)
                                            (= idx ativo) 0
                                            :else (min ativo (dec (count equipe-nova))))))))))
        (persistir!)
        registro))))

(defn em-tratamento [cid pid]
  "Registros da enfermaria ainda não concluídos, com o horário de retorno."
  [cid pid]
  (get (conta cid pid) "enfermaria" []))

(defn atualizar-ativo!
  "Atualiza hp-atual/status do pokémon ATUALMENTE ativo do jogador - chamar
  depois de qualquer ação de batalha, pra dano/cura/status sobreviverem
  entre batalhas (a mecânica toda só faz sentido se isso for mantido em dia)."
  [cid pid hp-atual status]
  (let [idx (indice-ativo cid pid)]
    (when (get (equipe cid pid) idx)
      (swap! contas update-in [cid pid "equipe" idx]
             #(assoc % "hp-atual" hp-atual "status" (when status (name status))))
      (persistir!))))

(defn atualizar-no-indice!
  "Atualiza HP/status de qualquer Pokémon do time sem trocar o Pokémon ativo."
  [cid pid idx hp-atual status]
  (when (get (equipe cid pid) idx)
    (swap! contas update-in [cid pid "equipe" idx]
           #(assoc % "hp-atual" hp-atual "status" (when status (name status))))
    (persistir!)
    true))

(defn reviver! [cid pid idx]
  (let [registro (get (equipe cid pid) idx)]
    (cond
      (nil? registro) :invalido
      (not (zero? (get registro "hp-atual" (get registro "hp")))) :nao-desmaiado
      (not (loja/consumir-reviver! cid pid)) :sem-item
      :else
      (let [hp (get registro "hp")]
        (swap! contas update-in [cid pid "equipe" idx] #(assoc % "hp-atual" hp "status" nil))
        (persistir!)
        hp))))

(def cooldown-cacada-minutos 30)
(def ^:private cooldown-cacada-ms (* cooldown-cacada-minutos 60 1000))

(defn pode-cacar? [cid pid]
  (>= (- (js/Date.now) (get (conta cid pid) "ultima-cacada" 0)) cooldown-cacada-ms))

(defn segundos-restantes-cooldown [cid pid]
  (let [faltam (- cooldown-cacada-ms (- (js/Date.now) (get (conta cid pid) "ultima-cacada" 0)))]
    (max 0 (js/Math.ceil (/ faltam 1000)))))

(defn registrar-cacada! [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c] (assoc (or c conta-vazia) "ultima-cacada" (js/Date.now))))
  (persistir!))

(defn sequencia-capturas [cid pid]
  (get (conta cid pid) "sequencia-capturas" 0))

(defn maior-sequencia-capturas [cid pid]
  (max (sequencia-capturas cid pid)
       (get (conta cid pid) "maior-sequencia-capturas" 0)))

(defn registrar-avistamento! [cid pid pokemon]
  (let [chave (-> (:nome pokemon) str/lower-case (str/replace #"\s+" "-"))]
    (swap! contas update-in [cid pid]
           #(-> (or % conta-vazia)
                (update "avistamentos" (fnil inc 0))
                (update-in ["vistos" chave "vezes"] (fnil inc 0))
                (assoc-in ["vistos" chave "nome"] (:nome pokemon))))
    (persistir!)
    true))

(defn registrar-captura!
  "Registra a espécie na Pokédex pessoal, incrementa a sequência e retorna
  a nova sequência de capturas."
  ([cid pid pokemon] (registrar-captura! cid pid pokemon nil))
  ([cid pid pokemon nome-treinador]
  (let [chave (-> (:nome pokemon) str/lower-case (str/replace #"\s+" "-"))]
    (swap! contas update-in [cid pid]
           (fn [c]
             (-> (or c conta-vazia)
                 (update "sequencia-capturas" (fnil inc 0))
                 (#(assoc % "maior-sequencia-capturas"
                          (max (get % "maior-sequencia-capturas" 0)
                               (get % "sequencia-capturas" 0))))
                 (update-in ["pokedex" chave]
                            (fn [entrada]
                              {"nome" (:nome pokemon) "tipos" (vec (:tipos pokemon))
                               "raridade" (or (:raridade pokemon) "comum")
                               "capturas" (inc (get entrada "capturas" 0))
                               "maior-nivel" (max (or (:nivel pokemon) 1) (get entrada "maior-nivel" 0))
                               "shiny-capturados" (+ (get entrada "shiny-capturados" 0)
                                                       (if (:shiny? pokemon) 1 0))
                               "primeira-captura" (or (get entrada "primeira-captura") (js/Date.now))
                               "ultima-captura" (js/Date.now)})))))
    (when (:shiny? pokemon)
      (swap! contas assoc-in [cid pid "shiny-colecao" chave]
             {"nome" (:nome pokemon) "imagem" (:imagem pokemon)})
      (when-not (get @descobertas-globais chave)
        (swap! descobertas-globais assoc chave
               {"nome" (:nome pokemon) "pid" pid "treinador" (or nome-treinador pid)
                "quando" (js/Date.now)})
        (armazenamento/salvar! "pokemon-descobertas" @descobertas-globais)))
    (persistir!)
    (sequencia-capturas cid pid))))

(defn resumo-descobertas [cid pid]
  (let [c (conta cid pid)
        dex (get c "pokedex" {})
        vistos (get c "vistos" {})]
    {:vistos (count vistos)
     :avistamentos (get c "avistamentos" 0)
     :capturados (count dex)
     :capturas (reduce + 0 (map #(get % "capturas" 0) (vals dex)))
     :shiny (reduce + 0 (map #(get % "shiny-capturados" 0) (vals dex)))
     :primeiros-shiny (->> @descobertas-globais vals (filter #(= (get % "pid") pid)) vec)
     :primeiros-globais (->> @descobertas-globais vals (sort-by #(get % "quando" 0) >) (take 10) vec)}))

(defn amizade [cid pid idx]
  (get-in (conta cid pid) ["equipe" idx "amizade"] 70))

(defn ganhar-amizade! [cid pid idx quantidade]
  (when (get (equipe cid pid) idx)
    (swap! contas update-in [cid pid "equipe" idx "amizade"]
           #(min 255 (+ (or % 70) quantidade)))
    (persistir!)
    (amizade cid pid idx)))

(defn quebrar-sequencia-capturas! [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c]
           (assoc (or c conta-vazia)
                  "maior-sequencia-capturas" (max (get c "maior-sequencia-capturas" 0)
                                                  (get c "sequencia-capturas" 0))
                  "sequencia-capturas" 0)))
  (persistir!))

(defn pokedex-pessoal [cid pid]
  (get (conta cid pid) "pokedex" {}))

(defn atualizar-tipos-pokedex! [cid pid chave tipos]
  (when (and (seq tipos) (get-in @contas [cid pid "pokedex" chave]))
    (swap! contas assoc-in [cid pid "pokedex" chave "tipos"] (vec tipos))
    (persistir!)))

(defn sincronizar-pokedex-equipe!
  "Inclui na Pokédex espécies de times criados antes desse recurso, sem
  alterar a sequência nem duplicar contagens já existentes."
  [cid pid]
  (let [antes (pokedex-pessoal cid pid)
        depois (reduce (fn [dex registro]
                         (let [chave (-> (get registro "nome") str/lower-case (str/replace #"\s+" "-"))]
                           (if (contains? dex chave)
                             (assoc-in dex [chave "tipos"] (vec (get registro "tipos")))
                             (assoc dex chave {"nome" (get registro "nome")
                                               "tipos" (vec (get registro "tipos"))
                                               "raridade" (if (= versao-raridade (get registro "versao-raridade"))
                                                            (get registro "raridade" "comum")
                                                            (raridade-por-registro registro))
                                               "capturas" 1}))))
                       antes (concat (equipe cid pid) (pc cid pid)))]
    (when (not= antes depois)
      (swap! contas assoc-in [cid pid "pokedex"] depois)
      (persistir!))
    depois))

;; crescimento por XP de batalha ("igual no jogo original", simplificado
;; pra não precisar de uma curva de EXP real por grupo de crescimento):
;; sobe 1 nível a cada 9 XP (vitória dá 3, capturas variam por raridade e
;; sequência, e derrota dá 1; o ritmo anterior do PvP é preservado)
;; e todos os
;; stats crescem um fator fixo por nível, até um teto de 100 (mesmo limite
;; dos jogos originais). Público porque zapbot.pokemon.core precisa do MESMO
;; fator pra calcular stats pós-evolução.
(def xp-por-vitoria 3)
(def xp-por-derrota 1)

(defn xp-por-nocautes
  "XP de cada Pokémon que entrou na partida: 1, 3, 5 ou 7, até três nocautes."
  [nocautes]
  (+ 1 (* 2 (min 3 (max 0 nocautes)))))

(defn bonus-xp-sequencia-capturas
  "Bônus adicional à raridade: primeira captura +0, segunda +1, terceira em diante +2."
  [sequencia]
  (min 2 (max 0 (dec sequencia))))

(defn ganhar-xp-no-indice!
  "Concede XP ao pokémon no índice informado do jogador; só sobe de nível de verdade
  (nível/stats, respeitando o teto) a cada `xp-por-nivel` pontos acumulados.
  O HP atual ganha o mesmo
  incremento absoluto que o HP máximo quando sobe (não é um heal completo,
  só preserva o quanto já estava faltando); um pokémon desmaiado continua
  com 0 HP. Retorna {:nome :nivel} só
  quando REALMENTE sobe de nível, nil caso contrário (sem pokémon no índice informado,
  já no nível máximo, ou ainda falta XP). Registros antigos de progresso
  por vitória são convertidos sem perder o avanço já conquistado."
  [cid pid idx quantidade]
  (when-let [registro (get (equipe cid pid) idx)]
    (let [nivel-atual (get registro "nivel" 1)
          xp-anterior (get registro "xp-desde-nivel"
                           (* xp-por-vitoria (get registro "vitorias-desde-nivel" 0)))
          xp-total (+ xp-anterior (max 0 quantidade))
          subidas (if (< nivel-atual nivel-maximo)
                    (min (js/Math.floor (/ xp-total xp-por-nivel))
                         (- nivel-maximo nivel-atual))
                    0)
          nivel-final (+ nivel-atual subidas)
          xp-restante (if (= nivel-final nivel-maximo)
                        0
                        (- xp-total (* subidas xp-por-nivel)))
          registro-novo (-> registro
                            (assoc "amizade" (min 255 (+ (get registro "amizade" 70) 10)))
                            (assoc "xp-desde-nivel" xp-total)
                            normalizar-xp-registro)
          ligas-removidas (vec
                           (for [[id slots] (get (conta cid pid) "times-liga")
                                 :when (and (some #{idx} slots)
                                            (not (elegivel? (obter-liga id) registro-novo)))]
                             id))]
      (swap! contas update-in [cid pid]
             #(limpar-times (assoc-in % ["equipe" idx] registro-novo)))
      (persistir!)
      (when (pos? subidas)
        {:nome (get registro "nome")
         :nivel nivel-final
         :niveis-subidos subidas
         :ligas-removidas ligas-removidas}))))

(defn ganhar-xp!
  [cid pid quantidade]
  (ganhar-xp-no-indice! cid pid (indice-ativo cid pid) quantidade))

(defn registro-para-raid! [cid pid idx]
  (garantir-id-pokemon! cid pid idx))

(defn resgatar-xp-raids! [cid pid]
  (vec (for [[id quantidade] (get (conta cid pid) "xp-raids-pendente" {})
             :let [idx (first (keep-indexed #(when (= id (get %2 "id-pokemon")) %1)
                                           (equipe cid pid)))]
             :when (some? idx)]
         (let [subida (ganhar-xp-no-indice! cid pid idx quantidade)]
           (swap! contas update-in [cid pid "xp-raids-pendente"] dissoc id)
           (persistir!)
           {:indice idx :subida subida}))))

(defn premiar-progresso-raid! [cid pid dia registro]
  (when (not= dia (get (conta cid pid) "raid-progresso-dia"))
    (let [id (get registro "id-pokemon")
          idx (when id (first (keep-indexed
                              #(when (= id (get %2 "id-pokemon")) %1)
                              (equipe cid pid))))]
      (swap! contas update-in [cid pid]
             #(-> (or % conta-vazia)
                  (assoc "raid-progresso-dia" dia)
                  (update "pe-raids" (fnil + 0) 6)
                  (cond-> id (update-in ["xp-raids-pendente" id] (fnil + 0) 6))))
      (persistir!)
      (let [resgate (first (filter #(= idx (:indice %)) (resgatar-xp-raids! cid pid)))]
        {:pe 6 :xp (if id 6 0) :pendente? (and id (nil? idx)) :indice idx
         :subida (:subida resgate)}))))

(defn subir-nivel!
  "Concede ao pokémon ativo o XP de uma vitória."
  [cid pid]
  (ganhar-xp! cid pid xp-por-vitoria))

(defn progresso-xp
  "Retorna o XP atual e o necessário para o próximo nível do registro."
  [registro]
  {:atual (get registro "xp-desde-nivel"
               (* xp-por-vitoria (get registro "vitorias-desde-nivel" 0)))
   :necessario xp-por-nivel})

(defn evoluir-no-indice!
  "Substitui os campos derivados de espécie (nome/imagem/tipos/habilidade/
  stats) do pokémon no índice informado do jogador - usado quando ele evolui. Golpes,
  status e nível não mudam; hp-atual ganha o mesmo incremento absoluto que
  o HP máximo (mesma regra do subir-nivel!). Retorna true se aplicou,
  false se não tinha pokémon no índice informado."
  [cid pid idx {:keys [nome-novo imagem imagem-shiny tipos habilidade hp ataque defesa atq-esp def-esp veloc]}]
  (do
    (if-let [registro (get (equipe cid pid) idx)]
      (let [incremento-hp (- hp (get registro "hp"))]
        (colecao-shiny! cid pid [registro])
        (swap! contas update-in [cid pid "equipe" idx]
               #(-> %
                    (assoc "nome" nome-novo "imagem" (if (get registro "shiny") (or imagem-shiny imagem) imagem)
                           "imagem-shiny" imagem-shiny "tipos" (vec tipos) "habilidade" habilidade
                           "hp" hp "ataque" ataque "defesa" defesa "atq-esp" atq-esp "def-esp" def-esp
                           "veloc" veloc)
                    (update "golpes" (fn [gs]
                                        (mapv golpe->registro
                                              (garantir-ataque-do-tipo (mapv golpe<-registro gs) tipos))))
                    (update "hp-atual" (fn [hp-atual] (if (pos? hp-atual) (+ hp-atual incremento-hp) 0)))))
        (colecao-shiny! cid pid [(get (equipe cid pid) idx)])
        (persistir!)
        true)
      false)))


(defn evoluir-ativo!
  [cid pid dados]
  (evoluir-no-indice! cid pid (indice-ativo cid pid) dados))

(defn registrar-doacao! [cid pid]
  (swap! contas update-in [cid pid "doacoes-pokemon"] (fnil inc 0))
  (persistir!))

(def ^:private catalogo-insignias
  [{:nome "Doador de Pokémon" :requisito "Doar 1 Pokémon" :metrica :doacoes :minimo 1 :xp-recompensa 3}
   {:nome "Doador Generoso" :requisito "Doar 10 Pokémon" :metrica :doacoes :minimo 10 :xp-recompensa 6}
   {:nome "Benfeitor Pokémon" :requisito "Doar 50 Pokémon" :metrica :doacoes :minimo 50 :xp-recompensa 12}
   {:nome "Mestre das Doações" :requisito "Doar 100 Pokémon" :metrica :doacoes :minimo 100 :xp-recompensa 24}
   {:nome "Primeira vitória" :requisito "1 vitória" :metrica :vitorias :minimo 1 :xp-recompensa 3}
   {:nome "Batalhador" :requisito "10 vitórias" :metrica :vitorias :minimo 10 :xp-recompensa 6}
   {:nome "Veterano" :requisito "50 vitórias" :metrica :vitorias :minimo 50 :xp-recompensa 12}
   {:nome "Campeão" :requisito "100 vitórias" :metrica :vitorias :minimo 100 :xp-recompensa 24}
   {:nome "Capturador" :requisito "3 capturas seguidas" :metrica :capturas :minimo 3 :xp-recompensa 3}
   {:nome "Caçador" :requisito "5 capturas seguidas" :metrica :capturas :minimo 5 :xp-recompensa 6}
   {:nome "Especialista" :requisito "10 capturas seguidas" :metrica :capturas :minimo 10 :xp-recompensa 12}
   {:nome "Mestre da captura" :requisito "20 capturas seguidas" :metrica :capturas :minimo 20 :xp-recompensa 24}])

(defn insignias-treinador [cid pid]
  (let [metricas {:doacoes (get (conta cid pid) "doacoes-pokemon" 0)
                  :vitorias (get (conta cid pid) "vitorias-treinador" 0)
                  :capturas (maior-sequencia-capturas cid pid)}]
    (mapv #(assoc % :conquistada? (>= (get metricas (:metrica %)) (:minimo %)))
          catalogo-insignias)))

(defn titulos-disponiveis [cid pid]
  (mapv :nome (filter :conquistada? (insignias-treinador cid pid))))

(defn titulo-selecionado [cid pid]
  (get (conta cid pid) "titulo"))

(defn selecionar-titulo! [cid pid numero]
  (let [titulos (titulos-disponiveis cid pid)
        idx (dec numero)]
    (if-let [titulo (get titulos idx)]
      (do (swap! contas assoc-in [cid pid "titulo"] titulo)
          (persistir!)
          {:status :ok :titulo titulo})
      {:status :invalido :titulos titulos})))

(defn xp-insignias [cid pid]
  ;; Cada conquista permanente contribui uma única vez para o total. Derivar
  ;; dos marcos persistidos também reconhece contas antigas, sem duplicar XP
  ;; ao consultar o perfil, reiniciar o bot ou repetir uma sequência.
  (reduce + 0 (map :xp-recompensa (filter :conquistada? (insignias-treinador cid pid)))))

(defn xp-treinador [cid pid]
  (+ (get (conta cid pid) "vitorias-treinador" 0) (xp-insignias cid pid) (loja/xp-missoes cid pid)
     (get (conta cid pid) "pe-ginasios" 0) (get (conta cid pid) "pe-raids" 0)))

(defn ganhar-pe-ginasio! [cid pid quantidade]
  (swap! contas update-in [cid pid "pe-ginasios"] (fnil + 0) quantidade)
  (persistir!))

(defn progresso-treinador
  "Distribui o XP total entre níveis com custos de 5, 7, 9, 11... XP."
  [xp]
  (loop [nivel 1 restante (max 0 xp) necessario 5]
    (if (< restante necessario)
      {:nivel nivel :xp-atual restante :xp-necessario necessario}
      (recur (inc nivel) (- restante necessario) (+ necessario 2)))))

(defn nivel-jogador
  "Nível calculado pelo XP de vitórias, insígnias e missões. Também calibra as caçadas."
  [cid pid]
  (:nivel (progresso-treinador (xp-treinador cid pid))))

(defn registrar-vitoria-treinador!
  "Registra uma vitória real. O XP das insígnias não altera esse contador."
  [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c] (update (or c conta-vazia) "vitorias-treinador" (fnil inc 0))))
  (persistir!))

(defn perfil-treinador
  "Inclui XP por vitórias, insígnias e missões resgatadas, sem repetir recompensas."
  [cid pid]
  (let [xp (xp-treinador cid pid)
        {:keys [nivel xp-atual xp-necessario]} (progresso-treinador xp)
        recorde (maior-sequencia-capturas cid pid)]
    {:nivel nivel
     :xp xp :xp-insignias (xp-insignias cid pid) :xp-missoes (loja/xp-missoes cid pid)
     :pe-ginasios (get (conta cid pid) "pe-ginasios" 0)
     :pe-raids (get (conta cid pid) "pe-raids" 0)
     :xp-atual xp-atual
     :xp-necessario xp-necessario
     :sequencia (sequencia-capturas cid pid) :recorde recorde
     :titulo (titulo-selecionado cid pid)
     :insignias (insignias-treinador cid pid)}))

;; Ginásios e trocas preservam registros completos e persistem no mesmo estado.
(defn insignias-ginasio [cid pid]
  (get (conta cid pid) "ginasios" {}))

(defn registrar-ginasio! [cid pid id dia]
  (let [anterior (get (insignias-ginasio cid pid) id)]
    (when (not= anterior dia)
      (swap! contas assoc-in [cid pid "ginasios" id] dia)
      (persistir!)
      (if anterior :revanche :primeira))))

(defn trocar-registros! [cid a ia ra b ib rb]
  (when (and (not= a b) ra rb
             (= ra (get (equipe cid a) ia))
             (= rb (get (equipe cid b) ib)))
    (swap! contas
           (fn [estado]
             (-> estado
                 (assoc-in [cid a "equipe" ia] rb)
                 (assoc-in [cid b "equipe" ib] ra)
                 (update-in [cid a] limpar-times)
                 (update-in [cid b] limpar-times))))
    (persistir!)
    true))

(defn atualizar-especie! [cid pid idx pokemon]
  (swap! contas update-in [cid pid "equipe" idx]
         #(assoc % "taxa-captura" (:taxa-captura pokemon)
                   "raridade" (:raridade pokemon)))
  (persistir!))

(defn time-ginasio [cid pid]
  (get (conta cid pid) "time-ginasio" []))

(defn salvar-time-ginasio! [cid pid indices]
  (swap! contas assoc-in [cid pid "time-ginasio"] (vec indices))
  (persistir!))

(defn colecao-shiny! [cid pid registros]
  ;; Recupera os shiny ainda disponíveis de contas anteriores à coleção histórica.
  (let [antes (get (conta cid pid) "shiny-colecao" {})
        depois (reduce (fn [dex r]
                         (if (get r "shiny")
                           (assoc dex (-> (get r "nome") str/lower-case (str/replace #"\s+" "-"))
                                  {"nome" (get r "nome") "imagem" (get r "imagem")}) dex))
                       antes registros)]
    (when (not= antes depois)
      (swap! contas assoc-in [cid pid "shiny-colecao"] depois)
      (persistir!))
    depois))
