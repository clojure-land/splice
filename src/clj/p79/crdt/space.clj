(ns p79.crdt.space
  (:require [p79.crdt :as crdt]
            [p79.crdt.map :as map]
            [cemerick.utc-dates :refer (now)]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.pprint :as pp])
  (:refer-clojure :exclude (read)))

(def ^:private rng (java.security.SecureRandom.))

(defn uuid [] (str (java.util.UUID/randomUUID)))
(defn time-uuid
  "Returns a sequential UUID. Guaranteed to:

(a) monotonically increase lexicographically
(b) contain [time] (or the current time in millis) as the most significant bits"
  ([] (time-uuid (System/currentTimeMillis)))
  ([time] (str (java.util.UUID. time (.nextLong rng)))))

(defmacro defroottype
  [type-name ctor-name type-tag value-name value-pred]
  (let [value-field (symbol (str ".-" value-name))
        type-tag (str "#" type-tag " ")
        [arg arg2] [(gensym) (gensym)]
        [type-arg type-arg2] (map #(with-meta % {:tag type-name}) [arg arg2])]
    `(do
       (deftype ~type-name [~value-name]
         clojure.lang.IDeref
         (deref [~arg] (~value-field ~type-arg))
         Comparable
         (compareTo [~arg ~arg2]
           (compare (~value-field ~type-arg) (~value-field ~type-arg2)))
         Object
         (toString [~arg] (pr-str ~arg))
         (hashCode [~arg] (inc (hash (~value-field ~type-arg))))
         (equals [~arg ~arg2]
           (and (instance? ~type-name ~arg2)
             (= (~value-field ~type-arg) (~value-field ~type-arg2))))
         ;; this here only for the benefit of Tombstone
         clojure.lang.ILookup
         (valAt [this# k#] (get ~value-name k#))
         (valAt [this# k# default#] (get ~value-name k# default#)))
       (defmethod print-method ~type-name [~type-arg ^java.io.Writer w#]
         (.write w# ~type-tag)
         (print-method (~value-field ~type-arg) w#))
       (defmethod print-dup ~type-name [o# w#]
         (print-method o# w#))
       (#'pp/use-method pp/simple-dispatch ~type-name #'pp/pprint-simple-default)
       (defn ~(symbol (str ctor-name "?"))
         ~(str "Returns true iff the sole argument is a " type-name)
         [x#]
         (instance? ~type-name x#))
       (defn ~ctor-name
         ~(str "Creates a new " type-name)
         [e#]
         (when e#
           (cond
             (instance? ~type-name e#) e#
             (~value-pred e#) (~(symbol (str type-name ".")) e#)
             :else (throw
                     (IllegalArgumentException.
                       (str "Cannot create " ~type-name " with value of type "
                         (class e#))))))))))

(defroottype Entity entity "entity" e string?)
;; TODO not sure if this distinct reference type is worthwhile.
;; if the primary use case is referring to entities, then #entity "abcd" is
;; no worse than #ref #entity "abcd", right?  Even the generalized case of e.g.
;; #ref #s3 "https://..." doesn't provide much (any?) semantic benefit.
;(defroottype Ref reference "ref" e entity?)
;(defroottype Tag tag "tag" t entity?)

;; TODO don't quite like the e/a/v naming here
;; s/p/o is used in RDF, but might be too much of a tie to semweb
;; [element datum state]? :-P

(defn metadata? [tuple] (= (:e tuple) (:write tuple)))

(defprotocol AsTuples
  (as-tuples [x]))

(defrecord Tuple [e a v write remove]
  AsTuples
  (as-tuples [this] [this]))

(defn tuple? [x] (instance? Tuple x))

(defn coerce-tuple
  "Positional factory function for class p79.crdt.space.Tuple
that coerces any shortcut entity values to Entity instances.
This fn should therefore always be used in preference to the Tuple. ctor."
  [e a v write remove]
  (Tuple. (entity e) a v (entity write) (entity remove)))

(extend-protocol AsTuples
  nil
  (as-tuples [x] [])
  java.util.List
  (as-tuples [ls]
    (if (== 5 (count ls))
      [(apply coerce-tuple ls)]
      (throw (IllegalArgumentException.
               (str "Vector/list cannot be tuple-ized, bad size: " (count ls))))))
  java.util.Map
  (as-tuples [m]
    ;; if Tuple ever loses its inline impl of as-tuples, we *must*
    ;; rewrite this to dynamically extend AsTuples to concrete Map
    ;; types; otherwise, there's no way to prefer an extension to
    ;; Tuple over one to java.util.Map
    (if-let [[_ e] (find m :db/id)]
      (let [time (:db/time m)
            tag (:db/tag m)
            s (seq (dissoc m :db/id))]
        (if s
          (mapcat (fn [[k v]]
                    (let [v (if (set? v) v #{v})]
                      (let [maps (filter map? v)
                            other (concat
                                    (remove map? v)
                                    (map (comp entity :db/id) maps))]
                        (concat
                          (mapcat as-tuples maps)
                          (map (fn [v] (coerce-tuple e k v tag nil)) other)))))
            s)
          (throw (IllegalArgumentException. "Empty Map cannot be tuple-ized."))))
      (throw (IllegalArgumentException. "Map cannot be tuple-ized, no :db/id")))))

(defn prep-tuples
  [op-tag tuples]
  (for [t tuples]
    (if (:write t) t (assoc t :write op-tag))))

(defprotocol Space
  (read [this]
     "Returns a lazy seq of the tuples in this space.")
  (write* [this tuples] "Writes the given tuples to this space.")
  
  ;; don't expose until we know how to efficiently return indexes
  ;; that incorporate the ambient time filter
  ;; (can it be done, given that we need to keep existing index entries
  ;; "live" for replicated updates from the past?)
  #_
  (as-of [this] [this time]
         "Returns a new space restricted to tuples written prior to [time]."))

; TODO how to control policy around which writes need to be replicated?
; probably going to require query on the destination in order to determine workset
(defn update-write-meta
  [space written-tuples]
  (let [last-replicated-write (-> space meta ::replication :lwr)
        writes (set (map :write written-tuples))
        out-of-order-writes (when last-replicated-write
                              (set/union
                                (set/select #(neg? (compare % last-replicated-write)))
                                (or (-> space meta ::replication :ooow) #{})))]
    (vary-meta space merge
      {::writes writes
       ::replication {:lwr last-replicated-write
                      :ooow out-of-order-writes}})))

(defn write
  "Writes the given data to this space optionally along with tuples derived from
a map of operation metadata, first converting it to tuples with `as-tuples`."
  ([this tuples] (write this nil tuples))
  ([this op-meta ts]
    (let [time (now)
          tag (entity (time-uuid (.getTime time)))
          op-meta (merge {:time time} op-meta {:db/id tag})
          tuples (->> (mapcat as-tuples ts)
                   (concat (as-tuples op-meta))
                   (prep-tuples tag))]
      (update-write-meta (write* this tuples) tuples))))

(defprotocol IndexedSpace
  (available-indexes [this])
  (index [this index-type])
  (q* [this query args]
     "Queries this space, returning a seq of results per the query's specification"))

(defn q
  [space {:keys [select planner args subs where] :as query} & arg-values]
  (q* space query arg-values))

(deftype IndexBottom [])
(deftype IndexTop [])
(def index-bottom (IndexBottom.))
(def index-top (IndexTop.))

(defn assign-map-ids
  "Walks the provided collection, adding :db/id's to all maps that don't have one already."
  [m]
  (walk/postwalk
    (fn [x]
      (cond
        (not (map? x)) x
        (:db/id x) x
        :else (assoc x :db/id (uuid))))
    m))
