(ns herdr-orch.quota-setup-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [herdr-orch.test-support :as support]
            [herdr-orch.usage :as usage]))

(def root (str (fs/canonicalize ".")))
(def bb (str (fs/which "bb")))
(def node (str (fs/which "node")))
(def shell (str (fs/which "bash")))
(def sleep (str (fs/which "sleep")))
(defn executable! [path body]
  (spit (str path) (str "#!" shell "\n" body))
  (fs/set-posix-file-permissions path "rwxr-xr-x"))
(defn fixture [f]
  (fs/create-dirs (fs/path root ".tmp"))
  (let [dir (str (fs/create-temp-dir {:dir (fs/path root ".tmp") :prefix "quota-setup-"}))]
    (try
     (let [env (support/quota-env! dir (fs/path root "skills/herdr-orch/scripts/test/fixtures/quota-axi"))
           bin (fs/path dir "quota-bin")]
       (executable! (fs/path bin "node") (str "if [ \"$1\" = --version ]; then echo \"${FAKE_NODE_VERSION:-v22.19.0}\"; exit; fi\nexec '" node "' \"$@\"\n"))
       (executable! (fs/path bin "npm")
                    (str "printf '%s\\n' \"$@\" >\"$FAKE_NPM_LOG\"\n"
                         "echo SECRET-private-config >&2\n"
                         "if [ -n \"$FAKE_NPM_OUTPUT\" ]; then '" node "' -e 'process.stdout.write(\"x\".repeat(1048577))'; fi\n"
                         "if [ -n \"$FAKE_NPM_SLEEP\" ]; then '" sleep "' 10 & echo $! >\"$FAKE_CHILD_PID\"; wait; fi\n"
                         "[ -z \"$FAKE_NPM_FAIL\" ] || exit 9\n"
                         "[ \"$1\" = install ] && [ \"$2\" = --prefix ] || exit 8\n"
                         "'" (fs/which "mkdir") "' -p \"$3/node_modules/.bin\"\n"
                         "printf '#!" shell "\\necho %s\\n' \"${FAKE_INSTALLED_VERSION:-0.1.69}\" >\"$3/node_modules/.bin/quota-axi\"\n"
                         "'" (fs/which "chmod") "' +x \"$3/node_modules/.bin/quota-axi\"\n"))
       (f dir (assoc env "FAKE_NPM_LOG" (str (fs/path dir "npm-log"))
                         "FAKE_CHILD_PID" (str (fs/path dir "child-pid")))))
     (finally (fs/delete-tree dir)))))
(defn run-setup [env & args]
  (let [p @(process/process [bb (str "-Duser.home=" (or (get env "FAKE_USER_HOME") (get env "HOME"))) "-cp" (str root "/skills/herdr-orch/scripts/src") "-e"
                            (str "(require '[herdr-orch.quota-setup :as s] '[herdr-orch.usage :as u]) "
                                 "(with-redefs [s/install-budget-ms 400 u/version-budget-ms 400 u/cleanup-budget-ms 250] "
                                 "(apply s/-main " (pr-str (vec args)) "))")]
                           {:env env :out :string :err :string})]
    (is (= 0 (:exit p)) (:err p))
    (is (not (str/includes? (str (:out p) (:err p)) "SECRET")))
    (json/parse-string (:out p) true)))

(deftest check-and-shared-discovery-never-install
  (fixture
   (fn [dir env]
     (is (:ready (run-setup env)))
     (is (:ready (run-setup (assoc env "FAKE_NODE_VERSION" "v23.0.0") "check")))
     (is (= "use-check-or-install" (:reason (run-setup env "typo"))))
     (is (not (fs/exists? (get env "FAKE_NPM_LOG")))))))

(deftest explicit-install-pins-local-prefix-and-disables-lifecycles
  (fixture
   (fn [dir env]
     (is (:ready (run-setup env "install")))
     (is (= ["install" "--prefix" (str (fs/path (get env "XDG_DATA_HOME") "herdr-orch/quota-axi"))
             "--ignore-scripts" "--save-exact" "--no-audit" "--no-fund" "quota-axi@0.1.69"]
            (str/split-lines (slurp (get env "FAKE_NPM_LOG")))))
     (is (:ready (run-setup (assoc env "FAKE_QUOTA_VERSION" "9.9.9") "check")) "managed executable wins over PATH")
     (is (not (:ready (run-setup (assoc env "FAKE_INSTALLED_VERSION" "9.9.9") "install")))))))

(deftest package-dependency-precedes-managed-and-path
  (fixture
   (fn [dir env]
     (let [bin (fs/path (get env "HERDR_ORCH_PACKAGE_ROOT") "node_modules/.bin")]
       (is (= "wrong-version" (:reason (run-setup (assoc env "FAKE_QUOTA_VERSION" "9.9.9") "check")))
           "positive control: PATH fallback is selected without a package dependency")
       (fs/create-dirs bin)
       (executable! (fs/path bin "quota-axi") "echo 0.1.69\n")
       (is (:ready (run-setup (assoc env "FAKE_QUOTA_VERSION" "9.9.9") "check")) "package dependency wins over PATH")
       (is (not (:ready (run-setup (assoc env "FAKE_INSTALLED_VERSION" "9.9.9") "install"))) "positive control: wrong managed install")
       (is (:ready (run-setup env "check")) "package dependency wins over a wrong managed install")
       (fs/delete-tree (fs/path (get env "XDG_DATA_HOME") "herdr-orch"))
       (executable! (fs/path bin "quota-axi") "echo 9.9.9\n")
       (is (= "wrong-version" (:reason (run-setup env "check"))) "a wrong package version does not fall through")))))

(deftest package-pin-matches-collector-pin
  (let [manifest (json/parse-string (slurp (str (fs/path root "package.json"))) true)]
    (is (= usage/version (get-in manifest [:dependencies :quota-axi])))))

(deftest node-and-install-failures-are-fixed-and-not-ready
  (fixture
   (fn [dir env]
     (doseq [v ["v22.18.9" "v20.99.0" "garbage-SECRET"]]
       (is (= "node-version" (:reason (run-setup (assoc env "FAKE_NODE_VERSION" v) "install")))))
     (is (not (fs/exists? (get env "FAKE_NPM_LOG"))) "Node is checked before npm")
     (is (= "wrong-version" (:reason (run-setup (assoc env "FAKE_QUOTA_VERSION" "9.9.9") "check"))))
     (is (= "install-failed" (:reason (run-setup (assoc env "FAKE_NPM_FAIL" "1") "install"))))
     (is (= "oversized-output" (:reason (run-setup (assoc env "FAKE_NPM_OUTPUT" "1") "install"))))
     (is (= "timeout" (:reason (run-setup (assoc env "FAKE_NPM_SLEEP" "1") "install"))))
     (let [pid (parse-long (str/trim (slurp (get env "FAKE_CHILD_PID"))))
           handle (.orElse (java.lang.ProcessHandle/of pid) nil)]
       (is (or (nil? handle) (not (.isAlive handle))) "timed-out npm descendants stop"))
     (is (= "missing-node" (:reason (run-setup (assoc env "PATH" "") "install"))))
     (fs/delete (fs/path dir "quota-bin/npm"))
     (is (= "missing-npm" (:reason (run-setup (assoc env "PATH" (str (fs/path dir "quota-bin"))) "install")))))))

(deftest home-selectors-and-missing-executable
  (fixture
   (fn [dir env]
     (fs/delete (fs/path dir "quota-bin/quota-axi"))
     (is (= "missing-executable" (:reason (run-setup (assoc env "PATH" (str (fs/path dir "quota-bin"))) "check"))))
     (let [env (dissoc env "XDG_DATA_HOME")]
       (is (:ready (run-setup env "install")))
       (is (:ready (run-setup env "check")))
       (is (fs/exists? (fs/path (get env "HOME") ".local/share/herdr-orch/quota-axi/node_modules/.bin/quota-axi"))))
     (let [home (str (fs/path dir "property-home"))
           env (assoc env "XDG_DATA_HOME" "" "HOME" "" "FAKE_USER_HOME" home)]
       (is (:ready (run-setup env "install")))
       (is (:ready (run-setup env "check")))
       (is (fs/exists? (fs/path home ".local/share/herdr-orch/quota-axi/node_modules/.bin/quota-axi"))))
     (let [p @(process/process [bb (str root "/skills/herdr-orch/scripts/quota-axi-setup") "check"]
                              {:env env :out :string :err :string})]
       (is (= 0 (:exit p)))
       (is (boolean? (:ready (json/parse-string (:out p) true))) "script emits machine-readable check"))
     (is (not (fs/exists? (get env "FAKE_VENDOR_LOG")))))))

(deftest cancellation-stops-owned-npm-descendants
  (fixture
   (fn [_ env]
     (let [p (process/process [bb "-cp" (str root "/skills/herdr-orch/scripts/src") "-m" "herdr-orch.quota-setup" "install"]
                              {:env (assoc env "FAKE_NPM_SLEEP" "1") :out :string :err :string})
           pid-file (get env "FAKE_CHILD_PID")]
       (try
         (loop [n 0]
           (when (and (< n 200) (not (fs/exists? pid-file))) (Thread/sleep 10) (recur (inc n))))
         (is (fs/exists? pid-file) "positive control: npm child started")
         (.destroyForcibly (:proc p))
         @p
         (when (fs/exists? pid-file)
           (let [handle (.orElse (java.lang.ProcessHandle/of (parse-long (str/trim (slurp pid-file)))) nil)]
             (loop [n 0]
               (when (and handle (.isAlive handle) (< n 200)) (Thread/sleep 10) (recur (inc n))))
             (is (or (nil? handle) (not (.isAlive handle))) "parent cancellation closes supervisor stdin")))
         (finally
           (process/destroy-tree p)
           (when (fs/exists? pid-file)
             (when-let [h (.orElse (java.lang.ProcessHandle/of (parse-long (str/trim (slurp pid-file)))) nil)]
               (.destroyForcibly h)))))))))
