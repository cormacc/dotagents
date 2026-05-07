# Clojure Idioms and Patterns

Reference for idiomatic Clojure code.

## Threading Macros

### -> (thread-first)

For object/map transformations:

```clojure
(-> user
    (assoc :last-login (now))
    (update :login-count inc)
    (dissoc :temporary-token))
```

### ->> (thread-last)

For sequence operations:

```clojure
(->> users
     (filter active?)
     (map :email)
     (remove nil?)
     (str/join ", "))
```

### some->

Short-circuit on nil:

```clojure
(some-> user :address :postal-code (subs 0 5))
```

### cond->

Conditional transformations:

```clojure
(cond-> request
  authenticated? (assoc :user current-user)
  admin?         (assoc :permissions :all))
```

Keep pipelines to 3-7 steps. Break up longer chains.

## Control Flow

### when

Single-branch with side effects:

```clojure
(when (valid-input? data)
  (log-event "Processing")
  (process data))
```

### cond

Multiple conditions:

```clojure
(cond
  (< n 0) :negative
  (= n 0) :zero
  :else   :positive)
```

### case

Constant dispatch:

```clojure
(case operation
  :add      (+ a b)
  :subtract (- a b)
  (throw (ex-info "Unknown op" {:op operation})))
```

## Data Structures

Prefer plain maps with keyword keys:

```clojure
{:id 123 :email "user@example.com" :roles #{:admin}}
```

Use destructuring:

```clojure
(defn format-user [{:keys [first-name last-name email]}]
  (str last-name ", " first-name " <" email ">"))

(defn connect [{:keys [host port] :or {port 8080}}]
  (create-connection host port))
```

## Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Functions/vars | kebab-case | `calculate-total`, `max-retries` |
| Predicates | suffix `?` | `valid?`, `active?` |
| Conversions | source->target | `map->vector`, `string->int` |
| Unsafe mutation | suffix `!` | `swap!`, `reset!`, `save-user!` |
| Mutable refs | prefix `!` | `!conn`, `!store` |
| Dynamic vars | earmuffs | `*connection*` |
| Private helpers | prefix `-` | `-parse-date` |
| Unused bindings | underscore | `_request` |

## Code Layout

- Line length: under 80 characters
- Indentation: 2 spaces, never tabs
- Closing parens: gather on single line

```clojure
;; Good
(when something
  (something-else))

;; Bad
(when something
  (something-else)
)
```

## Namespace Structure

JVM Clojure (`.clj`):

```clojure
(ns project.module
  "Brief description."
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
   [project.db :as db])
  (:import
   (java.time LocalDate)))

(set! *warn-on-reflection* true)
```

ClojureScript (`.cljs`) or cross-platform (`.cljc`):

```clojure
(ns project.module
  "Brief description."
  (:require
   [clojure.string :as str]
   [project.db :as db]))
```

Standard aliases:
- `str` for clojure.string
- `set` for clojure.set
- `io` for clojure.java.io (JVM only)

## Error Handling

JVM Clojure (`.clj`):

```clojure
(try
  (slurp "file.txt")
  (catch java.io.FileNotFoundException e
    (log/error "File not found" {:path "file.txt"})
    nil)
  (catch Exception e
    (log/error "Unexpected error" {:error e})
    (throw e)))
```

ClojureScript (`.cljs`):

```clojure
(try
  (some-fallible-op)
  (catch ExceptionInfo e
    (log/error "Structured error" (ex-data e)))
  (catch :default e
    (log/error "Unexpected error" {:error e})))
```

Cross-platform (`.cljc`):

```clojure
(try
  (some-op)
  (catch #?(:clj Exception :cljs :default) e
    (handle-error e)))
```

Use `ex-info` with structured data (works on all platforms):

```clojure
(throw (ex-info "Validation failed"
                {:field :email
                 :value input
                 :reason "Invalid format"}))
```

## Testing

```clojure
(deftest function-name-test
  (testing "happy path"
    (is (= expected (function input))))
  (testing "nil input"
    (is (nil? (function nil))))
  (testing "empty collection"
    (is (= [] (function [])))))
```

Coverage: happy path, nil, empty, boundary values, invalid types.

### Running Tests from the REPL

Use proper test runners that execute fixtures:

```
# pi-clojure (preferred)
clojure_eval { port: PORT, code: "(clojure.test/run-test-var #'myapp.test-ns/my-test)" }
clojure_eval { port: PORT, code: "(clojure.test/run-tests 'myapp.test-ns)" }

# CLI fallback
clj-nrepl-eval -p PORT "(clojure.test/run-test-var #'myapp.test-ns/my-test)"
clj-nrepl-eval -p PORT "(clojure.test/run-tests 'myapp.test-ns)"
```

Do NOT call test functions directly -- this bypasses fixtures.

### Exception Testing

JVM Clojure (`.clj`):

```clojure
(is (thrown? ArithmeticException (/ 1 0)))
(is (thrown-with-msg? ExceptionInfo #"Invalid" (module/bad-input nil)))
```

ClojureScript (`.cljs`):

```clojure
(is (thrown? ExceptionInfo (module/bad-input nil)))
(is (thrown-with-msg? ExceptionInfo #"Invalid" (module/bad-input nil)))
(is (thrown? js/Error (module/parse-int "not-a-number")))
```

### Template-Based Testing

```clojure
(are [x y] (= x y)
  2 (+ 1 1)
  4 (* 2 2)
  6 (+ 3 3))
```

### Behavior Notes

- `is` does NOT stop test execution on failure -- all assertions run.
- The message argument to `is` is ALWAYS evaluated, even on success; keep it cheap.

## Common Patterns

### Function Composition

```clojure
(def process-data
  (comp transform validate parse))
```

### Higher-Order Functions

```clojure
;; Instead of explicit recursion
(->> items
     (filter valid?)
     (map transform)
     (reduce combine))
```

### Memoization

```clojure
(def expensive-fn
  (memoize (fn [x] ...)))
```

### Multi-Arity Functions

```clojure
(defn greet
  ([name] (greet name "Hello"))
  ([name greeting] (str greeting ", " name)))
```

### Variadic Functions

```clojure
(defn sum [& numbers]
  (reduce + numbers))
```

## Anti-Patterns to Avoid

```clojure
;; FORBIDDEN: Mutable atoms for accumulation
(defn bad-sum [nums]
  (let [sum (atom 0)]
    (doseq [n nums]
      (swap! sum + n))
    @sum))

;; Good: Use reduce
(defn good-sum [nums]
  (reduce + nums))

;; FORBIDDEN: Nested null checks
(if user
  (if (:address user)
    (if (:zip (:address user))
      (:zip (:address user)))))

;; Good: Use some->
(some-> user :address :zip)
```

## Research Reference

The REPL-first approach is grounded in research showing LLMs with compiler access outperform model-only baselines by 5.3-79.4 percentage points (Kjellberg et al., 2026). The Clojure REPL serves as a runtime oracle that grounds the AI in executable truth.

Reference: arXiv:2601.12146v1
