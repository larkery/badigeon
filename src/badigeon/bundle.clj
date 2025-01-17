(ns badigeon.bundle
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.java.io :as io]
            [badigeon.utils :as utils]
            [clojure.string :as string]
            [clojure.tools.deps.alpha.util.maven :as maven])
  (:import [java.nio.file Path Paths Files
            FileVisitor FileVisitOption FileVisitResult
            FileSystemLoopException NoSuchFileException FileAlreadyExistsException
            StandardCopyOption LinkOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarFile JarEntry]
           [java.util EnumSet]))

(def ^{:dynamic true :private true :tag Path} *out-path* nil)
(def ^{:dynamic true :private true} *copied-paths* nil)

(defn- make-directory-file-visitor [^Path root-path ^Path to]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (when-not exception
        (let [new-dir (.resolve to (.relativize root-path dir))]
          (Files/setLastModifiedTime new-dir
                                     (Files/getLastModifiedTime
                                      dir (make-array LinkOption 0)))))
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      (let [new-dir (.resolve to (.relativize root-path dir))]
        (try
          (Files/copy
           ^Path dir new-dir
           ^"[Ljava.nio.file.StandardCopyOption;" (into-array
                                                   StandardCopyOption
                                                   [StandardCopyOption/COPY_ATTRIBUTES]))
          (catch FileAlreadyExistsException e
            ;; ignore
            nil))
        FileVisitResult/CONTINUE))
    (visitFile [_ path attrs]
      (let [new-file (.resolve to (.relativize root-path path))
            relative-path (when (some? *out-path*) (.relativize *out-path* new-file))]
        (when (and (some? *copied-paths*) (get *copied-paths* relative-path))
          (throw (ex-info "Duplicate path" {:from (str path)
                                            :to (str to)
                                            :relative-path (str relative-path)})))
        (when (some? *copied-paths*)
          (set! *copied-paths* (conj *copied-paths* relative-path)))
        (Files/copy
         ^Path path new-file
         ^"[Ljava.nio.file.StandardCopyOption;" (into-array
                                                 StandardCopyOption
                                                 [StandardCopyOption/COPY_ATTRIBUTES
                                                  StandardCopyOption/REPLACE_EXISTING]))
        FileVisitResult/CONTINUE))
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(def ^:const native-extensions
  #{#"\.so$"
    #"\.so.[0-9]+$"
    #"\.so\.[0-9]+\.[0-9]+$"
    #"\.so\.[0-9]+\.[0-9]+\.[0-9]+$"
    #"\.dylib$"
    #"\.dll$"
    #"\.a$"
    #"\.lib$"
    ;; overtone resources
    #"\.scx$"})

(defn- do-extract-native-dependencies
  [native-prefix path ^Path out-path ^Path native-path extensions]
  (let [^Path native-prefix (if (instance? Path native-prefix)
                              native-prefix
                              (Paths/get (str native-prefix) (make-array String 0)))
        native-path (.resolve out-path native-path)
        ^Path path (if (string? path)
                     (Paths/get path (make-array String 0))
                     path)
        f (.toFile path)]
    (when (and (.exists f) (not (.isDirectory f))
               (.endsWith (str path) ".jar"))
      (let [jar-file (JarFile. (str path))
            entries (enumeration-seq (.entries jar-file))]
        (doseq [^JarEntry entry entries]
          (let [entry-path (.getName entry)]
            (when (and (some #(re-find % entry-path) extensions)
                       (.startsWith entry-path (str native-prefix)))
              (let [entry-path (.relativize
                                native-prefix
                                (Paths/get (.getName entry) (make-array String 0)))
                    f-path (.resolve native-path entry-path)]
                (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
                (io/copy (.getInputStream jar-file entry) (.toFile f-path))))))))))

(defn copy-directory [from to-directory]
  (let [to-directory (if (string? to-directory)
                       (Paths/get to-directory (make-array String 0))
                       to-directory)
        from (if (string? from)
               (Paths/get from (make-array String 0))
               from)]
    (Files/walkFileTree from
                        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                        Integer/MAX_VALUE
                        (make-directory-file-visitor from to-directory))))

(defn copy-file [from to]
  (let [^Path to (if (string? to)
                   (Paths/get to (make-array String 0))
                   to)
        ^Path from (if (string? from)
                     (Paths/get from (make-array String 0))
                     from)
        relative-path (when (some? *out-path*) (.relativize *out-path* to))]
    (if (and (some? *copied-paths*)
             (get *copied-paths* relative-path))
      (throw (ex-info "Duplicate path" {:from (str from)
                                        :to (str to)
                                        :relative-path (str relative-path)}))
      (do
        (when (some? *out-path*)
          (set! *copied-paths* (conj *copied-paths* relative-path)))
        (Files/copy
         from to
         ^"[Ljava.nio.file.StandardCopyOption;" (into-array StandardCopyOption
                                                            [StandardCopyOption/COPY_ATTRIBUTES
                                                             StandardCopyOption/REPLACE_EXISTING]))))))

(defn- copy-dependency
  ([coords out-path]
   (copy-dependency coords out-path (Paths/get "lib" (make-array String 0))))
  ([{:keys [paths]} ^Path out-path ^Path libs-path]
   (doseq [path paths]
     (let [f (io/file path)]
       (when (.exists f)
         (cond (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
               (let [to (-> out-path (.resolve libs-path) (.resolve (.getName f)))]
                 (copy-file path to))
               (.isDirectory f)
               (copy-directory path out-path)))))))

(defn make-out-path [lib version]
  (let [[group-id artifact-id classifier] (maven/lib->names lib)]
    (utils/make-out-path artifact-id `{:mvn/version ~version
                                       ~@(when classifier [:classifier classifier]) ~@[]})))

(defn bundle
  "Creates a standalone bundle of the project resources and its dependencies. By default jar dependencies are copied in a \"lib\" folder, under the ouput directory. Other dependencies (local and git) are copied by copying their :paths content to the root of the output directory. By default, an exception is thrown when the project dependends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - libs-path: The path of the folder where dependencies are copied, relative to the output folder. Default to \"lib\"."
  ([out-path]
   (bundle out-path nil))
  ([out-path {:keys [deps-map
                     excluded-libs
                     allow-unstable-deps?
                     libs-path]}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)
         ^Path out-path (if (string? out-path)
                          (Paths/get out-path (make-array String 0))
                          out-path)
         ^Path libs-path (if libs-path
                           (if (string? libs-path)
                             (Paths/get libs-path (make-array String 0))
                             libs-path)
                           (Paths/get "lib" (make-array String 0)))]
     (when-not allow-unstable-deps?
       (utils/check-for-unstable-deps #(or (utils/snapshot-dep? %) (utils/local-dep? %)) resolved-deps))
     (Files/createDirectories (.resolve out-path libs-path) (make-array FileAttribute 0))
     (binding [*out-path* out-path
               *copied-paths* #{}]
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (when-not (contains? excluded-libs lib)
           (copy-dependency coords out-path libs-path)))
       (copy-dependency {:paths (:paths deps-map)} out-path libs-path))
     out-path)))

(defn extract-native-dependencies
  "Extract native dependencies (.so, .dylib, .dll, .a, .lib, .scx files) from jar dependencies. By default native dependencies are extracted to a \"lib\" folder under the output directory.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies with a jar format resolved from this map are searched for native dependencies. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - allow-unstable-deps: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - native-path: The path of the folder where native dependencies are extracted, relative to the output folder. Default to \"lib\".
  - native-prefixes: A map from libs (symbol) to a path prefix (string). Libs with a specified native-prefix are searched for native dependencies under the path of the native prefix only. The native-prefix is excluded from the output path of the native dependency.
  - native-extensions: A collection of native extension regexp. Files which name match one of these regexps are considered a native dependency. Default to badigeon.bundle/native-extensions."
  ([out-path]
   (extract-native-dependencies out-path nil))
  ([out-path {:keys [deps-map
                     allow-unstable-deps?
                     native-path
                     native-prefixes
                     native-extensions] :as opts}]
   (when out-path
     (let [deps-map (update deps-map :mvn/repos utils/with-standard-repos)
           resolved-deps (deps/resolve-deps deps-map nil)
           ^Path out-path (if (string? out-path)
                            (Paths/get out-path (make-array String 0))
                            out-path)
           ^Path native-path (if native-path
                               (if (string? native-path)
                                 (Paths/get native-path (make-array String 0))
                                 native-path)
                               (Paths/get "lib" (make-array String 0)))
           native-extensions (if (contains? opts :native-extensions)
                               native-extensions
                               badigeon.bundle/native-extensions)]
       (when-not allow-unstable-deps?
         (utils/check-for-unstable-deps #(utils/snapshot-dep? %) resolved-deps))
       (Files/createDirectories (.resolve out-path native-path) (make-array FileAttribute 0))
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (when (contains? native-prefixes lib)
           (doseq [path paths]
             (do-extract-native-dependencies
              (get native-prefixes lib)
              path out-path native-path native-extensions))))
       out-path))))

(defn extract-native-dependencies-from-file
  "Extract native dependencies (.so, .dylib, .dll, .a, .lib, .scx files) from a jar file. By default native dependencies are extracted to a \"lib\" folder under the output directory.
  - out-path: The path of the output directory.
  - file-path: The path of jar file from which the native dependencies are extracted.
  - native-path: The path of the folder where native dependencies are extracted, relative to the output folder. Default to \"lib\".
  - native-prefix: A map from libs (symbol) to a path prefix (string). Libs with a specified native-prefix are searched for native dependencies under the path of the native prefix only. The native-prefix is excluded from the output path of the native dependency.
  - native-extensions: A collection of native extension regexp. Files which name match one of these regexps are considered a native dependency. Default to badigeon.bundle/native-extensions."
  ([out-path file-path]
   (extract-native-dependencies-from-file out-path file-path nil))
  ([out-path file-path {:keys [native-path
                               native-prefix
                               native-extensions] :as opts}]
   (when (and out-path file-path)
     (let [^Path out-path (if (string? out-path)
                            (Paths/get out-path (make-array String 0))
                            out-path)
           ^Path file-path (if (string? file-path)
                             (Paths/get file-path (make-array String 0))
                             file-path)
           ^Path native-path (if native-path
                               (if (string? native-path)
                                 (Paths/get native-path (make-array String 0))
                                 native-path)
                               (Paths/get "lib" (make-array String 0)))
           native-prefix (or native-prefix "")
           native-extensions (if (contains? opts :native-extensions)
                               native-extensions
                               badigeon.bundle/native-extensions)]
       (Files/createDirectories
        (.resolve out-path native-path) (make-array FileAttribute 0))
       (do-extract-native-dependencies
        native-prefix file-path out-path native-path native-extensions)
       out-path))))

(def ^:const windows-like :windows-like)
(def ^:const posix-like :posix-like)

(defmulti make-script-path identity)
(defmulti make-script-header identity)
(defmulti classpath-separator identity)
(defmulti file-separator identity)

(defmethod make-script-path :posix-like [os-type]
  (Paths/get "bin/run.sh" (make-array String 0)))

(defmethod make-script-path :windows-like [os-type]
  (Paths/get "bin/run.bat" (make-array String 0)))

(defmethod make-script-header :posix-like [os-type]
  "#!/bin/sh\n")

(defmethod make-script-header :windows-like [os-type]
  "@echo off\r\n")

(defmethod classpath-separator :posix-like [os-type]
  ":")

(defmethod classpath-separator :windows-like [os-type]
  ";")

(defmethod file-separator :posix-like [os-type]
  "/")

(defmethod file-separator :windows-like [os-type]
  "\\")

(defn format-jvm-opts [jvm-opts]
  (let [formatted (string/join " " jvm-opts)]
    (if (> (count formatted) 0)
      (str " " formatted)
      formatted)))

(defn bin-script
  "Write a start script for the bundle under \"out-path\", using the \"main\" parameter as the CLojure namespace defining the -main method entry point.
  - os-type: Either the badigeon.bundle.windows-like constant or the badigeon.bundle.posix-like constant, depending on the wanted script type. Default to badigeon.bundle.posix-like .
  - script-path: The output path of the script, relative to the \"out-path\" parameter. Default to bin/run.sh or bin/run.bat, depending on the os-type .
  - script-header: A string prefixed to the script. Default to \"#!/bin/sh\n\" or \"@echo off\r\n\", depending on the os-type.
  - command: The command run by the script. Default to \"java\" or \"runtime/bin/java\" if the \"runtime\" folder contains a custom JRE created with jlink.
  - classpath: The classpath argument used when executing the command. Default a classpath containing the root folder and the lib directory.
  - jvm-opts: A vector of jvm arguments used when executing the command. Default to the empty vector.
  - args: A vector of arguments provided to the program. Default to the empty vector."
  ([out-path main]
   (bin-script out-path main nil))
  ([out-path main {:keys [os-type
                          script-path
                          script-header
                          command
                          classpath
                          jvm-opts
                          args]
                   :or {os-type posix-like
                        script-path (make-script-path os-type)
                        script-header (make-script-header os-type)
                        classpath (str ".."
                                       (classpath-separator os-type)
                                       ".." (file-separator os-type)
                                       "lib" (file-separator os-type)
                                       "*")
                        jvm-opts []
                        args []}}]
   (let [^Path out-path (if (string? out-path)
                          (Paths/get out-path (make-array String 0))
                          out-path)
         ^Path script-path (if (string? script-path)
                             (Paths/get script-path (make-array String 0))
                             script-path)
         script-path (.resolve out-path script-path)
         args (if args
                (str " " (clojure.string/join " " args))
                "")
         custom-runtime? (.exists (.toFile (.resolve out-path "runtime/bin/java")))
         command (or command
                     (if custom-runtime?
                       (str ".." (file-separator os-type)
                            "runtime" (file-separator os-type)
                            "bin" (file-separator os-type)
                            "java")
                       "java"))]
     (Files/createDirectories (.getParent script-path)
                              (make-array FileAttribute 0))
     (spit
      (str script-path)
      (format "%s%s -cp %s%s clojure.main -m %s%s"
              script-header command classpath (format-jvm-opts jvm-opts) main args)))))

(comment
  (utils/make-out-path "badigeon" {:mvn/version utils/version :classifier "rrr"})

  (let [out-path (make-out-path 'badigeon/badigeon utils/version)
        deps-map (assoc (deps-reader/slurp-deps "deps.edn") :paths ["target/classes"])]
    (badigeon.clean/clean "target/badigeon-0.0.1-SNAPSHOT")
    (badigeon.clean/clean "target/classes")
    (badigeon.compile/compile 'badigeon.main
                              {:compiler-options {:elide-meta [:doc :file :line :added]
                                                  :direct-linking true}})
    (bundle out-path {:deps-map deps-map
                      :allow-unstable-deps? true})
    (extract-native-dependencies out-path {:deps-map deps-map
                                           :allow-unstable-deps? true})
    (badigeon.jlink/jlink out-path)
    (bin-script out-path 'badigeon.main {:jvm-opts ["-Xmx1024m"]})
    (bin-script out-path 'badigeon.main {:os-type windows-like})
    (badigeon.zip/zip out-path (str out-path ".zip")))
  )

;; Excluded-libs excludes a lib but not its dependencies
;; File permissions on bin/scripts are not set. They would not be retained anyway
;; No exclusion / inclusion hooks because we can still manually remove/add files from the output folder
