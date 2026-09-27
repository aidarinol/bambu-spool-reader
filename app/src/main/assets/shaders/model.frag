// Plastic look for filament preview: hemisphere ambient + key/fill/rim lights fixed to the camera,
// so every side the user turns towards the screen is lit. GLSL ES 1.00.
precision mediump float;
uniform vec3 uColor;      // filament colour, sRGB 0..1
uniform float uSpec;      // specular strength (matte ~0.04, basic ~0.22, silk ~0.6)
uniform float uGloss;     // Blinn exponent
varying vec3 vN;
varying vec3 vP;

vec3 toLin(vec3 c) { return c * c; }          // cheap gamma 2.0 approximation
vec3 toSrgb(vec3 c) { return sqrt(c); }

void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    vec3 v = normalize(-vP);
    vec3 base = max(toLin(uColor), vec3(0.012));   // keep black filament readable

    vec3 kL = normalize(vec3(-0.45, 0.65, 0.62));   // key: upper left, in front
    vec3 fL = normalize(vec3(0.7, -0.15, 0.45));    // fill: right
    vec3 rL = normalize(vec3(0.1, 0.4, -0.9));      // rim: behind

    float hemi = 0.5 + 0.5 * n.y;
    vec3 amb = base * mix(0.16, 0.30, hemi);
    float kd = max(dot(n, kL), 0.0);
    float fd = max(dot(n, fL), 0.0);
    vec3 col = amb + base * (0.78 * kd + 0.28 * fd);

    float ks = pow(max(dot(n, normalize(kL + v)), 0.0), uGloss);
    float fs = pow(max(dot(n, normalize(fL + v)), 0.0), uGloss);
    col += vec3(uSpec) * (ks + 0.35 * fs);

    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * max(dot(n, rL) + 0.6, 0.0);
    col += vec3(0.10) * rim;

    gl_FragColor = vec4(toSrgb(clamp(col, 0.0, 1.0)), 1.0);
}
