(ns zapbot.pokemon.recuperacao-cartao
  "Card visual somente leitura para versões antigas do treinador."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.pokemon.imagens :as imagens]
            [zapbot.pokemon.boundary :as boundary]))

(def ^:private cores-tipo
  {"fire" "#ef5350" "water" "#42a5f5" "grass" "#66bb6a" "electric" "#fbc02d"
   "psychic" "#ec407a" "ice" "#4dd0e1" "dragon" "#7e57c2" "dark" "#5c6b73"
   "fairy" "#f48fb1" "fighting" "#e53935" "poison" "#ab47bc" "ground" "#a1887f"
   "rock" "#bcae66" "bug" "#9ccc65" "ghost" "#7e57c2" "steel" "#90a4ae"
   "flying" "#7986cb" "normal" "#b0bec5"})

(defn url-confiavel?
  "Um save antigo não pode instruir o servidor a baixar URLs arbitrárias."
  [url]
  (and (string? url) (<= (count url) 500)
       (boolean
        (or (re-matches #"https://raw\.githubusercontent\.com/PokeAPI/sprites/(?:master|refs/heads/master)/sprites/pokemon/[a-zA-Z0-9_./-]+" url)
            (re-matches #"https://cdn\.jsdelivr\.net/gh/PokeAPI/sprites@master/sprites/pokemon/[a-zA-Z0-9_./-]+" url)))))

(defn- texto [v]
  (cond
    (string? v) v
    (number? v) (str v)
    :else "?"))

(defn- abreviar [v maximo]
  (let [s (texto v)]
    (if (> (count s) maximo) (str (subs s 0 (dec maximo)) "…") s)))

(defn- xml [v]
  (-> (texto v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(def ^:private svg-indisponivel
  (str "<svg xmlns='http://www.w3.org/2000/svg' width='145' height='145'>"
       "<circle cx='72' cy='72' r='62' fill='#f8fafc' stroke='#111827' stroke-width='8'/>"
       "<path d='M10 72 A62 62 0 0 1 134 72Z' fill='#ef4444'/>"
       "<path d='M12 72 H132' stroke='#111827' stroke-width='9'/>"
       "<circle cx='72' cy='72' r='21' fill='#fff' stroke='#111827' stroke-width='8'/>"
       "</svg>"))

(defn- sprite! [registro]
  (let [url (get registro "imagem")]
    (p/let [original (if (url-confiavel? url)
                       (-> (imagens/baixar! url) (p/catch (fn [_] nil)))
                       (p/resolved nil))
            entrada (or original (js/Buffer.from svg-indisponivel))]
      (imagens/sprite! entrada 145 145 0.8))))

(defn- tipos-legado [pokemon]
  (let [tipos (get pokemon "tipos")]
    (if (and (sequential? tipos) (seq tipos))
      (str/join "/" (take 2 (map #(abreviar % 14) tipos)))
      "Tipo não informado")))

(defn svg-cartao
  "Molda cards nas mesmas proporções, cores e colunas da coleção Pokémon."
  [entradas titulo subtitulo]
  (let [entradas (vec (take 12 entradas))
        largura 1000
        altura (+ 120 (* 205 (js/Math.ceil (/ (count entradas) 2))) 20)
        cartoes
        (apply str
               (map-indexed
                (fn [idx {:keys [local indice pokemon]}]
                  (let [pokemon (if (map? pokemon) pokemon {})
                        coluna (mod idx 2)
                        linha (quot idx 2)
                        x (+ 20 (* coluna 480))
                        y (+ 120 (* linha 205))
                        cor (get cores-tipo (first (get pokemon "tipos")) "#78909c")
                        id (get pokemon "id-pokemon")
                        id (if (and (string? id) (not (str/blank? id))) id "sem UUID")]
                    (str "<g><rect x='" x "' y='" y
                         "' width='460' height='185' rx='18' fill='#172033' stroke='#2d3b55' stroke-width='2'/>"
                         "<rect x='" x "' y='" y "' width='12' height='185' rx='6' fill='" cor "'/>"
                         "<text x='" (+ x 165) "' y='" (+ y 43)
                         "' font-size='27' font-family='Arial,sans-serif' font-weight='bold' fill='#f8fafc'>"
                         (xml (abreviar (get pokemon "nome" "Sem nome") 20)) "</text>"
                         "<text x='" (+ x 165) "' y='" (+ y 75)
                         "' font-size='18' font-family='Arial,sans-serif' fill='#cbd5e1'>Nv. "
                         (xml (abreviar (get pokemon "nivel") 8)) " • "
                         (xml (abreviar (tipos-legado pokemon) 25)) "</text>"
                         "<text x='" (+ x 165) "' y='" (+ y 105)
                         "' font-size='18' font-family='Arial,sans-serif' fill='#e2e8f0'>"
                         (xml local) " #" indice "</text>"
                         "<text x='" (+ x 165) "' y='" (+ y 130)
                         "' font-size='15' font-family='Arial,sans-serif' fill='#94a3b8'>ID do Pokémon</text>"
                         "<text x='" (+ x 165) "' y='" (+ y 156)
                         "' font-size='12.5' font-family='monospace' fill='#cbd5e1'>"
                         (xml (abreviar id 36)) "</text></g>")))
                entradas))]
    (str "<svg xmlns='http://www.w3.org/2000/svg' width='" largura "' height='" altura "'>"
         "<rect width='100%' height='100%' fill='#0f172a'/>"
         "<rect width='100%' height='100' fill='#1e293b'/>"
         "<text x='35' y='45' font-size='32' font-family='Arial,sans-serif' font-weight='bold' fill='#f8fafc'>"
         (xml (abreviar titulo 44)) "</text>"
         "<text x='35' y='76' font-size='18' font-family='Arial,sans-serif' fill='#cbd5e1'>"
         (xml (abreviar subtitulo 85)) "</text>"
         cartoes "</svg>")))

(defn criar!
  "Recebe exclusivamente os registros já isolados e paginados do jogador."
  [entradas {:keys [titulo subtitulo arquivo]}]
  (let [entradas (vec (take 12 entradas))]
    (p/let [sprites (p/all (map #(sprite! (:pokemon %)) entradas))
          overlays (clj->js
                    (map-indexed (fn [idx sprite]
                                   #js {:input sprite
                                        :left (+ 28 (* (mod idx 2) 480))
                                        :top (+ 140 (* (quot idx 2) 205))})
                                 sprites))
          buffer (imagens/cartao! (svg-cartao entradas titulo subtitulo)
                                  overlays #js {:maxWidth 800})]
      (boundary/midia "image/jpeg" buffer (or arquivo "save-legado.jpg")))))
