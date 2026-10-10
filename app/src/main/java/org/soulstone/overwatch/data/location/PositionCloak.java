package org.soulstone.overwatch.data.location;

// OVERWATCH's own code, under the app's BSD 3-Clause license.

/**
 * The position OVERWATCH tells an online service: the centre of the ~1 km grid
 * square the device is in, never the device's own coordinates.
 *
 * <p><b>Who gets it.</b> Every online source that has to say where to look: the
 * ADS-B feeds ({@code AircraftClient}), OpenWeb Ninja ({@code WazeClient}) and the
 * direct Waze backend ({@code WazeRtFetcher}). The camera map asks Overpass about
 * its own coarser 0.05° cache cell instead ({@code DeflockClient}). Each client
 * keeps its own instance.
 *
 * <p><b>Why this exists.</b> v0.5.15 sent the raw GPS fix to Waze several times a
 * minute while its Settings screen said the position was blurred. v0.5.16 routed
 * every Waze message through this class. Until v0.5.18 the other sources still
 * sent the fix itself: the aircraft feeds got it to about 11 m every minute, and
 * OpenWeb Ninja got a box centred exactly on it. Now none of them learns anything
 * finer than the square you are in, however long you stay in it.
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
 * latitude rather than shrinking toward the poles. The degree constants match the
 * Waze protocol layer's, so the cells are exactly the ones v0.5.16 was wire-tested
 * with.
 *
 * <p><b>Detection is unaffected.</b> Distances are always measured from the real
 * fix, on the device. Callers only move the centre of the area they ask about, and
 * widen it by {@link #MAX_OFFSET_M} so the evaluation radius around the real
 * position always stays inside it.
 */
public final class PositionCloak {

    /** Edge of a grid cell, in metres. */
    public static final double CELL_M = 1000.0;

    /** How far past the current cell's edge the device must go before the cell changes. */
    public static final double HYSTERESIS_M = 150.0;

    /**
     * Upper bound on the distance between the real position and the centre reported
     * for it: the corner of the current cell grown by the hysteresis margin.
     */
    public static final double MAX_OFFSET_M = Math.sqrt(2.0) * (CELL_M / 2.0 + HYSTERESIS_M);

    private static final double M_PER_DEG_LAT = 110574.0;
    private static final double M_PER_DEG_LON_EQUATOR = 111320.0;
    private static final double LAT_STEP = CELL_M / M_PER_DEG_LAT;

    private boolean hasCell = false;
    private long row;
    private long col;

    /**
     * {@code {lat, lon}} of the centre of the cell to report for this real position.
     * Synchronized because a client may poll from different IO threads.
     */
    public synchronized double[] publicPosition(double lat, double lon) {
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
        double mPerDegLon = Math.cos(Math.toRadians(rowCentreLat(r))) * M_PER_DEG_LON_EQUATOR;
        // Clamp so a fix near a pole cannot divide by ~0.
        return CELL_M / Math.max(mPerDegLon, 1.0);
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
