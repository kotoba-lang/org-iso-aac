(ns aac.ics
  "AAC-LC `individual_channel_stream()` side-info + spectral data decode
   (ISO/IEC 14496-3:2005 §4.4.2.7 Tables 4.44/4.6/4.46/4.47/4.50, §4.6.3
   noiseless coding). Scope (see `aac.decode`'s namespace docstring for the
   full statement): **mono, ONLY_LONG_SEQUENCE only** — `window_sequence`
   values other than `ONLY_LONG_SEQUENCE` (0) throw, as do any of the
   optional tools this repo doesn't implement (`predictor_data_present`,
   `pulse_data_present`, `tns_data_present`, `gain_control_data_present`,
   and per-scalefactor-band PNS/intensity-stereo pseudo-codebooks
   NOISE_HCB/INTENSITY_HCB/INTENSITY_HCB2) — all of these are read (so the
   bitstream position stays correct up to the point they're detected) and
   then throw immediately if set, rather than silently mis-decoding.

   PNS in particular is near-ubiquitous in real `ffmpeg -c:a aac`-encoded
   streams (perceptual noise substitution replaces sparse high-frequency
   content with synthesized noise, and is on by default) — real fixtures
   for this repo's tests were generated with `-aac_pns 0` for exactly this
   reason (see `test/aac/decode_test.clj`'s docstring). PNS is also
   fundamentally not bit-exact-comparable across independent decoders even
   when implemented: the spec does not mandate a specific noise generator,
   so any two spec-compliant decoders synthesize DIFFERENT sample values
   for a PNS-coded band (only the target energy is normative) — throwing
   here rather than fabricating a decoder-specific noise generator keeps
   this repo's 'decode this and compare against real ffmpeg PCM' validation
   methodology honest."
  (:require [aac.bits :as bits]
            [aac.tables :as tables]
            [aac.huffman :as huffman]))

(def ^:private zero-hcb 0)
(def ^:private noise-hcb 13)
(def ^:private intensity-hcb2 14)
(def ^:private intensity-hcb 15)
(def ^:private out-of-scope-cbs #{noise-hcb intensity-hcb2 intensity-hcb})

(defn ics-info!
  "ics_info() (Table 4.6). Returns {:window-sequence :window-shape
   :max-sfb}. Throws unless window_sequence == ONLY_LONG_SEQUENCE (0) and
   predictor_data_present == 0 (both out of scope, see namespace
   docstring)."
  [r]
  (let [ics-reserved-bit (bits/bit! r)
        _ (when-not (zero? ics-reserved-bit)
            (throw (ex-info "aac.ics: ics_reserved_bit must be 0" {})))
        window-sequence (bits/bits! r 2)
        window-shape (bits/bit! r)]
    (when-not (zero? window-sequence)
      (throw (ex-info "aac.ics: only ONLY_LONG_SEQUENCE (window_sequence=0) is in scope"
                       {:window-sequence window-sequence})))
    (let [max-sfb (bits/bits! r 6)
          predictor-data-present (bits/bit! r)]
      (when-not (zero? predictor-data-present)
        (throw (ex-info "aac.ics: predictor_data_present is out of scope" {})))
      {:window-sequence window-sequence :window-shape window-shape :max-sfb max-sfb})))

(defn section-data!
  "section_data() (Table 4.46), ONLY_LONG_SEQUENCE case (`num_window_groups`
   is always 1 for a long window — §4.6.11.3.1's `window_grouping_info`).
   Returns `sfb_cb` — a vector of length `max-sfb`, one Huffman codebook
   index (or ZERO_HCB) per scalefactor band."
  [r max-sfb]
  (let [sect-esc-val 31]
    (loop [k 0 sfb-cb (vec (repeat max-sfb nil))]
      (if (>= k max-sfb)
        sfb-cb
        (let [sect-cb (bits/bits! r 4)
              sect-len (loop [total 0]
                         (let [incr (bits/bits! r 5)]
                           (if (= incr sect-esc-val)
                             (recur (+ total incr))
                             (+ total incr))))]
          (recur (+ k sect-len)
                 (reduce (fn [v i] (assoc v i sect-cb)) sfb-cb (range k (+ k sect-len)))))))))

(defn scale-factor-data!
  "scale_factor_data() (Table 4.47), non-ER path (`aacScalefactorDataResilienceFlag`
   == 0, always true for plain AAC-LC ADTS). `global-gain` seeds the DPCM
   chain (§4.6.3.3: 'Global gain ... is typically the value of the first
   active scalefactor ... The first active scalefactor is differentially
   coded relative to the global gain'). Returns a vector of length
   `(count sfb-cb)`, the absolute scalefactor per band (0 for ZERO_HCB
   bands, unused). Throws if a band is coded with NOISE_HCB/INTENSITY_HCB/
   INTENSITY_HCB2 (PNS / intensity stereo, out of scope — see namespace
   docstring)."
  [r sfb-cb global-gain]
  (loop [sfb 0 running global-gain out []]
    (if (>= sfb (count sfb-cb))
      out
      (let [cb (nth sfb-cb sfb)]
        (cond
          (= cb zero-hcb)
          (recur (inc sfb) running (conj out 0))

          (contains? out-of-scope-cbs cb)
          (throw (ex-info "aac.ics: PNS/intensity-stereo codebook is out of scope"
                           {:sfb sfb :codebook cb}))

          :else
          (let [delta (huffman/decode-scalefactor-dpcm! r)
                sf (+ running delta)]
            (recur (inc sfb) sf (conj out sf))))))))

(defn spectral-data!
  "spectral_data() (Table 4.50), ONLY_LONG_SEQUENCE (single window group).
   Decodes n-tuples per scalefactor band per `sfb-cb`'s codebook (skipping
   ZERO_HCB bands, whose coefficients are implicitly zero) and returns the
   full 1024-length quantized-coefficient vector (`swb-offsets` gives each
   band's [start, end) range; anything at/above `swb-offsets[max-sfb]` that
   isn't covered — i.e. above the highest coded scalefactor band — is left
   zero per §4.6.3.3: 'spectral information for all scalefactor bands at
   and above max_sfb ... is zero')."
  [r sfb-cb swb-offsets frame-len]
  (let [max-sfb (count sfb-cb)]
    (loop [sfb 0 coeffs (vec (repeat frame-len 0))]
      (if (>= sfb max-sfb)
        coeffs
        (let [cb (nth sfb-cb sfb)
              start (nth swb-offsets sfb)
              end (nth swb-offsets (inc sfb))]
          (if (= cb zero-hcb)
            (recur (inc sfb) coeffs)
            (let [dim (:dim (get huffman/codebook-params cb))
                  _ (when (nil? dim) (throw (ex-info "aac.ics: unsupported spectral codebook" {:cb cb :sfb sfb})))
                  coeffs (loop [bin start coeffs coeffs]
                           (if (>= bin end)
                             coeffs
                             (let [tuple (huffman/decode-spectral-tuple! r cb)]
                               (recur (+ bin dim)
                                      (reduce (fn [c i] (assoc c (+ bin i) (nth tuple i)))
                                              coeffs (range (min dim (- end bin))))))))]
              (recur (inc sfb) coeffs))))))))

(defn decode-individual-channel-stream!
  "`individual_channel_stream(common_window=false, scale_flag=false)`
   (Table 4.44), the single_channel_element() case (`common_window` is
   always false for SCE). Reads `global_gain`, `ics_info()`,
   `section_data()`, `scale_factor_data()`, the three optional-tool presence
   flags (pulse/tns/gain-control — throws if any is 1, out of scope), then
   `spectral_data()`. `sfi` is the ADTS `sampling_frequency_index` (drives
   the scalefactor-band table, `aac.tables`). Returns {:window-sequence
   :window-shape :max-sfb :scale-factors :coeffs}."
  [r sfi frame-len]
  (let [global-gain (bits/bits! r 8)
        {:keys [window-sequence window-shape max-sfb]} (ics-info! r)
        sfb-cb (section-data! r max-sfb)
        scale-factors (scale-factor-data! r sfb-cb global-gain)
        pulse-data-present (bits/bit! r)
        _ (when-not (zero? pulse-data-present)
            (throw (ex-info "aac.ics: pulse_data_present is out of scope" {})))
        tns-data-present (bits/bit! r)
        _ (when-not (zero? tns-data-present)
            (throw (ex-info "aac.ics: tns_data_present is out of scope" {})))
        gain-control-data-present (bits/bit! r)
        _ (when-not (zero? gain-control-data-present)
            (throw (ex-info "aac.ics: gain_control_data_present is out of scope" {})))
        swb-offsets (tables/swb-offsets sfi)
        coeffs (spectral-data! r sfb-cb swb-offsets frame-len)]
    {:window-sequence window-sequence
     :window-shape window-shape
     :max-sfb max-sfb
     :sfb-cb sfb-cb
     :scale-factors scale-factors
     :coeffs coeffs}))
