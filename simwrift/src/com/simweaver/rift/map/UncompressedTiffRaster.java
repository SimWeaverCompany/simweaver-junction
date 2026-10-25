package com.simweaver.rift.map;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Uncompressed 8-bit TIFF (strips or tiles; RGB, RGBA, grey or palette; chunky or planar), read
 * straight from the file: a row segment is one positional read per strip or tile it crosses, so
 * any part of a large raster is fast to reach and nothing is held in memory.
 */
final class UncompressedTiffRaster implements Raster {

    private final FileChannel ch;
    private final int width, height, spp, photometric;
    private final boolean planar;
    private final boolean tiled;
    private final int rowsPerStrip, tileWidth, tileLength, tilesAcross, tilesDown;
    private final long[] offsets;
    private final byte[][] palette;      // [3][256] for palette images

    /** @return the raster, or null when the file is not an uncompressed 8-bit TIFF */
    static UncompressedTiffRaster open(TiffFile t) throws IOException {
        if (t.first(TiffFile.COMPRESSION, 1) != 1) {
            return null;
        }
        long[] bits = t.longs(TiffFile.BITS_PER_SAMPLE);
        if (bits != null) {
            for (long b : bits) {
                if (b != 8) {
                    return null;
                }
            }
        }
        if (t.first(TiffFile.SAMPLE_FORMAT, 1) != 1) {
            return null;
        }
        int photometric = (int) t.first(TiffFile.PHOTOMETRIC, 2);
        if (photometric != 0 && photometric != 1 && photometric != 2 && photometric != 3) {
            return null;                                  // e.g. YCbCr, CMYK: left to ImageIO
        }
        return new UncompressedTiffRaster(t, photometric);
    }

    private UncompressedTiffRaster(TiffFile t, int photometric) throws IOException {
        this.width = (int) t.first(TiffFile.IMAGE_WIDTH, 0);
        this.height = (int) t.first(TiffFile.IMAGE_LENGTH, 0);
        this.spp = (int) t.first(TiffFile.SAMPLES_PER_PIXEL, 1);
        this.photometric = photometric;
        this.planar = t.first(TiffFile.PLANAR_CONFIGURATION, 1) == 2 && spp > 1;
        this.tiled = t.has(TiffFile.TILE_OFFSETS);
        if (width <= 0 || height <= 0) {
            throw new IOException("TIFF without size");
        }
        if (tiled) {
            tileWidth = (int) t.first(TiffFile.TILE_WIDTH, 0);
            tileLength = (int) t.first(TiffFile.TILE_LENGTH, 0);
            if (tileWidth <= 0 || tileLength <= 0) {
                throw new IOException("TIFF tiles without size");
            }
            tilesAcross = (width + tileWidth - 1) / tileWidth;
            tilesDown = (height + tileLength - 1) / tileLength;
            offsets = t.longs(TiffFile.TILE_OFFSETS);
            rowsPerStrip = 0;
        } else {
            rowsPerStrip = (int) Math.min(height, t.first(TiffFile.ROWS_PER_STRIP, height));
            offsets = t.longs(TiffFile.STRIP_OFFSETS);
            tileWidth = tileLength = tilesAcross = tilesDown = 0;
        }
        if (offsets == null || offsets.length == 0) {
            throw new IOException("TIFF without strip or tile offsets");
        }
        if (photometric == 3) {
            long[] cm = t.longs(TiffFile.COLOR_MAP);
            if (cm == null || cm.length < 3 * 256) {
                throw new IOException("palette TIFF without a colour map");
            }
            palette = new byte[3][256];
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < 256; i++) {
                    palette[c][i] = (byte) (cm[c * 256 + i] >> 8);
                }
            }
        } else {
            palette = null;
        }
        this.ch = FileChannel.open(t.path, StandardOpenOption.READ);
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
        String kind = photometric == 2 ? spp + " x 8 bit RGB" + (spp > 3 ? "+" : "") : photometric == 3 ? "8 bit palette" : "8 bit grey";
        return "uncompressed TIFF, " + kind + (planar ? ", planar" : "")
                + (tiled ? ", tiles of " + tileWidth + " x " + tileLength : ", strips of " + rowsPerStrip + " row" + (rowsPerStrip == 1 ? "" : "s"));
    }

    @Override
    public void readRgb(int row, int col0, int count, byte[] rgb) throws IOException {
        if (row < 0 || row >= height || col0 < 0 || count <= 0 || col0 + count > width) {
            throw new IOException("outside the raster: row " + row + ", columns " + col0 + "+" + count);
        }
        int planes = planar ? spp : 1;
        int pixelBytes = planar ? 1 : spp;
        byte[][] raw = new byte[planes][];
        for (int p = 0; p < planes; p++) {
            raw[p] = new byte[count * pixelBytes];
            readSegment(p, row, col0, count, pixelBytes, raw[p]);
        }
        for (int i = 0; i < count; i++) {
            int r, g, b;
            if (palette != null) {
                int v = raw[0][i] & 0xFF;
                r = palette[0][v] & 0xFF;
                g = palette[1][v] & 0xFF;
                b = palette[2][v] & 0xFF;
            } else if (photometric == 2 && spp >= 3) {
                if (planar) {
                    r = raw[0][i] & 0xFF;
                    g = raw[1][i] & 0xFF;
                    b = raw[2][i] & 0xFF;
                } else {
                    r = raw[0][i * spp] & 0xFF;
                    g = raw[0][i * spp + 1] & 0xFF;
                    b = raw[0][i * spp + 2] & 0xFF;
                }
            } else {
                int v = raw[0][i * pixelBytes] & 0xFF;
                if (photometric == 0) {
                    v = 255 - v;                          // WhiteIsZero
                }
                r = g = b = v;
            }
            rgb[i * 3] = (byte) r;
            rgb[i * 3 + 1] = (byte) g;
            rgb[i * 3 + 2] = (byte) b;
        }
    }

    /** Reads {@code count} pixels of one plane (or of all samples, chunky) into {@code out}. */
    private void readSegment(int plane, int row, int col0, int count, int pixelBytes, byte[] out) throws IOException {
        if (!tiled) {
            int strip = row / rowsPerStrip;
            int stripsPerPlane = (height + rowsPerStrip - 1) / rowsPerStrip;
            long base = offsets[Math.min(offsets.length - 1, plane * stripsPerPlane + strip)];
            long pos = base + (long) (row % rowsPerStrip) * width * pixelBytes + (long) col0 * pixelBytes;
            read(pos, out, 0, count * pixelBytes);
            return;
        }
        int tr = row / tileLength;
        int done = 0;
        while (done < count) {
            int col = col0 + done;
            int tc = col / tileWidth;
            int inTile = col % tileWidth;
            int n = Math.min(count - done, tileWidth - inTile);
            int index = plane * tilesAcross * tilesDown + tr * tilesAcross + tc;
            long pos = offsets[Math.min(offsets.length - 1, index)]
                    + ((long) (row % tileLength) * tileWidth + inTile) * pixelBytes;
            read(pos, out, done * pixelBytes, n * pixelBytes);
            done += n;
        }
    }

    private void read(long pos, byte[] out, int off, int len) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(out, off, len);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + (b.position() - off));
            if (r < 0) {
                throw new IOException("unexpected end of the TIFF file");
            }
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
