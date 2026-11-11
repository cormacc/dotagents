#!/usr/bin/env bb
;; Recover a bundled herdr-app mod file from an app executable.
;;
;; The app embeds each bundled mod's source as plain text and writes the
;; current version's copy under ~/.config/herdr-app/mods/. An older version's
;; source exists only in its executable, so this is how the orch-spaces review
;; gets a diff base after an app upgrade. The file starts at the first match
;; of MARKER (normally the mod's first line) and ends where its SHA-256 equals
;; SHA256, so an extract is byte-exact or fails.
;;
;; Usage: bb scripts/herdr-app-bundled-mod.bb EXECUTABLE MARKER SHA256 OUT
;; Example, from the 0.1.10 executable in the Nix store:
;;   bb scripts/herdr-app-bundled-mod.bb \
;;     /nix/store/<hash>-herdr-desktop-0.1.10/Applications/Herdr.app/Contents/MacOS/Herdr \
;;     '// Workspace spaces: stable' <sha256 from UPSTREAM.md> .tmp/spaces-0.1.10.js
(require '[babashka.fs :as fs])
(import '[java.security MessageDigest]
        '[java.util Arrays])

(def max-length 200000)

(defn hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn index-of [^bytes hay ^bytes needle]
  (let [n (alength needle) m (alength hay)]
    (loop [i 0]
      (cond (> (+ i n) m) nil
            (Arrays/equals hay i (+ i n) needle 0 n) i
            :else (recur (inc i))))))

(defn extract
  "The bytes from MARKER's first match to the end whose SHA-256 is SHA, or nil."
  [^bytes bs marker sha]
  (when-let [start (index-of bs (.getBytes ^String marker "UTF-8"))]
    (let [md (MessageDigest/getInstance "SHA-256")
          end (min (alength bs) (+ start max-length))]
      ;; A clone per byte hashes the file once, not once per candidate length.
      (loop [i start]
        (when (< i end)
          (.update md (aget bs i))
          (if (= sha (hex (.digest ^MessageDigest (.clone md))))
            (Arrays/copyOfRange bs start (inc i))
            (recur (inc i))))))))

(let [[executable marker sha out & extra] *command-line-args*]
  (when (or (nil? out) extra)
    (binding [*out* *err*] (println "Usage: bb scripts/herdr-app-bundled-mod.bb EXECUTABLE MARKER SHA256 OUT"))
    (System/exit 2))
  (let [bs (fs/read-all-bytes executable)]
    (if-let [found (extract bs marker (.toLowerCase ^String sha))]
      (do (fs/create-dirs (fs/parent (fs/absolutize out)))
          (fs/write-bytes out found)
          (println "wrote" out (alength ^bytes found) "bytes"))
      (do (binding [*out* *err*]
            (println (if (index-of bs (.getBytes ^String marker "UTF-8"))
                       "no file after the marker has that SHA-256"
                       "marker not found")))
          (System/exit 1)))))
