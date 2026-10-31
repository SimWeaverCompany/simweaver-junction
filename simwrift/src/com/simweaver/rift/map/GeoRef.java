package com.simweaver.rift.map;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a geographic (latitude/longitude, plate carree) raster lies: the outer edge of its top-left
 * pixel and the pixel size in degrees. From, in this order:
 * <ol>
 *   <li>a world file next to the image ({@code .tfw}, {@code .tifw}, {@code .wld}: six lines, pixel
 *       size x, two rotation terms, pixel size y (negative), centre of the top-left pixel x, y);</li>
 *   <li>the GeoTIFF tags ModelPixelScale + ModelTiepoint, or ModelTransformation;</li>
 *   <li>otherwise the whole globe, 180 W to 180 E and 90 N to 90 S (as the Natural Earth rasters).</li>
 * </ol>
 * Projected rasters (UTM, Mercator ...) are not reprojected: they are reported and placed as if
 * they covered the whole globe.
 */
final class GeoRef {

    final double west, north;          // outer edge of pixel (0, 0), degrees
    final double dLon, dLat;           // pixel size, degrees, both positive
    final String source;               // where it came from, for the log

    GeoRef(double west, double north, double dLon, double dLat, String source) {
        this.west = west;
        this.north = north;
        this.dLon = dLon;
        this.dLat = dLat;
        this.source = source;
    }

    double east(int width) {
        return west + width * dLon;
    }

    double south(int height) {
        return north - height * dLat;
    }

    /** Continuous column: 0 at the west edge of pixel 0, 1 at its east edge. */
    double col(double lon) {
        return (lon - west) / dLon;
    }

    /** Continuous row: 0 at the north edge of row 0. */
    double row(double lat) {
        return (north - lat) / dLat;
    }

    static GeoRef of(File image, TiffFile tiff, int width, int height, List<String> warnings) {
        GeoRef w = worldFile(image, warnings);
        if (w != null) {
            return w;
        }
        if (tiff != null) {
            int model = tiff.geoKey(1024);                   // GTModelTypeGeoKey: 1 projected, 2 geographic
            if (model == 1) {
                warnings.add(image.getName() + " is in a projected coordinate system; only latitude/longitude rasters are placed correctly");
            }
            boolean pixelIsPoint = tiff.geoKey(1025) == 2;    // GTRasterTypeGeoKey
            double[] scale = tiff.doubles(TiffFile.MODEL_PIXEL_SCALE);
            double[] tie = tiff.doubles(TiffFile.MODEL_TIEPOINT);
            if (scale != null && scale.length >= 2 && tie != null && tie.length >= 6 && scale[0] > 0 && scale[1] > 0) {
                double west = tie[3] - tie[0] * scale[0];
                double north = tie[4] + tie[1] * scale[1];
                if (pixelIsPoint) {
                    west -= scale[0] / 2;
                    north += scale[1] / 2;
                }
                return check(new GeoRef(west, north, scale[0], scale[1], "GeoTIFF tags"), width, height, image, warnings);
            }
            double[] m = tiff.doubles(TiffFile.MODEL_TRANSFORMATION);
            if (m != null && m.length >= 16 && m[0] > 0 && m[5] < 0) {
                if (m[1] != 0 || m[4] != 0) {
                    warnings.add(image.getName() + ": a rotated raster; the rotation is ignored");
                }
                return check(new GeoRef(m[3], m[7], m[0], -m[5], "GeoTIFF transformation"), width, height, image, warnings);
            }
        }
        if (Math.abs(width - 2.0 * height) > 2) {
            warnings.add(image.getName() + " has no georeferencing and is not 2:1; it is stretched over the whole globe");
        }
        return new GeoRef(-180, 90, 360.0 / width, 180.0 / height, "whole globe (no georeferencing found)");
    }

    private static GeoRef worldFile(File image, List<String> warnings) {
        String base = image.getName().replaceFirst("\\.[^.]+$", "");
        for (String ext : new String[] {".tfw", ".tifw", ".tiffw", ".wld", ".TFW", ".WLD"}) {
            File f = new File(image.getParentFile(), base + ext);
            if (!f.isFile()) {
                continue;
            }
            try {
                List<Double> v = new ArrayList<>();
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1)) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        v.add(Double.parseDouble(t.replace(',', '.')));
                    }
                }
                if (v.size() < 6 || v.get(0) <= 0 || v.get(3) >= 0) {
                    warnings.add(f.getName() + ": not a north-up world file (6 lines, negative y pixel size); ignored");
                    return null;
                }
                if (v.get(1) != 0 || v.get(2) != 0) {
                    warnings.add(f.getName() + ": the rotation terms are ignored");
                }
                double dx = v.get(0), dy = -v.get(3);
                return new GeoRef(v.get(4) - dx / 2, v.get(5) + dy / 2, dx, dy, "world file " + f.getName());
            } catch (IOException | NumberFormatException e) {
                warnings.add(f.getName() + ": cannot be read (" + e.getMessage() + "); ignored");
                return null;
            }
        }
        return null;
    }

    private static GeoRef check(GeoRef g, int width, int height, File image, List<String> warnings) {
        if (g.west < -180.5 || g.east(width) > 180.5 || g.north > 90.5 || g.south(height) < -90.5) {
            warnings.add(image.getName() + ": the georeferencing is outside -180..180 / -90..90 (" + g + "); not degrees?");
        }
        return g;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.4f W..E, %.4f N, pixel %.6f x %.6f deg", west, north, dLon, dLat);
    }
}
