(ns zapbot.treinador
  "Estado de 'treinador' de cada jogador pro !pokemon: time de pokémons
  capturados (persistido), qual está ativo pra batalhar, o cooldown de
  caçada, e o nível do treinador (contador próprio - ver nivel-jogador -
  separado de propósito do placar geral do zapbot.rank: !rank é
  compartilhado com velha/naval/quiz pra ranking geral, esse contador é
  só pra calibrar a força do pokémon selvagem sorteado na caçada).
  Convenção de persistência (ver zapbot.armazenamento): chaves sempre
  string, nunca keyword - por isso os pokémons da equipe são guardados num
  formato próprio (ver pokemon->registro/registro->pokemon), diferente do
  mapa interno (chaves keyword) que o zapbot.pokemon usa durante a batalha."
  (:require [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]))

(defonce ^:private contas (atom (or (armazenamento/obter "treinador") {})))
(armazenamento/registrar! "treinador" contas)

(defn- persistir! []
  (armazenamento/salvar! "treinador" @contas))

(def ^:private conta-vazia {"equipe" [] "ativo" 0 "ultima-cacada" 0 "vitorias-treinador" 0
                            "enfermaria" [] "sequencia-capturas" 0 "pokedex" {}})

(defn- conta [cid pid]
  (get-in @contas [cid pid] conta-vazia))

(defn- golpe->registro [g]
  {"nome-exibicao" (:nome-exibicao g) "tipo" (:tipo g) "poder" (:poder g) "classe" (name (:classe g))
   "alvo" (when (:alvo g) (name (:alvo g)))
   "precisao" (:precisao g) "status-causado" (some-> (:status-causado g) name)
   "chance-status" (:chance-status g) "cura" (:cura g) "dreno" (:dreno g)
   "recuo" (:recuo g) "min-acertos" (:min-acertos g) "max-acertos" (:max-acertos g)
   "alteracoes" (mapv (fn [{:keys [atributo estagios]}]
                          {"atributo" (name atributo) "estagios" estagios})
                        (:alteracoes g))})

(defn- golpe<-registro [g]
  {:nome-exibicao (get g "nome-exibicao") :tipo (get g "tipo") :poder (get g "poder")
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

(defn pokemon->registro
  "Converte um pokémon (mapa interno do zapbot.pokemon, chaves keyword) +
  hp-atual/status pro formato persistido (chaves string) guardado na equipe."
  [pokemon hp-atual status]
  {"nome" (:nome pokemon) "imagem" (:imagem pokemon) "tipos" (vec (:tipos pokemon))
   "habilidade" (:habilidade pokemon) "hp" (:hp pokemon) "ataque" (:ataque pokemon)
   "defesa" (:defesa pokemon) "atq-esp" (:atq-esp pokemon) "def-esp" (:def-esp pokemon)
   "veloc" (:veloc pokemon) "golpes" (mapv golpe->registro (:golpes pokemon))
   "versao-golpes" versao-golpes
   "hp-atual" hp-atual "status" (when status (name status)) "nivel" (or (:nivel pokemon) 1)
   "raridade" (or (:raridade pokemon) "comum") "versao-raridade" versao-raridade
   "lendario-api" (boolean (:lendario-api? pokemon))
   "mitico-api" (boolean (:mitico-api? pokemon))
   "paradox-api" (boolean (:paradox-api? pokemon))
   "taxa-captura" (:taxa-captura pokemon)
   "item" (:item pokemon)})

(defn registro->pokemon
  "Converte um registro da equipe (chaves string) de volta pro formato
  interno do zapbot.pokemon (chaves keyword). Retorna [pokemon hp-atual status]."
  [registro]
  [{:nome (get registro "nome") :imagem (get registro "imagem") :tipos (vec (get registro "tipos"))
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
  "Vetor de registros (chaves string, ver pokemon->registro) da equipe do
  jogador nesse chat."
  [cid pid]
  (get (conta cid pid) "equipe"))

(defn tem-pokemon? [cid pid]
  (pos? (count (equipe cid pid))))

(defn indice-ativo [cid pid]
  (get (conta cid pid) "ativo" 0))

(defn pokemon-ativo
  "[pokemon hp-atual status] do pokémon ativo do jogador, ou nil se ele
  ainda não tiver nenhum na equipe."
  [cid pid]
  (when-let [registro (get (equipe cid pid) (indice-ativo cid pid))]
    (registro->pokemon registro)))

(defn definir-ativo!
  "Define o índice (0-based) ativo, se existir na equipe. Retorna true se
  definiu, false se o índice não existe."
  [cid pid idx]
  (if (contains? (vec (equipe cid pid)) idx)
    (do (swap! contas assoc-in [cid pid "ativo"] idx) (persistir!) true)
    false))

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

(defn atualizar-golpes-ativo!
  "Substitui os golpes do pokémon ativo, preservando todos os demais dados."
  [cid pid golpes]
  (let [idx (indice-ativo cid pid)]
    (when (get (equipe cid pid) idx)
      (swap! contas update-in [cid pid "equipe" idx]
             #(assoc % "golpes" (mapv golpe->registro golpes) "versao-golpes" versao-golpes))
      (persistir!)
      true)))

(defn golpes-atuais?
  "Indica se o pokémon ativo já recebeu a regra atual de golpes por nível."
  [cid pid]
  (= versao-golpes (get-in @contas [cid pid "equipe" (indice-ativo cid pid) "versao-golpes"])))

(defn adicionar-pokemon!
  "Acrescenta um pokémon (mapa interno do zapbot.pokemon + hp-atual/status)
  na equipe do jogador nesse chat; se for o primeiro, já fica ativo (índice
  0) automaticamente. Retorna o índice (0-based) dele na equipe nova."
  [cid pid pokemon hp-atual status]
  (swap! contas update-in [cid pid]
         (fn [c] (update (or c conta-vazia) "equipe" conj (pokemon->registro pokemon hp-atual status))))
  (persistir!)
  (dec (count (equipe cid pid))))

(defn receber-doacao!
  "Acrescenta um registro JÁ no formato persistido (ver pokemon->registro)
  direto na equipe do destinatário, preservando nível/hp-atual/status como
  estavam - usado por !pokemon doar (não reseta o pokémon doado)."
  [cid pid registro]
  (swap! contas update-in [cid pid] (fn [c] (update (or c conta-vazia) "equipe" conj registro)))
  (persistir!))

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
        (swap! contas update-in [cid pid] #(assoc % "equipe" eq-nova "ativo" ativo-novo))
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
                       equipe-nova (into (vec (get c "equipe" [])) curados)]
                   (assoc c "enfermaria" em-tratamento
                            "equipe" equipe-nova
                            ;; se a equipe estava vazia, o primeiro que voltou
                            ;; deve poder ser usado imediatamente.
                            "ativo" (if (empty? equipe-atual) 0 (get c "ativo" 0))))))
        (persistir!)
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
                 (let [c (or c conta-vazia)]
                   (assoc c "equipe" equipe-nova
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

(defn registrar-captura!
  "Registra a espécie na Pokédex pessoal, incrementa a sequência e retorna
  a nova sequência de capturas."
  [cid pid pokemon]
  (let [chave (-> (:nome pokemon) str/lower-case (str/replace #"\s+" "-"))]
    (swap! contas update-in [cid pid]
           (fn [c]
             (-> (or c conta-vazia)
                 (update "sequencia-capturas" (fnil inc 0))
                 (update-in ["pokedex" chave]
                            (fn [entrada]
                              {"nome" (:nome pokemon)
                               "raridade" (or (:raridade pokemon) "comum")
                               "capturas" (inc (get entrada "capturas" 0))})))))
    (persistir!)
    (sequencia-capturas cid pid)))

(defn quebrar-sequencia-capturas! [cid pid]
  (swap! contas assoc-in [cid pid "sequencia-capturas"] 0)
  (persistir!))

(defn pokedex-pessoal [cid pid]
  (get (conta cid pid) "pokedex" {}))

(defn sincronizar-pokedex-equipe!
  "Inclui na Pokédex espécies de times criados antes desse recurso, sem
  alterar a sequência nem duplicar contagens já existentes."
  [cid pid]
  (let [antes (pokedex-pessoal cid pid)
        depois (reduce (fn [dex registro]
                         (let [chave (-> (get registro "nome") str/lower-case (str/replace #"\s+" "-"))]
                           (if (contains? dex chave)
                             dex
                             (assoc dex chave {"nome" (get registro "nome")
                                               "raridade" (if (= versao-raridade (get registro "versao-raridade"))
                                                            (get registro "raridade" "comum")
                                                            (raridade-por-registro registro))
                                               "capturas" 1}))))
                       antes (equipe cid pid))]
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
;; dos jogos originais). Público porque zapbot.pokemon precisa do MESMO
;; fator pra calcular stats pós-evolução.
(def ^:private nivel-maximo 100)
(def fator-crescimento-por-nivel 1.03)
(def ^:private xp-por-nivel 9)
(def xp-por-vitoria 3)
(def xp-por-derrota 1)
(def ^:private vitorias-treinador-por-nivel 3)

(defn ganhar-xp!
  "Concede XP ao pokémon ATIVO do jogador; só sobe de nível de verdade
  (nível/stats, respeitando o teto) a cada `xp-por-nivel` pontos acumulados.
  O HP atual ganha o mesmo
  incremento absoluto que o HP máximo quando sobe (não é um heal completo,
  só preserva o quanto já estava faltando); um pokémon desmaiado continua
  com 0 HP. Retorna {:nome :nivel} só
  quando REALMENTE sobe de nível, nil caso contrário (sem pokémon ativo,
  já no nível máximo, ou ainda falta XP). Registros antigos de progresso
  por vitória são convertidos sem perder o avanço já conquistado."
  [cid pid quantidade]
  (let [idx (indice-ativo cid pid)]
    (when-let [registro (get (equipe cid pid) idx)]
      (let [nivel-atual (get registro "nivel" 1)]
        (when (< nivel-atual nivel-maximo)
          (let [xp-anterior (get registro "xp-desde-nivel"
                                 (* xp-por-vitoria (get registro "vitorias-desde-nivel" 0)))
                xp-novo     (+ xp-anterior quantidade)]
            (if (< xp-novo xp-por-nivel)
              (do (swap! contas update-in [cid pid "equipe" idx]
                         #(-> % (assoc "xp-desde-nivel" xp-novo) (dissoc "vitorias-desde-nivel")))
                  (persistir!)
                  nil)
              (let [crescer       #(js/Math.round (* % fator-crescimento-por-nivel))
                    hp-max-antigo (get registro "hp")
                    hp-max-novo   (crescer hp-max-antigo)
                    incremento-hp (- hp-max-novo hp-max-antigo)
                    registro-novo (-> registro
                                      (assoc "nivel" (inc nivel-atual))
                                      (assoc "xp-desde-nivel" (- xp-novo xp-por-nivel))
                                      (dissoc "vitorias-desde-nivel")
                                      (assoc "hp" hp-max-novo)
                                      (update "hp-atual" #(if (pos? %) (+ % incremento-hp) 0))
                                      (update "ataque" crescer)
                                      (update "defesa" crescer)
                                      (update "atq-esp" crescer)
                                      (update "def-esp" crescer)
                                      (update "veloc" crescer))]
                (swap! contas assoc-in [cid pid "equipe" idx] registro-novo)
                (persistir!)
                {:nome (get registro "nome") :nivel (inc nivel-atual)}))))))))

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

(defn evoluir-ativo!
  "Substitui os campos derivados de espécie (nome/imagem/tipos/habilidade/
  stats) do pokémon ATIVO do jogador - usado quando ele evolui. Golpes,
  status e nível não mudam; hp-atual ganha o mesmo incremento absoluto que
  o HP máximo (mesma regra do subir-nivel!). Retorna true se aplicou,
  false se não tinha pokémon ativo."
  [cid pid {:keys [nome-novo imagem tipos habilidade hp ataque defesa atq-esp def-esp veloc]}]
  (let [idx (indice-ativo cid pid)]
    (if-let [registro (get (equipe cid pid) idx)]
      (let [incremento-hp (- hp (get registro "hp"))]
        (swap! contas update-in [cid pid "equipe" idx]
               #(-> %
                    (assoc "nome" nome-novo "imagem" imagem "tipos" (vec tipos) "habilidade" habilidade
                           "hp" hp "ataque" ataque "defesa" defesa "atq-esp" atq-esp "def-esp" def-esp
                           "veloc" veloc)
                    (update "hp-atual" + incremento-hp)))
        (persistir!)
        true)
      false)))


(defn nivel-jogador
  "Nível do treinador (usado pra exibir em !pokemon time/cacar e calibrar a
  força do pokémon selvagem sorteado na caçada) - contador próprio
  (\"vitorias-treinador\"), independente do placar geral do !rank: nível 1
  sem nenhuma vitória, +1 nível a cada `vitorias-treinador-por-nivel`
  vitórias de
  batalha em !pokemon (1 vitória = 1 nível subia rápido demais)."
  [cid pid]
  (inc (quot (get (conta cid pid) "vitorias-treinador" 0)
             vitorias-treinador-por-nivel)))

(defn registrar-vitoria-treinador!
  "Chamar quando o jogador vence uma batalha de !pokemon - soma 1 na
  contagem que define o nível do treinador (ver nivel-jogador)."
  [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c] (update (or c conta-vazia) "vitorias-treinador" (fnil inc 0))))
  (persistir!))
