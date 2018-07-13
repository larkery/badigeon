(ns badigeon.jlink
  (:require [badigeon.utils :as utils]
            [clojure.string :as string])
  (:import [java.util Optional]
           [java.util.spi ToolProvider]
           [java.io ByteArrayOutputStream PrintStream File]
           [java.nio.file Paths Path]))

(defmacro interface-static-call
  [sym argtypes]
  `(let [m# (.getMethod ~(symbol (namespace sym))
                        ~(name sym)
                        (into-array Class ~argtypes))]
     (fn [& args#]
       (.invoke m# nil (to-array args#)))))

(defn- jlink-command [java-home out-path module-path modules jlink-options]
  (into ["--module-path" module-path
         "--add-modules" modules
         "--output" (str out-path)]
        jlink-options))

(defn jlink
  ([out-path]
   (jlink out-path nil))
  ([out-path {:keys [jlink-path module-path modules jlink-options]
              :or {jlink-path (Paths/get "runtime" (make-array String 0))
                   modules ["java.base"]
                   jlink-options ["--strip-debug" "--no-man-pages"
                                  "--no-header-files" "--compress=2"]}}]
   (let [java-home (System/getProperty "java.home")
         ^Path out-path (if (string? out-path)
                          (Paths/get out-path (make-array String 0))
                          out-path)
         ^Path jlink-path (if (string? jlink-path)
                            (Paths/get jlink-path (make-array String 0))
                            jlink-path)
         jlink-path (.resolve out-path jlink-path)
         module-path (or module-path (str java-home File/separator "jmods"))
         modules (string/join "," modules)
         maybe-jlink ((interface-static-call ToolProvider/findFirst [java.lang.String]) "jlink")
         jlink (.orElse ^Optional maybe-jlink nil)]
     (when (nil? jlink)
       (throw (ex-info "JLink tool not found" {})))
     (let [jlink-out (ByteArrayOutputStream.)
           jlink-err (ByteArrayOutputStream.)]
       (.run ^ToolProvider jlink (PrintStream. jlink-out) (PrintStream. jlink-err)
             ^"[Ljava.lang.String;" (into-array
                                     String
                                     (jlink-command java-home jlink-path module-path modules
                                                    jlink-options)))
       (print (str jlink-out))
       (print (str jlink-err))))))


(comment
  (let [out-path (badigeon.bundle/make-out-path 'badigeon/badigeon {:mvn/version utils/version})]
    (badigeon.clean/clean (.resolve out-path "runtime"))
    (jlink out-path))
  
  )