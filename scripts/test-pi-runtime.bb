#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def runner (str (fs/path root "skills/herdr-orch/scripts/pi/test.sh")))
(def bash-bin (str (fs/which "bash")))

(defn with-fixture [f]
  (let [dir (fs/path root ".tmp" (str "pi-runtime-" (random-uuid)))
        system-bin (fs/path dir "system")
        installed-bin (fs/path dir "installed")
        npm-bin (fs/path dir "node_modules/.bin")
        installed-log (fs/path dir "installed.log")
        shim-log (fs/path dir "shim.log")]
    (try
      (doseq [bin [system-bin installed-bin npm-bin]] (fs/create-dirs bin))
      (doseq [cmd ["git" "dirname" "tail" "grep"]]
        (fs/create-sym-link (fs/path system-bin cmd) (fs/which cmd)))
      (spit (str (fs/path installed-bin "pi"))
            (str "#!" bash-bin "\n"
                 "printf '%s\\n' installed >> '" installed-log "'\n"
                 "if [ \"$1\" = --version ]; then echo fixture-version; exit 0; fi\n"
                 "if [ \"$HERDR_ORCH_EXPECT_PI_VERSION\" != fixture-version ]; then echo 'runtime mismatch'; exit 1; fi\n"
                 "test -n \"$HERDR_ORCH_FORBID_BUNDLE_PREFIX\" || exit 8\n"
                 "printf '%s\\n' 'SUITE fork-adapter passed=1 failed=0' 'SUITE fork-provider-context passed=1 failed=0' 'SUITE measurement-adapter passed=1 failed=0' 'SUITE account-launch passed=1 failed=0'\n"))
      (spit (str (fs/path npm-bin "pi"))
            (str "#!" bash-bin "\nprintf '%s\\n' shim >> '" shim-log "'\necho repository-pi-selected\nexit 9\n"))
      (doseq [bin [installed-bin npm-bin]]
        (process/shell {:out :string} "chmod" "+x" (str (fs/path bin "pi"))))
      (f {:system (str system-bin) :installed (str installed-bin) :npm (str npm-bin)
          :installed-log (str installed-log) :shim-log (str shim-log)})
      (finally (fs/delete-tree dir)))))

(defn run! [entries]
  (process/shell {:out :string :err :string :continue true
                  :extra-env {"PATH" (str/join ":" entries)}}
                 bash-bin runner))

(deftest installed-runtime-wins-over-an-npm-shim
  (with-fixture
    (fn [{:keys [system installed npm installed-log shim-log]}]
      (doseq [entries [[system installed] [npm system installed]]]
        (let [{:keys [exit out err]} (run! entries)]
          (is (zero? exit) (str out err))
          (is (str/includes? out "SUITE measurement-adapter passed=1 failed=0") out)
          (is (str/includes? out "mismatch control (expected to fail)") out)))
      (is (= 6 (count (str/split-lines (slurp installed-log)))))
      (is (not (fs/exists? shim-log))))))

(deftest repository-pi-is-not-a-fallback
  (with-fixture
    (fn [{:keys [system npm shim-log]}]
      (let [{:keys [exit out err]} (run! [npm system])]
        (is (= 1 exit) (str out err))
        (is (str/includes? err "installed Pi runtime") err)
        (is (not (fs/exists? shim-log)))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
