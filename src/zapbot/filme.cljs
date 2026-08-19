(ns zapbot.filme
  "Comando !filme - sinopse, nota e capa de filmes via TMDB (requer chave gratuita)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.traducao :as traducao]))

(def ^:private MessageMedia (.-MessageMedia wwjs))
(def ^:private base-url "https://api.themoviedb.org/3")
(def ^:private poster-base-url "https://image.tmdb.org/t/p/w500")

(defn- chave-ausente []
  (p/resolved
   (str "⚠️ Comando indisponível: configure TMDB_API_KEY no .env "
        "(chave gratuita em https://www.themoviedb.org/settings/api).")))

(defn- buscar-json [caminho]
  (-> (js/fetch (str base-url caminho
                      (if (str/includes? caminho "?") "&" "?")
                      "api_key=" config/tmdb-api-key))
      (p/then (fn [res] (.json res)))
      (p/then #(js->clj % :keywordize-keys true))))

(defn- ano [data-lancamento]
  (if (seq data-lancamento) (subs data-lancamento 0 4) "?"))

(defn- formatar [{:keys [title release_date vote_average]} sinopse]
  (str "🎬 *Filme do tio " config/bot-name " (" title ", " (ano release_date) "):*\n\n"
       sinopse
       "\n\n⭐ Nota: " (.toFixed vote_average 1) "/10"))

(defn- enviar-com-capa [message poster_path legenda]
  (-> (p/let [media (.fromUrl MessageMedia (str poster-base-url poster_path))
              _     (.reply message media nil #js {:caption legenda})]
        nil)
      (p/catch (fn [err]
                 (js/console.error "Erro ao enviar capa do filme:" err)
                 ;; se a capa falhar, ainda respondemos com o texto
                 legenda))))

(defn- responder [message filme legenda]
  (if (:poster_path filme)
    (enviar-com-capa message (:poster_path filme) legenda)
    (p/resolved legenda)))

(defn- com-sinopse-traduzida [message filme]
  (p/let [overview (:overview filme)
          sinopse  (if (str/blank? overview)
                     "(sinopse não disponível)"
                     (traducao/traduzir overview))]
    (responder message filme (formatar filme sinopse))))

(defn buscar-filme
  ([message]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/movie/popular?page=" (inc (rand-int 5))))
         (p/then (fn [data] (com-sinopse-traduzida message (rand-nth (:results data)))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme popular:" err)
                    "❌ Não consegui buscar um filme agora. Tente novamente mais tarde.")))))
  ([message titulo]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (-> (buscar-json (str "/search/movie?query=" (js/encodeURIComponent titulo)))
         (p/then (fn [data]
                   (if-let [filme (first (:results data))]
                     (com-sinopse-traduzida message filme)
                     (str "❓ Não encontrei o filme \"" titulo "\". Tente outro nome."))))
         (p/catch (fn [err]
                    (js/console.error "Erro ao buscar filme:" err)
                    "❌ Não consegui buscar o filme agora. Tente novamente mais tarde."))))))

(defn- formatar-item-lista [{:keys [title release_date vote_average]}]
  (str "*" title "* (" (ano release_date) ")"
       (when vote_average (str " - ⭐ " (.toFixed vote_average 1)))))

(defn buscar-filmes
  "!filmes <nome> - lista até 10 filmes encontrados pra esse nome (sem
  sinopse/capa, diferente do !filme singular que traz um só com detalhes)."
  [titulo]
  (cond
    (str/blank? config/tmdb-api-key)
    (chave-ausente)

    (str/blank? titulo)
    (p/resolved (str "❓ Use " config/prefix "filmes <nome> pra listar até 10 filmes encontrados com esse nome."))

    :else
    (-> (buscar-json (str "/search/movie?query=" (js/encodeURIComponent titulo)))
        (p/then (fn [data]
                  (let [encontrados (take 10 (:results data))]
                    (if (seq encontrados)
                      (str "🎬 *Filmes encontrados para \"" titulo "\":*\n\n"
                           (str/join "\n" (map-indexed (fn [i f] (str (inc i) ". " (formatar-item-lista f)))
                                                        encontrados)))
                      (str "❓ Não encontrei filmes para \"" titulo "\". Tente outro nome.")))))
        (p/catch (fn [err]
                   (js/console.error "Erro ao buscar filmes:" err)
                   "❌ Não consegui buscar filmes agora. Tente novamente mais tarde.")))))

(def ^:private vencedores-oscar
  ;; Vencedores do Oscar de Melhor Filme, pelo ano de lançamento do próprio
  ;; filme. Conferido ao vivo na Wikipedia (Academy Award for Best Picture)
  ;; em 19/08/2026. Sem entrada em 1928: antes da 7ª cerimônia (1934) cada
  ;; prêmio cobria uma "temporada" de dois anos, e o filme vencedor caiu ou
  ;; no ano anterior ou no seguinte dessa lacuna. Precisa atualização manual
  ;; a cada cerimônia nova (~março de cada ano).
  [{:ano 1927 :titulo "Wings" :concorrentes ["7th Heaven" "The Racket"]}
   {:ano 1929 :titulo "The Broadway Melody"
    :concorrentes ["Alibi" "The Hollywood Revue" "In Old Arizona" "The Patriot"]}
   {:ano 1930 :titulo "All Quiet on the Western Front"
    :concorrentes ["The Big House" "Disraeli" "The Divorcee" "The Love Parade"]}
   {:ano 1931 :titulo "Cimarron"
    :concorrentes ["East Lynne" "The Front Page" "Skippy" "Trader Horn"]}
   {:ano 1932 :titulo "Grand Hotel"
    :concorrentes ["Arrowsmith" "Bad Girl" "The Champ" "Five Star Final"
                   "One Hour with You" "Shanghai Express" "The Smiling Lieutenant"]}
   {:ano 1933 :titulo "Cavalcade"
    :concorrentes ["42nd Street" "A Farewell to Arms" "I Am a Fugitive from a Chain Gang"
                   "Lady for a Day" "Little Women" "The Private Life of Henry VIII"
                   "She Done Him Wrong" "Smilin' Through" "State Fair"]}
   {:ano 1934 :titulo "It Happened One Night"
    :concorrentes ["The Barretts of Wimpole Street" "Cleopatra" "Flirtation Walk"
                   "The Gay Divorcee" "Here Comes the Navy" "The House of Rothschild"
                   "Imitation of Life" "One Night of Love" "The Thin Man" "Viva Villa!"
                   "The White Parade"]}
   {:ano 1935 :titulo "Mutiny on the Bounty"
    :concorrentes ["Alice Adams" "Broadway Melody of 1936" "Captain Blood"
                   "David Copperfield" "The Informer" "The Lives of a Bengal Lancer"
                   "A Midsummer Night's Dream" "Les Misérables" "Naughty Marietta"
                   "Ruggles of Red Gap" "Top Hat"]}
   {:ano 1936 :titulo "The Great Ziegfeld"
    :concorrentes ["Anthony Adverse" "Dodsworth" "Libeled Lady" "Mr. Deeds Goes to Town"
                   "Romeo and Juliet" "San Francisco" "The Story of Louis Pasteur"
                   "A Tale of Two Cities" "Three Smart Girls"]}
   {:ano 1937 :titulo "The Life of Emile Zola"
    :concorrentes ["The Awful Truth" "Captains Courageous" "Dead End" "The Good Earth"
                   "In Old Chicago" "Lost Horizon" "One Hundred Men and a Girl"
                   "Stage Door" "A Star Is Born"]}
   {:ano 1938 :titulo "You Can't Take It with You"
    :concorrentes ["The Adventures of Robin Hood" "Alexander's Ragtime Band" "Boys Town"
                   "The Citadel" "Four Daughters" "Grand Illusion" "Jezebel"
                   "Pygmalion" "Test Pilot"]}
   {:ano 1939 :titulo "Gone with the Wind"
    :concorrentes ["Dark Victory" "Goodbye, Mr. Chips" "Love Affair"
                   "Mr. Smith Goes to Washington" "Ninotchka" "Of Mice and Men"
                   "Stagecoach" "The Wizard of Oz" "Wuthering Heights"]}
   {:ano 1940 :titulo "Rebecca"
    :concorrentes ["All This, and Heaven Too" "Foreign Correspondent" "The Grapes of Wrath"
                   "The Great Dictator" "Kitty Foyle" "The Letter" "The Long Voyage Home"
                   "Our Town" "The Philadelphia Story"]}
   {:ano 1941 :titulo "How Green Was My Valley"
    :concorrentes ["Blossoms in the Dust" "Citizen Kane" "Here Comes Mr. Jordan"
                   "Hold Back the Dawn" "The Little Foxes" "The Maltese Falcon"
                   "One Foot in Heaven" "Sergeant York" "Suspicion"]}
   {:ano 1942 :titulo "Mrs. Miniver"
    :concorrentes ["49th Parallel" "Kings Row" "The Magnificent Ambersons" "The Pied Piper"
                   "The Pride of the Yankees" "Random Harvest" "The Talk of the Town"
                   "Wake Island" "Yankee Doodle Dandy"]}
   {:ano 1943 :titulo "Casablanca"
    :concorrentes ["For Whom the Bell Tolls" "Heaven Can Wait" "The Human Comedy"
                   "In Which We Serve" "Madame Curie" "The More the Merrier"
                   "The Ox-Bow Incident" "The Song of Bernadette" "Watch on the Rhine"]}
   {:ano 1944 :titulo "Going My Way"
    :concorrentes ["Double Indemnity" "Gaslight" "Since You Went Away" "Wilson"]}
   {:ano 1945 :titulo "The Lost Weekend"
    :concorrentes ["Anchors Aweigh" "The Bells of St. Mary's" "Mildred Pierce" "Spellbound"]}
   {:ano 1946 :titulo "The Best Years of Our Lives"
    :concorrentes ["Henry V" "It's a Wonderful Life" "The Razor's Edge" "The Yearling"]}
   {:ano 1947 :titulo "Gentleman's Agreement"
    :concorrentes ["The Bishop's Wife" "Crossfire" "Great Expectations" "Miracle on 34th Street"]}
   {:ano 1948 :titulo "Hamlet"
    :concorrentes ["Johnny Belinda" "The Red Shoes" "The Snake Pit" "The Treasure of the Sierra Madre"]}
   {:ano 1949 :titulo "All the King's Men"
    :concorrentes ["Battleground" "The Heiress" "A Letter to Three Wives" "Twelve O'Clock High"]}
   {:ano 1950 :titulo "All About Eve"
    :concorrentes ["Born Yesterday" "Father of the Bride" "King Solomon's Mines" "Sunset Boulevard"]}
   {:ano 1951 :titulo "An American in Paris"
    :concorrentes ["Decision Before Dawn" "A Place in the Sun" "Quo Vadis" "A Streetcar Named Desire"]}
   {:ano 1952 :titulo "The Greatest Show on Earth"
    :concorrentes ["High Noon" "Ivanhoe" "Moulin Rouge" "The Quiet Man"]}
   {:ano 1953 :titulo "From Here to Eternity"
    :concorrentes ["Julius Caesar" "The Robe" "Roman Holiday" "Shane"]}
   {:ano 1954 :titulo "On the Waterfront"
    :concorrentes ["The Caine Mutiny" "The Country Girl" "Seven Brides for Seven Brothers"
                   "Three Coins in the Fountain"]}
   {:ano 1955 :titulo "Marty"
    :concorrentes ["Love Is a Many-Splendored Thing" "Mister Roberts" "Picnic" "The Rose Tattoo"]}
   {:ano 1956 :titulo "Around the World in 80 Days"
    :concorrentes ["Friendly Persuasion" "Giant" "The King and I" "The Ten Commandments"]}
   {:ano 1957 :titulo "The Bridge on the River Kwai"
    :concorrentes ["12 Angry Men" "Peyton Place" "Sayonara" "Witness for the Prosecution"]}
   {:ano 1958 :titulo "Gigi"
    :concorrentes ["Auntie Mame" "Cat on a Hot Tin Roof" "The Defiant Ones" "Separate Tables"]}
   {:ano 1959 :titulo "Ben-Hur"
    :concorrentes ["Anatomy of a Murder" "The Diary of Anne Frank" "The Nun's Story" "Room at the Top"]}
   {:ano 1960 :titulo "The Apartment"
    :concorrentes ["The Alamo" "Elmer Gantry" "Sons and Lovers" "The Sundowners"]}
   {:ano 1961 :titulo "West Side Story"
    :concorrentes ["Fanny" "The Guns of Navarone" "The Hustler" "Judgment at Nuremberg"]}
   {:ano 1962 :titulo "Lawrence of Arabia"
    :concorrentes ["The Longest Day" "The Music Man" "Mutiny on the Bounty" "To Kill a Mockingbird"]}
   {:ano 1963 :titulo "Tom Jones"
    :concorrentes ["America America" "Cleopatra" "How the West Was Won" "Lilies of the Field"]}
   {:ano 1964 :titulo "My Fair Lady"
    :concorrentes ["Becket" "Dr. Strangelove" "Mary Poppins" "Zorba the Greek"]}
   {:ano 1965 :titulo "The Sound of Music"
    :concorrentes ["Darling" "Doctor Zhivago" "Ship of Fools" "A Thousand Clowns"]}
   {:ano 1966 :titulo "A Man for All Seasons"
    :concorrentes ["Alfie" "The Russians Are Coming, the Russians Are Coming"
                   "The Sand Pebbles" "Who's Afraid of Virginia Woolf?"]}
   {:ano 1967 :titulo "In the Heat of the Night"
    :concorrentes ["Bonnie and Clyde" "Doctor Dolittle" "The Graduate" "Guess Who's Coming to Dinner"]}
   {:ano 1968 :titulo "Oliver!"
    :concorrentes ["Funny Girl" "The Lion in Winter" "Rachel, Rachel" "Romeo and Juliet"]}
   {:ano 1969 :titulo "Midnight Cowboy"
    :concorrentes ["Anne of the Thousand Days" "Butch Cassidy and the Sundance Kid"
                   "Hello, Dolly!" "Z"]}
   {:ano 1970 :titulo "Patton"
    :concorrentes ["Airport" "Five Easy Pieces" "Love Story" "M*A*S*H"]}
   {:ano 1971 :titulo "The French Connection"
    :concorrentes ["A Clockwork Orange" "Fiddler on the Roof" "The Last Picture Show"
                   "Nicholas and Alexandra"]}
   {:ano 1972 :titulo "The Godfather"
    :concorrentes ["Cabaret" "Deliverance" "The Emigrants" "Sounder"]}
   {:ano 1973 :titulo "The Sting"
    :concorrentes ["American Graffiti" "Cries and Whispers" "The Exorcist" "A Touch of Class"]}
   {:ano 1974 :titulo "The Godfather Part II"
    :concorrentes ["Chinatown" "The Conversation" "Lenny" "The Towering Inferno"]}
   {:ano 1975 :titulo "One Flew Over the Cuckoo's Nest"
    :concorrentes ["Barry Lyndon" "Dog Day Afternoon" "Jaws" "Nashville"]}
   {:ano 1976 :titulo "Rocky"
    :concorrentes ["All the President's Men" "Bound for Glory" "Network" "Taxi Driver"]}
   {:ano 1977 :titulo "Annie Hall"
    :concorrentes ["The Goodbye Girl" "Julia" "Star Wars" "The Turning Point"]}
   {:ano 1978 :titulo "The Deer Hunter"
    :concorrentes ["Coming Home" "Heaven Can Wait" "Midnight Express" "An Unmarried Woman"]}
   {:ano 1979 :titulo "Kramer vs. Kramer"
    :concorrentes ["All That Jazz" "Apocalypse Now" "Breaking Away" "Norma Rae"]}
   {:ano 1980 :titulo "Ordinary People"
    :concorrentes ["Coal Miner's Daughter" "The Elephant Man" "Raging Bull" "Tess"]}
   {:ano 1981 :titulo "Chariots of Fire"
    :concorrentes ["Atlantic City" "On Golden Pond" "Raiders of the Lost Ark" "Reds"]}
   {:ano 1982 :titulo "Gandhi"
    :concorrentes ["E.T. the Extra-Terrestrial" "Missing" "Tootsie" "The Verdict"]}
   {:ano 1983 :titulo "Terms of Endearment"
    :concorrentes ["The Big Chill" "The Dresser" "The Right Stuff" "Tender Mercies"]}
   {:ano 1984 :titulo "Amadeus"
    :concorrentes ["The Killing Fields" "A Passage to India" "Places in the Heart" "A Soldier's Story"]}
   {:ano 1985 :titulo "Out of Africa"
    :concorrentes ["The Color Purple" "Kiss of the Spider Woman" "Prizzi's Honor" "Witness"]}
   {:ano 1986 :titulo "Platoon"
    :concorrentes ["Children of a Lesser God" "Hannah and Her Sisters" "The Mission"
                   "A Room with a View"]}
   {:ano 1987 :titulo "The Last Emperor"
    :concorrentes ["Broadcast News" "Fatal Attraction" "Hope and Glory" "Moonstruck"]}
   {:ano 1988 :titulo "Rain Man"
    :concorrentes ["The Accidental Tourist" "Dangerous Liaisons" "Mississippi Burning" "Working Girl"]}
   {:ano 1989 :titulo "Driving Miss Daisy"
    :concorrentes ["Born on the Fourth of July" "Dead Poets Society" "Field of Dreams" "My Left Foot"]}
   {:ano 1990 :titulo "Dances with Wolves"
    :concorrentes ["Awakenings" "Ghost" "The Godfather Part III" "Goodfellas"]}
   {:ano 1991 :titulo "The Silence of the Lambs"
    :concorrentes ["Beauty and the Beast" "Bugsy" "JFK" "The Prince of Tides"]}
   {:ano 1992 :titulo "Unforgiven"
    :concorrentes ["The Crying Game" "A Few Good Men" "Howards End" "Scent of a Woman"]}
   {:ano 1993 :titulo "Schindler's List"
    :concorrentes ["The Fugitive" "In the Name of the Father" "The Piano" "The Remains of the Day"]}
   {:ano 1994 :titulo "Forrest Gump"
    :concorrentes ["Four Weddings and a Funeral" "Pulp Fiction" "Quiz Show" "The Shawshank Redemption"]}
   {:ano 1995 :titulo "Braveheart"
    :concorrentes ["Apollo 13" "Babe" "Il Postino: The Postman" "Sense and Sensibility"]}
   {:ano 1996 :titulo "The English Patient"
    :concorrentes ["Fargo" "Jerry Maguire" "Secrets & Lies" "Shine"]}
   {:ano 1997 :titulo "Titanic"
    :concorrentes ["As Good as It Gets" "The Full Monty" "Good Will Hunting" "L.A. Confidential"]}
   {:ano 1998 :titulo "Shakespeare in Love"
    :concorrentes ["Elizabeth" "Life Is Beautiful" "Saving Private Ryan" "The Thin Red Line"]}
   {:ano 1999 :titulo "American Beauty"
    :concorrentes ["The Cider House Rules" "The Green Mile" "The Insider" "The Sixth Sense"]}
   {:ano 2000 :titulo "Gladiator"
    :concorrentes ["Chocolat" "Crouching Tiger, Hidden Dragon" "Erin Brockovich" "Traffic"]}
   {:ano 2001 :titulo "A Beautiful Mind"
    :concorrentes ["Gosford Park" "In the Bedroom" "The Lord of the Rings: The Fellowship of the Ring"
                   "Moulin Rouge!"]}
   {:ano 2002 :titulo "Chicago"
    :concorrentes ["Gangs of New York" "The Hours" "The Lord of the Rings: The Two Towers" "The Pianist"]}
   {:ano 2003 :titulo "The Lord of the Rings: The Return of the King"
    :concorrentes ["Lost in Translation" "Master and Commander: The Far Side of the World"
                   "Mystic River" "Seabiscuit"]}
   {:ano 2004 :titulo "Million Dollar Baby"
    :concorrentes ["The Aviator" "Finding Neverland" "Ray" "Sideways"]}
   {:ano 2005 :titulo "Crash"
    :concorrentes ["Brokeback Mountain" "Capote" "Good Night, and Good Luck" "Munich"]}
   {:ano 2006 :titulo "The Departed"
    :concorrentes ["Babel" "Letters from Iwo Jima" "Little Miss Sunshine" "The Queen"]}
   {:ano 2007 :titulo "No Country for Old Men"
    :concorrentes ["Atonement" "Juno" "Michael Clayton" "There Will Be Blood"]}
   {:ano 2008 :titulo "Slumdog Millionaire"
    :concorrentes ["The Curious Case of Benjamin Button" "Frost/Nixon" "Milk" "The Reader"]}
   {:ano 2009 :titulo "The Hurt Locker"
    :concorrentes ["Avatar" "The Blind Side" "District 9" "An Education" "Inglourious Basterds"
                   "Precious" "A Serious Man" "Up" "Up in the Air"]}
   {:ano 2010 :titulo "The King's Speech"
    :concorrentes ["Black Swan" "The Fighter" "Inception" "The Kids Are All Right" "127 Hours"
                   "The Social Network" "Toy Story 3" "True Grit" "Winter's Bone"]}
   {:ano 2011 :titulo "The Artist"
    :concorrentes ["The Descendants" "Extremely Loud & Incredibly Close" "The Help" "Hugo"
                   "Midnight in Paris" "Moneyball" "The Tree of Life" "War Horse"]}
   {:ano 2012 :titulo "Argo"
    :concorrentes ["Amour" "Beasts of the Southern Wild" "Django Unchained" "Life of Pi"
                   "Lincoln" "Les Misérables" "Silver Linings Playbook" "Zero Dark Thirty"]}
   {:ano 2013 :titulo "12 Years a Slave"
    :concorrentes ["American Hustle" "Captain Phillips" "Dallas Buyers Club" "Gravity" "Her"
                   "Nebraska" "Philomena" "The Wolf of Wall Street"]}
   {:ano 2014 :titulo "Birdman"
    :concorrentes ["American Sniper" "Boyhood" "The Grand Budapest Hotel" "The Imitation Game"
                   "Selma" "The Theory of Everything" "Whiplash"]}
   {:ano 2015 :titulo "Spotlight"
    :concorrentes ["The Big Short" "Bridge of Spies" "Brooklyn" "Mad Max: Fury Road"
                   "The Martian" "The Revenant" "Room"]}
   {:ano 2016 :titulo "Moonlight"
    :concorrentes ["Arrival" "Fences" "Hacksaw Ridge" "Hell or High Water" "Hidden Figures"
                   "La La Land" "Lion" "Manchester by the Sea"]}
   {:ano 2017 :titulo "The Shape of Water"
    :concorrentes ["Call Me by Your Name" "Darkest Hour" "Dunkirk" "Get Out" "Lady Bird"
                   "Phantom Thread" "The Post" "Three Billboards Outside Ebbing, Missouri"]}
   {:ano 2018 :titulo "Green Book"
    :concorrentes ["Black Panther" "BlacKkKlansman" "Bohemian Rhapsody" "The Favourite"
                   "Roma" "A Star Is Born" "Vice"]}
   {:ano 2019 :titulo "Parasite"
    :concorrentes ["Ford v Ferrari" "The Irishman" "Jojo Rabbit" "Joker" "Little Women"
                   "Marriage Story" "1917" "Once Upon a Time in Hollywood"]}
   {:ano 2020 :titulo "Nomadland"
    :concorrentes ["The Father" "Judas and the Black Messiah" "Mank" "Minari"
                   "Promising Young Woman" "Sound of Metal" "The Trial of the Chicago 7"]}
   {:ano 2021 :titulo "CODA"
    :concorrentes ["Belfast" "Don't Look Up" "Drive My Car" "Dune" "King Richard"
                   "Licorice Pizza" "Nightmare Alley" "The Power of the Dog" "West Side Story"]}
   {:ano 2022 :titulo "Everything Everywhere All at Once"
    :concorrentes ["All Quiet on the Western Front" "Avatar: The Way of Water"
                   "The Banshees of Inisherin" "Elvis" "The Fabelmans" "Tár"
                   "Top Gun: Maverick" "Triangle of Sadness" "Women Talking"]}
   {:ano 2023 :titulo "Oppenheimer"
    :concorrentes ["American Fiction" "Anatomy of a Fall" "Barbie" "The Holdovers"
                   "Killers of the Flower Moon" "Maestro" "Past Lives" "Poor Things"
                   "The Zone of Interest"]}
   {:ano 2024 :titulo "Anora"
    :concorrentes ["The Brutalist" "A Complete Unknown" "Conclave" "Dune: Part Two"
                   "Emilia Pérez" "I'm Still Here" "Nickel Boys" "The Substance" "Wicked"]}
   {:ano 2025 :titulo "One Battle After Another"
    :concorrentes ["Bugonia" "F1" "Frankenstein" "Hamnet" "Marty Supreme" "The Secret Agent"
                   "Sentimental Value" "Sinners" "Train Dreams"]}])

(defn- buscar-filme-por-titulo-ano [message titulo ano]
  (-> (buscar-json (str "/search/movie?query=" (js/encodeURIComponent titulo) "&year=" ano))
      (p/then (fn [data]
                (if-let [filme (first (:results data))]
                  (com-sinopse-traduzida message filme)
                  (str "❓ Não achei \"" titulo "\" (" ano ") no TMDB."))))
      (p/catch (fn [err]
                 (js/console.error "Erro ao buscar filme do Oscar:" err)
                 "❌ Não consegui buscar esse filme agora. Tente novamente mais tarde."))))

(defn- formatar-concorrentes [concorrentes]
  (when (seq concorrentes)
    (str "🏅 Também concorreram a Melhor Filme nesse ano:\n"
         (str/join "\n" (map #(str "• " %) concorrentes)))))

(defn- buscar-vencedor-com-concorrentes [message {:keys [ano titulo concorrentes]}]
  (-> (buscar-filme-por-titulo-ano message titulo ano)
      (p/then (fn [resultado]
                (let [extra (formatar-concorrentes concorrentes)]
                  (cond
                    (and resultado extra) (str resultado "\n\n" extra)
                    extra                 extra
                    :else                 resultado))))))

(defn buscar-oscar
  "!oscar [ano] - filme vencedor do Oscar de Melhor Filme daquele ano, junto
  com os outros concorrentes da categoria (sem ano, sorteia um vencedor de
  toda a história do prêmio)."
  ([message]
   (if (str/blank? config/tmdb-api-key)
     (chave-ausente)
     (buscar-vencedor-com-concorrentes message (rand-nth vencedores-oscar))))
  ([message ano-texto]
   (cond
     (str/blank? config/tmdb-api-key)
     (chave-ausente)

     (str/blank? ano-texto)
     (buscar-oscar message)

     :else
     (let [ano      (js/parseInt ano-texto 10)
           vencedor (when-not (js/isNaN ano)
                      (first (filter #(= (:ano %) ano) vencedores-oscar)))]
       (if vencedor
         (buscar-vencedor-com-concorrentes message vencedor)
         (p/resolved (str "❓ Não tenho o vencedor do Oscar de Melhor Filme de " ano-texto
                          " (sei de " (:ano (first vencedores-oscar)) " a "
                          (:ano (last vencedores-oscar)) ", com uma lacuna em 1928).")))))))


(def ^:private generos-filme
  ;; ids conferidos ao vivo em /genre/movie/list?language=pt-BR (TMDB)
  {"acao" 28 "aventura" 12 "animacao" 16 "comedia" 35 "crime" 80
   "documentario" 99 "drama" 18 "familia" 10751 "fantasia" 14
   "historia" 36 "terror" 27 "musica" 10402 "misterio" 9648
   "romance" 10749 "ficcao cientifica" 878 "scifi" 878 "sci-fi" 878
   "cinema tv" 10770 "thriller" 53 "suspense" 53 "guerra" 10752
   "faroeste" 37 "western" 37})

(defn- normalizar [s]
  (-> s str/trim str/lower-case (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "")))

(defn- genero-id [nome]
  (get generos-filme (normalizar nome)))

(defn- pedido-de-lista? [texto]
  (contains? #{"" "listar" "generos" "gêneros" "genero" "gênero"} (normalizar texto)))

(defn- listar-generos []
  (str "🎭 *Gêneros* (tio " config/bot-name "):\n\n"
       "Ação, Aventura, Animação, Comédia, Crime, Documentário, Drama, "
       "Família, Fantasia, História, Terror, Música, Mistério, Romance, "
       "Ficção científica, Suspense/Thriller, Guerra, Faroeste"
       "\n\nUse " config/prefix "genero <nome> pra receber uma indicação."))

(defn buscar-por-genero
  "!genero <nome> - indica um filme popular daquele gênero (sem nome ou com
  'listar', mostra os gêneros disponíveis)."
  [message texto]
  (cond
    (str/blank? config/tmdb-api-key)
    (chave-ausente)

    (pedido-de-lista? texto)
    (p/resolved (listar-generos))

    :else
    (if-let [id (genero-id texto)]
      (-> (buscar-json (str "/discover/movie?with_genres=" id
                            "&sort_by=popularity.desc&vote_count.gte=50&page=" (inc (rand-int 5))))
          (p/then (fn [data]
                    (let [encontrados (:results data)]
                      (if (seq encontrados)
                        (com-sinopse-traduzida message (rand-nth encontrados))
                        (str "❓ Não encontrei filmes do gênero \"" texto "\" agora. Tente outro.")))))
          (p/catch (fn [err]
                     (js/console.error "Erro ao buscar filme por genero:" err)
                     "❌ Não consegui buscar um filme desse gênero agora. Tente novamente mais tarde.")))
      (p/resolved (str "❓ Gênero \"" texto "\" não reconhecido. Use " config/prefix
                       "genero listar pra ver as opções.")))))

