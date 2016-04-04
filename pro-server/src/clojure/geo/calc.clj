(ns geo.calc
)
(defn spherical-between [^double phi1 ^double lambda0 ^double c ^double az]
(let [cosphi1 (Math/cos phi1)
        sinphi1 (Math/sin phi1)
        cosaz (Math/cos az)
        sinaz (Math/sin az)
        sinc (Math/sin c)
        cosc (Math/cos c)
        phi2 (Math/asin (+ (* sinphi1 cosc) (* cosphi1 sinc cosaz)))
        lam2 (+ (Math/atan2 (* sinc sinaz) (- (* cosphi1 cosc) (* sinphi1 sinc cosaz))) lambda0)]
    [phi2 lam2]))
(defn spherical-distance [^double phi1 ^double lambda0 ^double phi ^double lambda]
(let [pdiff (Math/sin (/ (- phi phi1) 2.0))
        ldiff (Math/sin (/ (- lambda lambda0) 2.0))
        rval (Math/sqrt (+ (* pdiff pdiff) (* (Math/cos phi1) (Math/cos phi) ldiff ldiff)))]
    (* 2.0 (Math/asin rval))))
(defn spherical-azimuth [^double phi1 ^double lambda0 ^double phi ^double lambda]
(let [ldiff (- lambda lambda0)
        cosphi (Math/cos phi)]
    (Math/atan2 (* cosphi (Math/sin ldiff))
                  (- (* (Math/cos phi1) (Math/sin phi)) (* (Math/sin phi1) cosphi (Math/cos ldiff))))))
(defn future-pos [
(let [phi (Math/toRadians lat)
        lam (Math/toRadians lon)
        dir (Math/toRadians crs)
        way (* spd tim)
        way (Math/toRadians (/ way 60))
        [phi2 lam2] (spherical-between phi lam way dir)]
    [(Math/toDegrees phi2) (Math/toDegrees lam2)]))
(defn distance-nm [
(let [fi1 (Math/toRadians la1)
        ld1 (Math/toRadians lo1)
        fi2 (Math/toRadians la2)
        ld2 (Math/toRadians lo2)
        rad (spherical-distance fi1 ld1 fi2 ld2)]
    (* (Math/toDegrees rad) 60)))
(defn bear-deg [
(let [fi1 (Math/toRadians la1)
        ld1 (Math/toRadians lo1)
        fi2 (Math/toRadians la2)
        ld2 (Math/toRadians lo2)
        rad (spherical-azimuth fi1 ld1 fi2 ld2)
        deg (Math/toDegrees rad)]
    (cond
       (< deg 0) (+ deg 360.0)
       (> deg 360.0) (- deg 360.0)
       true deg)))
(defn seg-intersect [x1 y1 x2 y2 x3 y3 x4 y4]
(let [denom (- (* (- y4 y3) (- x2 x1)) (* (- x4 x3) (- y2 y1)))]
    (if (not (== denom 0.0))
      (let [ua (/ (- (* (- x4 x3) (- y1 y3)) (* (- y4 y3) (- x1 x3))) denom)
            ub (/ (- (* (- x2 x1) (- y1 y3)) (* (- y2 y1) (- x1 x3))) denom)]
        (if (and (<= 0.0 ua 1.0) (<= 0.0 ub 1.0))
          [(+ y1 (* ua (- y2 y1))) (+ x1 (* ua (- x2 x1)))])))))
(defn rev-bear [b]
(let [rb (+ b 180)]
    (if (> rb 360) (- rb 360) rb)))
(defn course-angle [crs 
(let [bea (bear-deg [lat1 lon1] [lat2 lon2])
        ca1 (- bea crs)]
    (cond
     (> ca1 180) (- ca1 360)
     (< ca1 -180) (+ ca1 360)
     true ca1)))
(defn abaft [crs crd1 crd2]
(let [ca (course-angle crs crd1 crd2)]
    (or (> ca 90) (< ca -90))))
