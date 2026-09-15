(ns zapbot.pokemon.raids
  "Raids cooperativas com equipes e HP próprios, persistidos por chat."
  (:require [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.missoes :as missoes]))

(defonce ^:private raids (atom (or (armazenamento/obter "raids") {})))
(armazenamento/registrar! "raids" raids)
(def duracao-inscricoes (* 15 60 1000))
(def duracao-combate (* 30 60 1000))
(def intervalo (* 6 60 60 1000))

(defn ativa? [raid agora]
  (and (contains? #{"inscricoes" "combate"} (get raid "fase"))
       (< agora (get raid "expira" 0))))

(defn entrar [raid pid nome registro agora]
  (cond
    (not (and (ativa? raid agora) (= "inscricoes" (get raid "fase"))))
    [raid "❓ Não há uma raid com inscrições abertas."]
    (some #{pid} (get raid "ordem")) [raid "Você já está inscrito nesta raid."]
    (>= (count (get raid "ordem")) 6) [raid "A raid já tem 6 participantes."]
    (not (and registro (pos? (get registro "hp-atual" 0))
              (treinador/elegivel? (treinador/obter-liga (get raid "liga")) registro)
              (some #(and (contains? #{"fisico" "especial"} (get % "classe"))
                          (pos? (get % "poder" 0))) (get registro "golpes"))))
    [raid "❌ Escolha um Pokémon com HP, da liga da raid e com um golpe de dano."]
    :else
    [(-> raid (update "ordem" conj pid)
         (assoc-in ["participantes" pid]
                   {"nome" nome "pokemon" registro "hp" (get registro "hp-atual") "dano" 0}))
     (str "✅ " nome " entrou com " (get registro "nome") ".")]))

(defn iniciar [raid pid agora]
  (cond
    (not (and (ativa? raid agora) (= "inscricoes" (get raid "fase"))))
    [raid "❓ Não há inscrições abertas."]
    (not= pid (get raid "criador")) [raid "Só quem abriu a raid pode iniciá-la."]
    (< (count (get raid "ordem")) 2) [raid "São necessários pelo menos 2 participantes."]
    :else
    (let [n (count (get raid "ordem"))
          registros (map #(get % "pokemon") (vals (get raid "participantes")))
          media (fn [stat] (/ (reduce + (map #(get % stat 50) registros)) n))]
      [(assoc raid "fase" "combate" "hp-chefe" (* 220 n) "hp-max" (* 220 n)
                   "defesa" (media "defesa") "def-esp" (media "def-esp")
                   "vez" (first (get raid "ordem")) "expira" (+ agora duracao-combate)
                   "proxima" (+ agora intervalo))
       "⚔️ Raid iniciada! Cada jogador ataca na sua vez; o chefe contra-ataca."])))

(defn atacar [raid pid slot agora]
  (let [participante (get-in raid ["participantes" pid])
        pokemon (get participante "pokemon")
        golpe (when (and (integer? slot) (<= 0 slot 3)) (get (get pokemon "golpes") slot))]
    (cond
      (not (and (ativa? raid agora) (= "combate" (get raid "fase"))))
      [raid "❓ Não há uma raid em combate."]
      (not= pid (get raid "vez")) [raid "⏳ Aguarde sua vez na raid."]
      (not (and golpe (contains? #{"fisico" "especial"} (get golpe "classe"))
                (pos? (get golpe "poder" 0))))
      [raid "❓ Use raid atacar <1-4>, escolhendo um golpe físico ou especial de dano."]
      :else
      (let [especial? (= "especial" (get golpe "classe"))
            ataque (get pokemon (if especial? "atq-esp" "ataque") 50)
            defesa (max 1 (get raid (if especial? "def-esp" "defesa") 50))
            dano (min (get raid "hp-chefe")
                      (max 10 (min 160 (js/Math.floor (+ 15 (* 0.6 (get golpe "poder") (/ ataque defesa)))))))
            novo (-> raid (update "hp-chefe" - dano)
                     (update-in ["participantes" pid "dano"] + dano))
            venceu? (zero? (get novo "hp-chefe"))
            revide (if venceu? 0 (max 1 (js/Math.ceil (/ (get pokemon "hp" 100) 5))))
            novo (update-in novo ["participantes" pid "hp"] #(max 0 (- % revide)))
            vivos (filter #(pos? (get-in novo ["participantes" % "hp"])) (get novo "ordem"))
            depois (concat (rest (drop-while #(not= pid %) (get novo "ordem")))
                           (take-while #(not= pid %) (get novo "ordem")) [pid])
            proximo (first (filter (set vivos) depois))
            novo (cond venceu? (assoc novo "fase" "vitoria")
                       (empty? vivos) (assoc novo "fase" "derrota")
                       :else (assoc novo "vez" proximo))]
        [novo (str "💥 " (get participante "nome") " usou " (get golpe "nome-exibicao" (get golpe "slug"))
                   ": " dano " de dano."
                   (when (pos? revide) (str " O chefe revidou: " revide " de dano."))
                   (when venceu? "\n🏆 O grupo venceu a raid!")
                   (when (= "derrota" (get novo "fase")) "\n😵 O grupo caiu. A raid terminou sem recompensa."))]))))

(defn resumo [raid agora]
  (if (ativa? raid agora)
    (str "🤝 *Raid cooperativa — Snorlax / " (get raid "liga") "*\n"
         (if (= "inscricoes" (get raid "fase"))
           "Inscrições abertas (2 a 6 jogadores)."
           (str "HP do chefe: " (get raid "hp-chefe") "/" (get raid "hp-max")
                "\nVez de: " (get-in raid ["participantes" (get raid "vez") "nome"])))
         "\n" (str/join "\n" (for [pid (get raid "ordem")
                                      :let [p (get-in raid ["participantes" pid])]]
                                  (str "• " (get p "nome") " — " (get-in p ["pokemon" "nome"])
                                       " • HP " (get p "hp") " • dano " (get p "dano"))))
         (when (= "combate" (get raid "fase"))
           (str "\nGolpes de quem está na vez:\n"
                (str/join "\n" (map-indexed
                  (fn [i g] (str (inc i) ". " (get g "nome-exibicao" (get g "slug"))
                                  (when-not (and (contains? #{"fisico" "especial"} (get g "classe"))
                                                 (pos? (get g "poder" 0))) " (indisponível na raid)")))
                  (get-in raid ["participantes" (get raid "vez") "pokemon" "golpes"])))))
         "\nRestam " (max 1 (js/Math.ceil (/ (- (get raid "expira") agora) 60000))) " min."
         "\n" config/prefix "pokemon raid " (if (= "inscricoes" (get raid "fase")) "entrar [número] | raid iniciar" "atacar <1-4>"))
    (str "🤝 Raid: " (case (get raid "fase") "vitoria" "vitória do grupo." "derrota" "grupo derrotado." "nenhuma ativa (encerrada ou expirada).")
         "\nAbra com " config/prefix "pokemon raid abrir <liga>."
         (when (> (get raid "proxima" 0) agora)
           (str " Nova raid em " (js/Math.ceil (/ (- (get raid "proxima") agora) 60000)) " min.")))))

(defn comando! [cid pid nome args registro liga-padrao agora]
  (let [[acao valor] args
        raid (get @raids cid)
        [novo texto]
        (case acao
          "abrir" (let [liga (treinador/obter-liga (or valor liga-padrao))]
                    (cond
                      (ativa? raid agora) [raid "Já existe uma raid neste chat."]
                      (> (get raid "proxima" 0) agora) [raid "⏳ Aguarde o intervalo entre raids."]
                      (nil? liga) [raid "Escolha a liga: iniciante, bronze, prata, ouro ou diamante."]
                      :else [{"fase" "inscricoes" "criador" pid "liga" (:id liga)
                              "ordem" [] "participantes" {} "expira" (+ agora duracao-inscricoes)}
                             "🤝 Inscrições abertas! Todos, inclusive o criador, devem entrar."]))
          "entrar" (entrar raid pid nome registro agora)
          "iniciar" (iniciar raid pid agora)
          "atacar" (atacar raid pid (when (re-matches #"[1-4]" (or valor "")) (dec (js/parseInt valor 10))) agora)
          "sair" (if (and (ativa? raid agora) (= "inscricoes" (get raid "fase")))
                   [(-> raid (update "ordem" #(vec (remove #{pid} %)))
                        (update "participantes" dissoc pid)) "Inscrição removida."]
                   [raid "Só é possível sair durante as inscrições; em combate, a raid termina por vitória, derrota ou prazo."])
          "cancelar" (if (and (= pid (get raid "criador")) (= "inscricoes" (get raid "fase")))
                       [(assoc raid "fase" "cancelada") "Raid cancelada."]
                       [raid "Só o criador pode cancelar, antes de iniciar."])
          [raid nil])]
    (when (not= novo raid)
      (swap! raids assoc cid novo)
      (armazenamento/salvar! "raids" @raids))
    (let [premios (when (and (= "vitoria" (get novo "fase")) (not= "vitoria" (get raid "fase")))
                    (for [[jogador p] (get novo "participantes") :when (pos? (get p "dano" 0))]
                      (str "\n💰 " (get p "nome") ": +"
                           (or (loja/premiar-raid! cid jogador (missoes/dia-atual)) 0)
                           " moedas (limite: uma recompensa por dia).")))]
      (str texto (apply str premios) (when texto "\n\n") (resumo novo agora)))))
