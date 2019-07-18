(ns talky.core
  (:require
   ["vscode" :as vscode]
   ["net" :as net]

   [talky.window :as window]
   [kitchen-async.promise :as p]))

(defn- register-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn []
                   (js/console.log (str "[Talky] RUN COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN COMMAND '" cmd-name "'") e))))]

    (-> (.-commands ^js vscode)
        (.registerCommand cmd-name callback))))

(defn- register-text-editor-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn [editor edit args]
                   (js/console.log (str "[Talky] RUN EDITOR COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys editor edit args)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN EDITOR COMMAND '" cmd-name "'") e))))]

    (-> (.-commands ^js vscode)
        (.registerTextEditorCommand cmd-name callback))))

(defn- register-disposable [^js context ^js disposable]
  (-> (.-subscriptions context)
      (.push disposable)))

(defn connect!
  [{:socket/keys [host port config on-connect on-close on-data]
    :or {config
         {:socket/encoder
          (fn [data]
            ;; See https://nodejs.org/api/net.html#net_socket_write_data_encoding_callback
            data)

          ;; You can also set the encoding.
          ;; See https://nodejs.org/api/net.html#net_socket_setencoding_encoding
          ;; :socket/encoding "utf8"

          :socket/decoder
          (fn [buffer-or-string]
            ;; See https://nodejs.org/api/net.html#net_event_data
            buffer-or-string)}

         on-connect
         (fn [socket]
           ;; Do stuff and returns nil.
           nil)

         on-close
         (fn [socket error?]
           ;; Do stuff and returns nil.
           nil)

         on-data
         (fn [socket buffer-or-string]
           ;; Do stuff and returns nil.
           nil)}
    :as socket}]
  (let [net-socket (doto (net/connect #js {:host host :port port})
                     (.once "connect"
                            (fn []
                              (on-connect socket)))
                     (.once "close"
                            (fn [error?]
                              (on-close socket error?)))
                     (.on "data"
                          (fn [buffer]
                            (let [{:socket/keys [decoder]} config]
                              (on-data socket (decoder buffer))))))

        net-socket (if-let [encoding (:socket/encoding config)]
                     (.setEncoding net-socket encoding)
                     net-socket)]
    {:socket net-socket

     :write!
     (fn write [data]
       (let [{:socket/keys [encoder]} config]
         (.write ^js net-socket (encoder data))))

     :end!
     (fn []
       (.end ^js net-socket))}))

(defn connected? [{:keys [socket]}]
  (when socket
    (not (.-pending socket))))

(defn- ^{:cmd "talky.connect"} connect [*sys]
  (if (connected? (get @*sys :talky/socket-client))
    (window/show-information-message "Talky is connected.")
    (.then (window/show-input-box
            {:ignoreFocusOut true
             :prompt "Host"
             :value "localhost"})
           (fn [host]
             (when host
               (.then (window/show-input-box
                       {:ignoreFocusOut true
                        :prompt "Port"
                        :value (str 5555)})
                      (fn [port]
                        (when port
                          (let [config
                                {:socket/encoding "utf8"

                                 :socket/decoder
                                 (fn [data]
                                   data)

                                 :socket/encoder
                                 (fn [data]
                                   (str data "\n"))}

                                on-connect
                                (fn [_]
                                  (window/show-information-message
                                   "Talky is connected."))

                                on-close
                                (fn [_ error?]
                                  (window/show-information-message
                                   (if error?
                                     "Talky was disconnected due an error. Sorry."
                                     "Talky is disconnected.")))

                                on-data
                                (fn [_ buffer]
                                  (let [^js output-channel (get @*sys :talky/output-channel)]
                                    (.appendLine output-channel buffer)

                                    (.show output-channel true)))

                                socket-client
                                (connect!
                                 #:socket {:host host
                                           :port (js/parseInt port)
                                           :config config
                                           :on-connect on-connect
                                           :on-close on-close
                                           :on-data on-data})]

                            (swap! *sys assoc :talky/socket-client socket-client))))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (let [{:keys [end!] :as socket-client} (get @*sys :talky/socket-client)]
    (if (connected? socket-client)
      (do
        (end!)
        (swap! *sys dissoc :talky/socket-client))
      (window/show-information-message "Talky is disconnected."))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys ^js editor ^js edit ^js args]
  (let [^js document  (.-document editor)
        ^js selection (.-selection editor)

        {:keys [write!] :as socket-client} (get @*sys :talky/socket-client)]
    (if (connected? socket-client)
      (write! (.getText document selection))
      (window/show-information-message "Talky is disconnected."))))

(def *sys
  (atom {}))


;; How to start a Clojure socket-based REPL
;; clj -J-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/repl}"

(defn activate [^js context]
  (let [^js output-channel (-> (.-window ^js vscode)
                               (.createOutputChannel "Talky"))]

    (->> (register-command *sys #'connect)
         (register-disposable context))

    (->> (register-command *sys #'disconnect)
         (register-disposable context))

    (->> (register-text-editor-command *sys #'send-selection-to-repl)
         (register-disposable context))

    (reset! *sys {:talky/output-channel output-channel})

    (.appendLine output-channel "Talky is active.\n"))

  nil)

(defn deactivate []
  (let [{:keys [end!] :as socket-client} (get @*sys :talky/socket-client)]
    (when (connected? socket-client)
      (end!))))

