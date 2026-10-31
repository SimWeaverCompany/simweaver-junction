package com.simweaver.rift.map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One background map: a geographic raster cut into Web Mercator tiles (EPSG:3857, 256 pixels,
 * {z}/{x}/{y} as OpenStreetMap). Each tile pixel is the average of up to 4 x 4 bilinear samples of
 * the raster, so zoomed-out tiles are smooth; the raster rows are read as needed, a few at a time.
 */
final class RasterMap {

    static final int TILE = 256;
    static final double MAX_LAT = 85.0511287798066;

    final String id;
    final String name;
    final File file;
    final Raster raster;
    final GeoRef geo;
    final int maxZoom;
    final List<String> warnings;

    RasterMap(String id, String name, File file, Raster raster, GeoRef geo, int maxZoomSetting, List<String> warnings) {
        this.id = id;
        this.name = name;
        this.file = file;
        this.raster = raster;
        this.geo = geo;
        this.warnings = warnings;
        // the zoom where a tile pixel is about one raster pixel (Natural Earth 10 m: 6)
        double rasterWorldPx = 360.0 / geo.dLon;
        int nativeZoom = (int) Math.round(Math.log(rasterWorldPx / TILE) / Math.log(2));
        this.maxZoom = Math.max(0, Math.min(14, maxZoomSetting > 0 ? maxZoomSetting : nativeZoom));
    }

    static RasterMap open(String id, String name, File file, int maxZoomSetting) throws IOException {
        List<String> warnings = new ArrayList<>();
        TiffFile tiff = null;
        Raster raster = null;
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            tiff = TiffFile.read(file.toPath());
            raster = UncompressedTiffRaster.open(tiff);
        }
        if (raster == null) {
            raster = ImageIoRaster.open(file);
        }
        GeoRef geo = GeoRef.of(file, tiff, raster.width(), raster.height(), warnings);
        return new RasterMap(id, name, file, raster, geo, maxZoomSetting, warnings);
    }

    /** West, south, east, north in degrees. */
    double[] bounds() {
        return new double[] {geo.west, geo.south(raster.height()), geo.east(raster.width()), geo.north};
    }

    /** Latitude of a Web Mercator y (0 = top, 1 = bottom of the world), degrees. */
    static double lat(double t) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * t))));
    }

    /**
     * Renders one tile.
     *
     * @param background RGB for places the raster does not cover
     * @return 256 x 256 RGB pixels
     */
    int[] render(int z, int x, int y, int background) throws IOException {
        int n = 1 << z;
        int w = raster.width(), h = raster.height();
        double worldPx = (double) TILE * n;
        double degPerOut = 360.0 / worldPx;
        double lonLeft = x * TILE * degPerOut - 180;
        double colsPerOut = degPerOut / geo.dLon;
        int sx = Math.max(1, Math.min(4, (int) Math.ceil(colsPerOut)));

        int[] out = new int[TILE * TILE];
        java.util.Arrays.fill(out, background);
        int c0 = (int) Math.floor(geo.col(lonLeft) - 1);
        int c1 = (int) Math.ceil(geo.col(lonLeft + TILE * degPerOut) + 1);
        c0 = Math.max(0, c0);
        c1 = Math.min(w - 1, c1);
        if (c1 < c0) {
            return out;                                            // west or east of the raster
        }
        int span = c1 - c0 + 1;
        Rows rows = new Rows(span, c0);

        double[] u = new double[TILE * sx];                         // raster column of each sample
        for (int i = 0; i < TILE; i++) {
            for (int s = 0; s < sx; s++) {
                u[i * sx + s] = geo.col(lonLeft + (i + (s + 0.5) / sx) * degPerOut) - 0.5;
            }
        }
        double[] v = new double[4];
        for (int j = 0; j < TILE; j++) {
            double tTop = (y * TILE + j) / worldPx, tBottom = (y * TILE + j + 1) / worldPx;
            double rowsPerOut = (lat(tTop) - lat(tBottom)) / geo.dLat;
            int sy = Math.max(1, Math.min(4, (int) Math.ceil(rowsPerOut)));
            boolean any = false;
            for (int s = 0; s < sy; s++) {
                v[s] = geo.row(lat((y * TILE + j + (s + 0.5) / sy) / worldPx)) - 0.5;
                if (v[s] > -1 && v[s] < h) {
                    any = true;
                }
            }
            if (!any) {
                continue;                                          // north or south of the raster
            }
            for (int i = 0; i < TILE; i++) {
                int r = 0, g = 0, b = 0, cnt = 0;
                for (int ty = 0; ty < sy; ty++) {
                    double vy = v[ty];
                    if (vy <= -1 || vy >= h) {
                        continue;
                    }
                    int r0 = (int) Math.floor(vy);
                    double fy = vy - r0;
                    int ra = clamp(r0, h), rb = clamp(r0 + 1, h);
                    byte[] rowA = rows.get(ra), rowB = rb == ra ? rowA : rows.get(rb);
                    for (int tx = 0; tx < sx; tx++) {
                        double ux = u[i * sx + tx];
                        if (ux <= -1 || ux >= w) {
                            continue;
                        }
                        int q0 = (int) Math.floor(ux);
                        double fx = ux - q0;
                        int qa = clamp(q0, w) - c0, qb = clamp(q0 + 1, w) - c0;
                        if (qa < 0 || qb >= span) {
                            continue;
                        }
                        for (int ch = 0; ch < 3; ch++) {
                            double top = (rowA[qa * 3 + ch] & 0xFF) * (1 - fx) + (rowA[qb * 3 + ch] & 0xFF) * fx;
                            double bot = (rowB[qa * 3 + ch] & 0xFF) * (1 - fx) + (rowB[qb * 3 + ch] & 0xFF) * fx;
                            int val = (int) Math.round(top * (1 - fy) + bot * fy);
                            if (ch == 0) {
                                r += val;
                            } else if (ch == 1) {
                                g += val;
                            } else {
                                b += val;
                            }
                        }
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    out[j * TILE + i] = ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
                }
            }
        }
        return out;
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : v >= n ? n - 1 : v;
    }

    /** The few raster rows a tile needs at a time (each output row reuses the previous rows). */
    private final class Rows {
        private final int span, c0;
        private final Map<Integer, byte[]> kept = new LinkedHashMap<Integer, byte[]>(32, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) {
                return size() > 16;
            }
        };

        Rows(int span, int c0) {
            this.span = span;
            this.c0 = c0;
        }

        byte[] get(int row) throws IOException {
            byte[] b = kept.get(row);
            if (b == null) {
                b = new byte[span * 3];
                raster.readRgb(row, c0, span, b);
                kept.put(row, b);
            }
            return b;
        }
    }

    /** Encodes tile pixels as JPEG. */
    static byte[] jpeg(int[] px, float quality) throws IOException {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, TILE, TILE, px, 0, TILE);
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter w = it.next();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bo)) {
            w.setOutput(out);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bo.toByteArray();
    }
}
