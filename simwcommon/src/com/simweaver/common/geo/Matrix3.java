package com.simweaver.common.geo;

/**
 * Immutable 3x3 matrix of doubles, row major ({@code mRC} = row R, column C).
 * <p>
 * Mostly used as a rotation (direction cosine) matrix. Naming convention in this package:
 * {@code A_TO_B} transforms the coordinates of a vector given in frame A into frame B,
 * so {@code vB = A_TO_B.transform(vA)}. The inverse of a rotation is its transpose.
 */
public final class Matrix3 {

    /** The identity matrix. */
    public static final Matrix3 IDENTITY = new Matrix3(1, 0, 0, 0, 1, 0, 0, 0, 1);

    final double m00;
    final double m01;
    final double m02;
    final double m10;
    final double m11;
    final double m12;
    final double m20;
    final double m21;
    final double m22;

    /**
     * Creates a matrix from its elements in row major order.
     *
     * @param m00 row 0 column 0
     * @param m01 row 0 column 1
     * @param m02 row 0 column 2
     * @param m10 row 1 column 0
     * @param m11 row 1 column 1
     * @param m12 row 1 column 2
     * @param m20 row 2 column 0
     * @param m21 row 2 column 1
     * @param m22 row 2 column 2
     */
    public Matrix3(double m00, double m01, double m02,
                   double m10, double m11, double m12,
                   double m20, double m21, double m22) {
        this.m00 = m00;
        this.m01 = m01;
        this.m02 = m02;
        this.m10 = m10;
        this.m11 = m11;
        this.m12 = m12;
        this.m20 = m20;
        this.m21 = m21;
        this.m22 = m22;
    }

    /**
     * Builds a matrix whose rows are the given vectors.
     *
     * @param r0 row 0
     * @param r1 row 1
     * @param r2 row 2
     * @return the matrix
     */
    public static Matrix3 ofRows(Vec3 r0, Vec3 r1, Vec3 r2) {
        return new Matrix3(r0.getX(), r0.getY(), r0.getZ(),
                           r1.getX(), r1.getY(), r1.getZ(),
                           r2.getX(), r2.getY(), r2.getZ());
    }

    /**
     * Builds a matrix whose columns are the given vectors.
     *
     * @param c0 column 0
     * @param c1 column 1
     * @param c2 column 2
     * @return the matrix
     */
    public static Matrix3 ofColumns(Vec3 c0, Vec3 c1, Vec3 c2) {
        return new Matrix3(c0.getX(), c1.getX(), c2.getX(),
                           c0.getY(), c1.getY(), c2.getY(),
                           c0.getZ(), c1.getZ(), c2.getZ());
    }

    /**
     * The skew symmetric cross product matrix {@code [w]x}, so that
     * {@code skew(w).transform(v) == w.cross(v)}.
     *
     * @param w the vector
     * @return the skew matrix
     */
    public static Matrix3 skew(Vec3 w) {
        return new Matrix3(0.0, -w.getZ(), w.getY(),
                           w.getZ(), 0.0, -w.getX(),
                           -w.getY(), w.getX(), 0.0);
    }

    /**
     * The outer product {@code a b^T}.
     *
     * @param a left vector
     * @param b right vector
     * @return the matrix
     */
    public static Matrix3 outer(Vec3 a, Vec3 b) {
        return new Matrix3(a.getX() * b.getX(), a.getX() * b.getY(), a.getX() * b.getZ(),
                           a.getY() * b.getX(), a.getY() * b.getY(), a.getY() * b.getZ(),
                           a.getZ() * b.getX(), a.getZ() * b.getY(), a.getZ() * b.getZ());
    }

    /**
     * @param row 0..2
     * @param col 0..2
     * @return the element
     */
    public double get(int row, int col) {
        switch (row * 3 + col) {
            case 0: return m00;
            case 1: return m01;
            case 2: return m02;
            case 3: return m10;
            case 4: return m11;
            case 5: return m12;
            case 6: return m20;
            case 7: return m21;
            case 8: return m22;
            default: throw new IndexOutOfBoundsException("row " + row + ", col " + col);
        }
    }

    /**
     * @param row 0..2
     * @return the row as a vector
     */
    public Vec3 row(int row) {
        switch (row) {
            case 0: return new Vec3(m00, m01, m02);
            case 1: return new Vec3(m10, m11, m12);
            case 2: return new Vec3(m20, m21, m22);
            default: throw new IndexOutOfBoundsException("row " + row);
        }
    }

    /**
     * @param col 0..2
     * @return the column as a vector
     */
    public Vec3 column(int col) {
        switch (col) {
            case 0: return new Vec3(m00, m10, m20);
            case 1: return new Vec3(m01, m11, m21);
            case 2: return new Vec3(m02, m12, m22);
            default: throw new IndexOutOfBoundsException("col " + col);
        }
    }

    /**
     * @param v vector
     * @return {@code this * v}
     */
    public Vec3 transform(Vec3 v) {
        double x = v.getX();
        double y = v.getY();
        double z = v.getZ();
        return new Vec3(m00 * x + m01 * y + m02 * z,
                        m10 * x + m11 * y + m12 * z,
                        m20 * x + m21 * y + m22 * z);
    }

    /**
     * Applies the transpose without building it; for a rotation this is the inverse transform.
     *
     * @param v vector
     * @return {@code this^T * v}
     */
    public Vec3 transformTransposed(Vec3 v) {
        double x = v.getX();
        double y = v.getY();
        double z = v.getZ();
        return new Vec3(m00 * x + m10 * y + m20 * z,
                        m01 * x + m11 * y + m21 * z,
                        m02 * x + m12 * y + m22 * z);
    }

    /**
     * @param o right hand matrix
     * @return {@code this * o}
     */
    public Matrix3 multiply(Matrix3 o) {
        return new Matrix3(
            m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
            m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
            m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
            m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
            m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
            m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
            m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
            m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
            m20 * o.m02 + m21 * o.m12 + m22 * o.m22);
    }

    /**
     * @param o other matrix
     * @return {@code this + o}
     */
    public Matrix3 plus(Matrix3 o) {
        return new Matrix3(m00 + o.m00, m01 + o.m01, m02 + o.m02,
                           m10 + o.m10, m11 + o.m11, m12 + o.m12,
                           m20 + o.m20, m21 + o.m21, m22 + o.m22);
    }

    /**
     * @param f factor
     * @return {@code this * f}
     */
    public Matrix3 times(double f) {
        return new Matrix3(m00 * f, m01 * f, m02 * f,
                           m10 * f, m11 * f, m12 * f,
                           m20 * f, m21 * f, m22 * f);
    }

    /** @return the transpose */
    public Matrix3 transpose() {
        return new Matrix3(m00, m10, m20,
                           m01, m11, m21,
                           m02, m12, m22);
    }

    /** @return the determinant */
    public double determinant() {
        return m00 * (m11 * m22 - m12 * m21)
             - m01 * (m10 * m22 - m12 * m20)
             + m02 * (m10 * m21 - m11 * m20);
    }

    /**
     * Largest absolute element of {@code this * this^T - I}; zero for an exact rotation.
     *
     * @return the orthonormality error
     */
    public double orthonormalityError() {
        Matrix3 p = multiply(transpose());
        double e = 0.0;
        e = Math.max(e, Math.abs(p.m00 - 1.0));
        e = Math.max(e, Math.abs(p.m11 - 1.0));
        e = Math.max(e, Math.abs(p.m22 - 1.0));
        e = Math.max(e, Math.abs(p.m01));
        e = Math.max(e, Math.abs(p.m02));
        e = Math.max(e, Math.abs(p.m12));
        return e;
    }

    /**
     * Largest absolute element difference.
     *
     * @param o other matrix
     * @return {@code max |this - o|}
     */
    public double maxDifference(Matrix3 o) {
        double e = 0.0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                e = Math.max(e, Math.abs(get(r, c) - o.get(r, c)));
            }
        }
        return e;
    }

    /** @return true when all elements are finite */
    public boolean isFinite() {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double v = get(r, c);
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Matrix3)) {
            return false;
        }
        Matrix3 m = (Matrix3) o;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (Double.compare(get(r, c), m.get(r, c)) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                h = 31 * h + Double.hashCode(get(r, c));
            }
        }
        return h;
    }

    @Override
    public String toString() {
        return "[" + row(0) + ", " + row(1) + ", " + row(2) + "]";
    }
}
