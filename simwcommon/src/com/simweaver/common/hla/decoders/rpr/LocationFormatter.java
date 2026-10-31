package com.simweaver.common.hla.decoders.rpr;

import com.simweaver.common.geo.Ellipsoid;
import com.simweaver.common.geo.Geodetic;
import com.simweaver.common.hla.decoders.DecodedValue;
import com.simweaver.common.hla.decoders.FormatContext;
import com.simweaver.common.hla.decoders.ValueFormatter;
import com.simweaver.common.iem.DataType;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

/**
 * {@code WorldLocationStruct} (RPR v2) and {@code LocationStruct} (RPR v3): WGS84 ECEF X/Y/Z,
 * shown as latitude, longitude and height above the ellipsoid, with derived children
 * {@code Latitude}, {@code Longitude} (degrees) and {@code Altitude} (metres, HAE). X, Y and Z
 * stay as the first children. The {@link Geodetic} is attached.
 * <p>
 * Not converted: values inside {@code RelativeSpatial} (host body frame, RPR v3) and points
 * closer than 100 km to the Earth's centre, which are offsets rather than world positions
 * (RPR v3 {@code LocationStruct} is world coordinates "unless specified otherwise").
 */
final class LocationFormatter implements ValueFormatter {

    /** Positions closer to the Earth's centre than this are not treated as world positions. */
    static final double MIN_WORLD_RADIUS = 100_000.0;

    @Override
    public Collection<String> getDataTypeNames() {
        return Arrays.asList("WorldLocationStruct", "LocationStruct");
    }

    @Override
    public boolean accepts(DataType type) {
        return Shapes.isVector64(type);
    }

    @Override
    public DecodedValue format(DecodedValue value, FormatContext ctx) {
        if (value.hasError() || value.getChildren().size() < 3 || SpatialFormatter.isRelative(ctx)) {
            return value;
        }
        double x = Shapes.num(value, 0);
        double y = Shapes.num(value, 1);
        double z = Shapes.num(value, 2);
        if (!(Math.sqrt(x * x + y * y + z * z) >= MIN_WORLD_RADIUS) || Double.isInfinite(x + y + z)) {
            return value;
        }
        Geodetic g = Ellipsoid.WGS84.toGeodetic(x, y, z);
        return value.toBuilder()
            .text(String.format(Locale.ROOT, "%.6f %.6f %.1f m", g.getLatitude(), g.getLongitude(), g.getHeight()))
            .child(Shapes.derived("Latitude", g.getLatitude(), 7, "deg"))
            .child(Shapes.derived("Longitude", g.getLongitude(), 7, "deg"))
            .child(Shapes.derived("Altitude", g.getHeight(), 2, "m"))
            .attachment(g)
            .build();
    }
}
