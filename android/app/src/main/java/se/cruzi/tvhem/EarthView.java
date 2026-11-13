package se.cruzi.tvhem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The rotating Earth, drawn natively on its own GL thread behind the (transparent) WebView.
 * It never waits for the web page, so it keeps turning smoothly while the planets move.
 * Covers the whole screen (only the bottom part has the Earth); the buffer is rendered at reduced size and scaled up by the hardware.
 */
public class EarthView extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final float QUALITY = 0.55f;
    private int prog, uRes, uRot, uCld, uC, uR, uStar;
    private volatile float target;   // 0 = home (still, cap at the bottom), 1 = screensaver (whole globe, turning)
    private float prog01;
    private double rot = 0.18, cld;
    private long last;
    private int bw, bh;
    private long t0 = SystemClock.uptimeMillis();
    private volatile boolean slow;

    private static final String VS = "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}";
    private static final String FS = "precision highp float;\n"
        + "uniform vec2 uRes,uC;uniform float uRot,uCld,uR,uStar;uniform sampler2D uDay,uAux;\n"
        + "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n"
        + "void main(){\n"
        + " vec2 sp=vec2(gl_FragCoord.x/uRes.x*1920.,(1.-gl_FragCoord.y/uRes.y)*1080.);\n"
        + " float R=uR;vec2 C=uC;\n"
        + " vec3 bg=vec3(.004,.008,.031);vec2 g=floor(sp/5.);float hs=hash(g);\n"
        + " bg+=vec3(.85,.9,1.)*step(.9965,hs)*fract(hs*977.)*uStar;\n"
        + " vec2 p=(sp-C)/R;p.y=-p.y;float r2=dot(p,p);\n"
        + " vec3 L=normalize(vec3(-.6,.62,.5));\n"
        + " float lit=clamp(dot(normalize(vec3(p,.25)),L)*.7+.45,.12,1.);\n"
        + " if(r2>1.){float d=(sqrt(r2)-1.)*R;float a=(exp(-d/22.)*.75+exp(-d/70.)*.28)*lit;\n"
        + "   gl_FragColor=vec4(mix(bg,vec3(.36,.62,1.),clamp(a,0.,1.)),1.);return;}\n"
        + " float z=sqrt(1.-r2);vec3 n=vec3(p,z);\n"
        + " float cr=cos(.38),sr=sin(.38);vec3 q=vec3(n.x*cr-n.y*sr,n.x*sr+n.y*cr,n.z);\n"
        + " float ct=cos(.12),st=sin(.12);vec3 b=vec3(q.x,q.y*ct+q.z*st,-q.y*st+q.z*ct);\n"
        + " float lon=atan(b.x,b.z),lat=asin(clamp(b.y,-1.,1.));\n"
        + " float v=.5-lat/3.14159265;float u=fract(lon/6.2831853+uRot);\n"
        + " float dif=dot(n,L),day=clamp(dif,0.,1.);\n"
        + " vec3 base=texture2D(uDay,vec2(u,v)).rgb;float lights=texture2D(uAux,vec2(u,v)).r;\n"
        + " float cl=clamp(texture2D(uAux,vec2(fract(u+uCld),v)).g*1.5,0.,1.);\n"
        + " vec3 col=base*(.05+1.25*day);\n"
        + " float ocean=clamp((base.b-max(base.r,base.g)*1.05)*6.,0.,1.);\n"
        + " col+=vec3(1.,.95,.85)*pow(max(dot(reflect(-L,n),vec3(0.,0.,1.)),0.),36.)*.38*ocean*(1.-cl);\n"
        + " col=mix(col,vec3(1.)*(.05+1.05*day),cl*.88);\n"
        + " col+=vec3(1.,.74,.38)*lights*lights*2.2*(1.-smoothstep(-.18,.08,dif))*(1.-cl*.8);\n"
        + " float fr=pow(1.-z,2.3);col=mix(col,vec3(.36,.62,1.)*(.25+.95*clamp(dif+.35,0.,1.)),fr*.8);\n"
        + " float edge=clamp((1.-sqrt(r2))*R/1.6,0.,1.);\n"
        + " gl_FragColor=vec4(mix(mix(bg,vec3(.36,.62,1.),clamp(1.05*lit,0.,1.)),col,edge),1.);}\n";

    public EarthView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Called from the page: half the frame rate in performance mode. */
    void setSlow(boolean s) { slow = s; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) getHolder().setFixedSize(Math.round(w * QUALITY), Math.round(h * QUALITY));
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private void texture(int unit, String asset) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        try (InputStream in = getContext().getAssets().open(asset)) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
            bm.recycle();
        } catch (Exception ignored) {}
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(prog);
        GLES20.glUseProgram(prog);
        FloatBuffer fb = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(new float[]{-1, -1, 3, -1, -1, 3}).position(0);
        int loc = GLES20.glGetAttribLocation(prog, "p");
        GLES20.glEnableVertexAttribArray(loc);
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, fb);
        uRes = GLES20.glGetUniformLocation(prog, "uRes");
        uRot = GLES20.glGetUniformLocation(prog, "uRot");
        uCld = GLES20.glGetUniformLocation(prog, "uCld");
        uC = GLES20.glGetUniformLocation(prog, "uC");
        uR = GLES20.glGetUniformLocation(prog, "uR");
        uStar = GLES20.glGetUniformLocation(prog, "uStar");
        texture(0, "day.jpg");
        texture(1, "aux.jpg");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uDay"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uAux"), 1);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        bw = w; bh = h;
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUniform2f(uRes, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min((now - last) / 1000f, 0.1f);
        last = now;
        // glide between the home view and the screensaver view over 2.6 s
        if (prog01 < target) prog01 = Math.min(target, prog01 + dt / 2.6f);
        else if (prog01 > target) prog01 = Math.max(target, prog01 - dt / 1.4f);
        float e = prog01 * prog01 * (3 - 2 * prog01);
        rot += dt * e / 80.0;          // one turn in 80 s, only in the screensaver
        cld += dt * e / 900.0;
        GLES20.glUniform2f(uC, 960f, 1700f + (540f - 1700f) * e);
        GLES20.glUniform1f(uR, 1000f + (400f - 1000f) * e);
        GLES20.glUniform1f(uStar, e);
        GLES20.glUniform1f(uRot, (float) (rot % 1.0));
        GLES20.glUniform1f(uCld, (float) (cld % 1.0));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        if (target == 0 && prog01 == 0) {   // home: the Earth stands still, so a couple of frames a second is plenty
            last = 0;
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        } else if (slow) try { Thread.sleep(16); } catch (InterruptedException ignored) {}
    }

    /** Screensaver on: the globe glides up to the middle and starts turning. Off: back to the still cap. */
    void setSaver(boolean on) {
        target = on ? 1f : 0f;
        last = 0;
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Page visible/hidden: when visible draw at least one frame. */
    void setShown(boolean on) {
        setRenderMode(on ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }
}
