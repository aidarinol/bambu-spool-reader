import id.min3d.spoolreader.ColorDb;
import id.min3d.spoolreader.SpoolData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** Plain-JVM regression test: real tag dumps -> expected colour names. Run by CI before building. */
public class ParserTest {
    public static void main(String[] args) throws Exception {
        ColorDb db = new ColorDb(Files.newInputStream(Paths.get("app/src/main/assets/colors.tsv")));
        List<String> exp = Files.readAllLines(Paths.get("test/fixtures/expected.tsv"));
        int fail = 0;
        for (String line : exp) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split("\t");
            Path p = Paths.get("test/fixtures", f[0]);
            byte[] d = Files.readAllBytes(p);
            byte[] uid = Arrays.copyOf(d, 4);
            // key-derivation check: sector trailers in the dump hold Key A
            byte[][] keys = SpoolData.deriveKeys(uid);
            for (int s = 0; s < 16; s++) {
                byte[] kA = Arrays.copyOfRange(d, (s * 4 + 3) * 16, (s * 4 + 3) * 16 + 6);
                if (!Arrays.equals(kA, keys[s])) { System.out.println("KEY MISMATCH " + f[0] + " sector " + s); fail++; break; }
            }
            byte[][] blocks = new byte[64][];
            for (int b : SpoolData.BLOCKS) blocks[b] = Arrays.copyOfRange(d, b * 16, b * 16 + 16);
            SpoolData sd = SpoolData.parse(uid, blocks);
            ColorDb.Match m = db.lookup(sd);
            String got = m == null ? "null" : m.name;
            boolean ok = got.equals(f[1]);
            if (!ok) fail++;
            System.out.println((ok ? "OK   " : "FAIL ") + f[0] + " -> " + got + " (" + (m == null ? "-" : m.how) + ") "
                    + sd.variantId + " " + sd.detailedType + " " + sd.colors);
        }
        if (fail > 0) { System.out.println(fail + " failure(s)"); System.exit(1); }
        System.out.println("all passed");
    }
}
