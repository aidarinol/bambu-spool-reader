package id.min3d.spoolreader;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stock-page ordering and printer compatibility per filament type.
 * Sources: bambulab.com/en/a2l/specs and bambulab.com/en/h2c/specs (checked Sep 2026).
 *  A2L  ideal: PLA, PETG, TPU, PVA; capable with a hardened-steel nozzle: PLA-CF, PETG-CF. Open frame, bed max 80 °C.
 *  H2C  PLA, PETG, TPU, PVA, BVOH, ABS, ASA, PC, PA, PET, PPS + CF/GF of PLA, PETG, PA, PET, PC, ABS, ASA, PPA, PPS.
 */
public final class Compat {

    /** Types pinned to the top of the stock page, in this order; everything else follows alphabetically. */
    private static final List<String> PINNED = Arrays.asList(
            "PLA Basic", "PLA Matte", "PETG Basic", "PETG Translucent", "PLA Wood", "PLA Glow", "PLA Marble");

    /** Filaments Chow treats as abrasive (hardened-steel nozzle). */
    private static final List<String> ABRASIVE = Arrays.asList("PLA Wood", "PLA Glow", "PLA Marble");

    public enum A2L { NO, YES, HARDENED }

    private Compat() {
    }

    public static int rank(String type) {
        for (int i = 0; i < PINNED.size(); i++) if (PINNED.get(i).equalsIgnoreCase(type)) return i;
        return PINNED.size();
    }

    /** Header label, e.g. "PLA Wood (abrasive)". */
    public static String label(String type) {
        for (String a : ABRASIVE) if (a.equalsIgnoreCase(type)) return type + " (abrasive)";
        return type;
    }

    public static A2L a2l(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        if (isCfGf(t)) {
            // Only PLA-CF and PETG-CF are listed for the A2L (with hardened-steel nozzle).
            if (t.startsWith("PLA") || t.startsWith("PETG")) return A2L.HARDENED;
            return A2L.NO;
        }
        for (String a : ABRASIVE) if (a.equalsIgnoreCase(type)) return A2L.HARDENED;
        if (t.startsWith("SUPPORT FOR")) {
            // PLA-based support materials only
            return (t.equals("SUPPORT FOR PLA") || t.equals("SUPPORT FOR PLA/PETG")) ? A2L.YES : A2L.NO;
        }
        if (t.startsWith("PLA") || t.startsWith("PETG") || t.startsWith("TPU") || t.startsWith("PVA")) return A2L.YES;
        return A2L.NO;
    }

    public static boolean h2c(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        if (t.equals(ColorDb.OTHER_TYPE.toUpperCase(Locale.ROOT))) return false;
        String[] base = {"PLA", "PETG", "TPU", "PVA", "BVOH", "ABS", "ASA", "PC", "PA", "PET", "PPS", "PPA", "SUPPORT FOR"};
        for (String b : base) if (t.startsWith(b)) return true;
        return false;
    }

    private static boolean isCfGf(String t) {
        return t.contains("-CF") || t.contains("-GF") || t.endsWith(" CF") || t.endsWith(" GF");
    }
}
