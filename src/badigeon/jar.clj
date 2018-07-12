(ns badigeon.jar
  (:require [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.tools.deps.alpha.gen.pom :as deps-gen-pom]
            [badigeon.pom :as pom]
            [badigeon.utils :as utils]
            [clojure.string :as string]
            [clojure.java.io :as io])
  (:import [java.nio.file Path Paths Files]
           [java.util EnumSet]
           [java.util.jar Manifest JarEntry JarOutputStream]
           [java.nio.file FileVisitor FileVisitResult FileSystemLoopException
            FileVisitOption NoSuchFileException]
           [java.io BufferedOutputStream FileOutputStream ByteArrayInputStream]))

(def ^:private default-manifest
  {"Created-By" (str "Badigeon " utils/version)
   "Built-By" (System/getProperty "user.name")
   "Build-Jdk" (System/getProperty "java.version")})

(defn- place-sections-last
  "Places sections at the end of the manifest seq, as specified by the
  Manifest spec. Retains ordering otherwise (if mf is ordered)."
  [mf]
  (sort-by val (fn [v1 v2]
                 (and (not (coll? v1)) (coll? v2)))
           (seq mf)))

(declare ^:private format-manifest-entry)

(defn- format-manifest-entry-section [k v]
  (->> (map format-manifest-entry v)
       (cons (str "\nName: " (name k) "\n"))
       (string/join)))

(defn- format-manifest-entry [[k v]]
  (if (coll? v)
    (format-manifest-entry-section k v)
    (->> (str (name k) ": " v)
         (partition-all 70)  ;; Manifest spec says lines <= 72 chars
         (map (partial apply str))
         (string/join "\n ")  ;; Manifest spec says join with "\n "
         (format "%s\n"))))

(defn make-manifest [main manifest-overrides]
  (let [manifest-overrides (into {} manifest-overrides)
        manifest (if main
                   (assoc default-manifest "Main-Class" (munge (str main)))
                   default-manifest)]
    (->> (merge manifest manifest-overrides)
         place-sections-last
         (map format-manifest-entry)
         (cons "Manifest-Version: 1.0\n")
         (string/join ""))))

(defn dotfiles-pred [root-path path]
  (.startsWith (str (.getFileName path)) "."))

(defn emacs-backups-pred [root-path path]
  (let [file-name (str (.getFileName path))]
    (or (.endsWith file-name "~") (.startsWith file-name "#"))))

(defn default-exclusion-predicate [root-path path]
  (or (dotfiles-pred root-path path)
      (emacs-backups-pred root-path path)))

(defn pom-path [group-id artifact-id root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^pom.xml$" (str (utils/relativize-path root-files path))))
    (format "META-INF/maven/%s/%s/pom.xml" group-id artifact-id)))

(defn deps-path [group-id artifact-id root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^deps.edn$" (str (utils/relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/deps.edn" group-id artifact-id)))

(defn readme-path [group-id artifact-id root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^(?i)readme(.*)$" (str (utils/relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/%s"
            group-id artifact-id (.getFileName path))))

(defn license-path [group-id artifact-id root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^(?i)license(.*)$" (str (utils/relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/%s"
            group-id artifact-id (.getFileName path))))

(defn default-inclusion-path [group-id artifact-id root-files path]
  (or (pom-path group-id artifact-id root-files path)
      (deps-path group-id artifact-id root-files path)
      (readme-path group-id artifact-id root-files path)
      (license-path group-id artifact-id root-files path)))

(defn put-jar-entry!
  "Adds a jar entry to the Jar output stream."
  [jar-out file path]
  (.putNextEntry jar-out (doto (JarEntry. (str path))
                           (.setTime (.lastModified file))))
  (when-not (.isDirectory file)
    (io/copy file jar-out)))

(defn path-file-visitor [jar-out exclusion-predicate root-path path attrs]
  (let [file-name (.getFileName path)]
    (when (and (not= root-path path)
               (not (when exclusion-predicate (exclusion-predicate root-path path))))
      (let [f (.toFile path)
            relative-path (str (utils/relativize-path root-path path))]
        (put-jar-entry! jar-out f relative-path)))))

(defn inclusion-path-visitor [jar-out pred root-path path attrs]
  (when-let [file-name (when pred (pred root-path path))]
    (let [f (.toFile path)]
      (let [bytes (.getBytes (slurp (str path)))]
        (.putNextEntry jar-out (JarEntry. file-name))
        (io/copy (ByteArrayInputStream. bytes) jar-out)))))

(defn copy-pom-properties [jar-out group-id artifact-id pom-properties]
  (let [path (format "META-INF/maven/%s/%s/pom.properties"
                     group-id artifact-id)]
    (.putNextEntry jar-out (JarEntry. path))
    (io/copy pom-properties jar-out)))

(defn make-file-visitor [jar-out pred root-path visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn jar-out pred root-path path attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn check-non-maven-dependencies [{:keys [deps]}]
  (doseq [[lib {:keys [:mvn/version] :as dep}] deps]
    (when (nil? version)
      (throw (ex-info "All dependencies must be Maven-based. Use the \"allow-all-dependencies?\" option to continue building the jar anyway. When using the \"allow-all-dependencies?\" option, only Maven-based depedencies are added to the pom.xml file."
                      {:lib lib
                       :dep dep})))))

(defn jar
  ([lib maven-coords]
   (jar maven-coords nil))
  ([lib maven-coords
    {:keys [out-path main manifest-entries
            paths deps :mvn/repos
            exclusion-predicate inclusion-path
            allow-all-dependencies?]
     :or {exclusion-predicate default-exclusion-predicate}
     :as options}]
   (let [root-path (Paths/get (System/getProperty "user.dir") (make-array String 0))
         artifact-id (name lib)
         group-id (or (namespace lib) artifact-id)
         inclusion-path (or inclusion-path
                            (partial default-inclusion-path group-id artifact-id))
         _ (when out-path (when-not (.endsWith (str out-path) ".jar")
                            (throw (ex-info "out-path must be a jar file"
                                            {:out-path out-path}))))
         out-path (or out-path (utils/make-out-path
                                artifact-id (assoc maven-coords :extension "jar")))
         out-path (if (string? out-path)
                    (Paths/get out-path (make-array String 0))
                    out-path)
         out-path (if (and (instance? Path out-path) (not (.isAbsolute out-path)))
                    (.resolve root-path out-path)
                    out-path)
         ;; Do not merge system and user wide deps.edn files
         deps-map (-> (deps-reader/slurp-deps "deps.edn")
                      ;; Replositories must be explicilty provided as parameters
                      (dissoc :mvn/repos)
                      (merge (select-keys options [:paths :deps :mvn/repos])))
         manifest (-> (make-manifest main manifest-entries)
                      (.getBytes)
                      (ByteArrayInputStream.)
                      (Manifest.))
         pom-properties (pom/make-pom-properties lib maven-coords)]
     (when-not allow-all-dependencies?
       (check-non-maven-dependencies deps-map))
     (pom/sync-pom lib maven-coords deps-map)
     (.mkdirs (.toFile (.getParent out-path)))
     (with-open [jar-out (-> (.toFile out-path)
                             (FileOutputStream.)
                             (BufferedOutputStream.)
                             (JarOutputStream. manifest))]
       (Files/walkFileTree root-path
                           (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                           Integer/MAX_VALUE
                           (make-file-visitor
                            jar-out inclusion-path root-path
                            inclusion-path-visitor))
       (doseq [path (:paths deps-map)]
         (Files/walkFileTree (.resolve root-path path)
                             (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                             Integer/MAX_VALUE
                             (make-file-visitor
                              jar-out exclusion-predicate
                              (.resolve root-path path)
                              path-file-visitor)))
       (copy-pom-properties jar-out group-id artifact-id pom-properties))
     (str out-path))))

(comment
  (defn inclusion-path [group-id artifact-id root-files path]
    (license-path group-id artifact-id root-files path))

  (defn exclusion-predicate [root-path path]
    (prn root-path path)
    true)

  (require '[badigeon.clean])
  (badigeon.clean/clean "target")
  (jar 'badigeongi2/badigeongi2
       {:mvn/version "0.0.3-SNAPSHOT"
        :classifier "cl"}
       {:manifest-entries {"Built-By" "ewen2"
                           "Project-awesome-level" "super-great"
                           :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                           :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}}
        #_:inclusion-path #_(partial inclusion-path "badigeongi2" "badigeonn3")
        #_:exclusion-predicate #_exclusion-predicate
        :paths ["src" "src-java"]
        #_:deps #_'{org.clojure/clojure {:mvn/version "1.9.0"}}
        :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
        :allow-all-dependencies? true})

  (jar 'badigeon/badigeon
       {:mvn/version "0.0.3-SNAPSHOT"}
       {:paths ["target/classes"]
        :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
        :allow-all-dependencies? true
        :main 'badigeon.main})
  
  )

;; AOT compilation, no sources in jar -> possibility to set a custom path (target/classes)
