package com.simweaver.common.geo;

import java.util.Locale;

/**
 * Immutable geodetic position: latitude and longitude in degrees, height in metres above
 * the ellipsoid (HAE). This is <em>not</em> height above mean sea level; that needs a geoid
 * model, which this package does not contain.
 */
public final class Geodetic {

    private final double latitude;
    private final double longitude;
    private final double height;

    /**
     * @param latitudeDeg  geodetic latitude, degrees, [-90, 90]
     * @param longitudeDeg longitude, degrees; any value, normalized to (-180, 180]
     * @param heightM      height above the ellipsoid, metres
     * @throws IllegalArgumentException if latitude is outside [-90, 90] or a value is NaN/infinite
     */
    public Geodetic(double latitudeDeg, double longitudeDeg, double heightM) {
        if (!(latitudeDeg >= -90.0 && latitudeDeg <= 90.0)) {
            throw new IllegalArgumentException("latitude out of range [-90, 90]: " + latitudeDeg);
        }
        if (Double.isNaN(longitudeDeg) || Double.isInfinite(longitudeDeg)) {
            throw new IllegalArgumentException("longitude not finite: " + longitudeDeg);
        }
        if (Double.isNaN(heightM) || Double.isInfinite(heightM)) {
            throw new IllegalArgumentException("height not finite: " + heightM);
        }
        this.latitude = latitudeDeg;
        this.longitude = Angles.normalize180(longitudeDeg);
        this.height = heightM;
    }

    /**
     * @param latitudeDeg  degrees
     * @param longitudeDeg degrees
     * @param heightM      metres above the ellipsoid
     * @return the position
     */
    public static Geodetic ofDegrees(double latitudeDeg, double longitudeDeg, double heightM) {
        return new Geodetic(latitudeDeg, longitudeDeg, heightM);
    }

    /**
     * @param latitudeRad  radians
     * @param longitudeRad radians
     * @param heightM      metres above the ellipsoid
     * @return the position
     */
    public static Geodetic ofRadians(double latitudeRad, double longitudeRad, double heightM) {
        double lat = Math.toDegrees(latitudeRad);
        // toDegrees(pi/2) may land a hair above 90
        if (lat > 90.0 && lat < 90.0 + 1e-9) {
            lat = 90.0;
        } else if (lat < -90.0 && lat > -90.0 - 1e-9) {
            lat = -90.0;
        }
        return new Geodetic(lat, Math.toDegrees(longitudeRad), heightM);
    }

    /** @return latitude, degrees */
    public double getLatitude() {
        return latitude;
    }

    /** @return longitude, degrees, (-180, 180] */
    public double getLongitude() {
        return longitude;
    }

    /** @return height above the ellipsoid, metres */
    public double getHeight() {
        return height;
    }

    /** @return latitude, radians */
    public double getLatitudeRadians() {
        return Math.toRadians(latitude);
    }

    /** @return longitude, radians */
    public double getLongitudeRadians() {
        return Math.toRadians(longitude);
    }

    /**
     * Formats as {@code "59.3293120 18.0685810 120.4"}.
     *
     * @param angleDecimals  decimals for latitude and longitude (7 = about 1 cm)
     * @param heightDecimals decimals for height
     * @return the text
     */
    public String format(int angleDecimals, int heightDecimals) {
        String a = "%." + angleDecimals + "f";
        return String.format(Locale.ROOT, a + " " + a + " %." + heightDecimals + "f",
                             latitude, longitude, height);
    }

    /**
     * Formats as degrees, minutes and decimal seconds with hemisphere letters,
     * for example {@code "59 19 45.52N 018 04 06.89E"}.
     *
     * @param secondDecimals decimals of the seconds
     * @return the text (height not included)
     */
    public String formatDms(int secondDecimals) {
        return dms(latitude, secondDecimals, 2, 'N', 'S') + " " + dms(longitude, secondDecimals, 3, 'E', 'W');
    }

    private static String dms(double value, int secondDecimals, int degreeDigits, char pos, char neg) {
        double scale = Math.pow(10.0, secondDecimals);
        // round once on total seconds so 59.9999 never prints as 60
        long totalScaled = Math.round(Math.abs(value) * 3600.0 * scale);
        long perMinute = Math.round(60.0 * scale);
        long perDegree = perMinute * 60L;
        long deg = totalScaled / perDegree;
        long min = (totalScaled % perDegree) / perMinute;
        double sec = (totalScaled % perMinute) / scale;
        int secWidth = secondDecimals > 0 ? 3 + secondDecimals : 2;
        String secFmt = "%0" + secWidth + "." + secondDecimals + "f";
        char hemi = value < 0.0 && totalScaled != 0 ? neg : pos;
        return String.format(Locale.ROOT, "%0" + degreeDigits + "d %02d " + secFmt + "%c", deg, min, sec, hemi);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Geodetic)) {
            return false;
        }
        Geodetic g = (Geodetic) o;
        return Double.compare(latitude, g.latitude) == 0
            && Double.compare(longitude, g.longitude) == 0
            && Double.compare(height, g.height) == 0;
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(latitude);
        h = 31 * h + Double.hashCode(longitude);
        return 31 * h + Double.hashCode(height);
    }

    /** @return {@code "59.3293120 18.0685810 120.40"} */
    @Override
    public String toString() {
        return format(7, 2);
    }
}
