package com.simweaver.common.hla.decoders;

import com.simweaver.common.iem.DataType;
import com.simweaver.common.iem.InformationExchangeModel;
import com.simweaver.common.iem.MemberOnClass;

/**
 * Cursor over the octets of one top-level value, handed to {@link EncodingHandler}s and (as
 * {@link FormatContext}) to {@link ValueFormatter}s. One instance per decode call; not thread
 * safe and must not be kept after the handler returns.
 * <p>
 * Positions are absolute indexes into {@link #getBuffer()}; alignment is relative to
 * {@link #getBase()}, the first octet of the top-level value (IEEE 1516.2 octet boundaries).
 * Reads never go past {@link #getLimit()}; trying to throws {@link HlaDecodeException}.
 */
public final class DecodeContext implements FormatContext {

    private final HlaDataDecoder decoder;
    private final byte[] buffer;
    private final int base;
    private final MemberOnClass<?, ?> member;
    private final long receivedMillis;
    private int pos;
    private int limit;
    int depth = -1;
    private int countHint = -1;

    DecodeContext(HlaDataDecoder decoder, byte[] buffer, int offset, int length,
                  MemberOnClass<?, ?> member, long receivedMillis) {
        this.decoder = decoder;
        this.buffer = buffer;
        this.base = offset;
        this.pos = offset;
        this.limit = offset + length;
        this.member = member;
        this.receivedMillis = receivedMillis;
    }

    /** @return the whole buffer (do not modify) */
    public byte[] getBuffer() {
        return buffer;
    }

    /** @return index of the first octet of the top-level value; alignment origin */
    public int getBase() {
        return base;
    }

    /** @return current read index */
    public int getPosition() {
        return pos;
    }

    /** @return index one past the last readable octet */
    public int getLimit() {
        return limit;
    }

    /** @return octets left before the limit */
    public int getRemaining() {
        return limit - pos;
    }

    /** @return offset of the current position from the base (for alignment arithmetic) */
    public int getRelativePosition() {
        return pos - base;
    }

    /**
     * Narrows (or restores) the readable range, e.g. to the length of an extendable variant's
     * alternative.
     *
     * @param newLimit new limit, between the position and the outer limit
     * @return the previous limit, to pass back later
     */
    public int setLimit(int newLimit) {
        if (newLimit < pos || newLimit > buffer.length) {
            throw error("invalid limit " + newLimit + " (position " + pos + ")");
        }
        int old = limit;
        limit = newLimit;
        return old;
    }

    /**
     * Moves the position to an absolute index within the limit (used to skip or to retry).
     *
     * @param newPos index between base and limit
     */
    public void seek(int newPos) {
        if (newPos < base || newPos > limit) {
            throw error("seek to " + newPos + " outside [" + base + ", " + limit + "]");
        }
        pos = newPos;
    }

    /**
     * Skips padding up to the next multiple of {@code boundary} (relative to the base).
     *
     * @param boundary octet boundary
     * @throws HlaDecodeException if the padding would pass the limit
     */
    public void align(int boundary) {
        if (boundary <= 1) {
            return;
        }
        int rel = pos - base;
        int pad = (boundary - rel % boundary) % boundary;
        if (pad > limit - pos) {
            throw error("data ends inside alignment padding (" + pad + " octets to a " + boundary
                + "-octet boundary, " + (limit - pos) + " left)");
        }
        pos += pad;
    }

    /**
     * Number of padding octets {@link #align(int)} would skip.
     *
     * @param boundary octet boundary
     * @return padding octets
     */
    public int paddingTo(int boundary) {
        if (boundary <= 1) {
            return 0;
        }
        int rel = pos - base;
        return (boundary - rel % boundary) % boundary;
    }

    /**
     * @param n octets to skip
     * @throws HlaDecodeException if fewer remain
     */
    public void skip(int n) {
        require(n);
        pos += n;
    }

    /**
     * @param n octets that must remain
     * @throws HlaDecodeException if fewer remain
     */
    public void require(int n) {
        if (n < 0 || n > limit - pos) {
            throw error("data too short: need " + n + " octets at offset " + (pos - base) + ", " + (limit - pos) + " left");
        }
    }

    /**
     * Reads an unsigned integer of 1..8 octets (no alignment).
     *
     * @param octets    size
     * @param bigEndian byte order
     * @return the value (64-bit values may be negative as signed long)
     */
    public long readUnsigned(int octets, boolean bigEndian) {
        require(octets);
        long v = 0;
        if (bigEndian) {
            for (int i = 0; i < octets; i++) {
                v = (v << 8) | (buffer[pos + i] & 0xffL);
            }
        } else {
            for (int i = octets - 1; i >= 0; i--) {
                v = (v << 8) | (buffer[pos + i] & 0xffL);
            }
        }
        pos += octets;
        return v;
    }

    /**
     * Reads a two's complement integer of 1..8 octets (no alignment).
     *
     * @param octets    size
     * @param bigEndian byte order
     * @return the sign extended value
     */
    public long readSigned(int octets, boolean bigEndian) {
        long v = readUnsigned(octets, bigEndian);
        int shift = 64 - 8 * octets;
        return shift == 0 ? v : (v << shift) >> shift;
    }

    /**
     * Aligns to 4 and reads an HLAinteger32BE (array counts, lengths).
     *
     * @return the value
     */
    public int readInt32BE() {
        align(4);
        return (int) readSigned(4, true);
    }

    /**
     * Decodes a nested value: alignment, encoding, formatters, depth limit.
     *
     * @param type datatype (null decodes nothing and gives an error node)
     * @param name node name
     * @return the node
     * @throws HlaDecodeException with the partial node when decoding fails
     */
    public DecodedValue decode(DataType type, String name) {
        return decoder.decodeNode(this, type, name);
    }

    /**
     * One-shot element count for the next length-less array decoded in this context (set by
     * the record handler from a count rule).
     *
     * @param count element count, or -1 for none
     */
    public void setCountHint(int count) {
        this.countHint = count;
    }

    /** @return the pending count hint and clears it; -1 when none */
    public int takeCountHint() {
        int c = countHint;
        countHint = -1;
        return c;
    }

    /**
     * Creates the exception to throw for undecodable data (no partial node).
     *
     * @param message what is wrong
     * @return the exception
     */
    public HlaDecodeException error(String message) {
        return new HlaDecodeException(message, null);
    }

    /** @return layouts of the model */
    public TypeLayouts getLayouts() {
        return decoder.getLayouts();
    }

    /** @return the decoder */
    public HlaDataDecoder getDecoder() {
        return decoder;
    }

    @Override
    public InformationExchangeModel getModel() {
        return decoder.getModel();
    }

    @Override
    public DecoderOptions getOptions() {
        return decoder.getOptions();
    }

    @Override
    public MemberOnClass<?, ?> getMember() {
        return member;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public long getReceivedMillis() {
        return receivedMillis;
    }

    @Override
    public long getNowMillis() {
        return decoder.getOptions().getClock().currentTimeMillis();
    }
}
