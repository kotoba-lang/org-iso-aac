(ns aac.tables
  "Scalefactor-band (SWB) boundary tables for the long (1024-line) transform
   window, ISO/IEC 14496-3:2005 Table 4.110 (`num_swb_long_window` /
   `swb_offset_long_window`, §4.5.2.3.2/4.5.2.3.3 `window_grouping_info`).
   `swb_offset[sfb]` is the index of the lowest spectral coefficient of
   scalefactor band `sfb`; `swb_offset[num_swb]` is always the transform
   length (1024) as a sentinel upper bound.

   Scope: only the 44.1 kHz / 48 kHz long-window table is transcribed
   (Table 4.110 states these two sample rates share one table) — this repo's
   fixtures are all 44100 Hz (see `aac.decode`'s namespace docstring for the
   sample-rate scope statement). Other `sampling_frequency_index` values
   throw rather than silently mis-decode; add their Table 4.11x rows here if
   a future fixture needs them."
  )

(def ^:private swb-offset-long-44100-48000
  "Table 4.110 (44.1/48 kHz, LONG_WINDOW / LONG_START / LONG_STOP), 49 bands
   + the transform-length sentinel (1024)."
  [0 4 8 12 16 20 24 28 32 36 40 48 56 64 72
   80 88 96 108 120 132 144 160 176 196 216 240 264 292
   320 352 384 416 448 480 512 544 576 608 640 672 704 736
   768 800 832 864 896 928 1024])

(def ^:private num-swb-long-44100-48000 49)

(def sampling-frequency-index->swb-offset-long
  "sampling_frequency_index -> swb_offset_long_window vector (49+1 entries,
   last = 1024). Only 4 (44100 Hz) and 3 (48000 Hz) populated (Table 4.110);
   see namespace docstring."
  {3 swb-offset-long-44100-48000
   4 swb-offset-long-44100-48000})

(def sampling-frequency-index->num-swb-long
  {3 num-swb-long-44100-48000
   4 num-swb-long-44100-48000})

(defn swb-offsets
  "swb_offset_long_window for `sfi` (sampling_frequency_index), or throws if
   this sample rate isn't in scope (see namespace docstring)."
  [sfi]
  (or (get sampling-frequency-index->swb-offset-long sfi)
      (throw (ex-info "aac.tables: unsupported sampling_frequency_index (only 44100/48000 Hz long-window SWB table is in scope)"
                       {:sampling-frequency-index sfi}))))

(defn num-swb [sfi]
  (or (get sampling-frequency-index->num-swb-long sfi)
      (throw (ex-info "aac.tables: unsupported sampling_frequency_index"
                       {:sampling-frequency-index sfi}))))
