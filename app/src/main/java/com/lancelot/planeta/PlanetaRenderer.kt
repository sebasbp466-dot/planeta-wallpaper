package com.lancelot.planeta

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PlanetaRenderer : GLSurfaceView.Renderer {

    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var scroll = 0f
    @Volatile var dim = 0f          // 0 = normal, 1 = modo ambiente atenuado (para el AOD)
    @Volatile private var kickT = -100f

    private val t0 = System.nanoTime()
    private var prog = 0
    private var w = 1
    private var h = 1
    private val u = IntArray(6)

    private fun now(): Float = (System.nanoTime() - t0) / 1e9f

    fun kick() {
        kickT = now()
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Planeta", GLES30.glGetShaderInfoLog(s))
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
        val vs = shader(GLES30.GL_VERTEX_SHADER, VS)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, FS)
        prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Planeta", GLES30.glGetProgramInfoLog(prog))
        val names = arrayOf("uRes", "uTime", "uTilt", "uZoom", "uDim", "uScroll")
        for (i in names.indices) u[i] = GLES30.glGetUniformLocation(prog, names[i])
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width
        h = height
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val start = System.nanoTime()
        val t = now()
        val k = ((t - kickT) / 1.8f).coerceIn(0f, 1f)
        val inv = 1f - k
        val zoom = inv * inv * inv
        GLES30.glUseProgram(prog)
        GLES30.glUniform2f(u[0], w.toFloat(), h.toFloat())
        GLES30.glUniform1f(u[1], t)
        GLES30.glUniform2f(u[2], tiltX, tiltY)
        GLES30.glUniform1f(u[3], zoom)
        GLES30.glUniform1f(u[4], dim)
        GLES30.glUniform1f(u[5], scroll)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // Limite de ~30 fps para ahorrar bateria.
        val ms = (System.nanoTime() - start) / 1_000_000
        if (ms < 33) {
            try {
                Thread.sleep(33 - ms)
            } catch (e: InterruptedException) {
            }
        }
    }

    companion object {
        private const val VS = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val FS = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec2 uTilt;
uniform float uZoom;
uniform float uDim;
uniform float uScroll;
out vec4 o;

float h3(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vn(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(h3(i), h3(i + vec3(1.0, 0.0, 0.0)), f.x),
            mix(h3(i + vec3(0.0, 1.0, 0.0)), h3(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
        mix(mix(h3(i + vec3(0.0, 0.0, 1.0)), h3(i + vec3(1.0, 0.0, 1.0)), f.x),
            mix(h3(i + vec3(0.0, 1.0, 1.0)), h3(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
        f.z);
}

float fbm(vec3 p, int n) {
    float a = 0.5;
    float s = 0.0;
    for (int i = 0; i < n; i++) {
        s += a * vn(p);
        p = p * 2.03 + 7.1;
        a *= 0.5;
    }
    return s;
}

vec3 rotY(vec3 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);
}

void main() {
    vec2 uv = (gl_FragCoord.xy * 2.0 - uRes) / uRes.y;
    vec3 ro = vec3(0.0);
    vec3 rd = normalize(vec3(uv, -1.6));
    float R = 4.0;
    // El planeta sube a su sitio al encender/desbloquear (uZoom 1 -> 0).
    vec3 C = vec3(uTilt.x * 0.8, -5.5 + uTilt.y * 0.4 - 1.4 * uZoom, -7.0);
    vec3 S = normalize(vec3(-0.65, 0.45, 0.25));

    // Estrellas
    vec3 col = vec3(0.0);
    vec3 g = floor((rd + vec3(uTilt * 0.02, 0.0)) * 220.0);
    float st = h3(g);
    float tw = 0.6 + 0.4 * sin(uTime * 2.0 + st * 60.0);
    col += vec3(step(0.9965, st) * tw);

    vec3 oc = ro - C;
    float b = dot(oc, rd);
    float cc = dot(oc, oc) - R * R;
    float disc = b * b - cc;
    float tc = -b;

    if (disc > 0.0) {
        float t = -b - sqrt(disc);
        vec3 p = ro + rd * t;
        vec3 n = normalize(p - C);
        vec3 q = rotY(n, uTime * 0.02 + uScroll * 0.6);

        float hgt = fbm(q * 2.2, 5);
        float land = smoothstep(0.50, 0.53, hgt);
        vec3 ocean = mix(vec3(0.01, 0.05, 0.16), vec3(0.03, 0.22, 0.42), smoothstep(0.30, 0.50, hgt));
        vec3 ground = mix(vec3(0.10, 0.28, 0.08), vec3(0.45, 0.38, 0.25), smoothstep(0.55, 0.65, hgt));
        ground = mix(ground, vec3(0.9), smoothstep(0.66, 0.72, hgt));
        vec3 albedo = mix(ocean, ground, land);

        float cl = fbm(q * 4.0 + vec3(uTime * 0.01, 0.0, 0.0), 4);
        float cloud = smoothstep(0.50, 0.68, cl) * 0.9;

        float diff = max(dot(n, S), 0.0);
        float spec = pow(max(dot(reflect(rd, n), S), 0.0), 40.0) * (1.0 - land) * 0.6;
        vec3 lit = albedo * (0.04 + diff * 1.1) + spec;
        lit = mix(lit, vec3(0.05 + diff), cloud);

        // Luces de ciudades en el lado nocturno
        float night = smoothstep(0.15, -0.05, dot(n, S));
        float city = step(0.78, vn(q * 40.0)) * land * (1.0 - cloud);
        lit += vec3(1.0, 0.8, 0.45) * city * night * 0.9;

        // Atmosfera en el borde
        float fr = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
        lit += vec3(0.25, 0.5, 1.0) * fr * (0.15 + diff * 0.9);
        col = lit;
    } else if (tc > 0.0) {
        // Resplandor de la atmosfera sobre el horizonte
        vec3 cp = ro + rd * tc;
        vec3 dir = normalize(cp - C);
        float d = length(cp - C) - R;
        float glow = exp(-d * 6.0);
        float lt = clamp(dot(dir, S) * 0.7 + 0.4, 0.0, 1.0);
        col += vec3(0.2, 0.45, 1.0) * glow * lt * 1.2;
    }

    col = 1.0 - exp(-col * 1.3);
    float l = dot(col, vec3(0.3, 0.59, 0.11));
    col = mix(col, vec3(l), uDim * 0.6) * mix(1.0, 0.35, uDim);
    o = vec4(col, 1.0);
}
"""
    }
}
