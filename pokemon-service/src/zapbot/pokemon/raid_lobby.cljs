(ns zapbot.pokemon.raid-lobby
  "Regras puras de abertura e inscrição de raides. Sem I/O ou efeitos econômicos.")

(defn aberta? [raid agora]
  (and (= "inscricoes" (get raid "fase"))
       (< agora (get raid "expira" 0))))

(defn criar
  "Cria um lobby explicitamente solicitado. id deve ser gerado no adaptador e
   persistido com unicidade; agora é epoch ms. Retorna [estado resultado]."
  [existente {:keys [id cid pid nome chefe liga ginasio nome-ginasio agora
                     duracao-ms max-jogadores min-jogadores]}]
  (cond
    (and existente (contains? #{"inscricoes" "combate"} (get existente "fase"))
         (< agora (get existente "expira" 0)))
    [existente {:ok? false :codigo :raide-ativa}]
    (or (not (string? id)) (empty? id) (not (string? cid)) (empty? cid)
        (not (string? pid)) (empty? pid) (not (map? chefe))
        (not (string? (get chefe "nome"))) (empty? (get chefe "nome" ""))
        (not (integer? agora)) (neg? agora)
        (not (integer? duracao-ms)) (not (<= 1000 duracao-ms 900000))
        (not (integer? min-jogadores)) (not (integer? max-jogadores))
        (not (<= 1 min-jogadores max-jogadores 6)))
    [existente {:ok? false :codigo :configuracao-invalida}]
    :else
    [{"id" id "chat" cid "criador" pid "fase" "inscricoes"
      "chefe" chefe "liga" liga "ginasio" ginasio
      "nome-ginasio" nome-ginasio "ordem" [] "participantes" {}
      "criada-em" agora "expira" (+ agora duracao-ms)
      "min-jogadores" min-jogadores "max-jogadores" max-jogadores}
     {:ok? true :codigo :criada}]))

(defn inscrever
  "Inscrição idempotente; validação do Pokémon ocorre no motor existente."
  [raid pid participante agora]
  (cond
    (not (aberta? raid agora)) [raid {:ok? false :codigo :inscricoes-fechadas}]
    (contains? (get raid "participantes" {}) pid)
    [raid {:ok? true :codigo :ja-inscrito}]
    (>= (count (get raid "ordem" [])) (get raid "max-jogadores" 6))
    [raid {:ok? false :codigo :lotada}]
    (or (not (string? pid)) (empty? pid) (not (map? participante)))
    [raid {:ok? false :codigo :participante-invalido}]
    :else
    [(-> raid
         (update "ordem" conj pid)
         (assoc-in ["participantes" pid] participante))
     {:ok? true :codigo :inscrito}]))

(defn encerrar-inscricoes
  "Decide início/cancelamento; não executa combate nem concede recompensas."
  [raid agora]
  (cond
    (not (= "inscricoes" (get raid "fase")))
    [raid {:ok? false :codigo :fase-invalida}]
    (< agora (get raid "expira" 0))
    [raid {:ok? false :codigo :prazo-nao-atingido}]
    (< (count (get raid "ordem" [])) (get raid "min-jogadores" 2))
    [(assoc raid "fase" "cancelada") {:ok? true :codigo :sem-participantes}]
    :else
    [(assoc raid "fase" "pronta") {:ok? true :codigo :pronta-para-combate}]))
