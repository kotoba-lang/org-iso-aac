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
  `aac.stereo`/`aac.imdct`): **mono (SCE) OR stereo (CPE, incl. mid/side
  stereo), `ONLY_LONG_SEQUENCE` only** AAC-LC —
  `ics_info()`/`section_data()`/`scale_factor_data()`/`spectral_data()`
  noiseless coding (all 11 spectral Huffman codebooks + the scalefactor
  codebook, Annex 4.A), inverse quantization, mid/side (M/S) stereo
  reconstruction (`aac.stereo`, per-scalefactor-band `ms_used`, §4.6.8.1),
  and the long-window (1024 line) IMDCT + sine/KBD windowing + 50%
  overlap-add filterbank (run independently per channel for CPE — see
  `aac.decode/decode-adts-frame-stereo`'s docstring for why overlap-add
  history is never shared across channels). Explicitly **out of scope**
  (all throw rather than mis-decode): intensity stereo (a per-band
  pseudo-codebook, separate from mid/side and NOT implemented — see
  `test/aac/decode_test.clj`'s stereo golden-vector test docstring for why
  this one bites in practice even with PNS disabled), LFE/coupling
  channels, SBR/PS (this is plain AAC-LC, not HE-AAC/v2), LTP,
  predictor/pulse/TNS/gain-control tools, PNS (perceptual noise
  substitution — see `aac.ics`'s namespace docstring for why this one
  matters a lot in practice), and any `window_sequence` other than
  `ONLY_LONG_SEQUENCE` (so encoder transient regions, which almost always
  block-switch to `EIGHT_SHORT_SEQUENCE`, aren't decodable — see
  `test/aac/decode_test.clj`'s docstring for how real fixtures work around
  this). Only 44100/48000 Hz sample rates.

## Usage

```clojure
(require '[aac.adts :as adts] '[aac.decode :as decode] '[aac.imdct :as imdct])

(adts/parse-header adts-bytes 0)
;; => {:mpeg-version :protection-absent? :profile :sampling-frequency-index
;;     :sample-rate :channel-configuration :frame-length :header-length
;;     :start :end}

(def frames (adts/frames adts-bytes))   ; vector of the above + :payload (raw AAC bytes)

;; decode a run of consecutive ONLY_LONG_SEQUENCE frames to PCM (mono, SCE):
(decode/decode-frames (subvec frames 3 14))
;; => concatenated float PCM samples (1024 per frame); (mapv decode/pcm->int16 ...)
;;    for 16-bit signed PCM matching a real decoder's output convention.

;; same, for STEREO (CPE, incl. mid/side) — two independent PCM channels:
(decode/decode-frames-stereo (subvec frames 3 14))
;; => {:left [...] :right [...]} (each concatenated float PCM, 1024/frame)
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

**Stereo/mid-side** (`aac.stereo`): validated the same way against a real
`ffmpeg -c:a aac -aac_pns 0 -aac_is 0`-encoded STEREO fixture with
channel-distinguishable content (left 440 Hz / right 880 Hz tones), whose
real `common_window=1`/`ms_mask_present=1` side info has a genuine per-band
MIX of `ms_used` bits (not uniformly on/off) — exercising both the
M/S-reconstructed and plain-L/R-passed-through code paths in the same
frame. Both channels independently match `ffmpeg`'s own PCM decode to the
same **1 LSB** max-diff as the mono case — see
`test/aac/decode_test.clj`'s `stereo-golden-vector` docstring for why
`-aac_is 0` (disabling INTENSITY STEREO specifically, a separate
out-of-scope tool) was additionally required beyond `-aac_pns 0`.

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
