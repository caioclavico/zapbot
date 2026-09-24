(ns zapbot.pokemon.raids
  "Raids cooperativas com equipes e HP próprios, persistidos por chat."
  (:require [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]
            [zapbot.pokemon.missoes :as missoes]
            [zapbot.pokemon.aventuras :as aventuras]))

(defonce ^:private raids (atom (or (armazenamento/obter "raids") {})))
(armazenamento/registrar! "raids" raids)
(def duracao-inscricoes (* 15 60 1000))
(def duracao-combate (* 30 60 1000))
(def intervalo (* 6 60 60 1000))

(defn ativa? [raid agora]
  (and (contains? #{"inscricoes" "combate"} (get raid "fase"))
       (< agora (get raid "expira" 0))))

(defonce agendas (atom (or (armazenamento/obter "raides-agendas") {})))
(armazenamento/registrar! "raides-agendas" agendas)

(defn acompanhar! [cid agora]
  (when-not (contains? @agendas cid)
    (swap! agendas assoc cid {"proxima" (+ agora intervalo)})
    (armazenamento/salvar! "raides-agendas" @agendas)))

(defn atual [cid] (get @raids cid))
(defn no-ginasio? [cid id agora]
  (let [r (atual cid)] (and (= id (get r "ginasio")) (ativa? r agora))))

(defn proximo-ginasio [cid]
  (let [ultimo (or (get-in @agendas [cid "ultimo-ginasio"])
                   (get (atual cid) "ginasio"))
        lista (vec aventuras/ginasios)
        indice (first (keep-indexed #(when (= ultimo (:id %2)) %1) lista))]
    (get lista (if (some? indice) (mod (inc indice) (count lista)) 0))))

(defn candidatos [nivel]
  (cond
    (>= nivel 50) [["mew" "mitico"] ["celebi" "mitico"] ["jirachi" "mitico"] ["mewtwo" "lendario"]]
    (>= nivel 30) [["articuno" "lendario"] ["zapdos" "lendario"] ["moltres" "lendario"] ["dragonite" "raro"]]
    :else [["snorlax" "raro"] ["lapras" "raro"] ["aerodactyl" "raro"] ["dratini" "raro"]]))

(defn liga-do-ginasio [nivel]
  (cond (>= nivel 50) "diamante" (>= nivel 40) "ouro" (>= nivel 30) "prata"
        (>= nivel 20) "bronze" :else "iniciante"))

(defn criar! [cid g chefe agora]
  (when-not (ativa? (atual cid) agora)
    (let [r {"id" (str (random-uuid)) "ginasio" (:id g) "nome-ginasio" (:nome g)
             "nivel-ginasio" (:nivel g) "chefe" chefe "fase" "inscricoes"
             "liga" (liga-do-ginasio (:nivel g)) "ordem" [] "participantes" {}
             "expira" (+ agora duracao-inscricoes) "proxima" (+ agora intervalo)}]
      (swap! raids assoc cid r)
      (swap! agendas assoc cid {"proxima" (+ agora intervalo) "ultimo-ginasio" (:id g)})
      (armazenamento/salvar! "raids" @raids)
      (armazenamento/salvar! "raides-agendas" @agendas)
      r)))

(defn captura-pendente [cid pid agora]
  (let [r (atual cid)]
    (when (and (= "vitoria" (get r "fase")) (< agora (get r "captura-expira" 0))
               (pos? (get-in r ["participantes" pid "dano"] 0))
               (< (get-in r ["capturas" pid "tentativas"] 0) 3)
               (not (get-in r ["capturas" pid "capturou"])))
      r)))

(defn registrar-tentativa! [cid pid capturou?]
  (swap! raids update-in [cid "capturas" pid]
         #(-> (or % {}) (update "tentativas" (fnil inc 0)) (assoc "capturou" capturou?)))
  (armazenamento/salvar! "raids" @raids))

(defn chance-captura [raridade]
  (get {"raro" 35 "lendario" 8 "mitico" 4} raridade 35))

(defn participando? [cid pid]
  (let [raid (get @raids cid)]
    (and (ativa? raid (.now js/Date))
         (contains? (get raid "participantes" {}) pid))))

(defn entrar [raid pid nome registro agora]
  (cond
    (not (and (ativa? raid agora) (= "inscricoes" (get raid "fase"))))
    [raid "❓ Não há uma raide com inscrições abertas."]
    (some #{pid} (get raid "ordem")) [raid "Você já está inscrito nesta raide."]
    (>= (count (get raid "ordem")) 6) [raid "A raide já tem 6 participantes."]
    (not (and registro (pos? (get registro "hp-atual" 0))
              (treinador/elegivel? (treinador/obter-liga (get raid "liga")) registro)
              (some #(and (contains? #{"fisico" "especial"} (get % "classe"))
                          (pos? (get % "poder" 0))) (get registro "golpes"))))
    [raid "❌ Escolha um Pokémon com HP, da liga da raide e com um golpe de dano."]
    :else
    [(-> raid (update "ordem" conj pid)
         (assoc-in ["participantes" pid]
                   {"nome" nome "pokemon" registro "hp" (get registro "hp-atual") "dano" 0}))
     (str "✅ " nome " entrou com " (get registro "nome") ".")]))

(defn iniciar [raid pid agora]
  (cond
    (not (and (ativa? raid agora) (= "inscricoes" (get raid "fase"))))
    [raid "❓ Não há inscrições abertas."]
    (and (get raid "criador") (not= pid (get raid "criador"))) [raid "Só quem abriu a raide pode iniciá-la."]
    (not (contains? (get raid "participantes") pid)) [raid "Entre na raide antes de iniciar."]
    (< (count (get raid "ordem")) 2) [raid "São necessários pelo menos 2 participantes."]
    :else
    (let [n (count (get raid "ordem"))
          registros (map #(get % "pokemon") (vals (get raid "participantes")))
          media (fn [stat] (/ (reduce + (map #(get % stat 50) registros)) n))]
      [(assoc raid "fase" "combate" "hp-chefe" (* 220 n) "hp-max" (* 220 n)
                   "defesa" (media "defesa") "def-esp" (media "def-esp")
                   "vez" (first (get raid "ordem")) "expira" (+ agora duracao-combate)
                   "proxima" (+ agora intervalo))
       "⚔️ Raide iniciada! Cada jogador ataca na sua vez; o chefe contra-ataca."])))

(defn atacar [raid pid slot agora]
  (let [participante (get-in raid ["participantes" pid])
        pokemon (get participante "pokemon")
        golpe (when (and (integer? slot) (<= 0 slot 3)) (get (get pokemon "golpes") slot))]
    (cond
      (not (and (ativa? raid agora) (= "combate" (get raid "fase"))))
      [raid "❓ Não há uma raide em combate."]
      (not= pid (get raid "vez")) [raid "⏳ Aguarde sua vez na raide."]
      (not (and golpe (contains? #{"fisico" "especial"} (get golpe "classe"))
                (pos? (get golpe "poder" 0))))
      [raid "❓ Use raide atacar <1-4>, escolhendo um golpe físico ou especial de dano."]
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
            novo (cond venceu? (assoc novo "fase" "vitoria" "captura-expira" (+ agora (* 30 60 1000)))
                       (empty? vivos) (assoc novo "fase" "derrota")
                       :else (assoc novo "vez" proximo))]
        [novo (str "💥 " (get golpe "nome-exibicao" (get golpe "slug")) " de *" (get pokemon "nome") "* (" (get participante "nome") ") causou "
                   dano " de dano em *" (get-in raid ["chefe" "nome"] "Snorlax") "*!"
                   (when (pos? revide) (str "\n💥 *" (get-in raid ["chefe" "nome"] "Snorlax") "* contra-atacou e causou " (min revide (get participante "hp")) " de dano em *" (get pokemon "nome") "*!"))
                   (when venceu? "\n🏆 O grupo venceu a raide!")
                   (when (= "derrota" (get novo "fase")) "\n😵 O grupo caiu. A raide terminou sem recompensa."))]))))

(defn resumo [raid agora]
  (if (ativa? raid agora)
    (str "🤝 *Raide — " (get-in raid ["chefe" "nome"] "Snorlax") " / " (get raid "liga") "*\n🏛️ " (get raid "nome-ginasio" "Ginásio") " • Nv." (get raid "nivel-ginasio" 1) "\n"
         (if (= "inscricoes" (get raid "fase"))
           "Inscrições abertas (2 a 6 jogadores)."
           (str "HP do chefe: " (get raid "hp-chefe") "/" (get raid "hp-max")
                "\nVez de: " (get-in raid ["participantes" (get raid "vez") "nome"]) " (@" (first (str/split (get raid "vez") #"@")) ")"))
         "\n" (str/join "\n" (for [pid (get raid "ordem")
                                      :let [p (get-in raid ["participantes" pid])]]
                                  (str "• " (get p "nome") " — " (get-in p ["pokemon" "nome"])
                                       " • HP " (get p "hp") " • dano " (get p "dano"))))
         (when (= "combate" (get raid "fase"))
           (str "\nGolpes de quem está na vez:\n"
                (str/join "\n" (map-indexed
                  (fn [i g] (str (inc i) ". " (get g "nome-exibicao" (get g "slug"))
                                  (when-not (and (contains? #{"fisico" "especial"} (get g "classe"))
                                                 (pos? (get g "poder" 0))) " (indisponível na raide)")))
                  (get-in raid ["participantes" (get raid "vez") "pokemon" "golpes"])))))
         "\nRestam " (max 1 (js/Math.ceil (/ (- (get raid "expira") agora) 60000))) " min."
         "\n" config/prefix "pokemon gin " (if (= "inscricoes" (get raid "fase")) "entrar [número] | !pk gin iniciar" "atacar <1-4>"))
    (str "🤝 Raide: " (case (get raid "fase") "vitoria" "vitória do grupo." "derrota" "grupo derrotado." "nenhuma ativa (encerrada ou expirada).")
         "\nAs raides aparecem automaticamente nos ginásios."
         (when (> (get raid "proxima" 0) agora)
           (str " Nova raide em " (js/Math.ceil (/ (- (get raid "proxima") agora) 60000)) " min.")))))

(defn comando! [cid pid nome args registro liga-padrao agora]
  (let [[acao valor] args
        raid (get @raids cid)
        [novo texto]
        (case acao
          "abrir" [raid "As raides aparecem automaticamente nos ginásios. Consulte !pk raide."]
          "entrar" (entrar raid pid nome registro agora)
          "iniciar" (iniciar raid pid agora)
          "atacar" (atacar raid pid (when (re-matches #"[1-4]" (or valor "")) (dec (js/parseInt valor 10))) agora)
          "sair" (if (and (ativa? raid agora) (= "inscricoes" (get raid "fase")))
                   [(-> raid (update "ordem" #(vec (remove #{pid} %)))
                        (update "participantes" dissoc pid)) "Inscrição removida."]
                   [raid "Só é possível sair durante as inscrições; em combate, a raide termina por vitória, derrota ou prazo."])
          "cancelar" (if (and (= pid (get raid "criador")) (= "inscricoes" (get raid "fase")))
                       [(assoc raid "fase" "cancelada") "Raide cancelada."]
                       [raid "Só o criador pode cancelar, antes de iniciar."])
          [raid nil])]
    (when (not= novo raid)
      (swap! raids assoc cid novo)
      (armazenamento/salvar! "raids" @raids))
    (let [dia (or (get novo "id") (missoes/dia-atual))
          premios (when (and (= "vitoria" (get novo "fase")) (not= "vitoria" (get raid "fase")))
                    (mapv (fn [[jogador participante]]
                            (let [moedas (loja/premiar-raid! cid jogador dia)
                                  progresso (when moedas
                                              (treinador/premiar-progresso-raid!
                                               cid jogador dia (get participante "pokemon")))]
                              (assoc progresso :pid jogador
                                     :texto (str "\n💰 " (get participante "nome") ": "
                                                 (if moedas
                                                   (str "+" moedas " moedas; +" (:pe progresso 0) " PE; +"
                                                        (:xp progresso 0) " XP para o Pokémon inscrito."
                                                        (when (:pendente? progresso)
                                                          " XP reservado até o Pokémon voltar à equipe e você usar um comando Pokémon.")
                                                        (when (zero? (:xp progresso 0))
                                                          " O Pokémon não está disponível na sua equipe."))
                                                   "recompensa desta raide já recebida.")
                                                 (when-let [subida (:subida progresso)]
                                                   (str "\n✨ " (:nome subida) " chegou ao nível " (:nivel subida) "!"))))))
                          (filter #(pos? (get (second %) "dano" 0)) (get novo "participantes"))))]
      {:texto (str texto
                  (when (= "vitoria" (get novo "fase"))
                    "\n🎯 Quem causou dano tem 3 tentativas por 30 minutos: !pk gin capturar pokebola. Capturado no nível 1. Os defensores voltaram ao ginásio.") (apply str (map :texto premios)) (when texto "\n\n") (resumo novo agora))
       :mentions (if (and (ativa? novo agora) (= "combate" (get novo "fase"))) [(get novo "vez")] [])
       :subidas (vec (filter :subida premios))})))
