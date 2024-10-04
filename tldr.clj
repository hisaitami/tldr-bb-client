#!/usr/bin/env bb

(ns tldr-bb-client.core
  "A TLDR pages client written in Babashka"
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.math :as math]
            [clojure.tools.cli :refer [parse-opts]]))

(def ^:dynamic *verbose* false)

(def ^:dynamic *force-color* false)

(def ^:dynamic *exit-on-error* false)

(def env
  (let [m (System/getenv)
        ks (map (comp keyword #(str/replace % #"_" "-") str/lower-case) (keys m))
        vs (vals m)]
    (zipmap ks vs)))

(def tldr-home ".tldrc")

(def zip-file "tldr.zip")

(def zip-url (str "https://github.com/tldr-pages/tldr/releases/latest/download/" zip-file))

(def page-suffix ".md")

(def cache-date (io/file (:home env) tldr-home "date"))

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
   (io/file (:home env) tldr-home))
  ([platform]
   (io/file (cache-path) (pages-dir (first lang-priority-list)) platform))
  ([lang platform page]
   (io/file (cache-path) (pages-dir lang) platform page)))

(defn lookup [platform page]
  (for [lang lang-priority-list
        platform (distinct [platform "common"])
        :let [path (cache-path lang platform page)]
        :when (.exists path)]
    path))

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
        ret (p/shell ["test" "-t" fd] {:continue true})]
    (= (:exit ret) 0)))

(defn format [content]
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
  ([^java.io.File file]
   (or (.exists file) (die "This page doesn't exist yet!"))
   (->> (format (slurp file)) (str \newline) println))
  ([platform page]
   (let [file (or (first (lookup platform page)) (io/file ""))]
     (display file))))

(defn -main
  "The main entry point of this program."
  [args]

  (alter-var-root #'*exit-on-error* (constantly true))

  (let [arguments args
        page (-> (str/join "-" arguments) (str/lower-case) (str page-suffix))]
    (display "osx" page)))

(-main *command-line-args*)
