package se.cruzi.tvhem;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The screensaver: 4000 glowing particles drifting on a slow flow, drawn natively on the GPU.
 * Every particle's path is computed in the vertex shader from its seed and the time, so the CPU does nothing per frame.
 * The view is invisible (not even composited) except while the screensaver runs.
 */
public class EarthView extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final int N = 4000;
    private int bgProg, pProg, bgRes, bgA, pRes, pT, pA, aSeed, aP;
    private FloatBuffer seeds, quad;
    private volatile float target;
    private float fade;
    private long last;
    private final long t0 = SystemClock.uptimeMillis();
    private int w = 1920, h = 1080;

    private static final String BG_VS = "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}";
    private static final String BG_FS =
        "precision mediump float;uniform vec2 uRes;uniform float uA;\n" +
        "void main(){vec2 u=gl_FragCoord.xy/uRes;\n" +
        " vec3 c=mix(vec3(.010,.012,.035),vec3(.020,.018,.055),u.y);\n" +
        " c+=vec3(.10,.05,.20)*exp(-length((u-vec2(.78,.72))*vec2(1.6,2.4))*2.2)*.55;\n" +
        " c+=vec3(.03,.09,.20)*exp(-length((u-vec2(.22,.30))*vec2(1.4,2.2))*2.2)*.55;\n" +
        " gl_FragColor=vec4(c*uA,1.);}";
    private static final String P_VS =
        "attribute vec4 aSeed;uniform float uT,uA;uniform vec2 uRes;varying vec4 vC;\n" +
        "void main(){\n" +
        " float z=aSeed.z,t=uT;\n" +
        " float sp=.006+.020*z;\n" +
        " vec2 p=aSeed.xy;\n" +
        " p.x=p.x+t*sp*.55+.035*sin(t*.11+aSeed.w*6.283);\n" +
        " p.y=p.y+t*sp*.18+.045*sin(t*.17+aSeed.x*9.);\n" +
        " p+=.025*vec2(sin(p.y*6.283*1.5+t*.21),cos(p.x*6.283*1.2-t*.16));\n" +
        " p=fract(p);\n" +
        " float big=step(.988,aSeed.w);\n" +
        " float s=mix(1.6,7.5,z*z*z)*(1.+big*3.5)*uRes.y/1080.;\n" +
        " float tw=.55+.45*sin(t*(.6+z*2.2)+aSeed.w*41.);\n" +
        " float a=(.22+.78*z)*tw*(1.-big*.65)*uA;\n" +
        " vec3 c=aSeed.w<.55?vec3(.50,.70,1.):aSeed.w<.85?vec3(.72,.55,1.):vec3(1.,.78,.55);\n" +
        " vC=vec4(c,a);\n" +
        " gl_PointSize=s*3.;\n" +
        " gl_Position=vec4(p*2.-1.,0.,1.);}";
    private static final String P_FS =
        "precision mediump float;varying vec4 vC;\n" +
        "void main(){vec2 d=gl_PointCoord-.5;float r=dot(d,d)*4.;\n" +
        " float g=exp(-r*9.)+exp(-r*2.5)*.25;\n" +
        " gl_FragColor=vec4(vC.rgb*g*vC.a,1.);}";

    public EarthView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Random r = new Random(7);
        float[] s = new float[N * 4];
        for (int i = 0; i < N; i++) {
            s[i * 4] = r.nextFloat();
            s[i * 4 + 1] = r.nextFloat();
            s[i * 4 + 2] = (float) Math.pow(r.nextFloat(), 1.6);   // most particles small and far away
            s[i * 4 + 3] = r.nextFloat();
        }
        seeds = ByteBuffer.allocateDirect(s.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        seeds.put(s).position(0);
        float[] q = {-1, -1, 3, -1, -1, 3};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
    }

    void setSlow(boolean s) { }

    private static int shader(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        return sh;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        bgProg = program(BG_VS, BG_FS);
        bgRes = GLES20.glGetUniformLocation(bgProg, "uRes");
        bgA = GLES20.glGetUniformLocation(bgProg, "uA");
        aP = GLES20.glGetAttribLocation(bgProg, "p");
        pProg = program(P_VS, P_FS);
        pRes = GLES20.glGetUniformLocation(pProg, "uRes");
        pT = GLES20.glGetUniformLocation(pProg, "uT");
        pA = GLES20.glGetUniformLocation(pProg, "uA");
        aSeed = GLES20.glGetAttribLocation(pProg, "aSeed");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        w = width; h = height;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        fade += (target > fade ? 1 : -1) * dt / (target > fade ? 2.0f : 0.8f);
        fade = Math.max(0, Math.min(1, fade));
        float a = fade * fade * (3 - 2 * fade);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(bgProg);
        GLES20.glUniform2f(bgRes, w, h);
        GLES20.glUniform1f(bgA, a);
        GLES20.glEnableVertexAttribArray(aP);
        GLES20.glVertexAttribPointer(aP, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glDisableVertexAttribArray(aP);

        if (a > 0.001f) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);   // light adds up: overlapping particles glow
            GLES20.glUseProgram(pProg);
            GLES20.glUniform2f(pRes, w, h);
            GLES20.glUniform1f(pT, ((now - t0) / 1000f) % 3600f);
            GLES20.glUniform1f(pA, a);
            GLES20.glEnableVertexAttribArray(aSeed);
            GLES20.glVertexAttribPointer(aSeed, 4, GLES20.GL_FLOAT, false, 0, seeds);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, N);
            GLES20.glDisableVertexAttribArray(aSeed);
        }
        if (target == 0 && fade == 0) setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Screensaver on: the particles fade in and drift. Off: they fade out. */
    void setSaver(boolean on) {
        target = on ? 1f : 0f;
        last = 0;
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Only the screensaver uses this view: otherwise it is not even composited. */
    void setShown(boolean on) {
        if (on) setRenderMode(RENDERMODE_CONTINUOUSLY);
        setVisibility(on ? VISIBLE : INVISIBLE);
    }
}
