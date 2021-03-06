; This Source Code Form is subject to the terms of the Mozilla Public License,
; v. 2.0. If a copy of the MPL was not distributed with this file, You can
; obtain one at http://mozilla.org/MPL/2.0/.

(ns cemerick.splice.memory
  (:require [cemerick.splice :as s :refer (TupleStore)]
            [cemerick.splice.memory.query :as q]
            #+clj [cemerick.splice.memory.planning :as p]
            [cemerick.splice.hosty :refer (now)]
            [cemerick.splice.uuid :refer (random-uuid)]
            [clojure.set :as set])
  #+cljs (:require-macros [cemerick.splice.memory.planning :as p]))

(defn- add-tuples
  [indexes tuples]
  (reduce
    (fn [indexes index-keys]
      (update-in indexes [index-keys] into tuples))
    indexes
    (keys indexes)))

(deftype MemSpace [site-id last-write-num metadata indexes]
  #+clj clojure.lang.IMeta
  #+clj (meta [this] metadata)
  #+cljs IMeta
  #+cljs (-meta [this] metadata)
  #+clj clojure.lang.IObj
  #+clj (withMeta [this meta] (MemSpace. site-id last-write-num meta indexes))
  #+cljs IWithMeta
  #+cljs (-with-meta [this meta] (MemSpace. site-id last-write-num meta indexes))
  TupleStore
  (available-indexes [this] (set (keys indexes)))
  (write* [this tuples]
    (let [[write-num tuples] (s/placeholders->eids site-id last-write-num tuples)
          metadata (if (== write-num last-write-num)
                     metadata
                     ; TODO this ::last-write bullshit is (almost) useless,
                     ; should just be a query
                     (assoc metadata ::last-write [site-id write-num]))]
      (MemSpace. site-id write-num metadata (add-tuples indexes tuples))))
  (scan [this index-spec beg end]
    (s/scan this index-spec beg end nil))
  (scan [this index-spec beg end options]
    (let [reverse (:reverse options false)
          index (indexes index-spec)]
      (assert index (str "No index available corresponding to index-spec "
                      index-spec))
      ((if reverse rsubseq subseq)
       index >= beg <= end))))

(defn site-idq
  [space]
  (ffirst (q/q space (p/plan {:select [?site]
                              :where [["-local-config" :local.quilt/site-id ?site]]}))))

(defn in-memory
  ([]
     (let [site-id (random-uuid)
           [_ tuples] (s/prep-write nil [{::s/e "-local-config"
                                          :local.quilt/site-id site-id}])]
       (s/write* (MemSpace. site-id 0 {} q/empty-indexes) tuples)))
  ([init-tuples]
     (let [indexes (add-tuples q/empty-indexes init-tuples)
           temp (MemSpace. nil nil nil indexes)
           site-id (site-idq temp)
           ; TODO this sort of answer should be queryable; we're really just
           ; looking for the first (last) result of a sorted set and
           ; destructuring; seems within reach once results are delivered as a
           ; lazy seq and not a concrete set
           last-write-num (->> (s/scan temp [:write :a :e :v :remove-write]
                                 (s/tuple s/index-bottom s/index-bottom s/index-bottom
                                   [site-id s/index-bottom])
                                 (s/tuple s/index-top s/index-top s/index-top 
                                   [site-id s/index-top])
                                 {:reverse true})
                            first
                            :write
                            second)]
       (assert (number? last-write-num) "Could not find last write number in initial set of tuples for in-memory splice")
       (MemSpace. site-id last-write-num {} indexes))))

(defn tuples->graph
  "Given a collection of [tuples], returns the graph they represent: a map
containing those tuples' entities as values, keyed by entity id."
  [tuples]
  (reduce
    ; TODO just ignoring removals for now :-/
    ; We should either account for removals within the scope of the provided
    ; tuples, or change entity values to include write and remove-write tags
    (fn [graph {:keys [e a v]}]
      (assoc graph e
             (let [ent (graph e)]
               (assoc ent a
                      (if-let [[_ v'] (find ent a)]
                        (if (set? v')
                          (conj v' v)
                          #{v v'})
                        v)))))
    {}
    tuples))

(defn entity-map
  "Returns a map containing all entries of the named entity, a
:cemerick.splice/e slot containing [entity-id], and metadata indicating the last
write on [space] at the time of the entity lookup."
  [space entity-id]
  (some-> (->> (q/q space (p/plan {:select [?t]
                               :args [?eid]
                               :where [[?eid _ _ _ :as ?t]]})
             entity-id)
        (apply concat)
        tuples->graph)
    (get entity-id)
    (assoc ::s/e entity-id)
    (with-meta (select-keys (meta space) [::s/last-write]))))
