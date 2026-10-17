(ns pokemon.domain.errors)

(defn error
  ([code] (error code {}))
  ([code data]
   (ex-info (name code) (assoc data :pokemon/error code))))

(defn error-code
  [ex]
  (when (instance? clojure.lang.ExceptionInfo ex)
    (:pokemon/error (ex-data ex))))

(defn conflict?
  "Anomalia Datomic de conflito (CAS / unique)."
  [ex]
  (let [data (ex-data ex)]
    (or (= :cognitect.anomalies/conflict (:cognitect.anomalies/category data))
        (boolean (re-find #"(?i)cas|unique|conflict"
                          (str (ex-message ex) " " data))))))
