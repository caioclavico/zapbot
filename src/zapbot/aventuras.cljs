(ns zapbot.aventuras)

(def pedras
  {"pedra-agua" {:nome "Pedra da Água" :emoji "💧"
                 :evolucoes {"poliwhirl" "poliwrath" "shellder" "cloyster" "staryu" "starmie" "eevee" "vaporeon" "lombre" "ludicolo" "panpour" "simipour"}}
   "pedra-trovao" {:nome "Pedra do Trovão" :emoji "⚡"
                   :evolucoes {"pikachu" "raichu" "eevee" "jolteon" "eelektrik" "eelektross" "charjabug" "vikavolt" "tadbulb" "bellibolt"}}
   "pedra-fogo" {:nome "Pedra do Fogo" :emoji "🔥"
                 :evolucoes {"vulpix" "ninetales" "growlithe" "arcanine" "eevee" "flareon" "pansear" "simisear" "capsakid" "scovillain"}}
   "pedra-folha" {:nome "Pedra da Folha" :emoji "🍃"
                  :evolucoes {"gloom" "vileplume" "weepinbell" "victreebel" "exeggcute" "exeggutor" "nuzleaf" "shiftry" "pansage" "simisage" "eevee" "leafeon"}}
   "pedra-lua" {:nome "Pedra da Lua" :emoji "🌙"
                :evolucoes {"nidorina" "nidoqueen" "nidorino" "nidoking" "clefairy" "clefable" "jigglypuff" "wigglytuff" "skitty" "delcatty" "munna" "musharna"}}})

(def ginasios
  [{:id "pedra" :nome "🪨 Pedra" :lider "Brock" :nivel 10 :item "pedra-lua"
    :time ["geodude" "sandshrew" "onix"]}
   {:id "agua" :nome "💧 Água" :lider "Misty" :nivel 20 :item "pedra-agua"
    :time ["psyduck" "staryu" "starmie"]}
   {:id "eletrico" :nome "⚡ Elétrico" :lider "Lt. Surge" :nivel 30 :item "pedra-trovao"
    :time ["voltorb" "pikachu" "raichu"]}
   {:id "planta" :nome "🍃 Planta" :lider "Erika" :nivel 40 :item "pedra-folha"
    :time ["tangela" "weepinbell" "vileplume"]}
   {:id "fogo" :nome "🔥 Fogo" :lider "Blaine" :nivel 50 :item "pedra-fogo"
    :time ["ponyta" "magmar" "arcanine"]}])

(defn obter-ginasio [id] (some #(when (= id (:id %)) %) ginasios))

(defn desbloqueado? [insignias id]
  (let [anteriores (take-while #(not= id (:id %)) ginasios)]
    (and (obter-ginasio id) (every? #(contains? (set insignias) (:id %)) anteriores))))

(def surtos
  [{:nome "Festival das Águas" :especies ["poliwag" "shellder" "staryu" "eevee"]}
   {:nome "Faíscas no Campo" :especies ["pikachu" "magnemite" "voltorb" "eevee"]}
   {:nome "Trilha em Chamas" :especies ["vulpix" "growlithe" "ponyta" "eevee"]}
   {:nome "Floresta em Festa" :especies ["oddish" "bellsprout" "exeggcute" "eevee"]}])

(def duracao-evento-ms (* 6 60 60 1000))

(defn evento-atual [agora]
  (let [periodo (quot agora duracao-evento-ms)]
    (assoc (nth surtos (mod periodo (count surtos)))
           :fim (* (inc periodo) duracao-evento-ms)
           :chance 50)))

(defn troca-valida? [proposta agora registro-a registro-b]
  (and proposta (< agora (:expira proposta))
       (some? registro-a) (some? registro-b)
       (= registro-a (:registro-a proposta))
       (= registro-b (:registro-b proposta))))
