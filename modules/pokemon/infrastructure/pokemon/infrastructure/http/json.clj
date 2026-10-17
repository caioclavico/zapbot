(ns pokemon.infrastructure.http.json
  "Encoder/decoder JSON mínimo (sem deps extras) para a bridge M4.1.
  Keywords viram camelCase; demais tipos escalares/coleções."
  (:require [clojure.string :as str])
  (:import [java.util List Map]))

(defn- camel-key
  [k]
  (let [s (if (keyword? k) (name k) (str k))
        parts (str/split s #"-")]
    (apply str (first parts) (map str/capitalize (rest parts)))))

(defn- escape
  [^String s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn write-str
  "Serializa valor Clojure → JSON string."
  [x]
  (cond
    (nil? x) "null"
    (true? x) "true"
    (false? x) "false"
    (number? x) (str x)
    (keyword? x) (str "\"" (escape (camel-key x)) "\"")
    (string? x) (str "\"" (escape x) "\"")
    (or (map? x) (instance? Map x))
    (str "{"
         (->> x
              (map (fn [[k v]]
                     (str "\"" (escape (camel-key k)) "\":" (write-str v))))
              (str/join ","))
         "}")
    (or (sequential? x) (set? x) (instance? List x))
    (str "[" (str/join "," (map write-str x)) "]")
    :else (str "\"" (escape (pr-str x)) "\"")))

(defn- skip-ws [^String s ^long i]
  (loop [j i]
    (if (and (< j (.length s))
             (Character/isWhitespace (.charAt s j)))
      (recur (inc j))
      j)))

(defn- parse-string [^String s ^long i]
  (when-not (= \" (.charAt s i))
    (throw (ex-info "json_expected_string" {:at i})))
  (let [sb (StringBuilder.)]
    (loop [j (inc i)]
      (when (>= j (.length s))
        (throw (ex-info "json_unclosed_string" {})))
      (let [c (.charAt s j)]
        (cond
          (= c \") [(str sb) (inc j)]
          (= c \\)
          (let [n (.charAt s (inc j))]
            (.append sb (case n
                          \" \"
                          \\ \\
                          \/ \/
                          \b \u0008
                          \f \u000c
                          \n \n
                          \r \r
                          \t \t
                          n))
            (recur (+ j 2)))
          :else (do (.append sb c) (recur (inc j))))))))

(declare parse-value)

(defn- parse-array [^String s ^long i]
  (when-not (= \[ (.charAt s i))
    (throw (ex-info "json_expected_array" {:at i})))
  (let [i (skip-ws s (inc i))]
    (if (= \] (.charAt s i))
      [[] (inc i)]
      (loop [j i acc []]
        (let [[v j2] (parse-value s j)
              j2 (skip-ws s j2)
              c (.charAt s j2)]
          (cond
            (= c \,) (recur (skip-ws s (inc j2)) (conj acc v))
            (= c \]) [(conj acc v) (inc j2)]
            :else (throw (ex-info "json_array_sep" {:at j2}))))))))

(defn- parse-object [^String s ^long i]
  (when-not (= \{ (.charAt s i))
    (throw (ex-info "json_expected_object" {:at i})))
  (let [i (skip-ws s (inc i))]
    (if (= \} (.charAt s i))
      [{} (inc i)]
      (loop [j i acc {}]
        (let [[k j2] (parse-string s (skip-ws s j))
              j2 (skip-ws s j2)]
          (when-not (= \: (.charAt s j2))
            (throw (ex-info "json_expected_colon" {:at j2})))
          (let [[v j3] (parse-value s (skip-ws s (inc j2)))
                j3 (skip-ws s j3)
                c (.charAt s j3)
                acc (assoc acc k v)]
            (cond
              (= c \,) (recur (skip-ws s (inc j3)) acc)
              (= c \}) [acc (inc j3)]
              :else (throw (ex-info "json_object_sep" {:at j3})))))))))

(defn- parse-number [^String s ^long i]
  (let [len (.length s)]
    (loop [j i]
      (if (and (< j len)
               (let [c (.charAt s j)]
                 (or (Character/isDigit c) (#{\- \+ \. \e \E} c))))
        (recur (inc j))
        (let [raw (.substring s i j)
              n (if (re-find #"[.eE]" raw)
                  (Double/parseDouble raw)
                  (Long/parseLong raw))]
          [n j])))))

(defn- parse-value [^String s ^long i]
  (let [i (skip-ws s i)
        c (.charAt s i)]
    (cond
      (= c \") (parse-string s i)
      (= c \{) (parse-object s i)
      (= c \[) (parse-array s i)
      (= c \t) (when (.startsWith s "true" i) [true (+ i 4)])
      (= c \f) (when (.startsWith s "false" i) [false (+ i 5)])
      (= c \n) (when (.startsWith s "null" i) [nil (+ i 4)])
      (or (= c \-) (Character/isDigit c)) (parse-number s i)
      :else (throw (ex-info "json_unexpected" {:at i :char c})))))

(defn read-str
  "Parse JSON → estruturas Clojure (chaves string)."
  [^String s]
  (let [[v j] (parse-value s 0)
        j (skip-ws s j)]
    (when (< j (.length s))
      (throw (ex-info "json_trailing" {:at j})))
    v))
