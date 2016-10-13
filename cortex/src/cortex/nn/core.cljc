(ns cortex.nn.core
  "Main cortex API function namespace"
  (:refer-clojure :exclude [clone])
  (:require [cortex.nn.impl.wiring :as wiring]
            [cortex.nn.impl.default- :as cortex.nn.impl.default]
            [cortex.nn.protocols :as cp]
            [cortex.nn.layers :as layers]
            [cortex.nn.backends :as b]
            [cortex.util :as util :refer [error]]
            [clojure.core.matrix :as m]))

#?(:clj (do
          (set! *warn-on-reflection* true)
          (set! *unchecked-math* :warn-on-boxed)))

;; ===========================================================================
;; Main module API functions

(defn calc
  "Runs the calculation for a module. Returns the updated module, with output available."
  ([m input]
    (cp/calc m input)))

(defn output
  "Gets the ouptut for a module. Assumes that either `calc` or a `forward` pass has been run.

   Throws an exception if not available."
  ([m]
    (or (cp/output m) (error "No output available for module: " #?(:clj (class m) :cljs (type m))))))

(defn calc-output
  "Performs the calculation for a module, as per `calc`. This is intended for use when you just want
   to use the module for computation, and do not care about the resulting module stats.

   Returns the module output as per `output`."
  ([m input]
   (cp/output (cp/calc m input))))

(defn join-item-list
  [item-seq]
  (let [vecs (map m/as-vector item-seq)]
    (if (seq vecs)
      (apply m/join vecs)
      [])))

(defn parameters
  "Gets the vector of parameters for a module (possibly empty)"
  ([m]
   (join-item-list (cp/parameters m))))

(defn parameter-count
  "Gets the number of parameters for a given module."
  ([m]
    (cp/parameter-count m)))

(defn gradient
  "Gets the accumulated gradient vector for a module (possibly empty)"
  ([m]
   (join-item-list (cp/gradient m))))

(defn forward
  "Runs the forward training pass on a neural network module."
  ([m input]
    (let [m (cp/prepare-forward m)]
      (cp/forward m input))))

(defn input-size
  "Returns the size of the module input array."
  ([m]
    (cp/input-size m)))

(defn output-size
  "Returns the size of the module output array."
  ([m]
    (cp/output-size m)))

(defn backward
  "Runs the backward training pass on a neural network module. Input must be the same as used
   in the previous forward pass."
  ([m input output-gradient]
    (cp/backward m input output-gradient)))

(defn input-gradient
  "Gets the input gradient for a module. Throws an exception if not available."
  ([m]
   (or (cp/input-gradient m) (error "No input gradient available - maybe run backward pass first?"))))

(defn optimise
  "Optimises a module using the given optimiser. Returns an [optimiser module] pair"
  ([optimiser module ^long batch-count]
   ;;Faster to create local copies of what could be quite large views.  This also means the
   ;;optimizer can copy those into itself and mutate them without affecting anything outside
   (let [mod-params (cp/parameters module)
         mod-gradient (cp/gradient module)
         params (util/assign-sparse-to-packed! (:packed-params optimiser) mod-params)
         grads (util/assign-sparse-to-packed! (:packed-grads optimiser) mod-gradient)
         ;;It would be more efficient to fold this multiplication into the
         ;;compute function call itself instead of an operation outside of it.
         _ (when-not (= 0 batch-count)
             (m/mul! grads (/ 1.0 batch-count)))
         optimiser (cp/compute-parameters optimiser grads params)
         parameters (parameters optimiser)
         module (cp/update-parameters module parameters)]
     [(assoc optimiser
             :packed-params params
             :packed-grads grads)
      module]))
  ([optimiser module]
    (optimise optimiser module 1)))

;; ===========================================================================
;; Module construction and combinator functions

(defn stack-module
  "Creates a linear stack of modules"
  ([modules]
    (when (empty? modules) (error "Stack must have at least one sub-module"))
    (cortex.nn.impl.wiring.StackModule. (vec modules))))

(defn clone
  "clones a module, including all internal state structures. New module will be independent of the original."
  ([m]
    (cp/clone m)))
