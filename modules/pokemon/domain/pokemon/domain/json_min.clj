(ns pokemon.domain.json-min
  "Leitor do subconjunto JSON usado pelos catálogos de golpe.
  Objetos, listas, strings, números, true/false/null."
  (:require [clojure.string :as str]))

(defn- parse-at [s i]
  (let [n (count s)]
    (letfn [(ws [i]
              (loop [i i]
                (if (and (< i n) (#{\space \newline \return \tab} (.charAt s i)))
                  (recur (inc i))
                  i)))
            (parse-string [i]
              (loop [i (inc i) sb (StringBuilder.)]
                (when (>= i n) (throw (ex-info "string json incompleta" {})))
                (let [c (.charAt s i)]
                  (cond
                    (= c \\)
                    (let [e (.charAt s (inc i))]
                      (recur (+ i 2)
                             (doto sb (.append (case e
                                                 \" \"
                                                 \\ \\
                                                 \/ \/
                                                 \n \newline
                                                 \r \return
                                                 \t \tab
                                                 (throw (ex-info "escape json" {:e (str e)})))))))
                    (= c \") [(.toString sb) (inc i)]
                    :else (recur (inc i) (doto sb (.append c)))))))
            (parse-number [i]
              (let [j (loop [j i]
                        (if (and (< j n) (re-matches #"[0-9eE.+-]" (str (.charAt s j))))
                          (recur (inc j))
                          j))
                    txt (subs s i j)
                    num (if (str/includes? txt ".") (Double/parseDouble txt) (Long/parseLong txt))]
                [num j]))
            (parse-value [i]
              (let [i (ws i)
                    c (.charAt s i)]
                (cond
                  (= c \") (parse-string i)
                  (= c \{) (parse-object (inc i))
                  (= c \[) (parse-array (inc i))
                  (= c \t)
                  (do (when-not (str/starts-with? (subs s i) "true")
                        (throw (ex-info "true" {})))
                      [true (+ i 4)])
                  (= c \f)
                  (do (when-not (str/starts-with? (subs s i) "false")
                        (throw (ex-info "false" {})))
                      [false (+ i 5)])
                  (= c \n)
                  (do (when-not (str/starts-with? (subs s i) "null")
                        (throw (ex-info "null" {})))
                      [nil (+ i 4)])
                  :else (parse-number i))))
            (skip-comma [i]
              (let [i (ws i)]
                (if (and (< i n) (= \, (.charAt s i))) (ws (inc i)) i)))
            (parse-object [i]
              (loop [i (ws i) m {}]
                (cond
                  (>= i n) (throw (ex-info "objeto incompleto" {}))
                  (= \} (.charAt s i)) [m (inc i)]
                  :else
                  (let [[k i2] (parse-string (ws i))
                        i3 (ws i2)
                        _ (when-not (= \: (.charAt s i3))
                            (throw (ex-info "esperado :" {:k k})))
                        [v i4] (parse-value (inc i3))]
                    (recur (skip-comma i4) (assoc m k v))))))
            (parse-array [i]
              (loop [i (ws i) xs []]
                (cond
                  (>= i n) (throw (ex-info "lista incompleta" {}))
                  (= \] (.charAt s i)) [xs (inc i)]
                  :else
                  (let [[v i2] (parse-value i)]
                    (recur (skip-comma i2) (conj xs v))))))]
      (first (parse-value (ws i))))))

(defn read-str [s]
  (parse-at s 0))
