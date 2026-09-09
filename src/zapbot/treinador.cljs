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
            [zapbot.golpes :as golpes]
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
  "Converte um pokémon (mapa interno do zapbot.pokemon, chaves keyword) +
  hp-atual/status pro formato persistido (chaves string) guardado na equipe."
  [pokemon hp-atual status]
  {"nome" (:nome pokemon) "imagem" (:imagem pokemon) "tipos" (vec (:tipos pokemon))
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
  (update c "times-liga"
          (fn [times]
            (into {} (map (fn [[id slots]]
                            [id (mapv #(cond (nil? %) nil (= % idx) nil (> % idx) (dec %) :else %) slots)])
                          times)))))

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

(defn niveis-time [cid pid id]
  (sort (map #(get (get (equipe cid pid) %) "nivel" 1) (time-liga cid pid id))))

(defn times-compativeis? [a b]
  (and (= 3 (count a) (count b))
       (every? true? (map #(<= (js/Math.abs (- %1 %2)) 5) (sort a) (sort b)))))

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

(defn aprender-golpe-ativo!
  [cid pid golpe]
  (aprender-golpe-no-indice! cid pid (indice-ativo cid pid) golpe))

(defn golpes-removidos
  "Nomes dos golpes que o dono mandou remover desse pokémon. Guardados por
  NOME (e não por posição) porque a lista de golpes é regerada inteira a
  cada subida de nível - ver zapbot.pokemon/atualizar-golpes-por-nivel!."
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
                     (update "golpes" #(let [v (vec %)]
                                         (vec (concat (subvec v 0 indice) (subvec v (inc indice))))))
                     (update "golpes-removidos" #(vec (distinct (conj (vec %) (golpes/chave (golpe<-registro golpe)))))))))
        (persistir!)
        nome))))

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
        (swap! contas update-in [cid pid] #(ajustar-times-remocao (assoc % "equipe" eq-nova "ativo" ativo-novo) idx))
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
                 (let [c (ajustar-times-remocao (or c conta-vazia) idx)]
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

(defn maior-sequencia-capturas [cid pid]
  (max (sequencia-capturas cid pid)
       (get (conta cid pid) "maior-sequencia-capturas" 0)))

(defn registrar-captura!
  "Registra a espécie na Pokédex pessoal, incrementa a sequência e retorna
  a nova sequência de capturas."
  [cid pid pokemon]
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
                              {"nome" (:nome pokemon)
                               "raridade" (or (:raridade pokemon) "comum")
                               "capturas" (inc (get entrada "capturas" 0))})))))
    (persistir!)
    (sequencia-capturas cid pid)))

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
  (do
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
                    ligas-removidas (vec (for [[id slots] (get (conta cid pid) "times-liga")
                                              :when (and (some #{idx} slots)
                                                         (not (elegivel? (obter-liga id) (assoc registro "nivel" (inc nivel-atual)))))] id))
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
                (swap! contas update-in [cid pid]
                       #(limpar-times (assoc-in % ["equipe" idx] registro-novo)))
                (persistir!)
                {:nome (get registro "nome") :nivel (inc nivel-atual) :ligas-removidas ligas-removidas}))))))))

(defn ganhar-xp!
  [cid pid quantidade]
  (ganhar-xp-no-indice! cid pid (indice-ativo cid pid) quantidade))

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
  [cid pid idx {:keys [nome-novo imagem tipos habilidade hp ataque defesa atq-esp def-esp veloc]}]
  (do
    (if-let [registro (get (equipe cid pid) idx)]
      (let [incremento-hp (- hp (get registro "hp"))]
        (swap! contas update-in [cid pid "equipe" idx]
               #(-> %
                    (assoc "nome" nome-novo "imagem" imagem "tipos" (vec tipos) "habilidade" habilidade
                           "hp" hp "ataque" ataque "defesa" defesa "atq-esp" atq-esp "def-esp" def-esp
                           "veloc" veloc)
                    (update "golpes" (fn [gs]
                                        (mapv golpe->registro
                                              (garantir-ataque-do-tipo (mapv golpe<-registro gs) tipos))))
                    (update "hp-atual" (fn [hp-atual] (if (pos? hp-atual) (+ hp-atual incremento-hp) 0)))))
        (persistir!)
        true)
      false)))


(defn evoluir-ativo!
  [cid pid dados]
  (evoluir-no-indice! cid pid (indice-ativo cid pid) dados))

(def ^:private catalogo-insignias
  [{:nome "Primeira vitória" :requisito "1 vitória" :metrica :vitorias :minimo 1 :xp-recompensa 3}
   {:nome "Batalhador" :requisito "10 vitórias" :metrica :vitorias :minimo 10 :xp-recompensa 6}
   {:nome "Veterano" :requisito "50 vitórias" :metrica :vitorias :minimo 50 :xp-recompensa 12}
   {:nome "Campeão" :requisito "100 vitórias" :metrica :vitorias :minimo 100 :xp-recompensa 24}
   {:nome "Capturador" :requisito "3 capturas seguidas" :metrica :capturas :minimo 3 :xp-recompensa 3}
   {:nome "Caçador" :requisito "5 capturas seguidas" :metrica :capturas :minimo 5 :xp-recompensa 6}
   {:nome "Especialista" :requisito "10 capturas seguidas" :metrica :capturas :minimo 10 :xp-recompensa 12}
   {:nome "Mestre da captura" :requisito "20 capturas seguidas" :metrica :capturas :minimo 20 :xp-recompensa 24}])

(defn insignias-treinador [cid pid]
  (let [metricas {:vitorias (get (conta cid pid) "vitorias-treinador" 0)
                  :capturas (maior-sequencia-capturas cid pid)}]
    (mapv #(assoc % :conquistada? (>= (get metricas (:metrica %)) (:minimo %)))
          catalogo-insignias)))

(defn xp-insignias [cid pid]
  ;; Cada conquista permanente contribui uma única vez para o total. Derivar
  ;; dos marcos persistidos também reconhece contas antigas, sem duplicar XP
  ;; ao consultar o perfil, reiniciar o bot ou repetir uma sequência.
  (reduce + 0 (map :xp-recompensa (filter :conquistada? (insignias-treinador cid pid)))))

(defn xp-treinador [cid pid]
  (+ (get (conta cid pid) "vitorias-treinador" 0) (xp-insignias cid pid)))

(defn progresso-treinador
  "Distribui o XP total entre níveis com custos de 5, 7, 9, 11... XP."
  [xp]
  (loop [nivel 1 restante (max 0 xp) necessario 5]
    (if (< restante necessario)
      {:nivel nivel :xp-atual restante :xp-necessario necessario}
      (recur (inc nivel) (- restante necessario) (+ necessario 2)))))

(defn nivel-jogador
  "Nível calculado pelo XP de vitórias e insígnias. Também calibra as caçadas."
  [cid pid]
  (:nivel (progresso-treinador (xp-treinador cid pid))))

(defn registrar-vitoria-treinador!
  "Registra uma vitória real. O XP das insígnias não altera esse contador."
  [cid pid]
  (swap! contas update-in [cid pid]
         (fn [c] (update (or c conta-vazia) "vitorias-treinador" (fnil inc 0))))
  (persistir!))

(defn perfil-treinador
  "Inclui o XP por vitórias e por insígnias permanentes, sem repetir recompensas."
  [cid pid]
  (let [xp (xp-treinador cid pid)
        {:keys [nivel xp-atual xp-necessario]} (progresso-treinador xp)
        recorde (maior-sequencia-capturas cid pid)]
    {:nivel nivel
     :xp xp :xp-insignias (xp-insignias cid pid)
     :xp-atual xp-atual
     :xp-necessario xp-necessario
     :sequencia (sequencia-capturas cid pid) :recorde recorde
     :insignias (insignias-treinador cid pid)}))
