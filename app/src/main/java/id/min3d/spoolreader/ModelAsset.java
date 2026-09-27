package id.min3d.spoolreader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Display model for the 3D product viewer ("M3D1" format, written by tools/gen_keychain_clicker.py).
 * Each part has one material slot; vertices are 12 bytes (int16 xyz in 0.01 mm + pad, int8 normal + pad).
 */
public final class ModelAsset {

    public static final int MAT_BASE = 0, MAT_KEYCAP = 1, MAT_LEGEND = 2, MAT_SWITCH = 3;
    public static final int STRIDE = 12;

    public static final class Part {
        public final int material, vertexCount, indexCount;
        /** Direct buffers, ready for glBufferData. */
        public final ByteBuffer vertices, indices;

        Part(int material, int vertexCount, int indexCount, ByteBuffer vertices, ByteBuffer indices) {
            this.material = material;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    public final List<Part> parts;
    /** Radius (mm) of the sphere around the origin that holds every vertex. */
    public final float radius;

    private ModelAsset(List<Part> parts, float radius) {
        this.parts = Collections.unmodifiableList(parts);
        this.radius = radius;
    }

    public static ModelAsset read(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(readAll(in)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 8 || b.get() != 'M' || b.get() != '3' || b.get() != 'D' || b.get() != '1')
            throw new IOException("Not a Min3D model file");
        int n = b.getInt();
        if (n <= 0 || n > 1000) throw new IOException("Bad part count " + n);
        List<Part> parts = new ArrayList<>(n);
        float r2 = 0f;
        for (int i = 0; i < n; i++) {
            int mat = b.getInt(), nv = b.getInt(), ni = b.getInt();
            if (nv <= 0 || nv > 65535 || ni <= 0 || ni % 3 != 0) throw new IOException("Bad part header " + i);
            if (b.remaining() < nv * STRIDE + ni * 2) throw new IOException("Model file is truncated");
            ByteBuffer v = ByteBuffer.allocateDirect(nv * STRIDE).order(ByteOrder.nativeOrder());
            for (int k = 0; k < nv; k++) {
                short x = b.getShort(), y = b.getShort(), z = b.getShort(), pad = b.getShort();
                v.putShort(x).putShort(y).putShort(z).putShort(pad);
                v.put(b.get()).put(b.get()).put(b.get()).put(b.get());
                float fx = x * 0.01f, fy = y * 0.01f, fz = z * 0.01f;
                r2 = Math.max(r2, fx * fx + fy * fy + fz * fz);
            }
            v.flip();
            ByteBuffer ix = ByteBuffer.allocateDirect(ni * 2).order(ByteOrder.nativeOrder());
            for (int k = 0; k < ni; k++) {
                int idx = b.getShort() & 0xFFFF;
                if (idx >= nv) throw new IOException("Bad index in part " + i);
                ix.putShort((short) idx);
            }
            ix.flip();
            parts.add(new Part(mat, nv, ni, v, ix));
        }
        return new ModelAsset(parts, (float) Math.sqrt(r2));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 21);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = s.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }
}
