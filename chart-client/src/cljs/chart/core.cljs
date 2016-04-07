(ns chart.core
  (:require
    [cljs.core.async :as async :refer [<! >! chan put! timeout close!]]
    [cognitect.transit :as t]
    [goog.string :as gstring]
    [goog.string.format]
    [ajax.core :refer (GET)])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

;; ---------------------- General constants ------------------------------------

(def pid180 (/ Math.PI 180)) ;; 1 degree in radians
(def nmrad (/ Math.PI 10800)) ;; 1 nautical mile in radians
(def chart (volatile! nil)) ;; chart object
(def mapobs (volatile! {})) ;; map of all flights on chart
(def links (volatile! {})) ;; map of all links on chart
(def trails (volatile! {})) ;; map of all trails on chart
(def DLT-EVT 1000) ;; check event queue from server every 1000 msec (1 sec)
(def DLT-MOV 200) ;; move flight every 200 msec (5 times per sec)
(def DLT-POP 10000) ;; update popup every 10000 msec (10 sec)
(def REM-CAL (volatile! {})) ;; remote call params
(def MYFS-INTL 1000) ;; my flights simulation interval (1 sec)
(def URL-CAL "http://localhost:3000/call/")
(def URL-EVT "http://localhost:4444/events/")
(def URL-NVI "http://localhost:3000/new-visible/")
(def URL-WVI "http://localhost:3000/watch-visible/")
(def URL-FLS "http://localhost:3000/flight-states/")
(def URL-INT "http://localhost:3000/intersect/")
(def URL-INF "http://localhost:3000/info/")
(def URL-TRL "http://localhost:3000/trail/")
(def URL-FLW "http://localhost:3000/follow/")
(def URL-SFW "http://localhost:3000/stopfollow/")
(def URL-CNS "http://localhost:3000/contries/")
(def URL-APS "http://localhost:3000/airports/")
(def URL-CAM "http://localhost:3000/camera/")
(def URL-AUT "http://localhost:3000/manual/")
(def URL-OSM "http://{s}.tile.osm.org/{z}/{x}/{y}.png")
(def URL-GST "http://{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}")
(def URL-GHB "http://{s}.google.com/vt/lyrs=s,h&x={x}&y={y}&z={z}")
(def URL-GTR "http://{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}")
(def URL-GSA "http://{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")
(def URL-ICO "http://localhost:4444/img/yachtr.png")

;; ----------------- Chart creation and control -------------------------

(defn by-id  [id]
  (.getElementById js/document id))

(defn get-html [id]
  (.-innerHTML (by-id id)))

(defn set-html! [id msg]
  (set! (.-innerHTML (by-id id)) msg))

(defn format
  "Formats a string using goog.string.format."
  [fmt & args]
  (apply gstring/format fmt args))

(defn mouse-move [lat lng]
  (set-html! "mousepos" (str "lat " (format "%.4f" lat) " lon " (format "%.4f" lng))))

;; ---------------------- General Functions ------------------------------------

(defn read-transit [x]
  (t/read (t/reader :json) x))

(defn write-transit [x]
  (t/write (t/writer :json) x))

(defn repeater [task timo]
  "Channel that repeats task (function call) forever"
  (go (while true
        (task)
        (<! (timeout timo)))))

(defn create-marker [lat lon]
  (let [pos (js/L.LatLng. lat lon)
        ico (js/L.icon #js{:iconUrl URL-ICO :iconSize #js[32, 32]})
        opt #js{:icon ico :draggable true}
        mk (-> js/L (.rotatedMarker pos opt))]
    mk))

(defn spherical-between [phi1 lambda0 c az]
  (let [cosphi1 (js/Math.cos phi1)
        sinphi1 (js/Math.sin phi1)
        cosaz (js/Math.cos az)
        sinaz (js/Math.sin az)
        sinc (js/Math.sin c)
        cosc (js/Math.cos c)
        phi2 (js/Math.asin (+ (* sinphi1 cosc) (* cosphi1 sinc cosaz)))
        lam2 (+ (js/Math.atan2 (* sinc sinaz) (- (* cosphi1 cosc) (* sinphi1 sinc cosaz))) lambda0)]
    [phi2 lam2]))

(defn future-pos [[lat lon] crs spd tim]
  (let [phi (* pid180 lat)
        lam (* pid180 lon)
        dir (* pid180 crs)
        way (* spd tim)
        way (* pid180 (/ way 60))
        [phi2 lam2] (spherical-between phi lam way dir)]
    [(/ phi2 pid180) (/ lam2 pid180)]))

(defn mapobPopup [id data]
  (let [[lat lon] (:coord data)]
    (str "<h3>" id "</h3>"
         "<table>"
         "<tr><td>latitude</td><td>" (format "%.4f" lat) "</td></tr>"
         "<tr><td>longitude</td><td>" (format "%.4f" lon) "</td></tr>"
         "<tr><td>course</td><td>" (:course data) "</td></tr>"
         "<tr><td>speed</td><td>" (format "%.1f" (:speed data)) "</td></tr>"
         "</table>")))

(defn popup [id]
  (if-let [data (@mapobs id)]
    (.bindPopup (:marker data) (mapobPopup id data))))

(defn move [id]
  (if-let [data (@mapobs id)]
    (let [spd (:speed data)]
      (if (> spd 0)
        (let [etim (+ (:time-from-turn data) DLT-MOV)
              ehrs (/ etim 36000000)
              [lat lon] (future-pos (:turn-coord data)
                                    (:course data)
                                    spd
                                    ehrs)
              pos (js/L.LatLng. lat lon)]
          (.setLatLng (:marker data) pos)
          (vswap! mapobs assoc id
                  (merge data
                         {:coord [lat lon]
                          :time-from-turn etim})))))))


(defn delete-mapob [id]
  (when-let [mob (@mapobs id)]
    (if-let [mvr (mob :mover)]
      (close! mvr))
    (if-let [pop (mob :poper)]
      (close! pop))
    (.removeLayer @chart (mob :marker))
    (vswap! mapobs dissoc id)))

(defn boat-add [id data]
  (if (@mapobs id)
    (delete-mapob id))
  (let [[lat lon] (:coord data)
        mrk (create-marker lat lon)]
    (.addTo mrk @chart)
    (set! (.. mrk -options -angle) (:course data))
    (.bindPopup mrk (mapobPopup id data))
    (vswap! mapobs assoc id
            (merge data
                   {:marker mrk
                    :mover (repeater #(move id) DLT-MOV)
                    :poper (repeater #(popup id) DLT-POP)}))))

(defn boat-maneuver [id data]
  (when-let [old (@mapobs id)]
    (vswap! mapobs assoc id (merge old data))
    (set! (.. (:marker old) -options -angle) (:course data))))

(defn clear-mapobs []
  (doseq [id (keys @mapobs)]
    (delete-mapob id)))

;;----------------------- Trail manipulation ------------------------

(defn add-trail [id lla options time]
  (let [ops (clj->js options)
        llg (map #(js/L.LatLng. (first %) (second %)) (partition 3 lla))
        lle (clj->js llg)
        trl (js/L.polyline lle ops)]
    (.addLayer @chart trl)
    (vswap! trails assoc-in [id :id] id)
    (vswap! trails assoc-in [id :trail] trl)
    (vswap! trails assoc-in [id :options] options)
    (if (> time 0)
      (go (<! (timeout time))
          (.removeLayer @chart trl)
          (vswap! trails dissoc id)))))

;;----------------------- Links manipulation ------------------------

(defn collect-llga [ids]
  (let [mobs (filter some? (map #(@mapobs %) ids))
        mrks (map #(:marker %) mobs)
        llgs (map #(.getLatLng %) mrks)]
    (clj->js llgs)))

(defn linkPopup [[id1 id2] ops]
  (let [alt1 (get-in @mapobs [id1 :altitude])
        alt2 (get-in @mapobs [id2 :altitude])
        adif (- alt1 alt2)
        titl (get ops :title)
        dmin (get ops :dmin)
        tmin (get ops :tmin)]
    (str "<h3>" titl "</h3>"
       "<table>"
       "<tr><td>Dmin</td><td>" (if (number? dmin) (int (* 1852 dmin))) " m</td></tr>"
       "<tr><td>Tmin</td><td>" (if (number? tmin) (int (* 60 tmin))) " min</td></tr>"
       "<tr><td>Alt-diff</td><td>" adif " ft</td></tr>"
       "</table>")))

(defn add-link [ids options]
  (let [ops (clj->js options)
        tmin (get options :tmin)
        del (if (number? tmin)
              (int (* 3600000 tmin))
              30000)
        llg (collect-llga ids)
        lnk (js/L.polyline llg ops)]
    (.addLayer @chart lnk)
    (.bindPopup lnk (linkPopup ids options))
    (vswap! links assoc ids lnk)
    (if (> del 0)
      (go (<! (timeout del))
          (.removeLayer @chart lnk)
          (vswap! links dissoc ids)))))

(defn delete-link [ids]
  (when-let [lnk (get @links ids)]
    (.removeLayer @chart lnk)
    (vswap! links dissoc ids)))

(defn shift-links []
  (doseq [[ids lnk] @links]
    (.setLatLngs lnk (collect-llga ids))))

(defn clear-links []
  (doseq [ids (keys @links)]
    (delete-link ids)))

(defn get-pos [id]
  (if-let [mob (@mapobs id)]
    (let [mrk (mob :marker)
          pos (.getLatLng mrk)
          lat (.-lat pos)
          lon (.-lng pos)]
      [lat lon])))

(defn add-popup
  ([id html time]
    (let [[lat lon] (get-pos id)]
      (add-popup lat lon html time)))
  ([lat lon html time]
    (let [pop (-> js/L (.popup {:maxWidth 600 :maxHeight 800 })
                (.setLatLng (array lat lon))
                (.setContent html))]
      (.addLayer @chart pop)
      (if (> time 0)
        (go (<! (timeout time))
            (.removeLayer @chart pop))))))

(declare new-visible)

(defn set-map-view [lat lon]
  (let [cen (js/L.LatLng. lat lon)
        zom (.getZoom @chart)]
    (.setView @chart cen zom {})
    (new-visible)))

(defn clear-dialog []
  (set-html! "callsign" "")
  (set-html! "hour" "")
  (set-html! "minute" "")
  (set-html! "from-country" "")
  (set-html! "from-airport" "")
  (set-html! "to-country" "")
  (set-html! "to-airport" ""))

(defn course [id]
  (int (/ (get-in @mapobs [id :anc-dir]) pid180)))

(defn set-course! [id crs]
  (let [crs (cond
             (>= crs 360) (- crs 360)
             (< crs 0) (+ crs 360)
             true crs)
        dir (* crs pid180)
        mrk (get-in @mapobs [id :marker])]
    (vswap! mapobs assoc-in [id :anc-dir] dir)
    (set! (.. mrk -options -angle) crs)))

(defn turn [id on-course]
  "turning of my flight plane on new course"
  (let [crs (course id)]
    (if (not= crs on-course)
      (let [side (if (> on-course crs)
                   (if (< (- on-course crs) 180)
                     :right
                     :left)
                   (if (< (- crs on-course) 180)
                     :left
                     :right))]
        (go (loop [crs crs]
              (if (< (Math/abs (- crs on-course)) 4)
                (set-course! id on-course)
                (do
                  (if (= side :left)
                    (set-course! id (- crs 4))
                    (set-course! id (+ crs 4)))
                  (<! (timeout MYFS-INTL))
                  (recur (course id))))))))))

(def CAMERA :off)
(def ONBOARD nil)

(defn display-flight-data []
  (if (= CAMERA :on)
    (when-let [[lat lon alt crs spd] (get-in @mapobs [ONBOARD :laloalcs])]
      (set-html! "course" (str "Course: " crs))
      (set-html! "speed" (str "Speed: " spd))
      (set-html! "altitude" (str "Altitude: " alt))
      (set-html! "lat" (str "Latitude: " (format "%.4f" lat)))
      (set-html! "lon" (str "Longitude: " (format "%.4f" lon))))))

;; ------------------------ Event handler ---------------------------

(defn event-handler [response]
  (doseq [{:keys [event] :as evt} (read-transit response)]
    ;;(println [:EVENT evt])
    (condp = event
      :boat-add (let [{:keys [id data]} evt]
                      (boat-add id data))
      :boat-remove (let [{:keys [id]} evt]
                      (delete-mapob id))
      :boat-maneuver (let [{:keys [id data]} evt]
                      (boat-maneuver id data))
      (println (str "Unknown event: " [event evt])))))

(defn error-handler [{:keys [status status-text]}]
  (println (str "AJAX ERROR: " status " " status-text)))

(defn check-events []
  (GET URL-EVT {:handler event-handler
                 :error-handler error-handler})
  (display-flight-data))

(defn no-handler [response])

;; -------------------------- Buttons --------------------------------

(defn info [id]
  (let [url (str URL-INF "?id=" id)]
    (GET url {:handler no-handler
              :error-handler error-handler})))

(defn trail [id]
  (let [url (str URL-TRL "?id=" id)]
    (GET url {:handler no-handler
              :error-handler error-handler})))

(defn follow [id]
  (let [url (str URL-FLW "?id=" id)]
    (GET url {:handler no-handler
              :error-handler error-handler})))

(defn stopfollow []
  (GET URL-SFW {:handler no-handler
              :error-handler error-handler}))

;; ----------------------------- Autopilot ----------------------------

(def MANUAL :off)

(defn manual-show []
  (when (= CAMERA :on)
    (set-html! "course-fld" (str "<input value='0' style='width:40px' "
                                 "onchange='javascript:rete4flight.core.manualcrs(this.value)'>"))
    (set-html! "speed-fld" (str "<input value='0' style='width:40px' "
                                "onchange='javascript:rete4flight.core.manualspd(this.value)'>"))
    (set-html! "altitude-fld" (str "<input value='0' style='width:40px' "
                                   "onchange='javascript:rete4flight.core.manualalt(this.value)'>"))))

(defn manual-hide []
  (set-html! "course-fld" "")
  (set-html! "speed-fld" "")
  (set-html! "altitude-fld" ""))

(defn manual []
  (cond
    (= MANUAL :off)
    (do (manual-show)
      (GET (str URL-AUT "?manual=on") {:handler no-handler :error-handler error-handler})
      (def MANUAL :on))
    (= MANUAL :on)
    (do (manual-hide)
      (GET (str URL-AUT "?manual=off") {:handler no-handler :error-handler error-handler})
      (def MANUAL :off))))

(defn manualcrs [crs]
  (let [url (str URL-AUT "?course=" crs)]
    (GET url {:handler no-handler :error-handler error-handler})))

(defn manualspd [spd]
  (let [url (str URL-AUT "?speed=" spd)]
    (GET url {:handler no-handler :error-handler error-handler})))

(defn manualalt [alt]
  (let [url (str URL-AUT "?altitude=" alt)]
    (GET url {:handler no-handler :error-handler error-handler})))

;; ------------------------ camera control ---------------------------

(defn camera-show []
  (set-html! "autopilot" "<h4>Autopilot</h4>")
  (set-html! "course" "Course: 000")
  (set-html! "speed" "Speed: 000")
  (set-html! "altitude" "Altitude: 00000")
  (set-html! "lat" "Latitude: 00.0000")
  (set-html! "lon" "Longitude: 00.0000")
  (set-html! "camera" "<h4>Camera</h4>")
  (set-html! "onboard" "Onboard:")
  (set-html! "onboard-fld" (str "<input value='callsign' style='width:90px' "
                                "onchange='javascript:rete4flight.core.camonb(this.value)'>"))
  (set-html! "heading" "Heading:")
  (set-html! "heading-fld" "<select onchange='javascript:rete4flight.core.camhea(this.value)' style='width:96px'>
             <option value='FORWARD'>FORWARD</option>
             <option value='BACKWARD'>BACKWARD</option>
             <option value='RIGHT'>RIGHT</option>
             <option value='LEFT'>LEFT</option>
             <option value='UP'>UP</option>
             <option value='DOWN'>DOWN</option>
             </select>")
  (set-html! "pitch" "Pitch:")
  (set-html! "pitch-fld" (str "<input value='-10' style='width:90px' "
                              "onchange='javascript:rete4flight.core.campit(this.value)'>"))
  (set-html! "roll" "Roll:")
  (set-html! "roll-fld" (str "<input value='0' style='width:90px' "
                             "onchange='javascript:rete4flight.core.camrol(this.value)'>")))

(defn camera-hide []
  (set-html! "autopilot" "")
  (set-html! "course" "")
  (set-html! "speed" "")
  (set-html! "altitude" "")
  (set-html! "lat" "")
  (set-html! "lon" "")
  (set-html! "camera" "")
  (set-html! "onboard" "")
  (set-html! "onboard-fld" "")
  (set-html! "heading" "")
  (set-html! "heading-fld" "")
  (set-html! "pitch" "")
  (set-html! "pitch-fld" "")
  (set-html! "roll" "")
  (set-html! "roll-fld" "")
  (manual-hide))

(defn camera []
  (cond
    (= CAMERA :off)
    (do (camera-show)
      (GET (str URL-CAM "?camera=on") {:handler no-handler :error-handler error-handler})
      (def CAMERA :on))
    (= CAMERA :on)
    (do (camera-hide)
      (GET (str URL-CAM "?camera=off") {:handler no-handler :error-handler error-handler})
      (def CAMERA :off))))

(defn camonb [obj]
  (let [url (str URL-CAM "?onboard=" obj)]
    (GET url {:handler no-handler :error-handler error-handler})
    (def ONBOARD obj)))

(defn camhea [hea]
  (let [url (str URL-CAM "?heading=" hea)]
    (GET url {:handler no-handler :error-handler error-handler})))

(defn campit [pit]
  (let [url (str URL-CAM "?pitch=" pit)]
    (GET url {:handler no-handler :error-handler error-handler})))

(defn camrol [rol]
  (let [url (str URL-CAM "?roll=" rol)]
    (GET url {:handler no-handler :error-handler error-handler})))

;; ---------------------- schedule flights ---------------------------

(defn schedule []
  (let [inp (str "<input type='text' id='callsign' style='width:80px' value='callsign'"
                 " onchange='javascript:rete4flight.core.selcallsgn(this.value)'>")]
    (set-html! "callsign" inp)
    (vreset! REM-CAL {"?func=" "schedule"})))

(defn sel-hour []
  (let [slh (str "<select onchange='javascript:rete4flight.core.selhour(this.value)'>"
                 "<option value='select'>hh</option>"
                 (apply str
                        (for [hour (range 24)]
                          (str "<option value='" hour "'>" hour "</option>")))
                 "</select>")]
    (set-html! "hour" slh)))

(defn sel-minute []
  (let [slm (str "<select onchange='javascript:rete4flight.core.selmin(this.value)'>"
                 "<option value='select'>mm</option>"
                 (apply str
                        (for [min (range 60)]
                          (str "<option value='" min "'>" min "</option>")))
                 "</select>")]
    (set-html! "minute" slm)))

(defn sel-from-country [contries]
  (let [sel (str "<select onchange='javascript:rete4flight.core.selfromcnt(this.value)'>"
                 "<option value='select'>from country</option>"
                 (apply str
                        (for [contry contries]
                          (str "<option value='" contry "'>" contry "</option>")))
                 "</select>")]
    (set-html! "from-country" sel)))

(defn sel-from-airport [airports]
  (let [sel (str "<select onchange='javascript:rete4flight.core.selfromapt(this.value)'>"
                 "<option value='select'>from airport</option>"
                 (apply str
                        (for [airport airports]
                          (str "<option value='" airport "'>" airport "</option>")))
                 "</select>")]
    (set-html! "from-airport" sel)))

;; ---------------------------- Move to Airport -------------------------------

(defn sel-to-country [contries]
  (let [sel (str "<select onchange='javascript:rete4flight.core.seltocnt(this.value)'>"
                 "<option value='select'>to country</option>"
                 (apply str
                        (for [contry contries]
                          (str "<option value='" contry "'>" contry "</option>")))
                 "</select>")]
    (set-html! "to-country" sel)))

(defn move-to []
  (vreset! REM-CAL {"?func=" "move-to"})
  (sel-to-country [])
  (GET URL-CNS {:handler (fn [r] (sel-to-country (read-transit r)))
                :error-handler error-handler}))

(defn sel-to-airport [airports]
  (let [sel (str "<select onchange='javascript:rete4flight.core.seltoapt(this.value)'>"
                 "<option value='select'>to airport</option>"
                 (apply str
                        (for [airport airports]
                          (str "<option value='" airport "'>" airport "</option>")))
                 "</select>")]
    (set-html! "to-airport" sel)))

(defn selcallsgn [csn]
  (vswap! REM-CAL assoc "&callsign=" csn)
  (sel-hour))

(defn selhour [hour]
  (vswap! REM-CAL assoc "&hour=" hour)
  (sel-minute))

(defn selmin [min]
  (vswap! REM-CAL assoc "&minute=" min)
  (sel-from-country [])
  (GET URL-CNS {:handler (fn [r] (sel-from-country (read-transit r)))
                :error-handler error-handler}))

(defn selfromcnt [country]
  (vswap! REM-CAL assoc "&from-country=" country)
  (sel-from-airport [])
  (let [url (str URL-APS "?contry=" country)]
    (GET url {:handler (fn [r] (sel-from-airport (read-transit r)))
              :error-handler error-handler})))

(defn selfromapt [airport]
  (vswap! REM-CAL assoc "&from-airport=" airport)
  (sel-to-country [])
  (GET URL-CNS {:handler (fn [r] (sel-to-country (read-transit r)))
                :error-handler error-handler}))

(defn seltocnt [country]
  (vswap! REM-CAL assoc "&to-country=" country)
  (sel-to-airport [])
  (let [url (str URL-APS "?contry=" country)]
    (GET url {:handler (fn [r] (sel-to-airport (read-transit r)))
              :error-handler error-handler})))

(declare remote-call)

(defn seltoapt [airport]
  (vswap! REM-CAL assoc "&to-airport=" airport)
  (remote-call))

;; -------------------------- Commands -------------------------------

(defn clear-all []
  (clear-links)
  (clear-mapobs))

(defn visible-map []
  (let [bnd (.getBounds @chart)]
    [(.getNorth bnd)
     (.getSouth bnd)
     (.getWest bnd)
     (.getEast bnd)]))

(defn get-map-center []
  (let [cen (.getCenter @chart)]
    [(.-lat cen) (.-lng cen)]))

(defn new-visible []
  (let [[n s w e] (visible-map)
        center (get-map-center)
        url (str URL-NVI "?n=" n "&s=" s "&w=" w "&e=" e "&c=" center)]
    (GET url {:handler no-handler
              :error-handler error-handler})))

(defn watch-visible []
  (let [[n s w e] (visible-map)
        center (get-map-center)
        url (str URL-WVI "?n=" n "&s=" s "&w=" w "&e=" e "&c=" center)]
    (clear-all)
    (GET url {:handler no-handler
              :error-handler error-handler})))

(defn flight-states []
  (GET URL-FLS {:handler no-handler
                :error-handler error-handler}))

(defn intersect []
  (GET URL-INT {:handler no-handler
                :error-handler error-handler}))

(def COMMANDS
  "<select onchange='javascript:rete4flight.core.commands(this.value)'>
  			<option value='commands'>Commands</option>
  			<option value='watch-visible'>Watch visible area</option>
  			<option value='flight-states'>State of flights</option>
  			<option value='intersect'>Intersections</option>
  			<option value='move-to'>Move to Airport</option>
  			<option value='schedule'>Schedule Flight</option>
  			<option value='camera'>Camera</option>
  			<option value='manual'>Manual Control</option>
  			<option value='clear'>Clear</option>
		</select>")

(defn commands [func]
  (condp = func
    "watch-visible" (watch-visible)
    "flight-states" (flight-states)
    "intersect" (intersect)
    "clear" (clear-all)
    "move-to" (move-to)
    "schedule" (schedule)
    "camera" (camera)
    "manual" (manual))
  (set-html! "commands" COMMANDS))

(defn remote-call []
  (let [fkey "?func="]
    (if-let [func (get @REM-CAL fkey)]
      (let [head (str fkey func)
            tail (for [[f k] (seq (dissoc @REM-CAL fkey))] (str f k))
            plis (str head (apply str tail))]
        (GET (str URL-CAL plis) {:handler no-handler
                                  :error-handler error-handler})))))

;; ------------------------ Initialization ----------------------------

(defn init []
  (let [m (-> js/L
            (.map "map")
            (.setView (array 62.8260 34.7436) 11)) ;; 40.8, -74.0) 9)) ;; New York City
        tile1 (-> js/L (.tileLayer URL-OSM
                         #js{:maxZoom 16
                             :attribution "OOGIS RL, OpenStreetMap &copy;"}))
        tile2 (-> js/L (.tileLayer URL-GSA
                         #js{:maxZoom 20
                             :subdomains #js["mt0" "mt1" "mt2" "mt3"]
                             :attribution "OOGIS RL, Google &copy;"}))
        tile3 (-> js/L (.tileLayer URL-GST
                         #js{:maxZoom 20
                             :subdomains #js["mt0" "mt1" "mt2" "mt3"]
                             :attribution "OOGIS RL, Google &copy;"}))
        tile4 (-> js/L (.tileLayer URL-GHB
                         #js{:maxZoom 20
                             :subdomains #js["mt0" "mt1" "mt2" "mt3"]
                             :attribution "OOGIS RL, Google &copy;"}))
        tile5 (-> js/L (.tileLayer URL-GTR
                         #js{:maxZoom 20
                             :subdomains #js["mt0" "mt1" "mt2" "mt3"]
                             :attribution "OOGIS RL, Google &copy;"}))
        base (clj->js {"OpenStreetMap" tile1
                       "Google Satellite" tile2
                       "Google Streets" tile3
                       "Google Hybrid" tile4
                       "Google Terrain" tile5})
        ctrl (-> js/L (.control.layers base nil))]
    (.addTo tile1 m)
    (.addTo ctrl m)
    (.on m "mousemove"
      (fn [e] (mouse-move (.. e -latlng -lat) (.. e -latlng -lng))))
    (vreset! chart m)
    (set-html! "commands" COMMANDS)
    (repeater #(check-events) DLT-EVT)))

;; ----------------------------- Start ---------------------------------

(set! (.-onload js/window) (init))

