(ns clojure-plugin-host.core
  (:require [shadow.cljs.modern :refer (defclass)]
            [clojure-plugin-host.plugin :as p]))

(def obsidian (js/require "obsidian"))
(def Plugin (.-Plugin obsidian))

(defclass ObsidianClojure
  (extends Plugin)

  (constructor
   [^js this ^js app ^js manifest]
   (super app manifest))

  Object
  (onload [this] (p/on-load this obsidian)))
