package com.simweaver.rift.map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Any image the JDK's ImageIO reads (compressed TIFF - LZW, Deflate, PackBits, JPEG - and also
 * PNG or JPEG files), read in bands of full-width rows that are kept for reuse. Slower than
 * {@link UncompressedTiffRaster} for large files, but it reads everything ImageIO can.
 */
final class ImageIoRaster implements Raster {

    private static final int BAND_ROWS = 128;
    private static final int BANDS_KEPT = 4;

    private final File file;
    private final ImageReader reader;
    private final ImageInputStream in;
    private final int width, height;
    private final String format;
    private final Map<Integer, int[]> bands = new LinkedHashMap<Integer, int[]>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> e) {
            return size() > BANDS_KEPT;
        }
    };

    static ImageIoRaster open(File file) throws IOException {
        ImageInputStream in = ImageIO.createImageInputStream(file);
        if (in == null) {
            throw new IOException("cannot open " + file);
        }
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        if (!it.hasNext()) {
            in.close();
            throw new IOException("no image reader for " + file.getName() + " (the JDK reads TIFF, PNG, JPEG, GIF, BMP)");
        }
        ImageReader r = it.next();
        r.setInput(in, true, true);
        return new ImageIoRaster(file, r, in);
    }

    private ImageIoRaster(File file, ImageReader reader, ImageInputStream in) throws IOException {
        this.file = file;
        this.reader = reader;
        this.in = in;
        this.width = reader.getWidth(0);
        this.height = reader.getHeight(0);
        this.format = reader.getFormatName();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public String describe() {
        return format + " read with ImageIO in bands of " + BAND_ROWS + " rows (convert to an uncompressed TIFF for speed)";
    }

    @Override
    public synchronized void readRgb(int row, int col0, int count, byte[] rgb) throws IOException {
        if (row < 0 || row >= height || col0 < 0 || count <= 0 || col0 + count > width) {
            throw new IOException("outside the raster: row " + row + ", columns " + col0 + "+" + count);
        }
        int band = row / BAND_ROWS;
        int[] px = bands.get(band);
        if (px == null) {
            int y0 = band * BAND_ROWS;
            int h = Math.min(BAND_ROWS, height - y0);
            ImageReadParam p = reader.getDefaultReadParam();
            p.setSourceRegion(new Rectangle(0, y0, width, h));
            BufferedImage img = reader.read(0, p);
            px = img.getRGB(0, 0, width, h, null, 0, width);
            bands.put(band, px);
        }
        int base = (row % BAND_ROWS) * width + col0;
        for (int i = 0; i < count; i++) {
            int v = px[base + i];
            rgb[i * 3] = (byte) (v >> 16);
            rgb[i * 3 + 1] = (byte) (v >> 8);
            rgb[i * 3 + 2] = (byte) v;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        reader.dispose();
        in.close();
        bands.clear();
    }

    @Override
    public String toString() {
        return file.getName();
    }
}
