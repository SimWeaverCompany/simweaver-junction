package com.simweaver.common.audio;

/**
 * The audio conversions the Talk radio bridge needs, without libraries: 16-bit PCM in either
 * byte order, G.711 mu-law, linear resampling, and the sample count of an Opus packet (from its
 * TOC byte, RFC 6716 §3.1; the audio itself is passed through, not decoded).
 *
 * <p>0.12.0: moved here from Junction's Talk router, so Rift's Comms view uses the same code.
 */
public final class Pcm {

    private Pcm() {
    }

    /** 16-bit samples from octets in the given byte order (an odd last octet is ignored). */
    public static short[] samples(byte[] b, boolean littleEndian) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            int lo = b[2 * i + (littleEndian ? 0 : 1)] & 0xFF;
            int hi = b[2 * i + (littleEndian ? 1 : 0)];
            s[i] = (short) ((hi << 8) | lo);
        }
        return s;
    }

    /** Octets of 16-bit samples in the given byte order. */
    public static byte[] octets(short[] s, boolean littleEndian) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            byte lo = (byte) s[i];
            byte hi = (byte) (s[i] >> 8);
            b[2 * i] = littleEndian ? lo : hi;
            b[2 * i + 1] = littleEndian ? hi : lo;
        }
        return b;
    }

    /** G.711 mu-law to 16-bit linear. */
    public static short[] muLawDecode(byte[] b) {
        short[] s = new short[b.length];
        for (int i = 0; i < b.length; i++) {
            int u = ~b[i] & 0xFF;
            int t = ((u & 0x0F) << 3) + 0x84;
            t <<= (u & 0x70) >> 4;
            s[i] = (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
        }
        return s;
    }

    /** 16-bit linear to G.711 mu-law. */
    public static byte[] muLawEncode(short[] s) {
        byte[] b = new byte[s.length];
        for (int i = 0; i < s.length; i++) {
            int pcm = s[i];
            int sign = (pcm >> 8) & 0x80;
            if (sign != 0) {
                pcm = -pcm;
            }
            if (pcm > 32635) {
                pcm = 32635;
            }
            pcm += 0x84;
            int exponent = 7;
            for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) {
                exponent--;
            }
            int mantissa = (pcm >> (exponent + 3)) & 0x0F;
            b[i] = (byte) ~(sign | (exponent << 4) | mantissa);
        }
        return b;
    }

    /** Linear interpolation from one sample rate to another (the same array when they are equal). */
    public static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || in.length == 0) {
            return in;
        }
        int n = (int) ((long) in.length * toRate / fromRate);
        short[] out = new short[Math.max(1, n)];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < out.length; i++) {
            double pos = i * step;
            int k = (int) pos;
            double f = pos - k;
            int a = in[Math.min(k, in.length - 1)];
            int b = in[Math.min(k + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * f);
        }
        return out;
    }

    /** Opus frame durations in 48 kHz samples, by TOC configuration number. */
    private static final int[] OPUS_FRAME = {
        480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880,   // SILK NB, MB, WB
        480, 960, 480, 960,                                                  // hybrid SWB, FB
        120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960  // CELT
    };

    /** @return the number of 48 kHz samples in an Opus packet, or 0 when it is not one */
    public static int opusSamples(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return 0;
        }
        int toc = packet[0] & 0xFF;
        int perFrame = OPUS_FRAME[toc >> 3];
        int frames;
        switch (toc & 0x3) {
            case 0:
                frames = 1;
                break;
            case 1:
            case 2:
                frames = 2;
                break;
            default:
                frames = packet.length > 1 ? packet[1] & 0x3F : 0;
        }
        return perFrame * frames;
    }
}
