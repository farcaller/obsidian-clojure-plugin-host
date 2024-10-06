(ns clojure-plugin-host.plugin
  (:require ["markdown-it" :as md]
            ["markdown-it-front-matter" :as mdfm]
            ["slug" :as slug]
            [kitchen-async.promise :as p]
            [sci.core :as sci]))

(defonce markdownit (-> (md) (.use mdfm #())))

(def loaded-plugins (atom {}))

(defn eval-page
  [^js this ^js obsidian {:keys [path contents]}]
  (let [id (slug path)
        markdown (js->clj (.parse markdownit contents) :keywordize-keys true)
        _ (.log js/console "loading" id markdown)
        first-code (first (filter #(and (= (.-type %) "fence") (= (.-tag %) "code")) markdown))
        first-code (.-content first-code)

        plugin-state (atom nil)]
    (letfn [(register-plugin
              [on-load]
              (swap! plugin-state assoc :on-load on-load))]
      (when first-code
        (sci/eval-string (str first-code "\n(obsidian/register-plugin on-load)") {:namespaces {'obsidian {'this this
                                                                                                          'exports obsidian
                                                                                                          'register-plugin register-plugin}}
                                                                                  :classes {'js js/global :allow :all}})
        (let [clsFactory (.eval js/window (str "(function anonymous(Plugin, onloadHandler){
                                 const c = class extends Plugin {
                                   onload() {
                                     return onloadHandler(this);
                                   }
                                 };
                                 return c;
                               })"))
              cls (clsFactory (.-Plugin obsidian) (:on-load @plugin-state))
              inst (cls. (.-app this) #js {:id id})]
          (swap! loaded-plugins assoc id inst)
          (.addChild this inst))))))

(defn get-all-plugins-pages
  [^js app]
  (let [dataview-api (-> app .-plugins .-plugins .-dataview .-api)
        all-pages (.pages dataview-api "#clojure-plugin")
        all-pages (-> all-pages .array (js->clj :keywordize-keys true))]
    all-pages))

(defn unload-plugin!
  [^js this id]
  (let [inst (get @loaded-plugins id)]
    (.removeChild this inst)
    (swap! loaded-plugins dissoc id)))

(defn on-load
  [^js this ^js obsidian]
  (enable-console-print!)
  (sci/alter-var-root sci/print-fn (constantly *print-fn*))
  (sci/alter-var-root sci/print-err-fn (constantly *print-err-fn*))

  (let [app (.-app this)
        vault (.-vault app)]
    (letfn [(load-plugin
              [path]
              (p/let [file (.getFileByPath vault path)
                      contents (.read vault file)]
                {:path path :contents contents}))
            (load-page-from-info
              [file-info]
              (load-plugin (-> file-info :file :path)))
            (on-rename
              [^js file old-path]
              (let [old-id (slug old-path)]
                (when (get @loaded-plugins old-id)
                  (let [new-id (slug (.-path file))]
                    (.log js/console "renamed plugin" old-id "to" new-id)
                    (unload-plugin! this old-id)
                    (p/let [contents (.read vault file)]
                      (eval-page this obsidian {:path (.-path file) :contents contents}))))))
            (on-modify
              [^js file]
              (let [id (slug (.-path file))]
                (when (get @loaded-plugins id)
                  (.log js/console "updated plugin" id)
                  (unload-plugin! this id)
                  (p/let [contents (.read vault file)]
                    (eval-page this obsidian {:path (.-path file) :contents contents})))))]
      (.registerEvent this (.on vault "rename" on-rename))
      (.registerEvent this (.on vault "modify" on-modify))
      (p/let [all-pages-files (get-all-plugins-pages app)
              all-pages (.all js/Promise (clj->js (map load-page-from-info all-pages-files)))]
        (doall (map #(eval-page this obsidian %) all-pages))))))
