(ns aac.adts
  "AAC ADTS (Audio Data Transport Stream) frame framing — ISO/IEC 13818-7
   (MPEG-2 AAC) / ISO/IEC 14496-3 (MPEG-4 AAC) Annex. ADTS wraps raw AAC
   access units in a 7-byte (9-byte with CRC) self-contained header carrying
   sample rate, channel config, and frame length, so a stream of AAC frames
   can be split without a container. Pure cljc, zero dependencies. Framing
   and header fields only — the AAC raw_data_block (SCE/CPE/LFE syntax
   elements, spectral data) is not decoded, matching this repo family's
   'coded audio/video payload stays opaque, only framing/metadata is
   decoded' boundary (see org-iso-h264/org-ietf-opus siblings).

   New implementation (not an extraction — utsushi.bitstream/parse-adts was
   an unimplemented TODO stub) as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  )

(def sampling-frequencies
  "ADTS sampling_frequency_index → Hz (index 13/14 reserved, 15 = explicit
   frequency not used by ADTS)."
  [96000 88200 64000 48000 44100 32000 24000 22050
   16000 12000 11025 8000 7350 nil nil nil])

(defn- u8  [b i] (bit-and (int (nth b i)) 0xff))
(defn- bits [v hi-bit-offset-from-msb n total-bits]
  ;; extract `n` bits from a `total-bits`-wide integer `v`, where the field
  ;; starts `hi-bit-offset-from-msb` bits in from the MSB.
  (bit-and (bit-shift-right v (- total-bits hi-bit-offset-from-msb n)) (dec (bit-shift-left 1 n))))

(defn parse-header
  "Parse one ADTS frame header at byte offset `off` in `b` (vector of
   unsigned bytes) → {:mpeg-version :protection-absent? :profile
   :sampling-frequency-index :sample-rate :channel-configuration
   :frame-length :header-length :start :end}. `:end` is the offset of the
   NEXT frame's header (this frame's payload runs [start+header-length,
   end))."
  [b off]
  (when (or (>= (+ off 7) (count b))
            (not= 0xFF (u8 b off))
            (not= 0xF0 (bit-and (u8 b (+ off 1)) 0xF0)))
    (throw (ex-info "aac: bad ADTS syncword" {:off off})))
  (let [b1 (u8 b (+ off 1)) b2 (u8 b (+ off 2)) b3 (u8 b (+ off 3))
        b4 (u8 b (+ off 4)) b5 (u8 b (+ off 5)) b6 (u8 b (+ off 6))
        mpeg-version       (bit-and (bit-shift-right b1 3) 1)   ; 0=MPEG-4, 1=MPEG-2
        protection-absent? (= 1 (bit-and b1 1))
        profile            (inc (bit-and (bit-shift-right b2 6) 3))   ; AAC profile = coded value + 1
        sfi                (bit-and (bit-shift-right b2 2) 0xF)
        channel-cfg        (bit-or (bit-shift-left (bit-and b2 1) 2) (bit-and (bit-shift-right b3 6) 3))
        frame-length       (bit-or (bit-shift-left (bit-and b3 3) 11)
                                   (bit-shift-left b4 3)
                                   (bit-and (bit-shift-right b5 5) 7))
        header-length      (if protection-absent? 7 9)]
    {:mpeg-version mpeg-version
     :protection-absent? protection-absent?
     :profile profile
     :sampling-frequency-index sfi
     :sample-rate (nth sampling-frequencies sfi)
     :channel-configuration channel-cfg
     :frame-length frame-length
     :header-length header-length
     :start off
     :end (+ off frame-length)}))

(defn frames
  "Split an ADTS byte stream `b` into consecutive frames from offset 0,
   each header parsed via parse-header, plus :payload (the raw AAC access
   unit bytes, still opaque/undecoded)."
  [b]
  (let [n (count b)]
    (loop [off 0 acc []]
      (if (>= off n)
        acc
        (let [h (parse-header b off)]
          (recur (:end h)
                 (conj acc (assoc h :payload (subvec b (+ off (:header-length h)) (:end h))))))))))
