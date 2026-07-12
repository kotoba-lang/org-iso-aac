(ns aac.decode-test
  "Golden-vector tests for `aac.decode` (this ecosystem's first real audio
   codec sample-decode implementation — previously only ADTS framing
   existed, see `aac.adts`'s namespace docstring).

   ## `tone1000-nopns.aac` — the real, non-trivial fixture

   A REAL `ffmpeg`(native `aac` encoder)-encoded ADTS stream:

     ffmpeg -f lavfi -i \"sine=frequency=1000:duration=0.35:sample_rate=44100\" \\
       -ac 1 -c:a aac -aac_pns 0 -b:a 96k tone1000-nopns.aac

   `-aac_pns 0` disables Perceptual Noise Substitution — verified during
   development that ffmpeg's encoder uses PNS (codebook 13/NOISE_HCB) for
   at least some scalefactor band in NEARLY EVERY real frame otherwise
   (probed via a throwaway script decoding `section_data()`/
   `scale_factor_data()` only). PNS is out of this repo's scope (see
   `aac.ics`'s namespace docstring) AND is fundamentally not bit-comparable
   across independent decoders even if implemented (the spec doesn't
   mandate a specific noise generator) — `-aac_pns 0` avoids the issue
   entirely rather than papering over it with a tolerance that would hide
   real bugs elsewhere.

   Frames 3-13 of this file (11 frames) are `ONLY_LONG_SEQUENCE` with
   `window_shape` 1 (KBD) throughout, no pulse/TNS/gain-control/PNS/
   intensity-stereo, using spectral codebooks 2-11 across various
   scalefactor bands (real, non-trivial spectral data — this is NOT a
   silent/near-empty frame, see `silence-golden-vector` below for that
   simpler case) — frames 0-2 are LONG_START/EIGHT_SHORT/LONG_STOP (the
   encoder's onset transient response to the sine wave starting abruptly
   from silence) and frame 14 onward transitions back out of
   ONLY_LONG_SEQUENCE; both are out of this repo's `ONLY_LONG_SEQUENCE`-only
   scope (`aac.ics/ics-info!` throws on them), which is WHY frames 3-13 are
   the ones this repo can decode at all — a real fixture necessarily starts
   a few frames into any stream that has an onset.

   Reference PCM: `ffmpeg -i tone1000-nopns.aac -f s16le -ar 44100 -ac 1
   tone1000-nopns.ref.pcm` (ffmpeg decoding its OWN encode — the standard
   golden-vector methodology this org's sibling repos use, e.g.
   `org-iso-h264`'s `decode_test.clj`).

   ## Why frame 3's OWN decoded output is excluded from the comparison

   AAC's IMDCT filterbank is 50%-overlap-add: frame N's final output
   depends on frame (N-1)'s second-half-windowed transform too
   (`aac.imdct/decode-frame`'s `prev-overlap`). Frame 2 (LONG_STOP_SEQUENCE)
   is out of scope, so this repo cold-starts (`aac.imdct/zero-overlap`) at
   frame 3 instead of carrying frame 2's real windowed history forward —
   frame 3's OWN PCM output is therefore wrong (missing that real overlap
   contribution) but its window_shape/overlap STATE SAVED FOR THE NEXT
   FRAME is computed independent of the input overlap (only frame 3's
   *output*, not what it hands forward, is affected) — so frame 4 onward
   is fully self-consistent and bit-comparable. `aac.decode/decode-frames`'s
   own docstring documents this same caveat generally for any mid-stream
   frame slice.

   ## Tolerance

   Max diff observed across all 10 compared frames (4-13, 10240 samples)
   is **1 least-significant-bit** of a 16-bit PCM sample (most samples are
   bit-exact, `0` diff) — floating-point IMDCT/window rounding, not a
   decode bug (this repo's IMDCT/dequantization/windowing are all
   double-precision float; ffmpeg's native encoder+decoder use `float`
   (32-bit) internally, so the two implementations' rounding at the very
   last bit of a 16-bit sample can occasionally differ by one). This is
   the same 'few-ULP tolerance for floating-point transform paths'
   methodology `kasane`'s JPEG DCT tests use, restated here for PCM: an
   allowed max-abs-diff of 2 LSB (looser than the actually-observed 1, to
   avoid a flaky test) rather than requiring literal bit-exactness."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [aac.adts :as adts]
            [aac.decode :as decode]
            [aac.imdct :as imdct]))

(defn- rd-bytes [p]
  (mapv #(bit-and (int %) 0xff)
        (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(defn- rd-pcm-s16le [p]
  (let [bytes (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))
        n (quot (count bytes) 2)]
    (mapv (fn [i]
            (let [lo (bit-and (int (aget bytes (* 2 i))) 0xff)
                  hi (aget bytes (inc (* 2 i)))] ;; high byte, signed (two's complement) is correct here
              (short (bit-or lo (bit-shift-left hi 8)))))
          (range n))))

(defn- decode-frame-range
  "Decode `frames[start,end)` (a sub-sequence of `aac.adts/frames`'
   output) cold-started at `start`, returning a vector of per-frame
   {:pcm [1024 floats]}."
  [frames start end]
  (loop [fs (subvec frames start end) prev-shape 0 prev-overlap imdct/zero-overlap acc []]
    (if (empty? fs)
      acc
      (let [{:keys [pcm window-shape overlap]} (decode/decode-adts-frame (first fs) prev-shape prev-overlap)]
        (recur (rest fs) window-shape overlap (conj acc pcm))))))

(deftest tone1000-golden-vector
  (let [bytes (rd-bytes "aac/fixtures/tone1000-nopns.aac")
        frames (adts/frames bytes)
        ref (rd-pcm-s16le "aac/fixtures/tone1000-nopns.ref.pcm")
        decoded (decode-frame-range frames 3 14)] ;; frames 3..13 inclusive
    (testing "11 frames decoded (3..13), all ONLY_LONG_SEQUENCE"
      (is (= 11 (count decoded))))
    (testing "frames 4..13 (skipping frame 3's own output, see namespace docstring) match real ffmpeg PCM within 2 LSB"
      (doseq [i (range 1 (count decoded))]
        (let [pcm (nth decoded i)
              overall-frame-idx (+ 3 i)
              ref-start (* overall-frame-idx 1024)
              ref-slice (subvec ref ref-start (+ ref-start 1024))
              pcm-int16 (mapv decode/pcm->int16 pcm)
              max-diff (apply max (map (fn [a b] (abs (- (int a) (int b)))) pcm-int16 ref-slice))]
          (is (<= max-diff 2) (str "frame " overall-frame-idx " max-diff " max-diff)))))
    (testing "sanity: the decoded tone is non-silent (real spectral data was actually decoded, not accidentally all-zero)"
      (is (> (apply max (map (fn [x] (abs (int x))) (mapv decode/pcm->int16 (nth decoded 1)))) 1000)))))

(deftest silence-golden-vector
  (let [bytes (rd-bytes "aac/fixtures/silence.aac")
        frames (adts/frames bytes)
        ref (rd-pcm-s16le "aac/fixtures/silence.ref.pcm")]
    (testing "all frames are ONLY_LONG_SEQUENCE with max_sfb=0 (no sections at all — the simplest possible legal ICS)"
      (let [decoded (decode-frame-range frames 0 (count frames))]
        (is (= (count frames) (count decoded)))
        (testing "silent input decodes to (near-)silent PCM, matching ffmpeg's own silent decode"
          (doseq [i (range (count decoded))]
            (let [pcm-int16 (mapv decode/pcm->int16 (nth decoded i))
                  ref-slice (subvec ref (* i 1024) (* (inc i) 1024))
                  max-diff (apply max (map (fn [a b] (abs (- (int a) (int b)))) pcm-int16 ref-slice))]
              (is (<= max-diff 2) (str "frame " i " max-diff " max-diff)))))))))

(deftest unsupported-window-sequence-throws
  (testing "EIGHT_SHORT_SEQUENCE / LONG_START / LONG_STOP throw rather than mis-decode"
    (let [bytes (rd-bytes "aac/fixtures/tone1000-nopns.aac")
          frames (adts/frames bytes)]
      ;; frame 0 is LONG_START_SEQUENCE, frame 1 is EIGHT_SHORT_SEQUENCE
      (is (thrown? clojure.lang.ExceptionInfo (decode/decode-adts-frame (nth frames 0) 0 imdct/zero-overlap)))
      (is (thrown? clojure.lang.ExceptionInfo (decode/decode-adts-frame (nth frames 1) 0 imdct/zero-overlap))))))
