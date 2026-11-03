#version 330 core
in float EyeDistance;
in vec3 SurfacePosition;
uniform sampler2D Image;
uniform sampler2D Coverage;
uniform sampler2D WorldDepth;
uniform sampler2D DistantDepth;
uniform sampler2D TransBefore;
uniform sampler2D TransAfter;
uniform int TransparentGuard;
uniform mat4 RayInverse;
uniform vec4 Viewport;
uniform vec2 RayDepth;
uniform vec2 DepthScale;
uniform vec2 DepthOffset;
uniform vec4 DistantProjection;
uniform vec2 DistantNdc;
uniform int DistantEnabled;
uniform int Pass;
uniform int Style;
uniform int Material;
uniform int ItrpMaterial;
layout(location=0) out vec4 fragColor;
layout(location=1) out vec4 SkySolidData;
layout(location=2) out vec4 SkyNormalData;

// Only routed to auxiliary attachments by the hash- and owner-validated ITRP bridge.
vec2 octNormal(vec3 n) {
    n.xy/=max(abs(n.x)+abs(n.y)+abs(n.z),1e-8);
    if(n.z<0.0) n.xy=(1.0-abs(n.yx))*vec2(n.x>=0.0?1.0:-1.0,n.y>=0.0?1.0:-1.0);
    return n.xy*0.5+0.5;
}

vec3 ray(vec2 uv) {
    vec2 ndc=uv*2.0-1.0;
    // Two finite points. A single inverse-VP point contains camera bob/portal translation.
    vec4 a=RayInverse*vec4(ndc,RayDepth.x,1.0);
    vec4 b=RayInverse*vec4(ndc,RayDepth.y,1.0);
    return normalize(b.xyz/b.w-a.xyz/a.w);
}
void main() {
    vec2 uv=(gl_FragCoord.xy-Viewport.xy)/Viewport.zw;
    vec2 depthUv=uv*DepthScale+DepthOffset;
    if(DistantEnabled!=0) {
        float d=texture(DistantDepth,depthUv).r;
        float ndc=d*DistantNdc.x+DistantNdc.y;
        float denominator=ndc*DistantProjection.z-DistantProjection.x;
        float z=(DistantProjection.y-ndc*DistantProjection.w)/denominator;
        if(abs(denominator)>1e-10 && -z>0.0 && -z<EyeDistance-0.02) discard;
    }
    if(Pass==1) { fragColor=vec4(gl_FragCoord.z,float(Material+1),0.0,1.0); return; }
    if(Pass==2) {
        vec2 owner=texture(Coverage,depthUv).rg;
        if(abs(owner.y-float(Material+1))>0.1) discard;
        float depth=texture(WorldDepth,depthUv).r;
        // Exact opaque provenance, not a generic "some sky depth" fingerprint. Foreground entities,
        // terrain, translucent depth writers and Iris hands win before the final color restoration.
        float tolerance=max(1e-7,abs(dFdx(gl_FragCoord.z))+abs(dFdy(gl_FragCoord.z)));
        // All faces of the SAME material sample the same infinite direction. TAA jitter can map
        // an edge pixel to a different-depth face in that material's batch. Requiring this triangle's
        // depth would leave temporal cracks. The material owner AND live world depth already prove
        // nearest-surface ownership; a different material/foreground/hand still fails those checks.
        if(abs(depth-owner.x)>tolerance) discard;
        if(TransparentGuard!=0) {
            vec4 before=texture(TransBefore,depthUv),after=texture(TransAfter,depthUv);
            vec4 change=abs(after-before);
            float limit=max(0.001,max(max(abs(before.r),abs(before.g)),abs(before.b))*0.0001);
            // A no-depth-write transparent/particle pixel is already correctly composed by the pack.
            // Preserve it verbatim; never repaint it with an opaque sky color at final resolve.
            if(max(max(change.r,change.g),max(change.b,change.a))>limit) discard;
        }
    }
    vec3 color;
    if(Style==1) color=vec3(1.0);
    else if(Style==2) color=vec3(0.0);
    else if(Style==3) {
        vec3 d=ray(uv);
        float u=dot(d.xz,d.xz)<1e-12 ? 0.5 : atan(d.z,d.x)/6.283185307179586+0.5;
        vec2 panorama=vec2(u,acos(clamp(d.y,-1.0,1.0))/3.141592653589793);
        // Explicit LOD: atan longitude discontinuity must not select a coarse mip at the wrap.
        color=textureLod(Image,panorama,0.0).rgb;
    } else color=texture(Image,uv).rgb;
    fragColor=vec4(color,1.0);
    SkySolidData=vec4(0.0);SkyNormalData=vec4(0.0);
    if(Pass==0 && ItrpMaterial!=0) {
        vec3 n=cross(dFdx(SurfacePosition),dFdy(SurfacePosition));
        n=dot(n,n)>1e-30 ? normalize(n) : vec3(0.0,1.0,0.0);
        vec2 oct=octNormal(n);
        // Audited 0.8.31 Pack2xU8: parallax=1, MATID_LAND=1, blocklight=0, skylight=1.
        SkySolidData=vec4(0.0,0.0,65281.0/65535.0,255.0/65535.0);
        SkyNormalData=vec4(oct,oct);
    }
}
