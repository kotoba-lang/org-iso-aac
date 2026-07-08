# kotoba-lang/org-iso-aac

Zero-dep portable `.cljc` AAC ADTS (Audio Data Transport Stream) frame
**framing** — ISO/IEC 13818-7 (MPEG-2 AAC) / ISO/IEC 14496-3 (MPEG-4 AAC).
Named `org-iso-aac` (ISO/IEC-numbered spec, consistent with `org-iso-h264`/
`org-iso-isobmff`/`org-iso-jpeg`/`org-iso-pdf`/`org-iso-opentype` in the
same batch).

**New implementation, not an extraction** — `kotoba-lang/utsushi`'s
`utsushi.bitstream/parse-adts` was an unimplemented `(throw (ex-info
"TODO..."))` stub (discovered while decomposing `utsushi` into
per-format-spec repos; see `com-junkawasaki/root` ADR precedent
2607072500). ADTS wraps raw AAC access units in a self-contained 7-byte
(9-byte with CRC) header carrying sample rate, channel config, and frame
length — this repo parses that header and splits the frame stream.
**Decoding the AAC `raw_data_block` itself (spectral data) is out of
scope** — the payload stays an opaque blob, matching the framing-only
boundary this repo family uses (`org-iso-h264`, `org-ietf-opus`).

## Usage

```clojure
(require '[aac.adts :as adts])

(adts/parse-header adts-bytes 0)
;; => {:mpeg-version :protection-absent? :profile :sampling-frequency-index
;;     :sample-rate :channel-configuration :frame-length :header-length
;;     :start :end}

(adts/frames adts-bytes)   ; => vector of the above + :payload (opaque AAC bytes)
```

## Validation

Validated against a **real ffmpeg/libavcodec-encoded ADTS stream**
(`ffmpeg -f lavfi -i sine=frequency=440 -c:a aac -f adts`): the decoded
header matches `ffprobe`'s reported sample rate (44100 Hz) and channel
count (1, mono) exactly, and `frames` is checked for self-consistency —
every computed frame boundary lands on a real `0xFFFx` sync word for the
next frame across the whole file.

## Test

```sh
clojure -M:test
```
