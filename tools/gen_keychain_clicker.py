"""Builds app/src/main/assets/models/keychain_clicker.m3d (display model for the 3D product viewer).

Geometry comes from the Min3D "membuat-clicker" skill (clicker_lib.py, FINAL connector):
  1x base TOP (hanger ring + socket) + 4x base MIDDLE (male + socket), chained at 21.2 mm pitch,
  5 keycaps with the legend M I N 3 D inlaid 0.6 mm (legend = separate colour object),
  plus a simple MX switch stand-in (fixed dark grey, only visible through the keycap gap).

Run:  CLICKER_LIB=/path/to/membuat-clicker/scripts python tools/gen_keychain_clicker.py

File format (little-endian), read by ModelAsset.java:
  magic "M3D1", int32 partCount
  per part: int32 material (0 base, 1 keycap, 2 legend, 3 switch), int32 vertexCount, int32 indexCount,
            vertexCount * 12 bytes (int16 x,y,z in 0.01 mm, int16 pad ; int8 nx,ny,nz,pad), indexCount * uint16
  (12-byte stride keeps every GL attribute 4-byte aligned)
Coordinates are already centred on the model's bounding box.
"""
import os, sys, struct
import numpy as np, trimesh, manifold3d as m3
from matplotlib.textpath import TextPath
from matplotlib.font_manager import FontProperties
from shapely.geometry import Polygon
from shapely.ops import unary_union

sys.path.insert(0, os.environ.get("CLICKER_LIB", "/mnt/skills/plugins/membuat-clicker/scripts"))
import clicker_lib as CL  # noqa: E402

TEXT = "MIN3D"
FONT = os.environ.get("CLICKER_FONT", "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf")
LETTER_H = 9.0        # cap height on the 18 mm keycap
INLAY = 0.6           # legend depth (3 layers at 0.20 mm)
KEY_Z = 17.6          # keycap local z=-3 (underside) sits at world z=14.6 (switch pressed-free height, approx.)
PITCH = 2 * CL.half()  # 21.2 mm
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "keychain_clicker.m3d")


def glyph_poly(ch):
    """Glyph outline as shapely polygon, centred, cap height LETTER_H, in glyph coords (x right, y up)."""
    tp = TextPath((0, 0), ch, size=10, prop=FontProperties(fname=FONT))
    rings = [np.asarray(p) for p in tp.to_polygons() if len(p) >= 3]
    polys = [Polygon(r).buffer(0) for r in rings]
    # even-odd: sort by area, subtract holes contained in bigger rings
    polys.sort(key=lambda p: -p.area)
    shapes = []
    for p in polys:
        host = next((s for s in shapes if s.contains(p.representative_point())), None)
        if host is not None:
            shapes[shapes.index(host)] = host.difference(p)
        else:
            shapes.append(p)
    g = unary_union(shapes)
    minx, miny, maxx, maxy = g.bounds
    s = LETTER_H / (maxy - miny)
    from shapely import affinity
    g = affinity.scale(g, s, s, origin=(0, 0))
    minx, miny, maxx, maxy = g.bounds
    return affinity.translate(g, -(minx + maxx) / 2, -(miny + maxy) / 2)


def legend_solid(ch):
    """Glyph mapped so it reads upright when the keychain hangs from the ring (-X = up): x = -gy, y = +gx."""
    g = glyph_poly(ch)
    from shapely import affinity
    g = affinity.affine_transform(g, [0, -1, 1, 0, 0, 0])  # (gx,gy) -> (-gy, gx)
    geoms = list(g.geoms) if hasattr(g, "geoms") else [g]
    solid = None
    for pg in geoms:
        pr = CL.prism(pg, 3.0 - INLAY, 3.0 + 0.02)  # 0.02 proud: wins the depth test over coplanar keycap faces
        solid = pr if solid is None else solid + pr
    return solid


def switch_standin():
    b = CL.box3(-7, 7, -7, 7, 3.0, 8.3)
    top = m3.Manifold.cylinder(15.0 - 8.3, 7 * 1.41, 5.2 * 1.41, 4).rotate((0, 0, 45)).translate((0, 0, 8.3))
    return b + top


CREASE_DEG = 35.0


def crease_normals(V, F, FN, FA, crease_deg):
    """Per-corner normals: area-weighted average of the faces around the vertex whose normal is within
    crease_deg of this face (sharp edges stay sharp, round parts look smooth). Returns split V, F, N."""
    cos_t = np.cos(np.radians(crease_deg))
    around = [[] for _ in range(len(V))]
    for fi, tri in enumerate(F):
        for vi in tri:
            around[vi].append(fi)
    out_v, out_n, key_to_idx = [], [], {}
    newF = np.empty_like(F)
    for fi, tri in enumerate(F):
        for c, vi in enumerate(tri):
            fs = [g for g in around[vi] if np.dot(FN[g], FN[fi]) >= cos_t]
            nrm = (FN[fs] * FA[fs, None]).sum(0)
            ln = np.linalg.norm(nrm)
            nrm = FN[fi] if ln < 1e-12 else nrm / ln
            q = np.clip(np.round(nrm * 127), -127, 127).astype(int)
            key = (vi, q[0], q[1], q[2])
            idx = key_to_idx.get(key)
            if idx is None:
                idx = key_to_idx[key] = len(out_v)
                out_v.append(V[vi])
                out_n.append(nrm)
            newF[fi, c] = idx
    return np.array(out_v), newF, np.array(out_n)


def export_part(mesh, material, xyz_offset):
    m = trimesh.Trimesh(mesh.vertices - xyz_offset, mesh.faces, process=True)
    v, f, n = crease_normals(np.asarray(m.vertices), np.asarray(m.faces), np.asarray(m.face_normals),
                             np.asarray(m.area_faces), CREASE_DEG)
    assert len(v) < 65536, "part too big for uint16 indices: %d verts" % len(v)
    q = np.round(v * 100).astype(np.int16)
    nq = np.clip(np.round(n * 127), -127, 127).astype(np.int8)
    buf = bytearray(struct.pack("<iii", material, len(v), f.size))
    vb = np.zeros((len(v), 12), dtype=np.uint8)
    vb[:, 0:6] = q.astype("<i2").view(np.uint8).reshape(-1, 6)
    vb[:, 8:11] = nq.view(np.uint8)
    buf += vb.tobytes()
    buf += f.astype("<u2").tobytes()
    return bytes(buf), len(v), len(f)


def main():
    parts = []  # (trimesh, material)
    for i, ch in enumerate(TEXT):
        dx = i * PITCH
        base = CL.base("TOP" if i == 0 else "MIDDLE")
        parts.append((CL.to_t(base.translate((dx, 0, 0))), 0))
        leg = legend_solid(ch)
        key = CL.keycap() - leg
        parts.append((CL.to_t(key.translate((dx, 0, KEY_Z))), 1))
        parts.append((CL.to_t(leg.translate((dx, 0, KEY_Z))), 2))
        parts.append((CL.to_t(switch_standin().translate((dx, 0, 0))), 3))
    for t, mat in parts:
        assert t.is_watertight, "not watertight (material %d)" % mat
    allv = np.vstack([t.vertices for t, _ in parts])
    lo, hi = allv.min(0), allv.max(0)
    centre = (lo + hi) / 2
    blob = bytearray(b"M3D1" + struct.pack("<i", len(parts)))
    tv = tf = 0
    for t, mat in parts:
        b, nv, nf = export_part(t, mat, centre)
        blob += b
        tv += nv
        tf += nf
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as fh:
        fh.write(blob)
    print("size mm:", np.round(hi - lo, 2).tolist(), "parts:", len(parts), "verts:", tv, "tris:", tf,
          "bytes:", len(blob), "->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
