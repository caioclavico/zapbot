(ns zapbot.treinador
  "Estado de 'treinador' de cada jogador pro !pokemon: time de pokémons
  capturados (persistido), qual está ativo pra batalhar, e o cooldown de
  caçada. Nível do jogador (usado pra calibrar a força do pokémon selvagem
  sorteado na caçada) é derivado das vitórias de !pokemon já registradas em
  zapbot.rank - não guarda um contador de XP à parte.
  Convenção de persistência (ver zapbot.armazenamento): chaves sempre
  string, nunca keyword - por isso os pokémons da equipe são guardados num
  formato próprio (ver pokemon->registro/registro->pokemon), diferente do
  mapa interno (chaves keyword) que o zapbot.pokemon usa durante a batalha."
  (:require [zapbot.armazenamento :as armazenamento]
            [zapbot.rank :as rank]))

(defonce ^:private contas (atom (or (armazenamento/obter "treinador") {})))
(armazenamento/registrar! "treinador" contas)

(defn- persistir! []
  (armazenamento/salvar! "treinador" @contas))

(def ^:private conta-vazia {"equipe" [] "ativo" 0 "ultima-cacada" 0})

(defn- conta [cid pid]
  (get-in @contas [cid pid] conta-vazia))

(defn- golpe->registro [g]
  {"nome-exibicao" (:nome-exibicao g) "tipo" (:tipo g) "poder" (:poder g) "classe" (name (:classe g))})

(defn- golpe<-registro [g]
  {:nome-exibicao (get g "nome-exibicao") :tipo (get g "tipo") :poder (get g "poder")
   :classe (keyword (get g "classe"))})

(defn pokemon->registro
  "Converte um pokémon (mapa interno do zapbot.pokemon, chaves keyword) +
  hp-atual/status pro formato persistido (chaves string) guardado na equipe."
  [pokemon hp-atual status]
  {"nome" (:nome pokemon) "imagem" (:imagem pokemon) "tipos" (vec (:tipos pokemon))
   "habilidade" (:habilidade pokemon) "hp" (:hp pokemon) "ataque" (:ataque pokemon)
   "defesa" (:defesa pokemon) "atq-esp" (:atq-esp pokemon) "def-esp" (:def-esp pokemon)
   "veloc" (:veloc pokemon) "golpes" (mapv golpe->registro (:golpes pokemon))
   "hp-atual" hp-atual "status" (when status (name status)) "nivel" (or (:nivel pokemon) 1)})

(defn registro->pokemon
  "Converte um registro da equipe (chaves string) de volta pro formato
  interno do zapbot.pokemon (chaves keyword). Retorna [pokemon hp-atual status]."
  [registro]
  [{:nome (get registro "nome") :imagem (get registro "imagem") :tipos (vec (get registro "tipos"))
    :habilidade (get registro "habilidade") :hp (get registro "hp") :ataque (get registro "ataque")
    :defesa (get registro "defesa") :atq-esp (get registro "atq-esp") :def-esp (get registro "def-esp")
    :veloc (get registro "veloc") :golpes (mapv golpe<-registro (get registro "golpes"))
    :nivel (get registro "nivel" 1)}
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

(def cooldown-cacada-minutos 5)
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

;; crescimento por batalha vencida ("igual no jogo original", simplificado
;; pra não precisar de uma curva de EXP real por grupo de crescimento):
;; sobe 1 nível e todos os stats crescem um fator fixo por nível, até um
;; teto de 100 (mesmo limite dos jogos originais). Público porque
;; zapbot.pokemon precisa do MESMO fator pra calcular stats pós-evolução.
(def ^:private nivel-maximo 100)
(def fator-crescimento-por-nivel 1.03)

(defn subir-nivel!
  "Sobe 1 nível o pokémon ATIVO do jogador (se ele ainda não estiver no
  nível máximo) e aumenta seus stats pelo fator de crescimento - o HP
  atual ganha o mesmo incremento absoluto que o HP máximo (não é um heal
  completo, só preserva o quanto já estava faltando). Retorna {:nome
  :nivel} se subiu, nil se não tinha pokémon ativo ou já estava no nível
  máximo (chamar depois de vitória em batalha)."
  [cid pid]
  (let [idx (indice-ativo cid pid)]
    (when-let [registro (get (equipe cid pid) idx)]
      (let [nivel-atual (get registro "nivel" 1)]
        (when (< nivel-atual nivel-maximo)
          (let [crescer       #(js/Math.round (* % fator-crescimento-por-nivel))
                hp-max-antigo (get registro "hp")
                hp-max-novo   (crescer hp-max-antigo)
                incremento-hp (- hp-max-novo hp-max-antigo)
                registro-novo (-> registro
                                  (assoc "nivel" (inc nivel-atual))
                                  (assoc "hp" hp-max-novo)
                                  (update "hp-atual" + incremento-hp)
                                  (update "ataque" crescer)
                                  (update "defesa" crescer)
                                  (update "atq-esp" crescer)
                                  (update "def-esp" crescer)
                                  (update "veloc" crescer))]
            (swap! contas assoc-in [cid pid "equipe" idx] registro-novo)
            (persistir!)
            {:nome (get registro "nome") :nivel (inc nivel-atual)}))))))

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
  "Nível do jogador (usado só pra calibrar a força do pokémon selvagem
  sorteado na caçada) - derivado das vitórias já registradas em !pokemon
  (zapbot.rank), não é um contador à parte: nível 1 sem nenhuma vitória,
  +1 nível a cada vitória de !pokemon."
  [cid pid]
  (inc (rank/vitorias-jogo cid pid "pokemon")))
