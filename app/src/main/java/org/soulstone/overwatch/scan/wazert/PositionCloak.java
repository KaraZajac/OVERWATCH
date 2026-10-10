package org.soulstone.overwatch.scan.wazert;

// OVERWATCH's own code, not vendored. Applied in WazeRtFetcher.

/**
 * The only position OVERWATCH ever tells Waze: the centre of the ~1 km grid
 * square the device is in, never the device's own coordinates.
 *
 * <p><b>Why this exists.</b> The Waze app protocol carries a position in three
 * places: the ClientInfo sent at register and login, a {@code Location,<lon>,<lat>}
 * line in each session's handshake, and the centre of every {@code MapDisplayed}
 * viewport query. The vendored codec jitters only the first, by up to ±500 m per
 * axis. The other two carry whatever they are given verbatim, and v0.5.15 gave them
 * the raw GPS fix — so it sent the exact position to Waze several times a minute
 * while its Settings screen said the position was blurred. Every one of those
 * messages now gets this cell centre instead, so the disclosure is what the copy
 * says: Waze learns which square you are in, and nothing finer, however long you
 * stay in it.
 *
 * <p><b>Why a fixed grid, not random noise.</b> Fresh noise on every poll averages
 * out: a minute-by-minute stream of independently jittered points converges on the
 * real position. Every point inside a square maps to the same centre, so there is
 * nothing to average.
 *
 * <p><b>Why hysteresis.</b> Parked on a cell edge, GPS wander would flip between two
 * cells on every poll, and the flipping itself would place you within metres of
 * that edge. A chosen cell is therefore kept until the device is
 * {@link #HYSTERESIS_M} beyond it.
 *
 * <p>Cells are {@link #CELL_M} square in metres, not in degrees: the longitude step
 * is computed per latitude row, so a cell is about as wide as it is tall at any
 * latitude rather than shrinking toward the poles.
 *
 * <p><b>Detection is unaffected.</b> Alert distances are always measured from the
 * real fix, on the device. This only moves the centre of the area Waze is asked
 * about, and {@link WazeRtFetcher} widens that area by {@link #MAX_OFFSET_M} so the
 * evaluation radius around the real position always stays inside it.
 *
 * <p>Not thread-safe; {@link WazeRtFetcher} calls it from synchronized methods.
 */
final class PositionCloak {

    /** Edge of a grid cell, in metres. */
    static final double CELL_M = 1000.0;

    /** How far past the current cell's edge the device must go before the cell changes. */
    static final double HYSTERESIS_M = 150.0;

    /**
     * Upper bound on the distance between the real position and the centre reported
     * for it: the corner of the current cell grown by the hysteresis margin.
     */
    static final double MAX_OFFSET_M = Math.sqrt(2.0) * (CELL_M / 2.0 + HYSTERESIS_M);

    private static final double LAT_STEP = CELL_M / WazeConstants.M_PER_DEG_LAT;

    private boolean hasCell = false;
    private long row;
    private long col;

    /** {@code {lat, lon}} of the centre of the cell to report for this real position. */
    double[] publicPosition(double lat, double lon) {
        long r = rowOf(lat);
        long c = colOf(r, lon);
        if (hasCell && (r != row || c != col) && insideWithMargin(lat, lon, row, col)) {
            return centre(row, col);
        }
        hasCell = true;
        row = r;
        col = c;
        return centre(r, c);
    }

    private static long rowOf(double lat) {
        return (long) Math.floor(lat / LAT_STEP);
    }

    private static double rowCentreLat(long r) {
        return (r + 0.5) * LAT_STEP;
    }

    /** Degrees of longitude spanning {@link #CELL_M} metres in row {@code r}. */
    private static double lonStep(long r) {
        // Clamp so a fix near a pole cannot divide by ~0.
        return CELL_M / Math.max(WazeConstants.mPerDegLon(rowCentreLat(r)), 1.0);
    }

    private static long colOf(long r, double lon) {
        return (long) Math.floor(lon / lonStep(r));
    }

    private static double[] centre(long r, long c) {
        return new double[]{ rowCentreLat(r), (c + 0.5) * lonStep(r) };
    }

    private static boolean insideWithMargin(double lat, double lon, long r, long c) {
        double fraction = HYSTERESIS_M / CELL_M;
        double latMargin = LAT_STEP * fraction;
        double step = lonStep(r);
        double lonMargin = step * fraction;
        return lat >= r * LAT_STEP - latMargin && lat <= (r + 1) * LAT_STEP + latMargin
                && lon >= c * step - lonMargin && lon <= (c + 1) * step + lonMargin;
    }
}
