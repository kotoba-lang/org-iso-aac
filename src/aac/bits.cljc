(ns aac.bits
  "MSB-first bit reader over a byte vector, for the AAC raw_data_block
   payload (ISO/IEC 14496-3 §4.4 syntax tables read every field this way —
   `uimsbf`/`bslbf`/`vlclbf`). Pure cljc, zero dependencies. Mirrors
   `kotoba-lang/org-iso-h264`'s `h264.expgolomb` reader shape (same
   `reader`/`bit!`/`bits!` names and atom-based bytepos/bitpos state) for
   consistency across this org's bitstream-format repos, minus the
   Exp-Golomb-specific `ue!`/`se!` (AAC has no Exp-Golomb codes — its
   variable-length fields are all Huffman, see `aac.huffman`)."
  )

(defn reader [data]
  {:data (vec data) :len (count data) :bytepos (atom 0) :bitpos (atom 0)})

(defn bit! [r]
  (let [bp @(:bytepos r) bi @(:bitpos r)]
    (when (>= bp (:len r)) (throw (ex-info "aac.bits: bit EOF" {})))
    (let [byte (nth (:data r) bp)
          v    (bit-and (bit-shift-right byte (- 7 bi)) 1)]
      (if (= bi 7) (do (reset! (:bitpos r) 0) (swap! (:bytepos r) inc))
          (reset! (:bitpos r) (inc bi)))
      v)))

(defn bits! [r n]
  (loop [i 0 acc 0] (if (= i n) acc (recur (inc i) (bit-or (bit-shift-left acc 1) (bit! r))))))

(defn byte-aligned? [r] (zero? @(:bitpos r)))

(defn align-to-byte!
  "Advance `r` to the next byte boundary if not already aligned (no-op if
   already aligned). Used by e.g. `data_stream_element()`'s
   `data_byte_align_flag`."
  [r]
  (when-not (byte-aligned? r)
    (reset! (:bitpos r) 0)
    (swap! (:bytepos r) inc)))

(defn bit-position
  "Total bits consumed so far (bytepos*8 + bitpos) — for diagnostics/tests."
  [r]
  (+ (* 8 @(:bytepos r)) @(:bitpos r)))
