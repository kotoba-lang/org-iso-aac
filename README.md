# kotoba-lang/org-iso-aac

Zero-dep portable `.cljc` AAC-LC (Low Complexity) decoder — ADTS (Audio
Data Transport Stream) frame framing + real spectral-to-PCM sample decode
— ISO/IEC 13818-7 (MPEG-2 AAC) / ISO/IEC 14496-3 (MPEG-4 AAC). Named
`org-iso-aac` (ISO/IEC-numbered spec, consistent with `org-iso-h264`/
`org-iso-isobmff`/`org-iso-jpeg`/`org-iso-pdf`/`org-iso-opentype` in the
same batch). This is the kotoba-lang ecosystem's **first audio codec
sample-decode implementation** (previously only ADTS framing existed).

**New implementation, not an extraction** — `kotoba-lang/utsushi`'s
`utsushi.bitstream/parse-adts` was an unimplemented `(throw (ex-info
"TODO..."))` stub (discovered while decomposing `utsushi` into
per-format-spec repos; see `com-junkawasaki/root` ADR precedent
2607072500).

## Scope

- **Framing** (`aac.adts`): ADTS's self-contained 7-byte (9-byte with CRC)
  header (sample rate, channel config, frame length) + frame splitting.
- **Decode** (`aac.decode` + `aac.ics`/`aac.huffman`/`aac.dequant`/
  `aac.imdct`): **mono, `ONLY_LONG_SEQUENCE` only** AAC-LC —
  `ics_info()`/`section_data()`/`scale_factor_data()`/`spectral_data()`
  noiseless coding (all 11 spectral Huffman codebooks + the scalefactor
  codebook, Annex 4.A), inverse quantization, and the long-window (1024
  line) IMDCT + sine/KBD windowing + 50% overlap-add filterbank. Explicitly
  **out of scope** (all throw rather than mis-decode): stereo (CPE, incl.
  mid/side + intensity stereo), LFE/coupling channels, SBR/PS (this is
  plain AAC-LC, not HE-AAC/v2), LTP, predictor/pulse/TNS/gain-control
  tools, PNS (perceptual noise substitution — see `aac.ics`'s namespace
  docstring for why this one matters a lot in practice), and any
  `window_sequence` other than `ONLY_LONG_SEQUENCE` (so encoder transient
  regions, which almost always block-switch to `EIGHT_SHORT_SEQUENCE`,
  aren't decodable — see `test/aac/decode_test.clj`'s docstring for how
  real fixtures work around this). Only 44100/48000 Hz sample rates.

## Usage

```clojure
(require '[aac.adts :as adts] '[aac.decode :as decode] '[aac.imdct :as imdct])

(adts/parse-header adts-bytes 0)
;; => {:mpeg-version :protection-absent? :profile :sampling-frequency-index
;;     :sample-rate :channel-configuration :frame-length :header-length
;;     :start :end}

(def frames (adts/frames adts-bytes))   ; vector of the above + :payload (raw AAC bytes)

;; decode a run of consecutive ONLY_LONG_SEQUENCE frames to PCM:
(decode/decode-frames (subvec frames 3 14))
;; => concatenated float PCM samples (1024 per frame); (mapv decode/pcm->int16 ...)
;;    for 16-bit signed PCM matching a real decoder's output convention.
```

## Validation

**Framing** (`aac.adts`): validated against a real ffmpeg/libavcodec-encoded
ADTS stream (`ffmpeg -f lavfi -i sine=frequency=440 -c:a aac -f adts`) —
decoded header matches `ffprobe`'s reported sample rate/channel count, and
`frames` is checked for self-consistency (every computed frame boundary
lands on a real sync word).

**Decode** (`aac.decode`): validated against real
`ffmpeg -c:a aac -aac_pns 0`-encoded fixtures, comparing this decoder's PCM
output against `ffmpeg` decoding its OWN encode (the same golden-vector
methodology `org-iso-h264`'s `decode_test.clj` uses). Max observed
diff across all compared frames: **1 LSB** of a 16-bit sample (most
samples bit-exact) — see `test/aac/decode_test.clj`'s docstring for the
full methodology, tolerance rationale (floating-point IMDCT rounding, not
a decode bug), and why the fixture needed `-aac_pns 0`.

The Kaiser-Bessel Derived (KBD) window construction (`aac.imdct/kbd-window`)
and the ~1360-row Annex 4.A Huffman codebook tables
(`aac.huffman-tables`) were independently cross-verified against real
reference decoders' own data during development — see those namespaces'
docstrings for the methodology (this matters because the ISO spec PDF's
own KBD formula didn't extract cleanly from OCR, and the Huffman tables
are large enough that a transcription error would be very hard to catch
by eye).

## Test

```sh
clojure -M:test
clojure -M:lint
```
