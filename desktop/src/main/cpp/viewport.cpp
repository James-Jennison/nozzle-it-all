#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

// Private offscreen context. Never shares state with Skiko, Swing or a window.
// The serialized JVM owner binds for one synchronous render and unbinds in finally:
// coroutine dispatchers may migrate between OS threads, not during this call stack.
namespace {
const char* vertex = R"GLSL(#version 300 es
precision highp float;
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 face;
layout(location=3) in vec3 albedo;
uniform vec2 viewport, centre;
uniform vec4 camera, view;
uniform vec3 translation, extent, origin;
uniform vec2 turn;
uniform int shadow;
uniform int uniformScale;
out vec3 color;
out float depth;
vec3 unit(vec3 n) { float l=length(n);return l>0.0?n/l:n; }
vec3 rotate(vec3 n) { return vec3(n.x*turn.x-n.y*turn.y,n.x*turn.y+n.y*turn.x,n.z); }
vec3 direction(vec3 n) {
    float x=n.x*camera.x-n.y*camera.y,y=n.x*camera.y+n.y*camera.x;
    return vec3(x,y*camera.z-n.z*camera.w,y*camera.w+n.z*camera.z);
}
void main() {
    vec3 world=rotate((position-origin)*extent)+translation;
    if(shadow!=0)world.z=0.0;
    vec3 v=direction(vec3(world.xy-centre,world.z));
    depth=-v.z;
    float denominator=max(view.y-v.z,view.y*0.2);
    gl_Position=vec4(2.0*(v.x*view.x*view.y+view.z*denominator)/viewport.x,
        2.0*(v.y*view.x*view.y+view.w*denominator)/viewport.y,0.0,denominator);
    if(shadow!=0){color=vec3(0);return;}
    vec3 n=direction(rotate(uniformScale!=0?normal*sign(extent):unit(normal/extent)));
    vec3 f=direction(rotate((uniformScale!=0?face*sign(extent):unit(face/extent))*sign(extent.x*extent.y*extent.z)));
    float d=dot(n,f),flip=f.z<0.0?-1.0:1.0;
    n=(d*d>0.58 && d>0.0?n:f)*flip;
    vec3 key=normalize(vec3(-0.45,-0.60,0.66)),fill=normalize(vec3(0.70,0.05,0.45));
    float l=0.30+0.62*max(0.0,dot(n,key))+0.20*max(0.0,dot(n,fill));
    float s=max(0.0,dot(n,normalize(key+vec3(0,0,1))));s*=s;s*=s;s*=s;float spec=s*s;
    float r=1.0-max(0.0,n.z);float rim=r*r*r;
    color=floor(clamp(albedo*l+vec3(0.22*spec)+vec3(0.10,0.10,0.12)*rim,0.0,1.0)*255.0+0.5)/255.0;
})GLSL";
const char* fragment = R"GLSL(#version 300 es
precision highp float;
in vec3 color;in float depth;
uniform vec4 view;
out vec4 result;
void main() {
    // Perspective-correct interpolated view depth, not average triangle depth.
    // Monotonic bounded mapping also preserves order beyond Projector's clamp.
    gl_FragDepth=0.5+0.5*(depth/(view.y+abs(depth)));
    result=vec4(color,1.0);
})GLSL";
const char* quadVertex = R"GLSL(#version 300 es
void main(){vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0);gl_Position=vec4(p,0,1);})GLSL";
const char* quadFragment = R"GLSL(#version 300 es
precision highp float;out vec4 result;void main(){result=vec4(0,0,0,0.28);})GLSL";

GLuint shader(GLenum type,const char* source) {
    GLuint s=glCreateShader(type);glShaderSource(s,1,&source,nullptr);glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){glDeleteShader(s);throw std::runtime_error("Viewport shader compilation failed");}return s;
}
GLuint program(const char* vs,const char* fs) {
    GLuint a=shader(GL_VERTEX_SHADER,vs),b=0,p=0;
    try {b=shader(GL_FRAGMENT_SHADER,fs);p=glCreateProgram();glAttachShader(p,a);glAttachShader(p,b);glLinkProgram(p);
        GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok)throw std::runtime_error("Viewport shader link failed");
    }catch(...){if(p)glDeleteProgram(p);glDeleteShader(a);if(b)glDeleteShader(b);throw;}
    glDeleteShader(a);glDeleteShader(b);return p;
}
struct Mesh {GLuint vao=0,vbo=0;GLsizei count=0;};
struct Renderer {
    EGLDisplay display=EGL_NO_DISPLAY;EGLSurface surface=EGL_NO_SURFACE;EGLContext context=EGL_NO_CONTEXT;
    GLuint geometry=0,quad=0,quadVao=0,fbo=0,texture=0,depthStencil=0;
    int width=0,height=0;bool current=false;
    EGLenum previousApi=EGL_OPENGL_ES_API;
    std::unordered_map<GLuint,Mesh> meshes;
    std::vector<uint8_t> staging;
    double readMs=0,copyMs=0;
    GLenum readFormat=GL_RGBA;
    std::string name;
    void bind(){
        if(current)return;
        if(eglGetCurrentContext()!=EGL_NO_CONTEXT)throw std::runtime_error("Foreign EGL context on viewport worker");
        previousApi=eglQueryAPI();
        if(!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("Viewport API unavailable");
        if(eglGetCurrentContext()!=EGL_NO_CONTEXT||!eglMakeCurrent(display,surface,surface,context)) {
            eglBindAPI(previousApi);throw std::runtime_error("Viewport context unavailable");
        }
        current=true;
    }
    void unbind(){if(current){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);current=false;eglBindAPI(previousApi);}}
    void init() {
        auto platform=reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
        display=platform?platform(EGL_PLATFORM_SURFACELESS_MESA,EGL_DEFAULT_DISPLAY,nullptr):EGL_NO_DISPLAY;
        EGLint major=0,minor=0;
        if(display==EGL_NO_DISPLAY||!eglInitialize(display,&major,&minor))throw std::runtime_error("Offscreen EGL unavailable");
        // Context creation selects an API too; preserve thread-local caller state.
        const EGLenum callerApi=eglQueryAPI();
        const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig config=nullptr;EGLint count=0;
        if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("Viewport EGL configuration unavailable");
        const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        surface=eglCreatePbufferSurface(display,config,size);
        if(!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("OpenGL ES unavailable");
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,attrs);
        eglBindAPI(callerApi);
        if(surface==EGL_NO_SURFACE||context==EGL_NO_CONTEXT)throw std::runtime_error("Viewport EGL context creation failed");
        bind();const char* renderer=reinterpret_cast<const char*>(glGetString(GL_RENDERER));name=renderer?renderer:"unknown";
        geometry=program(vertex,fragment);quad=program(quadVertex,quadFragment);glGenVertexArrays(1,&quadVao);
        glDisable(GL_CULL_FACE);glDisable(GL_DITHER);unbind();
    }
    void resize(int w,int h) {
        if(w==width&&h==height)return;
        if(w<=0||h<=0||w>16384||h>16384||int64_t(w)*h>16777216)throw std::runtime_error("Viewport dimensions unsupported");
        if(fbo)glDeleteFramebuffers(1,&fbo);if(texture)glDeleteTextures(1,&texture);if(depthStencil)glDeleteRenderbuffers(1,&depthStencil);
        fbo=texture=depthStencil=0;width=height=0;
        glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        glGenRenderbuffers(1,&depthStencil);glBindRenderbuffer(GL_RENDERBUFFER,depthStencil);
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH32F_STENCIL8,w,h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_RENDERBUFFER,depthStencil);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("Viewport framebuffer incomplete");
        // RGBA/UNSIGNED_BYTE is guaranteed for RGBA8. A reported implementation
        // format is not a promise of faster readback into fresh image storage.
        readFormat=GL_RGBA;
        // Persistent resident staging avoids re-registering fresh image storage
        // for each driver readback. Its native-order bytes copy contiguously.
        staging.resize(size_t(w)*h*4);
        width=w;height=h;
    }
    ~Renderer(){
        bool bound=false;
        if(context!=EGL_NO_CONTEXT&&surface!=EGL_NO_SURFACE)try{bind();bound=true;}catch(...){}
        if(bound) {
            for(auto& entry:meshes){glDeleteBuffers(1,&entry.second.vbo);glDeleteVertexArrays(1,&entry.second.vao);}
            if(geometry)glDeleteProgram(geometry);if(quad)glDeleteProgram(quad);if(quadVao)glDeleteVertexArrays(1,&quadVao);
            if(fbo)glDeleteFramebuffers(1,&fbo);if(texture)glDeleteTextures(1,&texture);if(depthStencil)glDeleteRenderbuffers(1,&depthStencil);
            unbind();
        }
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        // EGLDisplay is process/vendor shared. Do NOT eglTerminate another owner's display.
    }
};
Renderer& get(jlong handle){if(!handle)throw std::runtime_error("Viewport closed");return *reinterpret_cast<Renderer*>(handle);}
void error(JNIEnv* e){
    if(e->ExceptionCheck())return;
    const char* message="Viewport native failure";
    try{throw;}catch(const std::exception& x){
        // Keep x alive until ThrowNew has copied its message.
        jclass type=e->FindClass("java/lang/IllegalStateException");
        if(type){e->ThrowNew(type,x.what());e->DeleteLocalRef(type);}
        return;
    }catch(...){}
    jclass type=e->FindClass("java/lang/IllegalStateException");
    if(type){e->ThrowNew(type,message);e->DeleteLocalRef(type);}
    // FindClass failure already supplies a pending JVM exception. Preserve it.
}
std::vector<float> floats(JNIEnv* e,jfloatArray a){if(!a)throw std::runtime_error("Missing viewport data");std::vector<float> v(e->GetArrayLength(a));e->GetFloatArrayRegion(a,0,v.size(),v.data());if(e->ExceptionCheck())throw std::runtime_error("Viewport array read failed");return v;}
}
extern "C" {
JNIEXPORT jstring JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_sourceRevision(JNIEnv* e,jobject){return e->NewStringUTF(NIA_SOURCE_REVISION);}
JNIEXPORT jlong JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_create(JNIEnv* e,jobject){try{auto r=std::make_unique<Renderer>();r->init();return reinterpret_cast<jlong>(r.release());}catch(...){error(e);return 0;}}
JNIEXPORT jstring JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_describe(JNIEnv* e,jobject,jlong h){try{return e->NewStringUTF(get(h).name.c_str());}catch(...){error(e);return nullptr;}}
JNIEXPORT jboolean JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_bgra(JNIEnv* e,jobject,jlong h){try{get(h);return true;}catch(...){error(e);return false;}}
JNIEXPORT jfloatArray JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_timings(JNIEnv* e,jobject,jlong h){try{auto& r=get(h);float v[2]={float(r.readMs),float(r.copyMs)};auto a=e->NewFloatArray(2);if(a)e->SetFloatArrayRegion(a,0,2,v);return a;}catch(...){error(e);return nullptr;}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_begin(JNIEnv* e,jobject,jlong h,jint w,jint height,jfloatArray camera){try{
    auto& r=get(h);auto c=floats(e,camera);if(c.size()!=10)throw std::runtime_error("Invalid viewport camera");
    r.bind();r.resize(w,height);glBindFramebuffer(GL_FRAMEBUFFER,r.fbo);glViewport(0,0,w,height);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);glDepthMask(GL_TRUE);glStencilMask(255);glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,0);glClearDepthf(1);glClearStencil(0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
    glUseProgram(r.geometry);glUniform2f(glGetUniformLocation(r.geometry,"viewport"),w,height);
    glUniform4fv(glGetUniformLocation(r.geometry,"camera"),1,c.data());glUniform4fv(glGetUniformLocation(r.geometry,"view"),1,c.data()+4);
    glUniform2fv(glGetUniformLocation(r.geometry,"centre"),1,c.data()+8);
}catch(...){error(e);}}
JNIEXPORT jint JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_upload(JNIEnv* e,jobject,jlong h,jfloatArray positions,jintArray indices,jfloatArray normals,jfloatArray colors){try{
    auto& r=get(h);auto p=floats(e,positions),n=floats(e,normals),c=floats(e,colors);
    if(!r.current||!indices)throw std::runtime_error("Viewport upload outside frame");
    std::vector<jint> t(e->GetArrayLength(indices));e->GetIntArrayRegion(indices,0,t.size(),t.data());
    if(e->ExceptionCheck()||p.size()%3||t.size()%3||n.size()!=t.size()*3||c.size()!=t.size())throw std::runtime_error("Invalid viewport mesh arrays");
    if(t.size()>256*1024*1024/48)throw std::runtime_error("Viewport mesh exceeds buffer budget");
    for(float f:p)if(!std::isfinite(f))throw std::runtime_error("Nonfinite viewport position");
    for(float f:n)if(!std::isfinite(f))throw std::runtime_error("Nonfinite viewport normal");
    for(auto i:t)if(i<0||size_t(i)>=p.size()/3)throw std::runtime_error("Invalid viewport triangle index");
    std::vector<float> data(t.size()*12);
    for(size_t tri=0;tri<t.size()/3;tri++){
        const float *a=&p[t[tri*3]*3],*b=&p[t[tri*3+1]*3],*d=&p[t[tri*3+2]*3];
        float u[3],v[3];for(int k=0;k<3;k++){u[k]=b[k]-a[k];v[k]=d[k]-a[k];}
        float f[3]={u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]};
        float length=std::sqrt(f[0]*f[0]+f[1]*f[1]+f[2]*f[2]);if(length>0)for(float& component:f)component/=length;
        for(int corner=0;corner<3;corner++){size_t at=tri*3+corner;float* out=&data[at*12];
            std::copy_n(&p[t[at]*3],3,out);std::copy_n(&n[at*3],3,out+3);std::copy_n(f,3,out+6);std::copy_n(&c[tri*3],3,out+9);}
    }
    Mesh m;glGenVertexArrays(1,&m.vao);glBindVertexArray(m.vao);glGenBuffers(1,&m.vbo);glBindBuffer(GL_ARRAY_BUFFER,m.vbo);
    glBufferData(GL_ARRAY_BUFFER,data.size()*sizeof(float),data.data(),GL_STATIC_DRAW);
    for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,3,GL_FLOAT,GL_FALSE,12*sizeof(float),reinterpret_cast<void*>(size_t(i*3*sizeof(float))));}
    if(glGetError()!=GL_NO_ERROR){glDeleteBuffers(1,&m.vbo);glDeleteVertexArrays(1,&m.vao);throw std::runtime_error("Viewport buffer allocation failed");}
    m.count=t.size();r.meshes.emplace(m.vao,m);return m.vao;
}catch(...){error(e);return 0;}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_releaseMesh(JNIEnv* e,jobject,jlong h,jint id){try{auto& r=get(h);auto it=r.meshes.find(id);if(it!=r.meshes.end()){glDeleteBuffers(1,&it->second.vbo);glDeleteVertexArrays(1,&it->second.vao);r.meshes.erase(it);}}catch(...){error(e);}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_draw(JNIEnv* e,jobject,jlong h,jint id,jfloatArray pose,jboolean shadow){try{
    auto& r=get(h);auto p=floats(e,pose);if(!r.current||p.size()!=10)throw std::runtime_error("Invalid viewport pose");
    for(float f:p)if(!std::isfinite(f))throw std::runtime_error("Nonfinite viewport pose");
    if(p[3]==0||p[4]==0||p[5]==0)throw std::runtime_error("Degenerate viewport pose");
    auto it=r.meshes.find(id);if(it==r.meshes.end())throw std::runtime_error("Viewport mesh missing");
    glUseProgram(r.geometry);glBindVertexArray(it->second.vao);
    glUniform3f(glGetUniformLocation(r.geometry,"translation"),p[0],p[1],0);
    glUniform2f(glGetUniformLocation(r.geometry,"turn"),std::cos(p[2]),std::sin(p[2]));
    glUniform3fv(glGetUniformLocation(r.geometry,"extent"),1,p.data()+3);glUniform3fv(glGetUniformLocation(r.geometry,"origin"),1,p.data()+6);
    glUniform1i(glGetUniformLocation(r.geometry,"shadow"),shadow?1:0);
    glUniform1i(glGetUniformLocation(r.geometry,"uniformScale"),std::abs(p[3])==std::abs(p[4])&&std::abs(p[4])==std::abs(p[5]));
    if(shadow){
        glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glEnable(GL_STENCIL_TEST);glStencilMask(255);glClear(GL_STENCIL_BUFFER_BIT);
        glStencilFunc(GL_ALWAYS,1,255);glStencilOp(GL_KEEP,GL_KEEP,GL_REPLACE);glColorMask(GL_FALSE,GL_FALSE,GL_FALSE,GL_FALSE);
        glDrawArrays(GL_TRIANGLES,0,it->second.count);
        glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);glStencilMask(0);glStencilFunc(GL_EQUAL,1,255);glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(r.quad);glBindVertexArray(r.quadVao);glDrawArrays(GL_TRIANGLES,0,3);
    }else{
        glDisable(GL_BLEND);glDisable(GL_STENCIL_TEST);glEnable(GL_DEPTH_TEST);glDepthMask(GL_TRUE);glDepthFunc(GL_LESS);
        glDrawArrays(GL_TRIANGLES,0,it->second.count);
    }
}catch(...){error(e);}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_read(JNIEnv* e,jobject,jlong h,jlong output,jint bytes){try{
    auto& r=get(h);if(!r.current||!output||bytes<0||int64_t(bytes)!=int64_t(r.width)*r.height*4)throw std::runtime_error("Invalid viewport readback");
    // Projection inversion gives Skia row order. Read into resident storage, then
    // Convert once into Skia's native BGRA raster format. RGBA images force
    // expensive format conversion on the CPU bed/presentation path.
    auto start=std::chrono::steady_clock::now();
    glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(0,0,r.width,r.height,r.readFormat,GL_UNSIGNED_BYTE,r.staging.data());
    auto read=std::chrono::steady_clock::now();
    if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Viewport readback failed");
    auto* dst=reinterpret_cast<uint32_t*>(output);
    const auto* src=reinterpret_cast<const uint32_t*>(r.staging.data());
    for(size_t i=0;i<size_t(bytes)/4;i++) {
        const uint32_t p=src[i];
        dst[i]=(p&0xff00ff00u)|((p&0xffu)<<16)|((p>>16)&0xffu);
    }
    auto copy=std::chrono::steady_clock::now();
    r.readMs=std::chrono::duration<double,std::milli>(read-start).count();r.copyMs=std::chrono::duration<double,std::milli>(copy-read).count();
}catch(...){error(e);}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_end(JNIEnv* e,jobject,jlong h){try{get(h).unbind();}catch(...){error(e);}}
JNIEXPORT void JNICALL Java_com_nozzleitall_desktop_prepare_NativeViewport_destroy(JNIEnv*,jobject,jlong h){delete reinterpret_cast<Renderer*>(h);}
}
