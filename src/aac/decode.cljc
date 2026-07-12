(ns aac.decode
  "AAC-LC decode: ADTS frame -> PCM samples (ISO/IEC 14496-3:2005 §4.4-4.6).
   This is the top-level entry point tying together `aac.adts` (framing,
   pre-existing), `aac.ics` (side info + noiseless-coding spectral decode,
   incl. `channel_pair_element()`/CPE stereo parsing), `aac.dequant`
   (inverse quantization), `aac.stereo` (mid/side reconstruction), and
   `aac.imdct` (filterbank: IMDCT + windowing + overlap-add).

   ## Scope (deliberately narrow — this is the ecosystem's first audio
   codec implementation; see `com-junkawasaki/root` task history)

   - **Mono (`single_channel_element()`/SCE, channel_configuration 1) OR
     stereo (`channel_pair_element()`/CPE, channel_configuration 2,
     including mid/side stereo — see `aac.ics`/`aac.stereo`)**. Intensity
     stereo (a per-band pseudo-codebook, not a CPE-level tool), LFE,
     coupling channels, and multi-element/program_config_element streams
     all still throw.
   - **`ONLY_LONG_SEQUENCE` only** — `aac.ics/ics-info!` throws for
     LONG_START/EIGHT_SHORT/LONG_STOP. In particular this means encoder
     transient regions (attack transients almost always trigger a block
     switch to EIGHT_SHORT) are NOT decodable by this repo — real fixtures
     necessarily start a few frames into a stream, after the transient has
     settled into steady-state long blocks (see `test/aac/decode_test.clj`'s
     docstring for exactly which frame range and why).
   - **No SBR/PS** (this is plain AAC-LC, not HE-AAC/HE-AACv2), **no LTP**,
     **no predictor, pulse, TNS, or gain-control tools**, **no PNS or
     intensity stereo** (`aac.ics` throws on all of these — see that
     namespace's docstring, particularly for PNS: it's near-ubiquitous in
     real encoder output, so real fixtures here were generated with
     `ffmpeg -aac_pns 0`).
   - Only 11-bit `sampling_frequency_index` values 3 (48000 Hz) and 4
     (44100 Hz) are supported (`aac.tables`) — this repo's fixtures are all
     44100 Hz.

   ## Multi-element raw_data_block handling

   A real encoder's `raw_data_block()` is not always a bare single
   `single_channel_element()`/`channel_pair_element()` — `ffmpeg -c:a aac`
   was observed (during development) to prepend a `fill_element()`
   (`id_syn_ele` 6, ancillary/padding data, Table 4.11) before the SCE/CPE
   even in a stream's very first frame. `decode-raw-data-block!` (mono) and
   `decode-raw-data-block-cpe!` (stereo) below both skip `fill_element()`/
   `data_stream_element()` (Tables 4.10/4.11) structurally to reach the
   SCE/CPE, and throw immediately on any OTHER syntax element since those
   are out of this repo's scope."
  (:require [aac.bits :as bits]
            [aac.ics :as ics]
            [aac.dequant :as dequant]
            [aac.stereo :as stereo]
            [aac.tables :as tables]
            [aac.imdct :as imdct]))

(def ^:private id-sce 0)
(def ^:private id-cpe 1)
(def ^:private id-dse 4)
(def ^:private id-fil 6)
(def ^:private id-end 7)
(def ^:private frame-len 1024)

(defn- fill-element!
  "fill_element() (Table 4.11) — skip. `cnt` is 4 bits, escaped to
   14+esc_count (8 more bits) if 15; the payload is exactly `cnt` bytes
   (§'s `extension_payload()` always consumes the count it's given)."
  [r]
  (let [cnt0 (bits/bits! r 4)
        cnt (if (= cnt0 15) (+ 14 (bits/bits! r 8)) cnt0)]
    (dotimes [_ (* 8 cnt)] (bits/bit! r))))

(defn- data-stream-element!
  "data_stream_element() (Table 4.10) — skip."
  [r]
  (bits/bits! r 4) ;; element_instance_tag
  (let [align? (bits/bit! r)
        cnt0 (bits/bits! r 8)
        cnt (if (= cnt0 255) (+ cnt0 (bits/bits! r 8)) cnt0)]
    (when (= align? 1) (bits/align-to-byte! r))
    (dotimes [_ (* 8 cnt)] (bits/bit! r))))

(defn decode-raw-data-block!
  "Read `raw_data_block()` (Table 4.3) elements from `r` until the mono
   `single_channel_element()` is found (skipping FIL/DSE per namespace
   docstring), decode its `individual_channel_stream()`, and return
   `aac.ics/decode-individual-channel-stream!`'s result. Throws on any
   other syntax element (out of scope)."
  [r sfi]
  (loop [guard 0]
    (when (>= guard 16)
      (throw (ex-info "aac.decode: too many syntax elements without finding a single_channel_element" {})))
    (let [id-syn-ele (bits/bits! r 3)]
      (cond
        (= id-syn-ele id-sce)
        (do (bits/bits! r 4) ;; element_instance_tag
            (ics/decode-individual-channel-stream! r sfi frame-len))

        (= id-syn-ele id-dse)
        (do (data-stream-element! r) (recur (inc guard)))

        (= id-syn-ele id-fil)
        (do (fill-element! r) (recur (inc guard)))

        (= id-syn-ele id-end)
        (throw (ex-info "aac.decode: reached END without a single_channel_element (empty/silent frame or unsupported layout)" {}))

        :else
        (throw (ex-info "aac.decode: unsupported syntax element (only mono single_channel_element is in scope)"
                         {:id-syn-ele id-syn-ele}))))))

(defn decode-raw-data-block-cpe!
  "Like `decode-raw-data-block!` but for a STEREO `raw_data_block()` —
   skips `fill_element()`/`data_stream_element()` the same way, but expects
   (and decodes) a `channel_pair_element()` rather than an SCE. Returns
   `aac.ics/decode-channel-pair-element!`'s result: {:ch0 :ch1 :ms-mask
   :common-window?}. Throws on any other syntax element (out of scope for
   the stereo path — in particular a bare SCE here is NOT accepted, use
   `decode-raw-data-block!`/`decode-adts-frame`/`decode-frames` for
   mono channel_configuration 1 streams)."
  [r sfi]
  (loop [guard 0]
    (when (>= guard 16)
      (throw (ex-info "aac.decode: too many syntax elements without finding a channel_pair_element" {})))
    (let [id-syn-ele (bits/bits! r 3)]
      (cond
        (= id-syn-ele id-cpe)
        (do (bits/bits! r 4) ;; element_instance_tag
            (ics/decode-channel-pair-element! r sfi frame-len))

        (= id-syn-ele id-dse)
        (do (data-stream-element! r) (recur (inc guard)))

        (= id-syn-ele id-fil)
        (do (fill-element! r) (recur (inc guard)))

        (= id-syn-ele id-end)
        (throw (ex-info "aac.decode: reached END without a channel_pair_element (empty/silent frame or unsupported layout)" {}))

        :else
        (throw (ex-info "aac.decode: unsupported syntax element (only stereo channel_pair_element is in scope for decode-adts-frame-stereo)"
                         {:id-syn-ele id-syn-ele}))))))

(defn decode-adts-frame
  "Decode one ADTS frame (as produced by `aac.adts/frames`/`parse-header`)
   to 1024 PCM samples (floats — see `aac.decode/pcm->int16` for s16
   rounding/clipping). `prev-window-shape`/`prev-overlap` carry the running
   filterbank state from the previous frame in the stream (use
   `aac.imdct/zero-overlap` and any `window-shape` — it's multiplied by
   an all-zero `prev-overlap` so its value is irrelevant — for the first
   frame). Returns {:pcm [1024 floats] :window-shape :overlap} — the last
   two are this frame's own window_shape/second-half-overlap, to pass as
   `prev-window-shape`/`prev-overlap` for the NEXT frame."
  [frame prev-window-shape prev-overlap]
  (let [r (bits/reader (:payload frame))
        sfi (:sampling-frequency-index frame)
        {:keys [window-shape sfb-cb scale-factors coeffs]} (decode-raw-data-block! r sfi)
        swb-offsets (tables/swb-offsets sfi)
        spec (dequant/dequantize coeffs sfb-cb scale-factors swb-offsets)
        {:keys [pcm overlap]} (imdct/decode-frame spec window-shape prev-window-shape prev-overlap)]
    {:pcm pcm :window-shape window-shape :overlap overlap}))

(defn decode-frames
  "Decode a sequence of consecutive `aac.adts/frames` entries (already
   parsed by `aac.adts`) to a single concatenated PCM vector, threading
   filterbank state (overlap/window-shape) across frames — cold-started
   (`aac.imdct/zero-overlap`) at the FIRST frame given, matching a real
   decoder's stream-start behavior (§4.6.11.3.1's implicit zero history).

   IMPORTANT caveat if `frames` is a MID-STREAM slice (not starting at the
   true stream start): the first frame's OWN output will not match a real
   decoder's (which would have real, non-zero history from the actual
   preceding frame) — only the SECOND frame onward is bit-comparable,
   because each frame's own second-half overlap state (saved for the next
   frame) is computed independent of the input history, only the additive
   output for that one frame is affected. `test/aac/decode_test.clj`
   exploits exactly this to validate a mid-stream frame range against real
   ffmpeg PCM output (skipping the first decoded frame's own output)."
  [frames]
  (loop [fs frames prev-shape 0 prev-overlap imdct/zero-overlap acc []]
    (if (empty? fs)
      acc
      (let [{:keys [pcm window-shape overlap]} (decode-adts-frame (first fs) prev-shape prev-overlap)]
        (recur (rest fs) window-shape overlap (into acc pcm))))))

(defn decode-adts-frame-stereo
  "Decode one STEREO ADTS frame (channel_configuration 2,
   `channel_pair_element()`) to two independent 1024-sample PCM channels.
   `prev-window-shape-l`/`prev-overlap-l` and `prev-window-shape-r`/
   `prev-overlap-r` carry EACH channel's OWN running filterbank state
   independently — the CPE's shared `ics_info()` (`common_window`==1) only
   shares window_sequence/window_shape/max_sfb as bitstream SIDE INFO for
   THIS frame; the §4.6.11 filterbank (IMDCT + 50%-overlap-add) itself still
   runs once per channel with its own independent history, exactly like two
   parallel mono streams (`aac.imdct/decode-frame` is called twice, once per
   channel, mirroring `decode-adts-frame`'s single call).

   Pipeline per frame: `aac.decode/decode-raw-data-block-cpe!` (side info +
   noiseless-coding spectral decode for BOTH channels) -> `aac.dequant/
   dequantize` independently per channel (channel-local scalefactors/
   codebooks) -> `aac.stereo/apply-ms` (cross-channel M/S reconstruction,
   using the pair's shared `:ms-mask` — a no-op pass-through when
   `common_window`==0 or `ms_mask_present`==0) -> `aac.imdct/decode-frame`
   independently per channel.

   Returns {:pcm-l :pcm-r [1024 floats each] :window-shape-l :window-shape-r
   :overlap-l :overlap-r} — the last four, to pass as this same frame's
   `prev-*` for the NEXT frame (mirrors `decode-adts-frame`'s single-channel
   contract)."
  [frame prev-window-shape-l prev-overlap-l prev-window-shape-r prev-overlap-r]
  (let [r (bits/reader (:payload frame))
        sfi (:sampling-frequency-index frame)
        {:keys [ch0 ch1 ms-mask]} (decode-raw-data-block-cpe! r sfi)
        swb-offsets (tables/swb-offsets sfi)
        spec0 (dequant/dequantize (:coeffs ch0) (:sfb-cb ch0) (:scale-factors ch0) swb-offsets)
        spec1 (dequant/dequantize (:coeffs ch1) (:sfb-cb ch1) (:scale-factors ch1) swb-offsets)
        [spec-l spec-r] (stereo/apply-ms spec0 spec1 ms-mask swb-offsets)
        {pcm-l :pcm overlap-l :overlap} (imdct/decode-frame spec-l (:window-shape ch0) prev-window-shape-l prev-overlap-l)
        {pcm-r :pcm overlap-r :overlap} (imdct/decode-frame spec-r (:window-shape ch1) prev-window-shape-r prev-overlap-r)]
    {:pcm-l pcm-l :pcm-r pcm-r
     :window-shape-l (:window-shape ch0) :window-shape-r (:window-shape ch1)
     :overlap-l overlap-l :overlap-r overlap-r}))

(defn decode-frames-stereo
  "Like `decode-frames` but for STEREO `channel_pair_element()` ADTS frames
   — threads TWO independent filterbank states (one per channel; see
   `decode-adts-frame-stereo`'s docstring for why overlap/window-shape are
   never shared across channels even though CPE side info sometimes is).
   Cold-starts (`aac.imdct/zero-overlap`) both channels at the FIRST frame
   given, same convention/mid-stream-slice caveat as `decode-frames`.
   Returns {:left :right} (each a concatenated PCM vector, 1024 samples per
   frame, per channel)."
  [frames]
  (loop [fs frames
         prev-shape-l 0 prev-overlap-l imdct/zero-overlap
         prev-shape-r 0 prev-overlap-r imdct/zero-overlap
         acc-l [] acc-r []]
    (if (empty? fs)
      {:left acc-l :right acc-r}
      (let [{:keys [pcm-l pcm-r window-shape-l window-shape-r overlap-l overlap-r]}
            (decode-adts-frame-stereo (first fs) prev-shape-l prev-overlap-l prev-shape-r prev-overlap-r)]
        (recur (rest fs) window-shape-l overlap-l window-shape-r overlap-r
               (into acc-l pcm-l) (into acc-r pcm-r))))))

(defn pcm->int16
  "Round + clip one float PCM sample to a signed 16-bit integer, matching
   real decoders' final PCM conversion (verified against FAAD2's
   `to_PCM_16bit`/`CLIP`+`lrintf`, `output.c`: clip to
   [-32768.0, 32767.0] then round-to-nearest — no additional scale factor,
   the IMDCT's own 2/N normalization already produces PCM-range values)."
  [x]
  (let [clipped (max -32768.0 (min 32767.0 x))]
    #?(:clj (Math/round (double clipped))
       :cljs (js/Math.round clipped))))
