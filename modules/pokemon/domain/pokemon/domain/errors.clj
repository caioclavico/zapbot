(ns pokemon.domain.errors)

(defn error
  ([code] (error code {}))
  ([code data]
   (ex-info (name code) (assoc data :pokemon/error code))))

(defn error-code
  [ex]
  (when (instance? clojure.lang.ExceptionInfo ex)
    (:pokemon/error (ex-data ex))))

(defn datoms-conflict?
  "TX inválida: dois datoms contraditórios *na mesma* transação.
  Não é CAS entre writers — retry cego só repete a falha."
  [ex]
  (boolean (re-find #"(?i)datoms-conflict|two datoms in the same transaction"
                    (str (ex-message ex) " " (ex-data ex)))))

(defn conflict?
  "Anomalia Datomic de conflito (CAS / unique) — exclui datoms-conflict."
  [ex]
  (let [data (ex-data ex)]
    (and (not (datoms-conflict? ex))
         (or (= :cognitect.anomalies/conflict (:cognitect.anomalies/category data))
             (boolean (re-find #"(?i)cas|unique|conflict"
                               (str (ex-message ex) " " data)))))))
