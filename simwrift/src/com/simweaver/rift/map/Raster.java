package com.simweaver.rift.map;

import java.io.Closeable;
import java.io.IOException;

/**
 * A raster image with random row access, read as 8-bit RGB. Implementations are thread safe.
 */
interface Raster extends Closeable {

    int width();

    int height();

    /**
     * Reads part of one row as RGB triplets.
     *
     * @param row   0 = top
     * @param col0  first column
     * @param count number of pixels
     * @param rgb   receives {@code 3 * count} bytes from index 0
     */
    void readRgb(int row, int col0, int count, byte[] rgb) throws IOException;

    /** One line for the log, e.g. "uncompressed TIFF, 3 x 8 bit, strips of 1 row". */
    String describe();
}
