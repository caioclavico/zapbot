(ns zapbot.pokemon.pvp
  "Regras do desafio PvP; o Odisseu só transporta comandos HTTP.")

(def diferenca-maxima-niveis 3)
(def minutos-espera 5)
(def tempo-espera-ms (* minutos-espera 60 1000))

(defn bola-premio [pokemon]
  ;; Mesmas categorias de recompensa anteriores, agora pelo nível do desafiante.
  ;; Isso não limita a entrada nem normaliza os atributos dos participantes.
  (let [nivel (or (:nivel pokemon) 1)]
    (cond (<= nivel 25) "pokebola" (<= nivel 60) "grande-bola" :else "ultra-bola")))

(defn faixa-niveis [pokemon]
  (let [nivel (or (:nivel pokemon) 1)]
    {:min (max 1 (- nivel diferenca-maxima-niveis))
     :max (+ nivel diferenca-maxima-niveis)}))

(defn aguardando? [jogo]
  (and (not (:ginasio jogo)) (not (:carregando? jogo))
       (some? (get-in jogo [:jogadores :x]))
       (map? (get-in jogo [:pokemons :x]))
       (not (contains? (:jogadores jogo) :o))))

(defn expirado? [jogo agora]
  (and (aguardando? jogo) (number? (:desafio-expira-em jogo))
       (<= (:desafio-expira-em jogo) agora)))

(defn faixa-desafio [jogo]
  (or (:faixa-niveis jogo) (faixa-niveis (get-in jogo [:pokemons :x]))))

(defn compativel? [jogo pokemon]
  (let [{:keys [min max]} (faixa-desafio jogo)]
    (<= min (or (:nivel pokemon) 1) max)))

(defn restaurar-espera
  "Desafios legados usam seu timestamp original; restart não renova a espera.
  Batalhas já iniciadas, incluindo antigas partidas 3×3, continuam intactas."
  [jogo atualizado-em]
  (if (aguardando? jogo)
    (-> jogo
        (assoc :pvp? true
               :faixa-niveis (faixa-desafio jogo)
               :desafio-expira-em (or (:desafio-expira-em jogo)
                                      (+ atualizado-em tempo-espera-ms)))
        (dissoc :liga :reservas))
    jogo))

(defn outro-combate? [jogos cacadas cid pid agora]
  (or (some (fn [[outro jogo]]
              (and (not= cid outro) (not (expirado? jogo agora))
                   (some #{pid} (vals (:jogadores jogo))))) jogos)
      (some (fn [[outro caca]] (and (not= cid outro) (= pid (:pid caca)))) cacadas)))

(defn anuncio [prefix nome pokemon jogo]
  (let [{:keys [min max]} (faixa-desafio jogo)]
    (str "⚔️ *Desafio Pokémon!*\n\n"
         "🎮 *" nome "* está procurando um adversário!\n"
         "🐉 Pokémon: *" (:nome pokemon) "*\n"
         "⭐ Nível: " (or (:nivel pokemon) 1) "\n\n"
         "📊 Níveis aceitos: " min " a " max "\n"
         "Digite " prefix "pokemon para aceitar com seu Pokémon ativo!\n"
         "⏳ O desafio expira em " minutos-espera " minutos.")))

(defn incompatibilidade [prefix pokemon jogo]
  (let [{:keys [min max]} (faixa-desafio jogo)]
    (str "❌ *Pokémon incompatível!*\n\n"
         "🐲 Seu Pokémon: *" (:nome pokemon) "*\n"
         "⭐ Nível atual: " (or (:nivel pokemon) 1) "\n\n"
         "⚔️ O desafio aceita Pokémon entre os níveis " min " e " max ".\n"
         "🔄 Selecione outro Pokémon ativo dentro dessa faixa com " prefix
         "pokemon escolher <número> e envie " prefix "pokemon novamente.")))
