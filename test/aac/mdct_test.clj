(ns aac.mdct-test
  "Tests for `aac.mdct` — the FORWARD filterbank. These are not
   golden-vector tests against another implementation (there is no reference
   forward-MDCT bitstream to compare against: an encoder's transform output
   is internal, only the resulting BITSTREAM is observable, and that is what
   `aac.encode-test` checks against real ffmpeg). What is checkable here, and
   what actually pins the derivation in `aac.mdct`'s namespace docstring, is
   that forward-then-inverse RECONSTRUCTS: `analyze-frame` ->
   `aac.imdct/decode-frame` -> overlap-add must return the input signal.

   The scale factor is the part that a self-consistent-but-wrong pair would
   silently get away with, so `perfect-reconstruction-pins-the-scale` also
   asserts that the reconstruction FAILS when the forward scale is perturbed
   — otherwise a test that only checks 'round trip works' would pass for a
   transform pair that is off by a constant everywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [aac.mdct :as mdct]
            [aac.imdct :as imdct]))

(defn- test-signal
  "A deterministic, spectrally rich signal (three incommensurate tones plus a
   reproducible pseudo-random dither, so no single MDCT bin dominates and a
   per-bin sign/index error cannot hide)."
  [n]
  (let [seeded (java.util.Random. 20260730)]
    (mapv (fn [i]
            (+ (* 8000.0 (Math/sin (* 2.0 Math/PI 440.0 (/ (double i) 44100.0))))
               (* 3000.0 (Math/sin (* 2.0 Math/PI 1873.0 (/ (double i) 44100.0))))
               (* 1200.0 (Math/sin (* 2.0 Math/PI 7331.0 (/ (double i) 44100.0))))
               (* 200.0 (- (.nextDouble seeded) 0.5))))
          (range n))))

(defn- round-trip
  "Forward-analyze every block of `samples`, inverse-transform each with
   `aac.imdct/decode-frame` threading the overlap-add state, and return the
   concatenated reconstruction WITH the 1024-sample encoder delay already
   dropped (so index i lines up with `samples` index i). `spec-scale` lets a
   test perturb the forward scale to prove the test is sensitive to it."
  [samples window-shape spec-scale]
  (let [blocks (mdct/frame-blocks samples)
        out (loop [bs blocks prev-shape window-shape prev-overlap imdct/zero-overlap acc []]
              (if (empty? bs)
                acc
                (let [spec (mapv #(* spec-scale %)
                                 (mdct/analyze-frame (first bs) window-shape prev-shape))
                      {:keys [pcm overlap]} (imdct/decode-frame spec window-shape prev-shape prev-overlap)]
                  (recur (rest bs) window-shape overlap (into acc pcm)))))]
    (subvec out 1024)))

(defn- max-abs-error [a b n]
  (apply max (map (fn [i] (Math/abs (- (double (nth a i)) (double (nth b i))))) (range n))))

(deftest perfect-reconstruction-sine-window
  (testing "analyze-frame -> imdct/decode-frame reconstructs the signal exactly (sine window)"
    (let [samples (test-signal 5120)
          recon (round-trip samples 0 1.0)
          err (max-abs-error samples recon 5120)]
      ;; Peak signal amplitude here is ~12000, so 1e-6 absolute is ~1e-10
      ;; relative — pure double round-off through two O(N^2) transforms.
      (is (< err 1e-6) (str "max abs reconstruction error " err)))))

(deftest perfect-reconstruction-kbd-window
  (testing "same, KBD window (window_shape 1) — Princen-Bradley holds for it too"
    (let [samples (test-signal 5120)
          recon (round-trip samples 1 1.0)
          err (max-abs-error samples recon 5120)]
      (is (< err 1e-6) (str "max abs reconstruction error " err)))))

(deftest perfect-reconstruction-pins-the-scale
  (testing "the round trip FAILS if the forward scale is off — so the passing tests above are evidence about the factor 2, not just about self-consistency"
    (let [samples (test-signal 3072)
          half (round-trip samples 0 0.5)
          double- (round-trip samples 0 2.0)]
      (is (> (max-abs-error samples half 3072) 1000.0))
      (is (> (max-abs-error samples double- 3072) 1000.0)))))

(deftest frame-blocks-shape
  (testing "block count is frames+1 (the 1024-sample encoder delay) and the overlap is a 50% slide"
    (let [samples (vec (range 3072))
          blocks (mdct/frame-blocks samples)]
      (is (= 4 (count blocks)))
      (is (every? #(= 2048 (count %)) blocks))
      (testing "block 0 is zero-padded lead-in then the first 1024 samples"
        (is (= (vec (repeat 1024 0.0)) (subvec (nth blocks 0) 0 1024)))
        (is (= 0.0 (nth (nth blocks 0) 1024)))
        (is (= 1023.0 (nth (nth blocks 0) 2047))))
      (testing "block f's first half is block (f-1)'s second half"
        (doseq [f (range 1 4)]
          (is (= (subvec (nth blocks (dec f)) 1024 2048)
                 (subvec (nth blocks f) 0 1024)))))
      (testing "the tail is zero-padded, not truncated"
        (is (= (vec (repeat 1024 0.0)) (subvec (nth blocks 3) 1024 2048)))))))

(deftest non-multiple-of-1024-input
  (testing "a partial last frame is zero-padded rather than dropped"
    (let [samples (test-signal 1500)
          blocks (mdct/frame-blocks samples)
          recon (round-trip samples 0 1.0)]
      (is (= 3 (count blocks)))                       ;; ceil(1500/1024)=2, +1 delay
      (is (>= (count recon) 1500))
      (is (< (max-abs-error samples recon 1500) 1e-6)))))
