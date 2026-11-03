package com.andrej.glasstv.screensaver

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Choreographer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow

/**
 * Colours of a wallpaper's glass waves (same palette as tools/make_wallpapers.py), so the
 * "Liquid Glass" screen saver is the home wallpaper coming to life.
 */
class WavePalette(
    val top: Int,
    val bottom: Int,
    val glowA: Int,
    val glowB: Int,
    val glow: Pair<Float, Float>,
    /** (from, to, alpha) per wave, back to front. */
    val layers: List<Triple<Int, Int, Float>>,
)

val WAVE_PALETTES = mapOf(
    "tahoe" to WavePalette(0x010515, 0x0a1b55, 0x1f4fd1, 0x5a3cff, 0.35f to 0.25f,
        listOf(Triple(0x0e2f8f, 0x3a6cff, 0.62f), Triple(0x1f5fe0, 0x6fd0ff, 0.55f), Triple(0x3b2fd0, 0x8f7bff, 0.5f), Triple(0x0b47c8, 0x47b6ff, 0.6f))),
    "sunrise" to WavePalette(0x12041a, 0x4a0f30, 0xff6a3d, 0xb0236e, 0.28f to 0.3f,
        listOf(Triple(0x8c1d56, 0xff5f6d, 0.6f), Triple(0xd6406a, 0xffa45c, 0.55f), Triple(0xff7a45, 0xffd27a, 0.5f), Triple(0xb8205e, 0xff8a5c, 0.6f))),
    "aurora" to WavePalette(0x010a10, 0x032a33, 0x0fa3a3, 0x6b4dff, 0.3f to 0.25f,
        listOf(Triple(0x05606a, 0x18c7b4, 0.6f), Triple(0x0d8f7a, 0x5ef0a0, 0.5f), Triple(0x3a2fb0, 0x8a6cff, 0.5f), Triple(0x08707a, 0x2fe0c8, 0.6f))),
    "dusk" to WavePalette(0x0b0726, 0x2c1c62, 0xb06bff, 0x5b7bff, 0.26f to 0.28f,
        listOf(Triple(0x4b33c9, 0xa77bff, 0.6f), Triple(0x8a4fe0, 0xff8ad8, 0.52f), Triple(0x5f7bff, 0xb8d0ff, 0.45f), Triple(0x6a3fd8, 0xff9ccf, 0.58f))),
    "graphite" to WavePalette(0x060608, 0x17181d, 0x3b4252, 0x2a3140, 0.35f to 0.3f,
        listOf(Triple(0x262a33, 0x5d6575, 0.6f), Triple(0x3a404c, 0x8d96a8, 0.5f), Triple(0x2b303a, 0x6c7486, 0.55f), Triple(0x31363f, 0xa3acbd, 0.6f))),
    "midnight" to WavePalette(0x000000, 0x050a18, 0x123a9c, 0x000000, 0.3f to 0f,
        listOf(Triple(0x071233, 0x1d4fd8, 0.55f), Triple(0x0a1a4a, 0x3d7bff, 0.45f), Triple(0, 0, 0f), Triple(0, 0, 0f))),
)

/** Animated glass waves rendered by a fragment shader at 960x540, upscaled by the display hardware. */
@SuppressLint("ViewConstructor")
class LiquidGlView(context: Context, palette: WavePalette) : GLSurfaceView(context), Choreographer.FrameCallback {
    private var frame = 0
    private var running = false

    init {
        setEGLContextClientVersion(2)
        holder.setFixedSize(960, 540)
        setRenderer(LiquidRenderer(palette))
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    // 30 fps is plenty for slow waves and halves the GPU work on the TV.
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (frame++ % 2 == 0) requestRender()
        Choreographer.getInstance().postFrameCallback(this)
    }
}

private class LiquidRenderer(palette: WavePalette) : GLSurfaceView.Renderer {
    private val colors = FloatArray(12 * 3)
    private val alphas = FloatArray(4)
    private val glow = floatArrayOf(palette.glow.first, palette.glow.second)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val start = System.nanoTime()
    private var program = 0
    private var width = 960f
    private var height = 540f

    init {
        val list = listOf(palette.top, palette.bottom, palette.glowA, palette.glowB) +
            palette.layers.flatMap { listOf(it.first, it.second) }
        list.forEachIndexed { i, c ->
            colors[i * 3] = linear(c shr 16 and 0xFF)
            colors[i * 3 + 1] = linear(c shr 8 and 0xFF)
            colors[i * 3 + 2] = linear(c and 0xFF)
        }
        palette.layers.forEachIndexed { i, l -> alphas[i] = l.third }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glUseProgram(program)
        val t = ((System.nanoTime() - start) / 1e9).toFloat() % 3600f
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), t)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRes"), width, height)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uColors"), 12, colors, 0)
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(program, "uAlpha"), 4, alphas, 0)
        GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "uGlow"), 1, glow, 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, source: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source)
        GLES20.glCompileShader(it)
    }

    private fun linear(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private companion object {
        const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        // Same construction as the bundled wallpapers: translucent glass bands with a specular
        // rim that catches the light at the crests, slowly drifting.
        const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec2 uRes;
            uniform float uTime;
            uniform vec3 uColors[12];
            uniform float uAlpha[4];
            uniform vec2 uGlow;

            const float TAU = 6.2831853;

            vec3 band(vec3 col, vec2 uv, float base, float a1, float f1, float a2, float f2, float edge,
                      float ph, vec3 ca, vec3 cb, float alpha) {
                if (alpha <= 0.0) return col;
                float x = uv.x;
                float s1 = TAU * f1 * x + ph;
                float s2 = TAU * f2 * x + ph * 1.7 - uTime * 0.05;
                float y = base + a1 * sin(s1) + a2 * sin(s2) + 0.012 * sin(uTime * 0.11 + ph);
                float dy = (a1 * TAU * f1 * cos(s1) + a2 * TAU * f2 * cos(s2)) * 0.5625;
                float d = (uv.y - y) * 1080.0 / sqrt(1.0 + dy * dy);
                float inside = clamp(d / 2.0 + 0.5, 0.0, 1.0);
                float tx = clamp(x + 0.15 * sin(uv.y * 3.0 + ph), 0.0, 1.0);
                vec3 body = mix(ca, cb, tx);
                body *= 1.0 + 0.4 * exp(-max(d, 0.0) / 55.0) - 0.62 * clamp(d / 600.0, 0.0, 1.0);
                col = mix(col, body, inside * alpha);
                float m = 0.5 + 0.5 * sin(TAU * x * 1.15 + ph * 2.2);
                m = 0.22 + 0.78 * m * m / (1.0 + dy * dy * 6.25);
                float rim = (exp(-(d / 2.2) * (d / 2.2)) * 0.9 + exp(-(d / 10.0) * (d / 10.0)) * 0.12) * edge * m;
                float inner = exp(-((d - 16.0) / 5.0) * ((d - 16.0) / 5.0)) * 0.09 * edge * m;
                float above = d < 0.0 ? exp(d / 38.0) * 0.07 * edge * m : 0.0;
                return col + (rim + inner) * mix(vec3(1.0), body, 0.3) + above * cb;
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / uRes;
                uv.y = 1.0 - uv.y;
                float t = uTime;
                vec3 col = mix(uColors[0], uColors[1], uv.y * 0.85 + uv.x * 0.15);
                vec2 ga = vec2(0.2 + 0.05 * sin(t * 0.07), 0.15 + 0.04 * cos(t * 0.05));
                vec2 gb = vec2(0.8 + 0.05 * cos(t * 0.06), 0.3 + 0.05 * sin(t * 0.08));
                vec2 aspect = vec2(1.0, 0.5625);
                col += exp(-dot((uv - ga) * aspect, (uv - ga) * aspect) / 0.2) * uColors[2] * uGlow.x;
                col += exp(-dot((uv - gb) * aspect, (uv - gb) * aspect) / 0.14) * uColors[3] * uGlow.y;

                col = band(col, uv, 0.60, 0.10, 0.42, 0.028, 1.30, 0.9, 0.4 + t * 0.045, uColors[4], uColors[5], uAlpha[0]);
                col = band(col, uv, 0.68, 0.085, 0.58, 0.022, 1.75, 1.0, 1.9 - t * 0.038, uColors[6], uColors[7], uAlpha[1]);
                col = band(col, uv, 0.77, 0.075, 0.50, 0.020, 1.45, 0.9, 3.1 + t * 0.052, uColors[8], uColors[9], uAlpha[2]);
                col = band(col, uv, 0.87, 0.055, 0.80, 0.014, 2.20, 0.8, 4.6 - t * 0.06, uColors[10], uColors[11], uAlpha[3]);

                col *= clamp(1.0 - 0.35 * ((uv.x - 0.5) * (uv.x - 0.5) + (uv.y - 0.45) * (uv.y - 0.45)) * 1.6, 0.0, 1.0);
                col = pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2));
                float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233)) + t) * 43758.5453);
                gl_FragColor = vec4(col + (n - 0.5) / 160.0, 1.0);
            }
        """
    }
}
