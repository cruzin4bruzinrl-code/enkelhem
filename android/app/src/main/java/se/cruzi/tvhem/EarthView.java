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
    private int prog, uRes, uRot, uCld, uC, uR, uE, uT, uEB, uL;
    private volatile float target;   // 0 = home (still, cap at the bottom), 1 = screensaver (whole globe, turning)
    private float prog01;
    private double rot = 0.18, cld, simT;
    private long last;
    private int bw, bh;
    private long t0 = SystemClock.uptimeMillis();
    private volatile boolean slow;

    private static final String VS = "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}";
    private static final String FS = "precision highp float;\n" +
        "uniform vec2 uRes,uC;uniform float uRot,uCld,uR,uE,uT,uEB;uniform vec3 uL;uniform sampler2D uDay,uAux;\n" +
        "const vec2 S=vec2(960.,560.);const float K=.34;\n" +
        "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n" +
        "vec2 orb(float a,float T,float ph){float t=uT*6.2831853/T+ph;return vec2(cos(t),sin(t));}\n" +
        "vec3 orbit(vec3 col,vec2 sp,float a,float A){vec2 q=(sp-S)/vec2(a,a*K);float l=max(length(q),1e-3);\n" +
        " float g=length((q/l)/vec2(a,a*K));float dd=abs(l-1.)/g;return col+vec3(.45,.6,1.)*(1.-smoothstep(.4,1.6,dd))*.13*A;}\n" +
        "vec3 planet(vec3 col,vec2 sp,float a,float T,float ph,float sz,vec3 c1,vec3 c2,float kind,float pass,float A){\n" +
        " vec2 o=orb(a,T,ph);if(step(0.,o.y)!=pass)return col;\n" +
        " vec2 P=S+vec2(o.x*a,o.y*a*K);float r=sz*(1.+.12*o.y);\n" +
        " vec2 p=(sp-P)/r;if(dot(p,p)>6.)return col;float r2=dot(p,p);\n" +
        " vec2 tl=S-P;vec3 L=normalize(vec3(tl.x,-tl.y,length(tl)*.9+1.));\n" +
        " if(r2<1.){vec3 n=vec3(p.x,-p.y,sqrt(1.-r2));\n" +
        "  float band=kind>.5?.5+.5*sin(n.y*16.+sin(n.y*6.+n.x*2.)*1.4):.5+.5*sin(n.x*7.+n.y*11.)*sin(n.y*5.);\n" +
        "  vec3 base=mix(c1,c2,band);float dif=clamp(dot(n,L),0.,1.);\n" +
        "  vec3 pc=base*(.03+1.15*dif)+vec3(.5,.6,.8)*pow(1.-n.z,3.)*.25*dif;\n" +
        "  col=mix(col,pc,clamp((1.-sqrt(r2))*r*1.5,0.,1.)*A);}\n" +
        " if(kind>1.5){vec2 q=vec2(p.x*.92+p.y*.39,(p.y*.92-p.x*.39)/.36);float rr=length(q);\n" +
        "  float ring=smoothstep(1.3,1.42,rr)*(1.-smoothstep(2.05,2.25,rr))*(.55+.3*sin(rr*38.));\n" +
        "  if(ring>0.&&(r2>1.||q.y>0.)){float sh=r2<1.?1.:clamp(.35+.65*smoothstep(-.2,.6,dot(normalize(p),normalize(vec2(tl.x,tl.y)))+.4),0.,1.);\n" +
        "   col=mix(col,vec3(.86,.78,.6)*sh,ring*.8*A);}}\n" +
        " return col;}\n" +
        "vec3 earth(vec3 col,vec2 sp){float R=uR;vec2 C=uC;vec2 p=(sp-C)/R;p.y=-p.y;float r2=dot(p,p);vec3 L=uL;\n" +
        " float lit=clamp(dot(normalize(vec3(p,.25)),L)*.7+.45,.12,1.);\n" +
        " if(r2>1.){float d=(sqrt(r2)-1.)*R;float sc=clamp(R/90.,.12,1.);if(d>420.*sc)return col;\n" +
        "  float a=(exp(-d/(22.*sc))*.75+exp(-d/(70.*sc))*.28)*lit;return mix(col,vec3(.36,.62,1.),clamp(a,0.,1.));}\n" +
        " float z=sqrt(1.-r2);vec3 n=vec3(p,z);\n" +
        " float cr=cos(.38),sr=sin(.38);vec3 q=vec3(n.x*cr-n.y*sr,n.x*sr+n.y*cr,n.z);\n" +
        " float ct=cos(.12),st=sin(.12);vec3 b=vec3(q.x,q.y*ct+q.z*st,-q.y*st+q.z*ct);\n" +
        " float lon=atan(b.x,b.z),lat=asin(clamp(b.y,-1.,1.));\n" +
        " float v=.5-lat/3.14159265;float u=fract(lon/6.2831853+uRot);\n" +
        " float dif=dot(n,L),day=clamp(dif,0.,1.);\n" +
        " vec3 base=texture2D(uDay,vec2(u,v)).rgb;float lights=texture2D(uAux,vec2(u,v)).r;\n" +
        " float cl=clamp(texture2D(uAux,vec2(fract(u+uCld),v)).g*1.5,0.,1.);\n" +
        " vec3 c=base*(.05+1.25*day);\n" +
        " float ocean=clamp((base.b-max(base.r,base.g)*1.05)*6.,0.,1.);\n" +
        " c+=vec3(1.,.95,.85)*pow(max(dot(reflect(-L,n),vec3(0.,0.,1.)),0.),36.)*.38*ocean*(1.-cl);\n" +
        " c=mix(c,vec3(1.)*(.05+1.05*day),cl*.88);\n" +
        " c+=vec3(1.,.74,.38)*lights*lights*2.2*(1.-smoothstep(-.18,.08,dif))*(1.-cl*.8);\n" +
        " float fr=pow(1.-z,2.3);c=mix(c,vec3(.36,.62,1.)*(.25+.95*clamp(dif+.35,0.,1.)),fr*.8);\n" +
        " float edge=clamp((1.-sqrt(r2))*R/1.6,0.,1.);\n" +
        " return mix(mix(col,vec3(.36,.62,1.),clamp(1.05*lit,0.,1.)),c,edge);}\n" +
        "void main(){\n" +
        " vec2 sp=vec2(gl_FragCoord.x/uRes.x*1920.,(1.-gl_FragCoord.y/uRes.y)*1080.);\n" +
        " vec3 col=vec3(.004,.008,.031);vec2 g=floor(sp/5.);float hs=hash(g);\n" +
        " col+=vec3(.85,.9,1.)*step(.9965,hs)*fract(hs*977.)*uE;\n" +
        " float A=smoothstep(.3,1.,uE);\n" +
        " if(A>0.){\n" +
        "  col+=vec3(.25,.18,.4)*exp(-length((sp-vec2(1500.,250.))/vec2(700.,300.)))*.12*A;\n" +
        "  col=orbit(col,sp,115.,A);col=orbit(col,sp,165.,A);col=orbit(col,sp,225.,A);col=orbit(col,sp,290.,A);\n" +
        "  col=orbit(col,sp,400.,A);col=orbit(col,sp,530.,A);col=orbit(col,sp,660.,A);col=orbit(col,sp,780.,A);\n" +
        "  for(int i=0;i<2;i++){float pass=float(i);\n" +
        "   if(i==1){if(uEB>.5)col=earth(col,sp);\n" +
        "    float d=length(sp-S);float R=52.;float disc=smoothstep(R+1.,R-1.,d);\n" +
        "    vec3 core=mix(vec3(1.,.97,.86),vec3(1.,.6,.16),pow(clamp(d/R,0.,1.),2.2));\n" +
        "    \n" +
        "    col+=vec3(1.,.66,.3)*(exp(-max(d-R,0.)/28.)*.85+exp(-d/240.)*.3)*(1.-disc)*A;\n" +
        "    col=mix(col,core,disc*A);}\n" +
        "   col=planet(col,sp,115.,18.,1.0,6.,vec3(.55,.52,.5),vec3(.75,.72,.68),0.,pass,A);\n" +
        "   col=planet(col,sp,165.,31.,2.6,10.,vec3(.85,.7,.45),vec3(.95,.85,.62),0.,pass,A);\n" +
        "   col=planet(col,sp,290.,57.,4.4,8.,vec3(.7,.32,.18),vec3(.85,.5,.3),0.,pass,A);\n" +
        "   col=planet(col,sp,400.,156.,.4,30.,vec3(.72,.55,.4),vec3(.95,.88,.76),1.,pass,A);\n" +
        "   col=planet(col,sp,530.,257.,5.5,25.,vec3(.8,.7,.5),vec3(.95,.88,.68),2.,pass,A);\n" +
        "   col=planet(col,sp,660.,457.,3.2,16.,vec3(.55,.82,.86),vec3(.7,.9,.92),1.,pass,A);\n" +
        "   col=planet(col,sp,780.,665.,2.1,15.,vec3(.22,.38,.85),vec3(.35,.52,.95),1.,pass,A);}\n" +
        " }\n" +
        " if(uEB<.5)col=earth(col,sp);\n" +
        " gl_FragColor=vec4(col,1.);}\n";

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
        uE = GLES20.glGetUniformLocation(prog, "uE");
        uT = GLES20.glGetUniformLocation(prog, "uT");
        uEB = GLES20.glGetUniformLocation(prog, "uEB");
        uL = GLES20.glGetUniformLocation(prog, "uL");
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
        // glide from the home view (Earth cap at the bottom) out to the whole solar system, over 6 s
        if (prog01 < target) prog01 = Math.min(target, prog01 + dt / 6f);
        else if (prog01 > target) prog01 = Math.max(target, prog01 - dt / 1.2f);
        float e = prog01 * prog01 * (3 - 2 * prog01);
        if (e > 0) simT += dt; else simT = 0;
        rot += dt * e / 60.0;
        cld += dt * e / 900.0;
        // Earth on its orbit (same numbers as in the shader: a=225, period 40 s, start angle 0.9)
        double ang = simT * 2 * Math.PI / 40.0 + 0.9;
        float px = 960f + (float) Math.cos(ang) * 225f, py = 560f + (float) Math.sin(ang) * 225f * 0.34f;
        float pr = 11f * (1f + 0.12f * (float) Math.sin(ang));
        float ec = 1f - (1f - e) * (1f - e) * (1f - e);
        float cx = 960f + (px - 960f) * ec, cy = 1700f + (py - 1700f) * ec;
        float r = (float) Math.exp(Math.log(1000) + (Math.log(pr) - Math.log(1000)) * e);
        // light: fixed on the home screen, from the Sun in the solar system
        float lx = -.6f, ly = .62f, lz = .5f;
        float vx = 960f - px, vy = -(560f - py), vl = (float) Math.hypot(vx, vy);
        float sx = vx, sy = vy, sz = vl * .9f + 1f, sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        lx += (sx / sl - lx) * e; ly += (sy / sl - ly) * e; lz += (sz / sl - lz) * e;
        float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        GLES20.glUniform2f(uC, cx, cy);
        GLES20.glUniform1f(uR, r);
        GLES20.glUniform1f(uE, e);
        GLES20.glUniform1f(uT, (float) (simT % 200000.0));
        GLES20.glUniform1f(uEB, (e > 0.98f && Math.sin(ang) < 0) ? 1f : 0f);
        GLES20.glUniform3f(uL, lx / ll, ly / ll, lz / ll);
        GLES20.glUniform1f(uRot, (float) (rot % 1.0));
        GLES20.glUniform1f(uCld, (float) (cld % 1.0));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        if (target == 0 && prog01 == 0) {   // home: the Earth stands still, so a couple of frames a second is plenty
            last = 0;
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        } else if (target == 1 && prog01 == 1) {   // the solar system moves slowly: 30 frames a second is plenty
            try { Thread.sleep(22); } catch (InterruptedException ignored) {}
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
