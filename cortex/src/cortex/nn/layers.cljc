(ns cortex.nn.layers
  (:require [cortex.nn.protocols :as cp]
            [cortex.util :as util :refer [error]]
            [cortex.nn.impl.layers :as impl]
            [cortex.nn.impl.wiring]
            [clojure.core.matrix :as m]
            [cortex.nn.backends :as b]
            [cortex.nn.impl.layers.convolution :as conv])
  (:refer-clojure :exclude [identity]))

#?(:clj (do
          (set! *warn-on-reflection* true)
          (set! *unchecked-math* :warn-on-boxed)))

;; ===========================================================================
;; Layer constructors

(defn function
  "Wraps a Clojure function in a cortex module. The function f will be applied to the input to produce the output.

   An optional gradient-fn may be provided, in which case the input gradient will be calculated by:
   (gradient-fn input output-gradient)"
  ([f]
    (when-not (fn? f) (error "function-module requires a Clojure function"))
    (cortex.nn.impl.wiring.FunctionModule. f))
  ([f gradient-fn]
    (when-not (fn? f) (error "function-module requires a Clojure function"))
    (cortex.nn.impl.wiring.FunctionModule. f nil {:gradient-fn gradient-fn})))

(defn logistic
  "Creates a logistic module of the given shape."
  ([shape]
    (when-not (coll? shape)
      (error "logistic layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Logistic.
      (util/empty-array shape)
      (util/empty-array shape))))

(defn tanh
  "Creates a tanh module of the given shape."
  ([shape]
    (when-not (coll? shape)
      (error "tanh layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Tanh.
      (util/empty-array shape)
      (util/empty-array shape))))

(defn dropout
  "Creates a dropout module of the given shape.

   During training, units will be included with the given probability."
  ([shape probability]
    (when-not (coll? shape)
      (error "Dropout layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Dropout.
      (util/empty-array shape)
      (util/empty-array shape)
      (util/empty-array shape)
      (impl/create-dropout-noise-fn probability)
      nil
      {:probability (double probability)})))

(defn gaussian-multiplicative-noise
  "Creates a multiplicative gaussian noise module of the given shape.

   During training, gauassian noise will be applied with the given probability, in which case:
     x -> x * N(1.0,sd)"
  ([shape noise-fn]
    (when-not (coll? shape)
      (error "Dropout layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Dropout.
      (util/empty-array shape)
      (util/empty-array shape)
      (util/empty-array shape)
      noise-fn))
  ([shape probability sd]
    (let [noise-fn (impl/create-gaussian-multiplicative-noise-fn probability sd)]
      (gaussian-multiplicative-noise shape noise-fn))))

(defn scale
  "Creates a linear scaling layer with the specified shape and multiplication factor
   An optional constant offset may also be provided"
  ([shape factor]
    (scale shape factor nil))
  ([shape factor constant]
    (let [factor (if (and factor (number? factor) (== 1.0 (double factor))) nil factor)
          constant (if (and constant (m/zero-matrix? constant)) nil constant)]
      (cortex.nn.impl.layers.Scale. (util/empty-array shape) (util/empty-array shape) factor constant))))

(defn identity
  "Creates an identity layer with the specified shape"
  ([shape factor]
    (scale shape 1.0)))

(defn softmax
  "Creates a softmax module of the given shape."
  ([shape]
    (when-not (coll? shape)
      (error "softmax layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Softmax.
        (util/empty-array shape)
        (util/empty-array shape))))

(defn softplus
  "Creates a softplus module of the given shape."
  ([shape]
    (when-not (coll? shape)
      (error "softplus layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.Softplus.
        (util/empty-array shape)
        (util/empty-array shape))))

(defn relu
  "Creates a rectified linear (ReLU) module of the given shape.

   An optional factor may be provided to scale negative values, which otherwise defaults to 0.0"
  ([shape & {:keys [negval]
             :or {negval 0.0}}]
    (when-not (coll? shape)
      (error "relu layer constructor requires a shape vector"))
    (cortex.nn.impl.layers.RectifiedLinear.
        (util/empty-array shape)
        (util/empty-array shape)
        (util/empty-array shape)
        (double negval))))

(defn linear
  "Constructs a weighted linear transformation module using a dense matrix and bias vector.
   Shape of input and output are determined by the weight matrix.

   Options available: 
    :l2-max-constraint = constraint to apply to l2 norm of rows (i.e. the vector of weights for each output)
    :weight-scale      = factor to multiply the weights during initialisation (default 1.0)"
  ([weights bias]
    (linear weights bias nil))
  ([weights bias options]
    (let [weights (m/array :vectorz weights)
          bias (m/array :vectorz bias)
          wm (cortex.nn.impl.layers.Linear. weights bias nil options)
          [n-outputs n-inputs] (m/shape weights)
          n-outputs (long n-outputs)
          n-inputs (long n-inputs)]
      (if-let [weight-scale (:weight-scale options)]
        (m/scale! weights weight-scale))
      (when-not (== n-outputs (m/dimension-count bias 0)) (error "Mismatched weight and bias shapes"))
      (-> wm
        (assoc :weight-gradient (util/empty-array [n-outputs n-inputs]))
        (assoc :bias-gradient (util/empty-array [n-outputs]))
        (assoc :input-gradient (util/empty-array [n-inputs]))))))

(defn linear-layer
  "Creates a linear layer with a new randomised weight matrix for the given number of inputs and outputs.

   Options available:
    :weights           = specify the weight matrix to use directly
    :bias              = specify the bias vector to use directly
    :l2-max-constraint = constraint to apply to l2 norm of rows (i.e. the vector of weights for each output)
    :weight-scale      = factor to multiply the weights during initialisation (default 1.0)"
  ([n-inputs n-outputs & {:keys [weights bias l2-max-constraint] :as options}]
    (linear (or weights (util/weight-matrix n-outputs n-inputs))
            (or bias (util/empty-array [n-outputs]))
            options)))

(defn split
  "Creates a split later using a collection of modules. The split layer returns the outputs of each
   sub-module concatenated into a vector, i.e. it behaves as a fn: input -> [output0 output1 ....]"
  ([modules]
    (let [modules (vec modules)]
      (cortex.nn.impl.wiring.Split. modules))))

(defn combine
  "Creates a combine layer that applies a specified combination function to create the output.
   i.e. it behaves as a fn: [input0 input1 ....] -> output

   An optional gradient-fn may be provided, in which case the backward pass will compute a vector of input
   gradients according to (gradient-fn input output-gradient). In the absence of a gradient-fn, input
   gradients will be zero."
  ([combine-fn]
    (cortex.nn.impl.wiring.Combine. combine-fn))
  ([combine-fn gradient-fn]
    (cortex.nn.impl.wiring.Combine. combine-fn nil {:gradient-fn gradient-fn})))

(defn normaliser
  "Constructs a normaliser of the given shape. A normaliser layer scales input to mean 0.0 and s.d. 1.0,
   Adjusting scaling factors over time during training as new input is provided."
  ([shape]
    (normaliser shape nil))
  ([shape {:keys [learn-rate normaliser-factor] :as options}]
    (when-not (coll? shape)
      (error "normaliser layer constructor requires a shape vector"))
    (let [output (util/empty-array shape)
          input-gradient (util/empty-array shape)
          mean (util/empty-array shape)
          sd (util/empty-array shape)
          acc-ss (util/empty-array shape)
          acc-mean (util/empty-array shape)
          tmp (util/empty-array shape)
          ]
      (m/fill! sd 1.0)
      (m/fill! acc-ss 1.0)
      (cortex.nn.impl.layers.Normaliser. output input-gradient sd mean acc-ss acc-mean tmp nil options))))

(defn autoencoder
  "Constructs an auto-encoder, using the specified 'up' and 'down' modules.

   During model usagem, only the 'up' module is used. During training, the 'down' module attempts to
   regenerate the input of the 'up' modulefrom the output of the 'up' module.

   For a denoising auto-encoder, you may put a later that generates noise in the 'down' module.
   Shape of output of up must match input of down, and vice-versa."
  ([up down]
    (autoencoder up down nil))
  ([up down options]
    (cortex.nn.impl.layers.Autoencoder. up down
                                     (m/clone (cp/output down)) (m/clone (cp/output up))
                                     nil options)))


(defn convolutional
  [input-width input-height num-input-channels
   kernel-width kernel-height pad-x pad-y stride-x stride-y
   num-kernels
   & {:keys [weights bias]}]
  (let [^long input-width input-width
        ^long num-input-channels num-input-channels
        ^long kernel-width kernel-width
        ^long kernel-height kernel-height
        conv-config (conv/create-conv-layer-config input-width input-height
                                                   kernel-width kernel-height
                                                   pad-x pad-y
                                                   stride-x stride-y
                                                   num-input-channels
                                                   num-kernels)
        weights (or weights
                    (util/weight-matrix num-kernels (* kernel-width
                                                       kernel-height
                                                       num-input-channels)))
        bias (or bias
                 (b/zero-array [1 num-kernels]))]
    (conv/->Convolutional weights bias conv-config)))


(defn max-pooling
  "Performs per-channel max within a convolution window.  Thus output has same number of channels
as the input"
  [input-width input-height num-input-channels
   kernel-width kernel-height pad-x pad-y stride-x stride-y]
  (let [^long num-input-channels num-input-channels
        ^long input-width input-width
        ^long input-height input-height
        conv-config (conv/create-conv-layer-config input-width input-height
                                                   kernel-width kernel-height
                                                   pad-x pad-y
                                                   stride-x stride-y
                                                   num-input-channels)]
    (conv/->Pooling conv-config)))
