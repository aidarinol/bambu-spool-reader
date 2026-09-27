package id.min3d.spoolreader;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 2.0 product viewer. One finger: rotate freely in any direction (trackball, no gimbal lock).
 * Two fingers: pinch to zoom, drag to move. Double tap: back to the default view.
 * Rendering is on demand (RENDERMODE_WHEN_DIRTY) so the viewer uses no battery while idle.
 */
public class ModelView extends GLSurfaceView {

    private static final String TAG = "ModelView";

    /** Colour + surface of one material slot. */
    public static final class Look {
        final float r, g, b, spec, gloss;

        public Look(int rgb, float spec, float gloss) {
            this.r = ((rgb >> 16) & 0xFF) / 255f;
            this.g = ((rgb >> 8) & 0xFF) / 255f;
            this.b = (rgb & 0xFF) / 255f;
            this.spec = spec;
            this.gloss = gloss;
        }
    }

    private final Renderer3D renderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float lastX, lastY, lastFocusX, lastFocusY;
    private boolean multi;

    public ModelView(Context ctx, ModelAsset model, String vertSrc, String fragSrc, int bgRgb) {
        super(ctx);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new MsaaChooser());
        setPreserveEGLContextOnPause(true);
        renderer = new Renderer3D(model, vertSrc, fragSrc, bgRgb);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoomBy(d.getScaleFactor());
                requestRender();
                return true;
            }
        });
        tapDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                showPreset(Preset.DEFAULT);
                return true;
            }
        });
    }

    public enum Preset { DEFAULT, FRONT, SIDE, BACK }

    public void setLook(int material, Look look) {
        renderer.setLook(material, look);
        requestRender();
    }

    public void showPreset(Preset p) {
        renderer.animateTo(p);
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // The viewer sits above a scrolling list: keep the parent from stealing the gesture.
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(e.getActionMasked() != MotionEvent.ACTION_UP);
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);
        int n = e.getPointerCount();
        float fx = 0, fy = 0;
        for (int i = 0; i < n; i++) {
            fx += e.getX(i);
            fy += e.getY(i);
        }
        fx /= n;
        fy /= n;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multi = false;
                lastX = e.getX();
                lastY = e.getY();
                renderer.stopAnimation();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                multi = true;
                // re-anchor so the finger count change does not cause a jump
                lastFocusX = fx;
                lastFocusY = fy;
                if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && n == 2) {
                    int stay = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(stay);
                    lastY = e.getY(stay);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (n >= 2) {
                    renderer.panBy((fx - lastFocusX) / getHeight(), (fy - lastFocusY) / getHeight());
                    lastFocusX = fx;
                    lastFocusY = fy;
                } else if (!multi || n == 1) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    // full view height drag = 200 degrees
                    renderer.rotateBy(dx / getHeight() * 200f, dy / getHeight() * 200f);
                }
                requestRender();
                break;
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------

    /** 4x MSAA when the phone offers it, otherwise a plain RGB888 + depth config. */
    private static final class MsaaChooser implements EGLConfigChooser {
        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            final int EGL_OPENGL_ES2_BIT = 4;
            int[][] tries = {
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE},
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
                    {EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
            };
            for (int[] attrs : tries) {
                EGLConfig[] cfg = new EGLConfig[1];
                int[] num = new int[1];
                if (egl.eglChooseConfig(display, attrs, cfg, 1, num) && num[0] > 0) return cfg[0];
            }
            throw new IllegalStateException("No OpenGL ES 2.0 config on this phone");
        }
    }

    // ------------------------------------------------------------------------------------------

    private static final class Renderer3D implements GLSurfaceView.Renderer {
        private static final float FOV = 30f;
        private static final long ANIM_MS = 380;

        private final ModelAsset model;
        private final String vertSrc, fragSrc;
        private final float bgR, bgG, bgB;

        // state shared with the UI thread (guarded by this)
        private final float[] q = Quat.defaultView();
        private float zoom = 1f, panX, panY;
        private final Look[] looks = new Look[4];
        private float[] animFrom, animTo;
        private long animStart;

        // GL thread only
        private int program, aPos, aNrm, uMVP, uMV, uNrmMat, uColor, uSpec, uGloss;
        private int[] vbo, ibo;
        private final float[] proj = new float[16], view = new float[16], rot = new float[16], mv = new float[16], mvp = new float[16];
        private final float[] nrm = new float[9];
        private float aspect = 1f;

        Renderer3D(ModelAsset model, String vertSrc, String fragSrc, int bg) {
            this.model = model;
            this.vertSrc = vertSrc;
            this.fragSrc = fragSrc;
            bgR = ((bg >> 16) & 0xFF) / 255f;
            bgG = ((bg >> 8) & 0xFF) / 255f;
            bgB = (bg & 0xFF) / 255f;
            looks[ModelAsset.MAT_BASE] = new Look(0x303030, 0.2f, 32f);
            looks[ModelAsset.MAT_KEYCAP] = new Look(0xF0F0F0, 0.2f, 32f);
            looks[ModelAsset.MAT_LEGEND] = new Look(0x101010, 0.2f, 32f);
            looks[ModelAsset.MAT_SWITCH] = new Look(0x2A2A2E, 0.15f, 24f);
        }

        synchronized void setLook(int mat, Look l) {
            looks[mat] = l;
        }

        synchronized void rotateBy(float degX, float degY) {
            float ang = (float) Math.sqrt(degX * degX + degY * degY);
            if (ang < 1e-3f) return;
            // screen drag (dx, dy) turns the object about the view axis (dy, dx, 0); screen y points down
            float[] d = Quat.axisAngle(degY / ang, degX / ang, 0f, ang);
            Quat.mulInto(d, q, q);
            Quat.normalize(q);
        }

        synchronized void zoomBy(float f) {
            zoom = Math.max(0.6f, Math.min(6f, zoom * f));
        }

        synchronized void panBy(float dx, float dy) {
            float k = 1.1f / zoom;
            panX = Math.max(-1f, Math.min(1f, panX + dx * k));
            panY = Math.max(-1f, Math.min(1f, panY - dy * k));
        }

        synchronized void stopAnimation() {
            animTo = null;
        }

        synchronized void animateTo(Preset p) {
            animFrom = q.clone();
            animTo = Quat.preset(p);
            animStart = SystemClock.uptimeMillis();
            if (p == Preset.DEFAULT) {
                zoom = 1f;
                panX = panY = 0f;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = link(vertSrc, fragSrc);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aNrm = GLES20.glGetAttribLocation(program, "aNrm");
            uMVP = GLES20.glGetUniformLocation(program, "uMVP");
            uMV = GLES20.glGetUniformLocation(program, "uMV");
            uNrmMat = GLES20.glGetUniformLocation(program, "uNrmMat");
            uColor = GLES20.glGetUniformLocation(program, "uColor");
            uSpec = GLES20.glGetUniformLocation(program, "uSpec");
            uGloss = GLES20.glGetUniformLocation(program, "uGloss");

            int n = model.parts.size();
            vbo = new int[n];
            ibo = new int[n];
            GLES20.glGenBuffers(n, vbo, 0);
            GLES20.glGenBuffers(n, ibo, 0);
            for (int i = 0; i < n; i++) {
                ModelAsset.Part p = model.parts.get(i);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
                p.vertices.position(0);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, p.vertices.capacity(), p.vertices, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
                p.indices.position(0);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.indices.capacity(), p.indices, GLES20.GL_STATIC_DRAW);
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            // No back-face culling: rounding vertices to 0.01 mm can flip hair-thin sliver triangles,
            // and culling them would leave pin-hole cracks. The shader lights back faces correctly.
            GLES20.glDisable(GLES20.GL_CULL_FACE);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            aspect = h == 0 ? 1f : (float) w / h;
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            float z, px, py;
            Look[] l;
            boolean animating;
            synchronized (this) {
                animating = animTo != null;
                if (animating) {
                    float t = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) ANIM_MS);
                    float e = t * t * (3f - 2f * t);
                    Quat.slerpInto(animFrom, animTo, e, q);
                    if (t >= 1f) animTo = null;
                }
                Quat.toMatrix(q, rot);
                z = zoom;
                px = panX;
                py = panY;
                l = looks.clone();
            }

            // Fit the model's bounding sphere inside the narrower side of the view.
            float halfFov = (float) Math.toRadians(FOV / 2);
            float fitFov = aspect < 1f ? (float) Math.atan(Math.tan(halfFov) * aspect) : halfFov;
            float dist = model.radius * 1.08f / (float) Math.sin(fitFov) / z;
            Matrix.perspectiveM(proj, 0, FOV, aspect, Math.max(1f, dist - model.radius * 1.5f), dist + model.radius * 1.5f);
            float visH = (float) Math.tan(halfFov) * dist * 2f;
            Matrix.setIdentityM(view, 0);
            Matrix.translateM(view, 0, px * visH, py * visH, -dist);
            Matrix.multiplyMM(mv, 0, view, 0, rot, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0);
            for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) nrm[c * 3 + r] = rot[c * 4 + r];

            GLES20.glClearColor(bgR, bgG, bgB, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uMV, 1, false, mv, 0);
            GLES20.glUniformMatrix3fv(uNrmMat, 1, false, nrm, 0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aNrm);
            for (int i = 0; i < model.parts.size(); i++) {
                ModelAsset.Part p = model.parts.get(i);
                Look look = l[Math.max(0, Math.min(3, p.material))];
                GLES20.glUniform3f(uColor, look.r, look.g, look.b);
                GLES20.glUniform1f(uSpec, look.spec);
                GLES20.glUniform1f(uGloss, look.gloss);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_SHORT, false, ModelAsset.STRIDE, 0);
                GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_BYTE, true, ModelAsset.STRIDE, 8);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            if (animating) requestRenderFromGl();
        }

        private GLSurfaceView owner;

        private void requestRenderFromGl() {
            if (owner != null) owner.requestRender();
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(s);
                GLES20.glDeleteShader(s);
                throw new IllegalStateException("Shader compile failed: " + log);
            }
            return s;
        }

        private static int link(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(p);
                Log.e(TAG, log);
                throw new IllegalStateException("Shader link failed: " + log);
            }
            return p;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        renderer.owner = this;
    }

    @Override
    protected void onDetachedFromWindow() {
        renderer.owner = null;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------------------------------

    /** Unit quaternions as float[4] {w, x, y, z}. */
    static final class Quat {
        private Quat() {
        }

        static float[] axisAngle(float x, float y, float z, float deg) {
            float h = (float) Math.toRadians(deg) / 2f, s = (float) Math.sin(h);
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-6f) return new float[]{1, 0, 0, 0};
            return new float[]{(float) Math.cos(h), x / len * s, y / len * s, z / len * s};
        }

        /** out = a * b (apply b first, then a). out may alias a or b. */
        static void mulInto(float[] a, float[] b, float[] out) {
            float w = a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3];
            float x = a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2];
            float y = a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1];
            float z = a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0];
            out[0] = w;
            out[1] = x;
            out[2] = y;
            out[3] = z;
        }

        static float[] mul(float[] a, float[] b) {
            float[] o = new float[4];
            mulInto(a, b, o);
            return o;
        }

        static void normalize(float[] q) {
            float l = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            for (int i = 0; i < 4; i++) q[i] /= l;
        }

        static void slerpInto(float[] a, float[] b, float t, float[] out) {
            float d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
            float s = 1f;
            if (d < 0f) {
                d = -d;
                s = -1f;
            }
            float ka, kb;
            if (d > 0.9995f) {
                ka = 1f - t;
                kb = t;
            } else {
                float th = (float) Math.acos(d), sn = (float) Math.sin(th);
                ka = (float) Math.sin((1f - t) * th) / sn;
                kb = (float) Math.sin(t * th) / sn;
            }
            for (int i = 0; i < 4; i++) out[i] = ka * a[i] + kb * s * b[i];
            normalize(out);
        }

        /** Column-major 4x4 rotation for android.opengl.Matrix / GLSL. */
        static void toMatrix(float[] q, float[] m) {
            float w = q[0], x = q[1], y = q[2], z = q[3];
            m[0] = 1 - 2 * (y * y + z * z);
            m[1] = 2 * (x * y + w * z);
            m[2] = 2 * (x * z - w * y);
            m[3] = 0;
            m[4] = 2 * (x * y - w * z);
            m[5] = 1 - 2 * (x * x + z * z);
            m[6] = 2 * (y * z + w * x);
            m[7] = 0;
            m[8] = 2 * (x * z + w * y);
            m[9] = 2 * (y * z - w * x);
            m[10] = 1 - 2 * (x * x + y * y);
            m[11] = 0;
            m[12] = m[13] = m[14] = 0;
            m[15] = 1;
        }

        /** Keychain hanging from its ring (model -X = screen up), keycaps facing the camera. */
        static float[] front() {
            return axisAngle(0, 0, 1, -90f);
        }

        static float[] defaultView() {
            return preset(Preset.DEFAULT);
        }

        static float[] preset(Preset p) {
            switch (p) {
                case FRONT:
                    return front();
                case SIDE:
                    return mul(axisAngle(0, 1, 0, -90f), front());
                case BACK:
                    return mul(axisAngle(0, 1, 0, 180f), front());
                case DEFAULT:
                default:
                    return mul(axisAngle(1, 0, 0, 16f), mul(axisAngle(0, 1, 0, -32f), front()));
            }
        }
    }
}
