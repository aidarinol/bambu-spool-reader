package id.min3d.spoolreader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java parser for Bambu Lab spool RFID tags (MIFARE Classic 1K).
 * No Android dependencies, so it can be tested on a desktop JVM against real dumps.
 * Layout reference: https://github.com/Bambu-Research-Group/RFID-Tag-Guide/blob/main/BambuLabRfid.md
 */
public final class SpoolData {

    private static final byte[] MASTER = {
            (byte) 0x9a, (byte) 0x75, (byte) 0x9c, (byte) 0xf2, (byte) 0xc4, (byte) 0xf7, (byte) 0xca, (byte) 0xff,
            (byte) 0x22, (byte) 0x2c, (byte) 0xb9, (byte) 0x76, (byte) 0x9b, (byte) 0x41, (byte) 0xbc, (byte) 0x96};
    private static final byte[] INFO = "RFID-A\0".getBytes(StandardCharsets.US_ASCII);

    /** Blocks the app reads; sector = block / 4. */
    public static final int[] BLOCKS = {1, 2, 4, 5, 6, 12, 14, 16};

    public String uid = "";
    public String variantId = "";
    public String materialId = "";
    public String filamentType = "";
    public String detailedType = "";
    public final List<String> colors = new ArrayList<>(); // "#RRGGBBAA"
    public int weightG;
    public float diameterMm;
    public int dryTempC, dryHours, bedTempC, hotendMaxC, hotendMinC;
    public String productionDate = "";
    public int lengthM;

    /** HKDF-SHA256(uid, salt=MASTER, info="RFID-A\0") -> 16 six-byte Key-A values, one per sector. */
    public static byte[][] deriveKeys(byte[] uid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(MASTER, "HmacSHA256"));
            byte[] prk = mac.doFinal(uid);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[96];
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < okm.length; i++) {
                mac.update(t);
                mac.update(INFO);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, okm.length - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
            }
            byte[][] keys = new byte[16][6];
            for (int s = 0; s < 16; s++) System.arraycopy(okm, s * 6, keys[s], 0, 6);
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param blocks array of 64 entries; entries not read may be null. */
    public static SpoolData parse(byte[] uid, byte[][] blocks) {
        SpoolData d = new SpoolData();
        d.uid = hex(uid, ":");
        byte[] b1 = blocks[1];
        if (b1 != null) {
            d.variantId = str(b1, 0, 8);
            d.materialId = str(b1, 8, 8);
        }
        if (blocks[2] != null) d.filamentType = str(blocks[2], 0, 16);
        if (blocks[4] != null) d.detailedType = str(blocks[4], 0, 16);
        byte[] b5 = blocks[5];
        if (b5 != null) {
            d.colors.add("#" + hex(b5, 0, 4));
            d.weightG = u16(b5, 4);
            d.diameterMm = ByteBuffer.wrap(b5, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        }
        byte[] b6 = blocks[6];
        if (b6 != null) {
            d.dryTempC = u16(b6, 0);
            d.dryHours = u16(b6, 2);
            d.bedTempC = u16(b6, 6);
            d.hotendMaxC = u16(b6, 8);
            d.hotendMinC = u16(b6, 10);
        }
        if (blocks[12] != null) d.productionDate = str(blocks[12], 0, 16);
        if (blocks[14] != null) d.lengthM = u16(blocks[14], 4);
        byte[] b16 = blocks[16];
        if (b16 != null && u16(b16, 0) == 2 && u16(b16, 2) >= 2) {
            // second colour is stored reversed (ABGR)
            byte[] rev = {b16[7], b16[6], b16[5], b16[4]};
            d.colors.add("#" + hex(rev, 0, 4));
        }
        return d;
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < end; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        return sb.toString().trim();
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xFF));
        return sb.toString();
    }

    static String hex(byte[] b, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
