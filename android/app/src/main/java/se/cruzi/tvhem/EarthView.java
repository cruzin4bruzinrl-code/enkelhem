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
    private int prog, uRes, uRot, uCld, uC, uR, uE, uT, uEB, uL, uM;
    private volatile float target;   // 0 = home (still, cap at the bottom), 1 = screensaver (whole globe, turning)
    private float prog01;
    private double rot = 0.18, cld, simT;
    private long last;
    private int bw, bh;
    private long t0 = SystemClock.uptimeMillis();
    private volatile boolean slow;
    volatile float shownE;
    volatile double shownT;

    private static final String VS = "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}";
    private static final String FS = "precision highp float;\n" +
        "uniform vec2 uRes,uC;uniform float uRot,uCld,uR,uE,uT,uEB;uniform vec3 uL,uM;uniform sampler2D uDay,uAux;\n" +
        "const vec2 S=vec2(960.,560.);const float K=.34;\n" +
        "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n" +
        "float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);\n" +
        " return mix(mix(hash(i),hash(i+vec2(1.,0.)),f.x),mix(hash(i+vec2(0.,1.)),hash(i+vec2(1.,1.)),f.x),f.y);}\n" +
        "float fbm(vec2 p){return noise(p)*.55+noise(p*2.1)*.3+noise(p*4.3)*.15;}\n" +
        "vec2 orb(float a,float T,float ph){float t=uT*6.2831853/T+ph;return vec2(cos(t),sin(t));}\n" +
        "vec3 orbit(vec3 col,vec2 sp,float a,float A){vec2 q=(sp-S)/vec2(a,a*K);float l=max(length(q),1e-3);\n" +
        " float g=length((q/l)/vec2(a,a*K));float dd=abs(l-1.)/g;return col+vec3(.45,.6,1.)*(1.-smoothstep(.4,1.6,dd))*.11*A;}\n" +
        "vec3 surface(vec3 n,float kind,float spin){\n" +
        " float lon=atan(n.x,n.z)+spin,lat=n.y;vec2 uv=vec2(lon*1.6,lat*3.);\n" +
        " if(kind<.5){float c=fbm(uv*3.);float cr=smoothstep(.62,.7,noise(uv*6.))*.25;return vec3(.42,.4,.38)+vec3(.3)*c-cr;}\n" +
        " if(kind<1.5){float sw=fbm(uv*2.+vec2(spin*.3,0.));return mix(vec3(.88,.74,.48),vec3(.98,.92,.75),sw);}\n" +
        " if(kind<2.5){float c=fbm(uv*2.5);vec3 m=mix(vec3(.62,.27,.14),vec3(.86,.5,.3),c);m=mix(m,vec3(.35,.17,.1),smoothstep(.55,.75,fbm(uv*4.+3.)));\n" +
        "  return mix(m,vec3(.95,.95,.98),smoothstep(.82,.9,abs(lat)));}\n" +
        " if(kind<3.5){float w=fbm(vec2(lon*3.,lat*2.))*.6;float b=.5+.5*sin(lat*18.+w*3.+sin(lat*5.)*1.5);\n" +
        "  vec3 c=mix(vec3(.66,.48,.33),vec3(.96,.9,.8),b);\n" +
        "  vec2 d=vec2(mod(lon+1.,6.2831853)-3.1416,lat+.33)*vec2(1.,2.4);float spot=1.-smoothstep(.12,.2,length(d));\n" +
        "  return mix(c,vec3(.78,.32,.2),spot*.85);}\n" +
        " if(kind<4.5){float b=.5+.5*sin(lat*14.+fbm(vec2(lon*2.,lat*3.))*1.2);return mix(vec3(.82,.72,.5),vec3(.96,.9,.72),b);}\n" +
        " if(kind<5.5){return mix(vec3(.58,.84,.88),vec3(.72,.92,.94),.5+.5*sin(lat*9.));}\n" +
        " float b=.5+.5*sin(lat*12.+fbm(vec2(lon*2.,lat*3.)));vec3 c=mix(vec3(.16,.3,.78),vec3(.3,.48,.92),b);\n" +
        " vec2 d=vec2(mod(lon+2.,6.2831853)-3.1416,lat-.25)*vec2(1.,2.2);return mix(c,vec3(.08,.12,.35),(1.-smoothstep(.1,.18,length(d)))*.8);}\n" +
        "vec3 planet(vec3 col,vec2 sp,float a,float T,float ph,float sz,float kind,float pass,float A){\n" +
        " vec2 o=orb(a,T,ph);if(step(0.,o.y)!=pass)return col;\n" +
        " vec2 P=S+vec2(o.x*a,o.y*a*K);float r=sz*(1.+.12*o.y);\n" +
        " vec2 p=(sp-P)/r;float r2=dot(p,p);if(r2>6.)return col;\n" +
        " vec2 tl=S-P;vec3 L=normalize(vec3(tl.x,-tl.y,length(tl)*.9+1.));\n" +
        " bool ringed=kind>3.5&&kind<5.5;\n" +
        " vec2 rq=vec2(p.x*.92+p.y*.39,(p.y*.92-p.x*.39)/.36);if(kind>4.5)rq=vec2(p.y*.97-p.x*.24,(p.x*.97+p.y*.24)/.3);float rr=length(rq);\n" +
        " if(r2<1.){vec3 n=vec3(p.x,-p.y,sqrt(1.-r2));\n" +
        "  vec3 base=surface(n,kind,uT*6.2831853/(kind>2.5?9.:20.));\n" +
        "  float dif=clamp(dot(n,L),0.,1.);\n" +
        "  vec3 pc=base*(.03+1.15*dif)+vec3(.55,.65,.9)*pow(1.-n.z,3.)*.22*dif;\n" +
        "  if(kind>4.5&&kind<5.5)pc*=1.;\n" +
        "  if(kind>3.5&&kind<4.5){float sh=smoothstep(.04,.0,abs(rq.y+.08))*step(rr,2.2)*step(1.,rr);pc*=1.-sh*.0;}\n" +
        "  col=mix(col,pc,clamp((1.-sqrt(r2))*r*1.5,0.,1.)*A);}\n" +
        " if(ringed){float ring;\n" +
        "  if(kind<4.5)ring=smoothstep(1.28,1.38,rr)*(1.-smoothstep(2.15,2.3,rr))*(.6+.25*sin(rr*42.))*(1.-.85*smoothstep(1.86,1.9,rr)*(1.-smoothstep(1.95,1.99,rr)))*(rr<1.55?.55:1.);\n" +
        "  else ring=smoothstep(1.55,1.6,rr)*(1.-smoothstep(1.66,1.72,rr))*.35;\n" +
        "  if(ring>0.&&(r2>1.||rq.y>0.)){\n" +
        "   col=mix(col,(kind<4.5?vec3(.88,.8,.62):vec3(.7,.85,.9))*(.35+.65*clamp(dot(normalize(vec3(p.x,-p.y,.8)),L)+.3,0.,1.)),ring*.85*A);}}\n" +
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
        "vec3 moon(vec3 col,vec2 sp,float A){if(uM.z<=0.)return col;vec2 p=(sp-uM.xy)/uM.z;float r2=dot(p,p);if(r2>1.)return col;\n" +
        " vec3 n=vec3(p.x,-p.y,sqrt(1.-r2));vec2 tl=S-uM.xy;vec3 L=normalize(vec3(tl.x,-tl.y,length(tl)*.9+1.));\n" +
        " vec3 c=surface(n,0.,0.)*1.2*(.04+1.1*clamp(dot(n,L),0.,1.));return mix(col,c,clamp((1.-sqrt(r2))*uM.z*1.5,0.,1.)*A);}\n" +
        "void main(){\n" +
        " vec2 sp=vec2(gl_FragCoord.x/uRes.x*1920.,(1.-gl_FragCoord.y/uRes.y)*1080.);\n" +
        " vec3 col=vec3(.004,.008,.031);vec2 g=floor(sp/5.);float hs=hash(g);\n" +
        " col+=vec3(.85,.9,1.)*step(.9965,hs)*fract(hs*977.)*uE;\n" +
        " float A=smoothstep(.3,1.,uE);\n" +
        " if(A>0.){\n" +
        "  float n1=length((sp-vec2(1450.,230.))/vec2(800.,330.));if(n1<4.)col+=vec3(.3,.18,.45)*fbm(sp/260.+3.)*exp(-n1)*.22*A;\n" +
        "  float n2=length((sp-vec2(330.,900.))/vec2(700.,280.));if(n2<4.)col+=vec3(.12,.2,.4)*fbm(sp/300.+9.)*exp(-n2)*.16*A;\n" +
        "  col=orbit(col,sp,115.,A);col=orbit(col,sp,165.,A);col=orbit(col,sp,225.,A);col=orbit(col,sp,290.,A);\n" +
        "  col=orbit(col,sp,405.,A);col=orbit(col,sp,535.,A);col=orbit(col,sp,665.,A);col=orbit(col,sp,785.,A);\n" +
        "  vec2 bq=(sp-S)/vec2(1.,K);float br=length(bq);\n" +
        "  if(abs(br-347.)<20.){float ba=atan(bq.y,bq.x)-uT*.025;vec2 cell=vec2(floor(ba*140.),floor(br/4.));float h=hash(cell);\n" +
        "   col+=vec3(.75,.7,.62)*step(.9,h)*(.3+.7*fract(h*91.))*(1.-smoothstep(10.,20.,abs(br-347.)))*.4*A;}\n" +
        "  for(int i=0;i<2;i++){float pass=float(i);\n" +
        "   if(i==1){if(uEB>.5){col=earth(col,sp);col=moon(col,sp,A);}\n" +
        "    float d=length(sp-S);float R=54.;float disc=smoothstep(R+1.,R-1.,d);\n" +
        "    float gr=d<R+1.?fbm((sp-S)/7.+vec2(uT*.15,-uT*.1)):.5;\n" +
        "    vec3 core=mix(vec3(1.,.96,.82),vec3(1.,.55,.12),pow(clamp(d/R,0.,1.),2.2))*(.9+.18*gr);\n" +
        "    float ang=atan(sp.y-S.y,sp.x-S.x);float cor=d<R+260.?exp(-max(d-R,0.)/(26.+10.*fbm(vec2(ang*3.,uT*.2)))):0.;\n" +
        "    col+=vec3(1.,.66,.3)*(cor*.85+exp(-d/240.)*.3)*(1.-disc)*A;\n" +
        "    col=mix(col,core,disc*A);}\n" +
        "   col=planet(col,sp,115.,18.,1.0,7.,0.,pass,A);\n" +
        "   col=planet(col,sp,165.,31.,2.6,11.,1.,pass,A);\n" +
        "   col=planet(col,sp,290.,57.,4.4,9.,2.,pass,A);\n" +
        "   col=planet(col,sp,405.,156.,.4,34.,3.,pass,A);\n" +
        "   col=planet(col,sp,535.,257.,5.5,28.,4.,pass,A);\n" +
        "   col=planet(col,sp,665.,457.,3.2,17.,5.,pass,A);\n" +
        "   col=planet(col,sp,785.,665.,2.1,16.,6.,pass,A);}\n" +
        " }\n" +
        " if(uEB<.5){col=earth(col,sp);col=moon(col,sp,A);}\n" +
        " gl_FragColor=vec4(col,1.);}\n";

    public EarthView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Orbit table shared with the labels (same numbers as in the shader): a, period s, start angle, size. */
    static final float[][] PLANETS = {
        {115, 18, 1.0f, 7}, {165, 31, 2.6f, 11}, {225, 40, 0.9f, 12}, {290, 57, 4.4f, 9},
        {405, 156, 0.4f, 34}, {535, 257, 5.5f, 28}, {665, 457, 3.2f, 17}, {785, 665, 2.1f, 16}};
    static final String[] NAMES = {"Merkurius", "Venus", "Jorden", "Mars", "Jupiter", "Saturnus", "Uranus", "Neptunus"};

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
        uM = GLES20.glGetUniformLocation(prog, "uM");
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
        float pr = 12f * (1f + 0.12f * (float) Math.sin(ang));
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
        // the Moon circles the Earth (once every 8 s), only once the Earth is out on its orbit
        double ma = simT * 2 * Math.PI / 8.0;
        float mr = e > 0.7f ? 4.5f * (e - 0.7f) / 0.3f : 0f;
        GLES20.glUniform3f(uM, cx + (float) Math.cos(ma) * 24f * e, cy + (float) Math.sin(ma) * 24f * 0.45f * e, mr);
        shownE = e; shownT = simT;
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
    /** Only the screensaver uses the solar system: otherwise the view is not even composited. */
    void setShown(boolean on) {
        setRenderMode(on ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
        setVisibility(on ? VISIBLE : INVISIBLE);
    }
}
