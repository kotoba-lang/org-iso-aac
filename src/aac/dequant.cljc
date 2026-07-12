(ns aac.dequant
  "AAC-LC inverse quantization + scaling (ISO/IEC 14496-3:2005 §4.6.3.3,
   the `quant_to_spec`-equivalent step): for each quantized spectral
   coefficient `x_quant` in scalefactor band `sfb` (whose absolute
   scalefactor is `scale_factor`, from `aac.ics/scale-factor-data!`):

     x = sign(x_quant) * |x_quant|^(4/3) * 2^((scale_factor - 100) / 4)

   Cross-checked against FAAD2's floating-point `iquant()`/`quant_to_spec()`
   (`specrec.c`) during development: FAAD2 factors the power-of-two term as
   `pow2sf_tab[scale_factor>>2] * pow2_table[scale_factor&3]`, a fixed-point-
   friendly decomposition of the exact same `2^((scale_factor-100)/4)` (its
   `pow2sf_tab[25] == 1.0` folds in the `-100`/4 == `-25` offset) — algebraically
   identical to the direct formula used here, just restated for a lookup-table
   implementation this repo doesn't need (plain `Math/pow` is exact and
   portable enough at this scope)."
  )

(defn- pow4-3 [x]
  #?(:clj (Math/pow x (/ 4.0 3.0))
     :cljs (js/Math.pow x (/ 4.0 3.0))))

(defn- pow2 [x]
  #?(:clj (Math/pow 2.0 x)
     :cljs (js/Math.pow 2.0 x)))

(defn dequantize-coeff
  "Inverse-quantize + scale one coefficient."
  [x-quant scale-factor]
  (if (zero? x-quant)
    0.0
    (let [mag (pow4-3 (if (neg? x-quant) (- x-quant) x-quant))
          scale (pow2 (/ (- scale-factor 100) 4.0))
          v (* mag scale)]
      (if (neg? x-quant) (- v) v))))

(defn dequantize
  "Dequantize a full 1024-length `coeffs` vector using `sfb-cb`/`scale-factors`
   (per `aac.ics/decode-individual-channel-stream!`) and `swb-offsets`
   (`aac.tables/swb-offsets`) to map each coefficient bin to its scalefactor
   band. Bands coded with ZERO_HCB are left as 0.0 (their scale-factors
   entry is a meaningless placeholder, per `aac.ics/scale-factor-data!`)."
  [coeffs sfb-cb scale-factors swb-offsets]
  (let [max-sfb (count sfb-cb)
        n (count coeffs)]
    (loop [sfb 0 out (vec (repeat n 0.0))]
      (if (>= sfb max-sfb)
        out
        (let [cb (nth sfb-cb sfb)]
          (if (zero? cb)
            (recur (inc sfb) out)
            (let [start (nth swb-offsets sfb)
                  end (nth swb-offsets (inc sfb))
                  sf (nth scale-factors sfb)
                  out (reduce (fn [o i] (assoc o i (dequantize-coeff (nth coeffs i) sf)))
                               out (range start end))]
              (recur (inc sfb) out))))))))
