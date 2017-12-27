(ns metabase.automagic-dashboards.rules
  "Validation, transformation to cannonical form, and loading of heuristics."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.types]
            [metabase.util :as u]
            [metabase.util.schema :as su]
            [schema
             [coerce :as sc]
             [core :as s]]
            [yaml.core :as yaml]))

(def ^Integer max-score
  "Maximal (and default) value for heuristics scores."
  100)

(def ^:private Score (s/constrained s/Int #(<= 0 % max-score)))

(def ^:private MBQL [s/Any])

(def ^:private Identifier s/Str)

(def ^:private Metric {Identifier {(s/required-key :metric) MBQL
                                   (s/required-key :score)  Score}})

(def ^:private Filter {Identifier {(s/required-key :filter) MBQL
                                   (s/required-key :score)  Score}})

(defn- field-type?
  [t]
  (isa? t :type/Field))

(defn- table-type?
  [t]
  (isa? t :type/Table))

(defn ga-dimension?
  "Does string `t` denote a Google Analytics dimension?"
  [t]
  (str/starts-with? t "ga:"))

(def ^:private TableType (s/constrained s/Keyword table-type?))
(def ^:private FieldType (s/either (s/constrained s/Str ga-dimension?)
                                   (s/constrained s/Keyword field-type?)))

(def ^:private FieldSpec (s/either [FieldType]
                                   [(s/one TableType "table") FieldType]))

(def ^:private Dimension {Identifier {(s/required-key :field_type) FieldSpec
                                      (s/required-key :score)      Score}})

(def ^:private OrderByPair {Identifier (s/enum "descending" "ascending")})

(def  ^:private Visualization [(s/one s/Str "visualization") su/Map])

(def ^:private Card
  {Identifier {(s/required-key :title)         s/Str
               (s/required-key :visualization) Visualization
               (s/required-key :score)         Score
               (s/optional-key :dimensions)    [s/Str]
               (s/optional-key :filters)       [s/Str]
               (s/optional-key :metrics)       [s/Str]
               (s/optional-key :limit)         su/IntGreaterThanZero
               (s/optional-key :order_by)      [OrderByPair]
               (s/optional-key :description)   s/Str}})

(def ^:private ^{:arglists '([definitions])} identifiers
  (comp set (partial map (comp key first))))

(defn- all-references
  [k cards]
  (mapcat (comp k val first) cards))

(defn dimension-form?
  "Does form denote a dimension referece?"
  [form]
  (and (sequential? form)
       (#{:dimension "dimension" "DIMENSION"} (first form))))

(defn collect-dimensions
  "Return all dimension references in form."
  [form]
  (->> form
       (tree-seq (some-fn map? sequential?) identity)
       (filter dimension-form?)
       (map second)
       distinct))

(defn- valid-references?
  "Check if all references to metrics, dimensions, and filters are valid (ie.
   have a corresponding definition)."
  [{:keys [metrics dimensions filters cards]}]
  (let [defined-dimensions (identifiers dimensions)
        defined-metrics    (identifiers metrics)
        defined-filters    (identifiers filters)]
    (and (every? defined-metrics (all-references :metrics cards))
         (every? defined-filters (all-references :filters cards))
         (every? defined-dimensions (all-references :dimensions cards))
         (->> cards
              (all-references :order_by)
              (every? (comp (into defined-dimensions defined-metrics) key first)))
         (every? defined-dimensions (collect-dimensions [metrics filters])))))

(def ^:private Rules
  (s/constrained
   {(s/required-key :table_type)  TableType
    (s/required-key :title)       s/Str
    (s/required-key :dimensions)  [Dimension]
    (s/required-key :cards)       [Card]
    (s/optional-key :description) s/Str
    (s/optional-key :metrics)     [Metric]
    (s/optional-key :filters)     [Filter]}
   valid-references?))

(defn- with-defaults
  [defaults]
  (fn [x]
    (let [[identifier definition] (first x)]
      {identifier (merge defaults definition)})))

(defn- shorthand-definition
  "Expand definition of the form {identifier value} with regards to key `k` into
   {identifier {k value}}."
  [k]
  (fn [x]
    (let [[identifier definition] (first x)]
      (if (map? definition)
        x
        {identifier {k definition}}))))

(defn- ensure-seq
  [x]
  (if (or (sequential? x) (nil? x))
    x
    [x]))

(defn- ->type
  [x]
  (cond
    (keyword? x)      x
    (ga-dimension? x) x
    :else             (keyword "type" x)))

(def ^:private rules-validator
  (sc/coercer!
   Rules
   {[s/Str]       ensure-seq
    [OrderByPair] ensure-seq
    FieldSpec     (fn [x]
                    (map ->type (str/split x #"\.")))
    OrderByPair   (fn [x]
                    (if (string? x)
                      {x "ascending"}
                      x))
    Visualization (fn [x]
                    (if (string? x)
                      [x {}]
                      (first x)))
    Metric        (comp (with-defaults {:score max-score})
                        (shorthand-definition :metric))
    Dimension     (comp (with-defaults {:score max-score})
                        (shorthand-definition :field_type))
    Filter        (comp (with-defaults {:score max-score})
                        (shorthand-definition :filter))
    Card          (with-defaults {:score max-score})
    TableType     ->type
    FieldType     ->type
    Identifier    (fn [x]
                    (if (keyword? x)
                      (name x)
                      x))}))

(def ^:private rules-dir "resources/automagic_dashboards")

(def ^:private ^{:arglists '([f])} file-name->table-type
  (comp (partial re-find #".+(?=\.yaml)") (memfn ^java.io.File getName)))

(defn load-rules
  "Load and validate all rules in `rules-dir`."
  []
  (->> rules-dir
       clojure.java.io/file
       file-seq
       (filter (memfn ^java.io.File isFile))
       (keep (fn [^java.io.File f]
               (try
                 (-> f
                     slurp
                     yaml/parse-string
                     (update :table_type #(or % (file-name->table-type f)))
                     rules-validator)
                 (catch Exception e
                   (log/error (format "Error parsing %s:\n%s"
                                      (.getName f)
                                      (-> e
                                          ex-data
                                          (select-keys [:error :value])
                                          u/pprint-to-str)))
                   nil))))))

(defn -main
  "Entry point for lein task `validate-automagic-dashboards`"
  [& _]
  (doall (load-rules))
  (System/exit 0))