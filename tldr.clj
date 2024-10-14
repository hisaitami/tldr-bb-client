#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.math :as math]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]])

(def ^:dynamic *verbose* false)

(def ^:dynamic *force-color* false)

(def ^:dynamic *exit-on-error* false)

(def env
  (let [envname->keyword (comp keyword #(str/replace % #"_" "-") str/lower-case)
        m (System/getenv)
        k (map envname->keyword (keys m))
        v (vals m)]
    (zipmap k v)))

(def tldr-home ".tldrc")

(def zip-file "tldr.zip")

(def zip-url (str "https://github.com/tldr-pages/tldr/releases/latest/download/" zip-file))

(def page-suffix ".md")

(def cache-date (str (fs/path (:home env) tldr-home "date")))

(def lang-priority-list
  (let [lang (str (:lang env))
        language (str/split (str (:language env)) #":")
        default [lang "en"]]
    (->> (if (some empty? [lang language]) default
           (concat language default))
         (remove empty?)
         distinct)))

(defn current-datetime []
  (long (math/ceil (/ (System/currentTimeMillis) 1000))))

(defn pages-dir [lang]
  (let [prefix "pages"
        lang (second (re-matches #"^([a-z]{2}(_[A-Z]{2})*).*$" (str lang)))
        cc (subs (str lang) 0 2)]
    (cond
      (empty? lang) prefix
      (= "en" cc) prefix
      (#{"pt_BR" "pt_PT" "zh_TW"} lang) (str/join "." [prefix lang])
      :else (str/join "." [prefix cc]))))

(defn cache-path
  ([]
   (fs/path (:home env) tldr-home))
  ([platform]
   (fs/path (cache-path) (pages-dir (first lang-priority-list)) platform))
  ([lang platform page]
   (fs/path (cache-path) (pages-dir lang) platform page)))

(defn lookup [platform page]
  (for [lang lang-priority-list
        platform (distinct [platform "common"])
        :let [path (cache-path lang platform page)]
        :when (fs/exists? path)]
    (str path)))

(defn die [& args]
  (let [msg (apply str args)]
    (if-not *exit-on-error* (throw (Exception. msg))
      (binding [*out* *err*]
        (println msg)
        (System/exit 1)))))

(defn ansi-str [& coll]
  (let [colors {:reset "\u001b[0m"
                :bold  "\u001b[1m"
                :red   "\u001b[31m"
                :green "\u001b[32m"
                :blue  "\u001b[34m"
                :white "\u001b[37m"
                :bright-white "\u001b[37;1m"}]
    (apply str (replace colors coll))))

(defn tty? [x]
  (let [fd (x {:in 0 :out 1 :err 2})
        ret (shell ["test" "-t" fd] {:continue true})]
    (= (:exit ret) 0)))

(defn md->ansi-str [content]
  (let [color? (or *force-color* (and (empty? (:no-color env)) (tty? :out)))
        parse (fn [s m r] (str/replace s m (if color? r "$1")))]
    (-> content
        (parse #"^#\s+(.+)" (ansi-str :bright-white "$1" :reset))
        (parse #"(?m)^> (.+)" (ansi-str :white "$1" :reset))
        (str/replace #"(?m):\n$" ":")
        (parse #"(?m)^(- .+)" (ansi-str :green "$1" :reset))
        (parse #"(?m)^`(.+)`$" (ansi-str :red "    $1" :reset))
        (parse #"\{\{(.+?)\}\}" (ansi-str :reset :blue "$1" :red))
        (str/replace #"\\([{}])" "$1"))))

(defn display
  ([file]
   (or (fs/exists? file) (die "This page doesn't exist yet!"))
   (->> (slurp file) md->ansi-str (str \newline) println))
  ([platform page]
   (display (first (lookup platform page)))))

(defn mkdtemp [template]
  (let [ret (shell {:out :string :err :string} "mktemp -d" template)]
    (or (empty? (:err ret)) (die "Error: Creating Directory:" template))
    (str/trim (:out ret))))

(defn download-zip [url path]
  (let [ret (shell {:err :string} "curl -sL" url "-o" path)]
    (or (empty? (:err ret)) (die "Error: Downloading File:" url))
    path))

(defn update-localdb []
  (let [tmp-dir (mkdtemp "/tmp/tldr-XXXXXX")
        zip-path (download-zip zip-url (str (fs/path tmp-dir zip-file)))]
    (when *verbose* (println "Successfully downloaded:" zip-path))
    (shell {:dir (:home env)} "unzip -q" "-uo" zip-path "-d" tldr-home)
    (when (fs/directory? tmp-dir) (shell "rm -rf" tmp-dir))
    (spit cache-date (current-datetime))
    (println "Successfully updated local database")))

(defn clear-localdb []
  (let [{:keys [err]} (shell {:dir (:home env) :err :string} "rm -rf" tldr-home)]
    (or (empty? err) (die err))
    (println "Successfully removed"
             (if *verbose* (str (fs/path (:home env) tldr-home))
               "local database"))))

(defn list-localdb [platform]
  (let [path (cache-path platform)
        re (re-pattern (str page-suffix "$"))]
    (or (fs/exists? path) (die "Can't open cache directory:" path))
    (println (md->ansi-str "# Pages for"))
    (doseq [file (fs/list-dir path)]
      (let [entry (str/replace (fs/file-name file) re "")]
        (println entry)))))

(defn check-localdb []
  (when *verbose* (println "Checking local database..."))
  (if (not (fs/exists? cache-date)) (update-localdb)
    (let [created (parse-long (slurp cache-date))
          current (current-datetime)
          elapsed (- current created)]
      (when *verbose* (println "*" created current elapsed))
      (when (> elapsed (* 60 60 24 7 2))
        (println "Local database is older than two weeks, attempting to update it..."
                 "\nTo prevent automatic updates, set the environment variable"
                 "TLDR_AUTO_UPDATE_DISABLED")
        (update-localdb)))))

(defn- default-platform []
  (let [ret (shell {:out :string :err :string} "uname -s")]
    (or (empty? (:err ret)) (die "Error: Unknown platform"))
    (let [sysname (str/trim (:out ret))]
      (case sysname
        "Linux" "linux"
        "Darwin" "osx"
        "SunOS" "sunos"
        "Windows" "windows"
        "common"))))

(def cli-spec [["-h" "--help" "print this help and exit"]
               ["-u" "--update" "update local database"]
               ["-v" "--version" "print version and exit"]
               ["-c" "--clear-cache" "clear local database"]
               ["-V" "--verbose" "display verbose output"
                :default false
                :default-desc ""]
               ["-l" "--list" "list all entries in the local database"]])

(def version "tldr-bb-client v0.0.2")

(defn usage [options-summary]
  (->> ["usage: tldr.clj [OPTION]... PAGE\n"
        "available commands:"
        options-summary]
       (str/join \newline)))

(defn- has-key? [m k]
  (contains? k m))

(defn- select-platform [options]
  (condp has-key? options
    :linux   "linux"
    :osx     "osx"
    :sunos   "sunos"
    :windows "windows"
    (or (:platform options) "common")))

(defn -main
  "The main entry point of this program."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-spec)
        platform (select-platform options)]

    (alter-var-root #'*exit-on-error* (constantly true))
    (when errors
      (die "The following errors occurred while parsing your command:\n\n"
           (str/join \newline errors)))

    (alter-var-root #'*verbose* (constantly (:verbose options)))
    (alter-var-root #'*force-color* (constantly (:color options)))

    (condp has-key? options
      ;; show version info
      :version (println version)

      ;; show usage summary
      :help (println (usage summary))

      ;; update local database
      :update (update-localdb)

      ;; clear local database
      :clear-cache (clear-localdb)

      ;; list all entries in the local database
      :list (list-localdb platform)

      ;; if no argument is given, show usage and exit as failure,
      ;; otherwise display the specified page
      (if (empty? arguments) (die (usage summary))
        (let [update? (empty? (:tldr-auto-update-disabled env))
              page (-> (str/join "-" arguments) str/lower-case (str page-suffix) fs/file-name)]
          (when update? (check-localdb))
          (display platform page))))))

(-main *command-line-args*)
