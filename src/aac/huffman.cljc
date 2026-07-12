(ns aac.huffman
  "AAC-LC noiseless-coding Huffman decode (ISO/IEC 14496-3:2005 §4.6.3.3
   \"Decoding process\" + Annex 4.A tables, `aac.huffman-tables`). Builds a
   canonical binary trie per codebook from the (length, codeword) table and
   walks it one bit at a time — a plain, literal transcription of the
   decoding process, not FAAD2's 2-step lookup-table optimization (deliberate:
   this repo is Apache-2.0, FAAD2 is GPL-2.0, and a byte-for-byte-optimized
   lookup-table ARRANGEMENT is a different (and copyrightable) artifact from
   the underlying spec-mandated codeword-to-value mapping the trie approach
   decodes directly from Annex 4.A's own published tables; see
   `aac.huffman-tables`'s namespace docstring for how those tables were
   sourced/verified).

   Scope: the scalefactor codebook (Table 4.A.1) and all eleven spectral
   codebooks (1-11, Table 4.132 / Tables 4.A.2-4.A.12) are implemented —
   more than this repo's original 'at least 1-2 codebooks actually used in
   real data' target, because empirically decoding a real ffmpeg/libavcodec
   AAC-LC ONLY_LONG_SEQUENCE frame with actual (non-silent) audio content
   requires MOST of codebooks 1-11 simultaneously (a single real frame's
   sections routinely span 5-9 distinct codebooks — verified by probing
   `section_data()` output against `ffmpeg -c:a aac`-encoded fixtures during
   development). Codebooks 12 (reserved), 13 (NOISE_HCB/PNS), 14
   (INTENSITY_HCB2) and 15 (INTENSITY_HCB) are pseudo-codebooks that carry NO
   Huffman-coded spectral data (§4.6.3.3) — `aac.ics` skips them structurally
   and never calls into this namespace for them; PNS/intensity-stereo
   themselves are out of scope and `aac.ics` throws if a scalefactor band is
   actually marked with one (see that namespace's docstring)."
  (:require [aac.bits :as bits]
            [aac.huffman-tables :as tabs]))

;; --- canonical trie construction --------------------------------------

(defn build-trie
  "Build a binary trie from a `cbN-huffman-table`-shaped vector (index i ->
   [length codeword]): each leaf is the codeword's table index `i` (NOT yet
   translated to spectral/scalefactor values — see `idx->tuple`/scalefactor
   `index_offset`). Internal nodes are maps {0 <child-or-leaf>, 1
   <child-or-leaf>}; a leaf is a bare integer (the index)."
  [table]
  (loop [trie {} i 0]
    (if (>= i (count table))
      trie
      (let [[length codeword] (nth table i)
            bits-str (map #(bit-and (bit-shift-right codeword %) 1)
                          (range (dec length) -1 -1))]
        (recur (update-in trie bits-str (fn [existing]
                                           (when (map? existing)
                                             (throw (ex-info "aac.huffman: codeword prefix collision building trie"
                                                              {:index i :length length :codeword codeword})))
                                           i))
               (inc i))))))

(def ^:private trie-cache (atom {}))

(defn- trie-for [key table]
  (or (get @trie-cache key)
      (let [t (build-trie table)]
        (swap! trie-cache assoc key t)
        t)))

(defn decode-index!
  "Walk `trie` one bit at a time from `r` until a leaf (integer) is found;
   returns the table index."
  [r trie]
  (loop [node trie]
    (if (integer? node)
      node
      (let [b (bits/bit! r)]
        (if-let [next (get node b)]
          (recur next)
          (throw (ex-info "aac.huffman: no codeword matches (bitstream desync or corrupt data)" {})))))))

;; --- scalefactor (Table 4.A.1 / Table 4.131) ---------------------------

(def ^:private sf-index-offset -60)

(defn decode-scalefactor-dpcm!
  "Decode one Huffman scalefactor/is_position/noise_nrg DPCM codeword ->
   differential value (index_offset + huffman index, Table 4.131)."
  [r]
  (+ sf-index-offset (decode-index! r (trie-for :sf tabs/sf-huffman-table))))

;; --- spectral codebooks 1-11 (Table 4.132 / Tables 4.A.2-4.A.12) -------

(def codebook-params
  "cb -> {:dim :unsigned? :lav}, Table 4.132."
  {1  {:dim 4 :unsigned? false :lav 1}
   2  {:dim 4 :unsigned? false :lav 1}
   3  {:dim 4 :unsigned? true  :lav 2}
   4  {:dim 4 :unsigned? true  :lav 2}
   5  {:dim 2 :unsigned? false :lav 4}
   6  {:dim 2 :unsigned? false :lav 4}
   7  {:dim 2 :unsigned? true  :lav 7}
   8  {:dim 2 :unsigned? true  :lav 7}
   9  {:dim 2 :unsigned? true  :lav 12}
   10 {:dim 2 :unsigned? true  :lav 12}
   11 {:dim 2 :unsigned? true  :lav 16}})

(def ^:private cb-table
  {1 tabs/cb1-huffman-table 2 tabs/cb2-huffman-table 3 tabs/cb3-huffman-table
   4 tabs/cb4-huffman-table 5 tabs/cb5-huffman-table 6 tabs/cb6-huffman-table
   7 tabs/cb7-huffman-table 8 tabs/cb8-huffman-table 9 tabs/cb9-huffman-table
   10 tabs/cb10-huffman-table 11 tabs/cb11-huffman-table})

(defn idx->tuple
  "Translate a decoded Huffman table index to its n-tuple of quantized
   spectral values, per the pseudo-C code in §4.6.3.3 ('The result of
   Huffman decoding each spectrum n-tuple is the codeword index ... This
   index is translated to the n-tuple spectral values ...')."
  [cb idx]
  (let [{:keys [dim unsigned? lav]} (get codebook-params cb)
        _ (when (nil? dim) (throw (ex-info "aac.huffman: unknown/unsupported spectral codebook" {:cb cb})))
        mod- (if unsigned? (inc lav) (inc (* 2 lav)))
        off (if unsigned? 0 lav)]
    (if (= dim 4)
      (let [w (- (quot idx (* mod- mod- mod-)) off)
            idx1 (- idx (* (+ w off) mod- mod- mod-))
            x (- (quot idx1 (* mod- mod-)) off)
            idx2 (- idx1 (* (+ x off) mod- mod-))
            y (- (quot idx2 mod-) off)
            idx3 (- idx2 (* (+ y off) mod-))
            z (- idx3 off)]
        [w x y z])
      (let [y (- (quot idx mod-) off)
            idx1 (- idx (* (+ y off) mod-))
            z (- idx1 off)]
        [y z]))))

(defn- read-sign-bits!
  "For unsigned codebooks: one sign bit ('1' = negative) per NON-ZERO
   coefficient, immediately following the Huffman codeword (§4.6.3.3)."
  [r tuple]
  (mapv (fn [v]
          (if (zero? v)
            v
            (if (= 1 (bits/bit! r)) (- v) v)))
        tuple))

(def ^:private escape-flag 16)

(defn- read-escape!
  "ESC_HCB (codebook 11) escape_sequence for a value of magnitude 16
   (`escape_flag`): N one-bits (prefix, N>=1) + one zero-bit (separator) +
   an (N+4)-bit unsigned integer word -> decoded magnitude
   2^(N+4) + escape_word (§4.6.3.3), sign already applied by the caller via
   the sign bit read for this coefficient's slot before the escape."
  [r]
  (loop [n 0]
    (if (= 1 (bits/bit! r))
      (recur (inc n))
      (let [word-bits (+ n 4)
            word (bits/bits! r word-bits)]
        (+ (bit-shift-left 1 word-bits) word)))))

(defn decode-spectral-tuple!
  "Decode one n-tuple (quad for cb<5, pair for cb>=5) of quantized spectral
   coefficients for spectral codebook `cb` (1-11). Handles sign bits
   (unsigned codebooks) and codebook 11's escape mechanism. Returns the
   signed quantized integer tuple (a vector of length 4 or 2)."
  [r cb]
  (let [table (or (get cb-table cb) (throw (ex-info "aac.huffman: unsupported spectral codebook" {:cb cb})))
        {:keys [unsigned?]} (get codebook-params cb)
        idx (decode-index! r (trie-for [:cb cb] table))
        tuple (idx->tuple cb idx)
        tuple (if unsigned? (read-sign-bits! r tuple) tuple)]
    (if (= cb 11)
      (mapv (fn [v]
              (if (= (if (neg? v) (- v) v) escape-flag)
                (let [mag (read-escape! r)]
                  (if (neg? v) (- mag) mag))
                v))
            tuple)
      tuple)))
