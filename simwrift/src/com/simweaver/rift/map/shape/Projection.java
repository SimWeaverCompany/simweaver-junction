package com.simweaver.rift.map.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The coordinate system of a shapefile, from its {@code .prj} (WKT 1, as ESRI and most tools
 * write it), turning its coordinates into longitude/latitude (degrees).
 *
 * <p>Supported: geographic systems (WGS 84, ETRS89, SWEREF 99 geographic, ...); Transverse
 * Mercator (SWEREF 99 TM and its local zones, UTM, Gauss-Krüger), with Krüger's series as
 * Lantmäteriet publishes them (sub-millimetre far from the central meridian); Web Mercator
 * (Popular Visualisation Pseudo-Mercator, Mercator_Auxiliary_Sphere). Datum shifts are not
 * applied: data on ETRS89 / SWEREF 99 / WGS 84 is placed within a metre; other datums (RT 90,
 * ED50, ...) can be off by tens to hundreds of metres, which {@link #warning} says.
 */
public abstract class Projection {

    /** What the .prj says, for the log ("SWEREF99 TM (Transverse Mercator, GRS 1980)"). */
    public final String description;
    /** A note for the log, or null. */
    public final String warning;

    Projection(String description, String warning) {
        this.description = description;
        this.warning = warning;
    }

    /**
     * @param x   easting (or longitude)
     * @param y   northing (or latitude)
     * @param out receives {longitude, latitude} in degrees
     */
    public abstract void toLonLat(double x, double y, double[] out);

    /** Longitude/latitude in degrees, as they are. */
    public static Projection geographic(String name, String warning) {
        return new Projection(name, warning) {
            @Override
            public void toLonLat(double x, double y, double[] out) {
                out[0] = x;
                out[1] = y;
            }
        };
    }

    // ------------------------------------------------------------------ WKT

    /**
     * Reads a .prj.
     *
     * @throws IllegalArgumentException when the projection is not supported (the message says which)
     */
    public static Projection fromWkt(String wkt) {
        Node root = Node.parse(wkt);
        if (root == null) {
            throw new IllegalArgumentException("the .prj cannot be read");
        }
        String kind = root.name.toUpperCase(Locale.ROOT);
        Node geog = kind.equals("GEOGCS") ? root : root.child("GEOGCS");
        String datum = geog == null ? "" : text(geog.child("DATUM"));
        double[] ell = spheroid(geog);
        String warning = datumWarning(datum);
        if (kind.equals("GEOGCS")) {
            return geographic(text(root) + " (longitude/latitude)", warning);
        }
        if (!kind.equals("PROJCS")) {
            throw new IllegalArgumentException("the .prj is a " + root.name + ", not a GEOGCS or PROJCS");
        }
        String proj = text(root.child("PROJECTION"));
        String p = proj.toLowerCase(Locale.ROOT).replace(' ', '_');
        double unit = 1;
        for (Node c : root.children) {
            if (c.name.equalsIgnoreCase("UNIT") && c.values.size() >= 2) {
                unit = number(c.values.get(1), 1);
            }
        }
        String name = text(root);
        if (p.contains("transverse_mercator") || p.contains("gauss_kruger")) {
            double lon0 = param(root, 0, "central_meridian", "longitude_of_origin", "longitude_of_center");
            double lat0 = param(root, 0, "latitude_of_origin", "latitude_of_center");
            double k0 = param(root, 1, "scale_factor");
            double fe = param(root, 0, "false_easting");
            double fn = param(root, 0, "false_northing");
            return new TransverseMercator(name + " (Transverse Mercator, " + ellipsoidName(geog) + ")", warning,
                    ell[0], ell[1], lon0, lat0, k0, fe, fn, unit);
        }
        if (p.contains("mercator_auxiliary_sphere") || p.contains("pseudo_mercator") || p.contains("popular_visualisation")
                || (p.startsWith("mercator") && ell[1] == 0)) {
            double lon0 = param(root, 0, "central_meridian", "longitude_of_origin");
            double fe = param(root, 0, "false_easting");
            double fn = param(root, 0, "false_northing");
            return new WebMercator(name + " (Web Mercator)", warning, ell[0], lon0, fe, fn, unit);
        }
        throw new IllegalArgumentException("projection " + proj + " (" + name + ") is not supported; "
                + "use longitude/latitude, Transverse Mercator (SWEREF 99 TM, UTM) or Web Mercator");
    }

    private static String datumWarning(String datum) {
        String d = datum.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (d.isEmpty() || d.contains("WGS_1984") || d.contains("WGS84") || d.contains("ETRS") || d.contains("EUROPEAN_TERRESTRIAL")
                || d.contains("SWEREF") || d.contains("ITRF") || d.contains("NAD_1983") || d.contains("GRS_1980")) {
            return null;
        }
        return "datum " + datum + " is not WGS 84 / ETRS89: no datum shift is applied, so positions may be off by up to a few hundred metres";
    }

    /** {semi-major axis, flattening} of the GEOGCS (GRS 80 when it says nothing). */
    private static double[] spheroid(Node geog) {
        Node d = geog == null ? null : geog.child("DATUM");
        Node s = d == null ? null : d.child("SPHEROID");
        if (s == null && geog != null) {
            s = geog.child("SPHEROID");
        }
        if (s == null || s.values.size() < 3) {
            return new double[] {6378137.0, 1 / 298.257222101};
        }
        double a = number(s.values.get(1), 6378137.0);
        double inv = number(s.values.get(2), 298.257222101);
        return new double[] {a, inv == 0 ? 0 : 1 / inv};
    }

    private static String ellipsoidName(Node geog) {
        Node d = geog == null ? null : geog.child("DATUM");
        Node s = d == null ? null : d.child("SPHEROID");
        return s == null ? "GRS 1980" : text(s).replace('_', ' ');
    }

    private static double param(Node root, double def, String... names) {
        for (Node c : root.children) {
            if (c.name.equalsIgnoreCase("PARAMETER") && c.values.size() >= 2) {
                String n = c.values.get(0).toLowerCase(Locale.ROOT).replace(' ', '_');
                for (String w : names) {
                    if (n.equals(w)) {
                        return number(c.values.get(1), def);
                    }
                }
            }
        }
        return def;
    }

    private static String text(Node n) {
        return n == null || n.values.isEmpty() ? "" : n.values.get(0);
    }

    private static double number(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** A WKT 1 node: NAME[value, value, CHILD[...], ...]; quoted values unquoted. */
    static final class Node {
        final String name;
        final List<String> values = new ArrayList<>();
        final List<Node> children = new ArrayList<>();

        Node(String name) {
            this.name = name;
        }

        Node child(String n) {
            for (Node c : children) {
                if (c.name.equalsIgnoreCase(n)) {
                    return c;
                }
            }
            return null;
        }

        static Node parse(String wkt) {
            if (wkt == null) {
                return null;
            }
            int[] at = {0};
            return node(wkt.trim(), at);
        }

        private static Node node(String s, int[] at) {
            int start = at[0];
            while (at[0] < s.length() && s.charAt(at[0]) != '[' && s.charAt(at[0]) != '(') {
                at[0]++;
            }
            if (at[0] >= s.length()) {
                return null;
            }
            Node n = new Node(s.substring(start, at[0]).trim());
            at[0]++;
            while (at[0] < s.length()) {
                char ch = s.charAt(at[0]);
                if (ch == ']' || ch == ')') {
                    at[0]++;
                    return n;
                }
                if (ch == ',' || Character.isWhitespace(ch)) {
                    at[0]++;
                    continue;
                }
                if (ch == '"') {
                    int end = s.indexOf('"', at[0] + 1);
                    if (end < 0) {
                        return n;
                    }
                    n.values.add(s.substring(at[0] + 1, end));
                    at[0] = end + 1;
                    continue;
                }
                int j = at[0];
                while (j < s.length() && ",[]()".indexOf(s.charAt(j)) < 0) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == '[' || s.charAt(j) == '(')) {
                    Node c = node(s, at);
                    if (c == null) {
                        return n;
                    }
                    n.children.add(c);
                } else {
                    n.values.add(s.substring(at[0], j).trim());
                    at[0] = j;
                }
            }
            return n;
        }
    }

    // ------------------------------------------------------------------ Transverse Mercator

    /**
     * Gauss conformal projection (Transverse Mercator) with Krüger's n-series to the 4th order,
     * as in Lantmäteriet's "Gauss Konforma projektion" formulas; a latitude of origin other than
     * 0 is taken into account through the meridian arc.
     */
    public static final class TransverseMercator extends Projection {
        private final double lon0;
        private final double k0;
        private final double fe;
        private final double fn;
        private final double unit;
        private final double aRoof;
        private final double e2;
        private final double d1, d2, d3, d4;           // inverse
        private final double b1, b2, b3, b4;           // forward
        private final double as, bs, cs, ds;           // conformal -> geodetic latitude
        private final double af, bf, cf, df;           // geodetic -> conformal latitude
        private final double northingOfOrigin;

        TransverseMercator(String description, String warning, double a, double f, double lon0, double lat0, double k0,
                           double fe, double fn, double unit) {
            super(description, warning);
            this.lon0 = Math.toRadians(lon0);
            this.k0 = k0;
            this.fe = fe;
            this.fn = fn;
            this.unit = unit;
            e2 = f * (2 - f);
            double n = f / (2 - f);
            double n2 = n * n, n3 = n2 * n, n4 = n3 * n;
            aRoof = a / (1 + n) * (1 + n2 / 4 + n4 / 64);
            d1 = n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360;
            d2 = n2 / 48 + n3 / 15 - 437 * n4 / 1440;
            d3 = 17 * n3 / 480 - 37 * n4 / 840;
            d4 = 4397 * n4 / 161280;
            b1 = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180;
            b2 = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440;
            b3 = 61 * n3 / 240 - 103 * n4 / 140;
            b4 = 49561 * n4 / 161280;
            double e4 = e2 * e2, e6 = e4 * e2, e8 = e6 * e2;
            as = e2 + e4 + e6 + e8;
            bs = -(7 * e4 + 17 * e6 + 30 * e8) / 6;
            cs = (224 * e6 + 889 * e8) / 120;
            ds = -(4279 * e8) / 1260;
            af = e2;
            bf = (5 * e4 - e6) / 6;
            cf = (104 * e6 - 45 * e8) / 120;
            df = 1237 * e8 / 1260;
            northingOfOrigin = lat0 == 0 ? 0 : forwardNorthing(Math.toRadians(lat0));
        }

        private double forwardNorthing(double lat) {
            double s = Math.sin(lat), s2 = s * s;
            double latStar = lat - s * Math.cos(lat) * (af + bf * s2 + cf * s2 * s2 + df * s2 * s2 * s2);
            double xi = latStar;
            return k0 * aRoof * (xi + b1 * Math.sin(2 * xi) + b2 * Math.sin(4 * xi) + b3 * Math.sin(6 * xi) + b4 * Math.sin(8 * xi));
        }

        @Override
        public void toLonLat(double x, double y, double[] out) {
            double xi = ((y * unit) - fn + northingOfOrigin) / (k0 * aRoof);
            double eta = ((x * unit) - fe) / (k0 * aRoof);
            double xiP = xi - d1 * Math.sin(2 * xi) * Math.cosh(2 * eta) - d2 * Math.sin(4 * xi) * Math.cosh(4 * eta)
                    - d3 * Math.sin(6 * xi) * Math.cosh(6 * eta) - d4 * Math.sin(8 * xi) * Math.cosh(8 * eta);
            double etaP = eta - d1 * Math.cos(2 * xi) * Math.sinh(2 * eta) - d2 * Math.cos(4 * xi) * Math.sinh(4 * eta)
                    - d3 * Math.cos(6 * xi) * Math.sinh(6 * eta) - d4 * Math.cos(8 * xi) * Math.sinh(8 * eta);
            double latStar = Math.asin(Math.sin(xiP) / Math.cosh(etaP));
            double dLon = Math.atan(Math.sinh(etaP) / Math.cos(xiP));
            double s = Math.sin(latStar), s2 = s * s;
            double lat = latStar + s * Math.cos(latStar) * (as + bs * s2 + cs * s2 * s2 + ds * s2 * s2 * s2);
            out[0] = Math.toDegrees(lon0 + dLon);
            out[1] = Math.toDegrees(lat);
        }

        /** The other way (for tests): longitude/latitude in degrees to {easting, northing} in the file's unit. */
        public double[] fromLonLat(double lonDeg, double latDeg) {
            double lat = Math.toRadians(latDeg), dLon = Math.toRadians(lonDeg) - lon0;
            double s = Math.sin(lat), s2 = s * s;
            double latStar = lat - s * Math.cos(lat) * (af + bf * s2 + cf * s2 * s2 + df * s2 * s2 * s2);
            double xiP = Math.atan(Math.tan(latStar) / Math.cos(dLon));
            double etaP = atanh(Math.cos(latStar) * Math.sin(dLon));
            double n = k0 * aRoof * (xiP + b1 * Math.sin(2 * xiP) * Math.cosh(2 * etaP) + b2 * Math.sin(4 * xiP) * Math.cosh(4 * etaP)
                    + b3 * Math.sin(6 * xiP) * Math.cosh(6 * etaP) + b4 * Math.sin(8 * xiP) * Math.cosh(8 * etaP)) + fn - northingOfOrigin;
            double e = k0 * aRoof * (etaP + b1 * Math.cos(2 * xiP) * Math.sinh(2 * etaP) + b2 * Math.cos(4 * xiP) * Math.sinh(4 * etaP)
                    + b3 * Math.cos(6 * xiP) * Math.sinh(6 * etaP) + b4 * Math.cos(8 * xiP) * Math.sinh(8 * etaP)) + fe;
            return new double[] {e / unit, n / unit};
        }

        private static double atanh(double v) {
            return 0.5 * Math.log((1 + v) / (1 - v));
        }
    }

    // ------------------------------------------------------------------ Web Mercator

    /** Spherical Mercator on the semi-major axis (EPSG:3857 and its ESRI names). */
    public static final class WebMercator extends Projection {
        private final double r;
        private final double lon0;
        private final double fe;
        private final double fn;
        private final double unit;

        WebMercator(String description, String warning, double r, double lon0, double fe, double fn, double unit) {
            super(description, warning);
            this.r = r;
            this.lon0 = lon0;
            this.fe = fe;
            this.fn = fn;
            this.unit = unit;
        }

        @Override
        public void toLonLat(double x, double y, double[] out) {
            out[0] = lon0 + Math.toDegrees((x * unit - fe) / r);
            out[1] = Math.toDegrees(Math.atan(Math.sinh((y * unit - fn) / r)));
        }
    }
}
