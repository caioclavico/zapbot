(ns pokemon.migration.fixtures
  "Fixtures Cassandra sintéticas para M5.x — nunca dados reais/produção."
  (:require [pokemon.domain.reward :as reward])
  (:import [java.util UUID]))

(def chat-id "chat-m50-syn-001")
(def player-id "player-ash-syn@c.us")
(def player-orphan-loja "player-loja-orfao@c.us")
(def group-label "Grupo sintético M5.0")

(def ^:private voltorb-hist-id "hist-voltorb-shiny-001")

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
   "Gengar" "Onix" "Drowzee" "Hypno" "Krabby" "Kingler" "Electrode"
   "Exeggcute" "Exeggutor" "Cubone" "Marowak" "Hitmonlee"
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
  [{:keys [prefix idx species shiny nivel xp-desde-nivel hp id-pokemon]}]
  (let [nivel (long (or nivel (+ 5 (mod idx 40))))
        hp (long (or hp (+ 40 (* nivel 2))))
        species (or species (nth species-pool (mod idx (count species-pool))))]
    {"id-pokemon" (or id-pokemon (poke-id prefix idx))
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

(defn- joy-envelope
  [poke pronto-em]
  {"pokemon" poke "pronto-em" pronto-em})

(defn active-team-26
  "Save atual representativo M5.3: 19 equipe + 7 enfermaria (envelope Joy).
   Voltorb shiny opcional na equipe (não exigido pelo matching)."
  []
  (let [equipe
        (vec
         (for [i (range 19)]
           (cond
             (= i 0) (make-pokemon {:prefix "act" :idx i :species "Pikachu" :nivel 25 :shiny false})
             (= i 7) (make-pokemon {:prefix "act" :idx i :species "Voltorb" :nivel 18 :shiny true
                                    :id-pokemon "act-voltorb-shiny-007"})
             (= i 15) (make-pokemon {:prefix "act" :idx i :species "Gyarados" :nivel 42 :shiny true})
             :else (make-pokemon {:prefix "act" :idx i}))))
        enfermaria
        (vec
         (for [i (range 19 26)]
           (joy-envelope
            (make-pokemon {:prefix "act-joy" :idx i
                           :species (str "JoyMon" i)
                           :nivel (inc (mod i 8))})
            (+ 1791929455454 (- i 19)))))]
    {:equipe equipe :pc [] :enfermaria enfermaria}))

(defn historical-team-119
  "Histórico independente: 119 na equipe (como o save real M5.2).
   Voltorb shiny com id estável hist-voltorb-shiny-001 (critério de matching)."
  []
  (let [equipe
        (vec
         (for [i (range 119)]
           (cond
             (= i 0) (make-pokemon {:prefix "hist" :idx i :species "Pikachu" :nivel 30})
             (= i 3) (make-pokemon {:prefix "hist" :idx i :species "Voltorb" :nivel 12 :shiny true
                                    :id-pokemon voltorb-hist-id})
             (= i 50) (make-pokemon {:prefix "hist" :idx i :species "Dragonite" :nivel 55 :shiny true})
             :else (make-pokemon {:prefix "hist" :idx i}))))]
    {:equipe equipe :pc [] :enfermaria []}))

(defn- treinador-conta
  [equipe-or-map extras]
  (let [base (if (map? equipe-or-map)
               {"equipe" (vec (:equipe equipe-or-map))
                "pc" (vec (:pc equipe-or-map []))
                "enfermaria" (vec (:enfermaria equipe-or-map []))}
               {"equipe" (vec equipe-or-map)
                "pc" []
                "enfermaria" []})]
    (merge
     base
     ;; Defaults alinhados ao save atual real (XP 29). Histórico sobrescreve via extras.
     {"ativo" 0
      "vitorias-treinador" 3
      "pe-ginasios" 26
      "pe-raids" 0
      "xp-insignias" 0
      "xp-missoes" 0
      "ginasios" {"pedra" "2024-01-10" "agua" "2024-02-01"}
      "sequencia-capturas" 4
      "maior-sequencia-capturas" 9
      "ultima-cacada" 1700000000000}
     extras)))

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

(defn- save-slot-envelope
  "Envelope no formato próximo ao SaveSlotStore (Etapa 1), sintético."
  [{:keys [slot-id account loja source-kind source-version created-at]}]
  (let [meta {:source source-kind
              :sourceVersion source-version
              :sourceHash "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :contentHash "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              :createdAt (or created-at "2024-06-01T12:00:00.000Z")
              :format "json-account-v1"
              :pokemonCount (->> [(:equipe account) (:pc account) (:enfermaria account)
                                  (get account "equipe") (get account "pc") (get account "enfermaria")]
                                 (filter sequential?)
                                 (map count)
                                 (reduce + 0))
              :globalReceiptCount 0
              :globalReceiptHash "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
        ;; Embute loja no account para o scanner de slots (mesmo saldo do overlay)
        account+loja (cond-> account
                       loja (assoc "loja" loja))]
    {:schemaVersion 1
     :identityHash "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
     :slotId slot-id
     :metadata meta
     :account account+loja
     :globalReceipts {:module "pokemon-save-global-receipts"
                      :partition "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                      :snapshotHash (:globalReceiptHash meta)
                      :snapshot []}
     :integrityHash "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}))

(defn build-cassandra-dump
  "Dump sintético: treinador vivo (26) + envelope save-slot (119) + overlays."
  []
  (let [equipe-a (active-team-26)
        hist (historical-team-119)
        hist-account (treinador-conta hist {"nota-fixture" "historico-independente"
                                            ;; XP 127 = nível 10 (critério M5.2/M5.3)
                                            "vitorias-treinador" 31
                                            "pe-ginasios" 90
                                            "pe-raids" 6
                                            "xp-insignias" 0
                                            "xp-missoes" 0})
        ;; Moedas só na loja (evita ambiguidade live vs envelope com saldos diferentes)
        hist-loja (loja-conta {"moedas" 437 "inventario" {"great-ball" 4 "pocao-maxima" 1}})
        treinador-ativo {player-id (treinador-conta equipe-a {})}
        loja-ativo {player-id (loja-conta {"moedas" 198})
                    player-orphan-loja {"moedas" 99 "inventario" {"pocao" 1}}}
        slot-partition "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd:historico-119"
        envelope (save-slot-envelope
                  {:slot-id "historico-119"
                   :account hist-account
                   :loja hist-loja
                   ;; sourceVersion deliberadamente NÃO identifica o histórico
                   :source-kind "legacy_estado_particionado"
                   :source-version "1"
                   :created-at "2024-03-15T08:30:00.000Z"})
        hist-envelope {:snapshot-id "syn-hist-independente-001"
                       :kind :historical-independent
                       :chat-id chat-id
                       :player-id player-id
                       :target-slot "b"
                       :label "historico-independente-119"
                       :created-at "2024-03-15T08:30:00.000Z"
                       :source {:module "pokemon-save-slots"
                                :partition slot-partition
                                :sourceVersion "1"
                                :note "sourceVersion NÃO identifica; usar snapshot-id + count + Voltorb"}
                       :treinador hist-account
                       :loja hist-loja}
        rows [{:modulo "treinador" :particao chat-id :valor treinador-ativo}
              {:modulo "loja" :particao chat-id :valor loja-ativo}
              {:modulo "ginasios" :particao chat-id :valor {"pedra" {"lider" player-id "motivacao" 80}}}
              {:modulo "pokemon-descobertas" :particao "Voltorb"
               :valor {"descobridor" player-id "chat" chat-id}}
              {:modulo "pokemon-save-slots" :particao slot-partition :valor envelope}
              {:modulo "pokemon-save-slot-index"
               :particao "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
               :valor {:schemaVersion 1
                       :slots {"historico-119"
                               {:contentHash (get-in envelope [:metadata :contentHash])
                                :createdAt (get-in envelope [:metadata :createdAt])}}}}]]
    {:meta {:synthetic true
            :offline-dump true
            :milestone "M5.1"
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
     :group {:chat-id chat-id :label group-label}
     :fingerprints {:active-voltorb-shiny-id "act-voltorb-shiny-007"
                    :historical-voltorb-shiny-id voltorb-hist-id
                    :historical-pokemon-count 119
                    :active-pokemon-count 26}}))

(defn content-hash
  [x]
  (reward/payload-hash {:migration-fixture (reward/canonicalize x)}))
