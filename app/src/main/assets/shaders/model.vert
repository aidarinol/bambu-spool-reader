// Shared by the Android viewer (GLES 2.0) and the WebGL test page. GLSL ES 1.00.
attribute vec3 aPos;      // int16, 0.01 mm units
attribute vec3 aNrm;      // int8, normalized
uniform mat4 uMVP;
uniform mat4 uMV;
uniform mat3 uNrmMat;
varying vec3 vN;
varying vec3 vP;

void main() {
    vec4 p = vec4(aPos * 0.01, 1.0);
    vN = uNrmMat * aNrm;
    vP = (uMV * p).xyz;
    gl_Position = uMVP * p;
}
