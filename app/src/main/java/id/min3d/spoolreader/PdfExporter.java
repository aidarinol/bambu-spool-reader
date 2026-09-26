package id.min3d.spoolreader;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** Writes an A4 stock report: only filaments with stock > 0, grouped by type. */
public final class PdfExporter {

    private static final int W = 595, H = 842, M = 40; // A4 in points
    private static final int ROW = 20, HEAD = 26;

    private final Paint title = paint(18, true, Color.BLACK);
    private final Paint sub = paint(10, false, Color.rgb(90, 90, 90));
    private final Paint group = paint(12, true, Color.WHITE);
    private final Paint text = paint(10.5f, false, Color.BLACK);
    private final Paint code = paint(8.5f, false, Color.rgb(120, 120, 120));
    private final Paint qty = paint(10.5f, true, Color.BLACK);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PdfDocument doc;
    private PdfDocument.Page page;
    private Canvas c;
    private int y, pageNo;
    private String footer;

    public PdfExporter() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(0.6f);
        stroke.setColor(Color.rgb(150, 150, 150));
        qty.setTextAlign(Paint.Align.RIGHT);
    }

    /** @return number of spools written */
    public int write(OutputStream out, Map<String, List<StockStore.Item>> groups, String generatedAt) throws IOException {
        int total = 0, colours = 0;
        for (List<StockStore.Item> l : groups.values())
            for (StockStore.Item it : l)
                if (it.qty > 0) {
                    total += it.qty;
                    colours++;
                }
        footer = "Min3D Studio  ·  " + generatedAt;
        doc = new PdfDocument();
        pageNo = 0;
        newPage();
        c.drawText("Filament Stock", M, y + 18, title);
        y += 34;
        c.drawText("Generated " + generatedAt + "   ·   " + total + " spool(s), " + colours + " colour(s)", M, y, sub);
        y += 22;

        for (Map.Entry<String, List<StockStore.Item>> g : groups.entrySet()) {
            int sum = 0, n = 0;
            for (StockStore.Item it : g.getValue())
                if (it.qty > 0) {
                    sum += it.qty;
                    n++;
                }
            if (n == 0) continue;
            ensure(HEAD + ROW);
            groupHeader(g.getKey(), sum, false);
            for (StockStore.Item it : g.getValue()) {
                if (it.qty <= 0) continue;
                if (y + ROW > H - M - 20) {
                    newPage();
                    groupHeader(g.getKey(), sum, true);
                }
                row(it);
            }
            y += 10;
        }
        finishPage();
        doc.writeTo(out);
        doc.close();
        return total;
    }

    private void groupHeader(String type, int sum, boolean cont) {
        fill.setColor(Color.rgb(34, 34, 38));
        c.drawRect(M, y, W - M, y + HEAD - 6, fill);
        c.drawText(Compat.label(type) + (cont ? "  (cont.)" : ""), M + 8, y + 14, group);
        Paint r = paint(10, true, Color.WHITE);
        r.setTextAlign(Paint.Align.RIGHT);
        c.drawText(sum + " spool(s)", W - M - 8, y + 14, r);
        y += HEAD;
    }

    private void row(StockStore.Item it) {
        int sx = M + 6, sy = y + 3, sw = 14;
        int n = Math.max(1, it.colors.size());
        for (int i = 0; i < n; i++) {
            fill.setColor(i < it.colors.size() ? argb(it.colors.get(i)) : Color.LTGRAY);
            float x0 = sx + (float) sw * i / n, x1 = sx + (float) sw * (i + 1) / n;
            c.drawRect(x0, sy, x1, sy + sw, fill);
        }
        c.drawRect(sx, sy, sx + sw, sy + sw, stroke);
        c.drawText(it.name, sx + sw + 10, y + 14, text);
        float nameW = text.measureText(it.name);
        c.drawText(it.code, sx + sw + 18 + nameW, y + 14, code);
        c.drawText(String.valueOf(it.qty), W - M - 8, y + 14, qty);
        Paint line = new Paint();
        line.setColor(Color.rgb(225, 225, 225));
        c.drawLine(M, y + ROW, W - M, y + ROW, line);
        y += ROW;
    }

    private void ensure(int need) {
        if (y + need > H - M - 20) newPage();
    }

    private void newPage() {
        finishPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        c = page.getCanvas();
        y = M;
    }

    private void finishPage() {
        if (page == null) return;
        c.drawText(footer + "   ·   page " + pageNo, M, H - M + 10, sub);
        doc.finishPage(page);
        page = null;
    }

    private static Paint paint(float size, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        if (bold) p.setTypeface(Typeface.DEFAULT_BOLD);
        return p;
    }

    /** "#RRGGBBAA" -> ARGB; transparent filaments drawn light grey so they stay visible on paper. */
    static int argb(String hex) {
        try {
            long v = Long.parseLong(hex.substring(1), 16);
            int r = (int) (v >> 24) & 0xFF, g = (int) (v >> 16) & 0xFF, b = (int) (v >> 8) & 0xFF, a = (int) v & 0xFF;
            if (a == 0) return Color.rgb(235, 235, 235);
            return Color.argb(a, r, g, b);
        } catch (Exception e) {
            return Color.GRAY;
        }
    }
}
