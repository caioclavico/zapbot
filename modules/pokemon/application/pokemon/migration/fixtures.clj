(ns pokemon.migration.fixtures
  "Fixtures Cassandra sintéticas para M5.0 — nunca dados reais/produção."
  (:require [pokemon.domain.reward :as reward])
  (:import [java.util UUID]))

(def chat-id "chat-m50-syn-001")
(def player-id "player-ash-syn@c.us")
(def player-orphan-loja "player-loja-orfao@c.us")
(def group-label "Grupo sintético M5.0")

(def species-pool
  ["Bulbasaur" "Ivysaur" "Venusaur" "Charmander" "Charmeleon" "Charizard"
   "Squirtle" "Wartortle" "Blastoise" "Caterpie" "Metapod" "Butterfree"
   "Weedle" "Kakuna" "Beedrill" "Pidgey" "Pidgeotto" "Pidgeot" "Rattata"
   "Raticate" "Spearow" "Fearow" "Ekans" "Arbok" "Pikachu" "Raichu"
   "Sandshrew" "Sandslash" "Nidoran♀" "Nidorina" "Nidoqueen" "Nidoran♂"
   "Nidorino" "Nidoking" "Clefairy" "Clefable" "Vulpix" "Ninetales"
   "Jigglypuff" "Wigglytuff" "Zubat" "Golbat" "Oddish" "Gloom" "Vileplume"
   "Paras" "Parasect" "Venonat" "Venomoth" "Diglett" "Dugtrio" "Meowth"
   "Persian" "Psyduck" "Golduck" "Mankey" "Primeape" "Growlithe" "Arcanine"
   "Poliwag" "Poliwhirl" "Poliwrath" "Abra" "Kadabra" "Alakazam" "Machop"
   "Machoke" "Machamp" "Bellsprout" "Weepinbell" "Victreebel" "Tentacool"
   "Tentacruel" "Geodude" "Graveler" "Golem" "Ponyta" "Rapidash" "Slowpoke"
   "Slowbro" "Magnemite" "Magneton" "Farfetch'd" "Doduo" "Dodrio" "Seel"
   "Dewgong" "Grimer" "Muk" "Shellder" "Cloyster" "Gastly" "Haunter"
   "Gengar" "Onix" "Drowzee" "Hypno" "Krabby" "Kingler" "Voltorb"
   "Electrode" "Exeggcute" "Exeggutor" "Cubone" "Marowak" "Hitmonlee"
   "Hitmonchan" "Lickitung" "Koffing" "Weezing" "Rhyhorn" "Rhydon"
   "Chansey" "Tangela" "Kangaskhan" "Horsea" "Seadra" "Goldeen" "Seaking"
   "Staryu" "Starmie" "Mr. Mime" "Scyther" "Jynx" "Electabuzz" "Magmar"
   "Pinsir" "Tauros" "Magikarp" "Gyarados" "Lapras" "Ditto" "Eevee"
   "Vaporeon" "Jolteon" "Flareon" "Porygon" "Omanyte" "Omastar" "Kabuto"
   "Kabutops" "Aerodactyl" "Snorlax" "Articuno" "Zapdos" "Moltres"
   "Dratini" "Dragonair" "Dragonite" "Mewtwo" "Mew"])

(defn- poke-id
  [prefix idx]
  (str prefix "-" (format "%04d" idx) "-" (subs (str (UUID/nameUUIDFromBytes
                                                      (.getBytes (str prefix "|" idx)
                                                                "UTF-8"))) 0 8)))

(defn- make-pokemon
  [{:keys [prefix idx species shiny nivel xp-desde-nivel hp]}]
  (let [nivel (long (or nivel (+ 5 (mod idx 40))))
        hp (long (or hp (+ 40 (* nivel 2))))
        species (or species (nth species-pool (mod idx (count species-pool))))]
    {"id-pokemon" (poke-id prefix idx)
     "nome" species
     "especie" species
     "shiny" (boolean shiny)
     "nivel" nivel
     "xp-desde-nivel" (long (or xp-desde-nivel (mod (* idx 17) 90)))
     "hp" hp
     "hp-atual" hp
     "amizade" (+ 50 (mod idx 50))
     "ataque" (+ 20 (mod idx 60))
     "defesa" (+ 20 (mod (* idx 3) 60))
     "atq-esp" (+ 20 (mod (* idx 5) 60))
     "def-esp" (+ 20 (mod (* idx 7) 60))
     "veloc" (+ 20 (mod (* idx 11) 60))
     "tipos" (if (odd? idx) ["electric"] ["normal"])
     "raridade" (if shiny "lendario" "comum")
     "golpes" [{"nome" "Investida" "tipo" "normal" "poder" 40}
               {"nome" "Choque do Trovão" "tipo" "electric" "poder" 40}]}))

(defn active-team-26
  "Save atual: 26 Pokémon, inclui Voltorb shiny."
  []
  (vec
   (for [i (range 26)]
     (cond
       (= i 0) (make-pokemon {:prefix "act" :idx i :species "Pikachu" :nivel 25 :shiny false})
       (= i 7) (make-pokemon {:prefix "act" :idx i :species "Voltorb" :nivel 18 :shiny true})
       (= i 15) (make-pokemon {:prefix "act" :idx i :species "Gyarados" :nivel 42 :shiny true})
       :else (make-pokemon {:prefix "act" :idx i})))))

(defn historical-team-119
  "Histórico independente: 119 Pokémon (não mesclar com o atual)."
  []
  (vec
   (for [i (range 119)]
     (cond
       (= i 3) (make-pokemon {:prefix "hist" :idx i :species "Voltorb" :nivel 12 :shiny true})
       (= i 50) (make-pokemon {:prefix "hist" :idx i :species "Dragonite" :nivel 55 :shiny true})
       :else (make-pokemon {:prefix "hist" :idx i})))))

(defn- treinador-conta
  [equipe extras]
  (merge
   {"equipe" equipe
    "ativo" 0
    "vitorias-treinador" 12
    "pe-ginasios" 3
    "pe-raids" 2
    "xp-insignias" 150
    "xp-missoes" 40
    "ginasios" {"pedra" "2024-01-10" "agua" "2024-02-01"}
    "sequencia-capturas" 4
    "maior-sequencia-capturas" 9
    "ultima-cacada" 1700000000000
    "enfermaria" []}
   extras))

(defn- loja-conta
  [extras]
  (merge
   {"moedas" 3500
    "inventario" {"poke-ball" 12 "pocao" 5 "antidoto" 2 "fruta" 8}
    "expansoes-pokemon-50" 1
    "expansoes-mochila-50" 0
    "capacidade-mochila" 50
    "kit-inicial?" true}
   extras))

(defn build-cassandra-dump
  "Dump sintético no formato estado_particionado + envelopes históricos explícitos.

  Proveniência dos dois saves NÃO usa sourceVersion V1/V2 como identidade:
  cada snapshot carrega :provenance com snapshot-id e kind explícitos."
  []
  (let [equipe-a (active-team-26)
        equipe-b (historical-team-119)
        treinador-ativo {player-id (treinador-conta equipe-a {})}
        loja-ativo {player-id (loja-conta {})
                    ;; loja sem treinador → quarentena
                    player-orphan-loja {"moedas" 99 "inventario" {"pocao" 1}}}
        ginasios {chat-id {"pedra" {"lider" player-id "motivacao" 80}}}
        hist-account (treinador-conta equipe-b {"nota-fixture" "historico-independente"})
        hist-loja (loja-conta {"moedas" 900 "inventario" {"great-ball" 4 "pocao-maxima" 1}})
        hist-envelope {:snapshot-id "syn-hist-independente-001"
                       :kind :historical-independent
                       :chat-id chat-id
                       :player-id player-id
                       :target-slot "b"
                       :label "historico-independente-119"
                       :source {:module "fixture/historical-envelope"
                                :partition (str "syn|" chat-id "|" player-id)
                                ;; sourceVersion deliberadamente ruidoso / não canônico
                                :sourceVersion "IGNORE-ME-NOT-IDENTITY"
                                :note "sourceVersion NÃO identifica o histórico; usar snapshot-id"}
                       :treinador hist-account
                       :loja hist-loja}
        rows [{:modulo "treinador" :particao chat-id :valor treinador-ativo}
              {:modulo "loja" :particao chat-id :valor loja-ativo}
              {:modulo "ginasios" :particao chat-id :valor (get ginasios chat-id)}
              {:modulo "pokemon-descobertas" :particao "Voltorb"
               :valor {"descobridor" player-id "chat" chat-id}}]]
    {:meta {:synthetic true
            :milestone "M5.0"
            :warning "Fixtures apenas — proibido conectar produção"}
     :estado_particionado rows
     :historical_snapshots [hist-envelope]
     :active_provenance {:snapshot-id "syn-active-atual-001"
                         :kind :canonical-active
                         :chat-id chat-id
                         :player-id player-id
                         :target-slot "a"
                         :label "save-atual-26"
                         :source {:module "treinador"
                                  :partition chat-id
                                  :note "Conta viva em treinador+loja"}}
     :group {:chat-id chat-id :label group-label}}))

(defn content-hash
  [x]
  (reward/payload-hash {:migration-fixture (reward/canonicalize x)}))
