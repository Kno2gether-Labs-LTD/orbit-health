package dev.avijit.orbithealth.ui.sky

/**
 * AGSL for every living sky (API 33+ RuntimeShader). One program renders all six; the sky's look comes entirely from
 * uniforms filled from [SkySpec] / [SkyFrame], so the Kotlin token tables stay the single source of truth.
 *
 * Layers, in the mockup's CSS paint order:
 *  1. vertical gradient fill (up to 10 stops);
 *  2. elliptical radial blobs flagged "before noise" (premultiplied, source-over);
 *  3. Tide's shimmer band;
 *  4. fbm value noise, soft-light blended white (W3C soft-light with a white source = D(b)), optionally masked;
 *  5. blobs flagged "after noise" (First light's sun glow);
 *  6. First light's sun disc + halo (clipped at the horizon), horizon line and reflection;
 *  7. Night's bottom scrim.
 * Stars are drawn afterwards on the CPU at full resolution (crisper than a half-resolution layer).
 *
 * All geometry is in dp: the fragment coordinate is divided by `uPxPerDp` (layer pixels per dp, i.e. density × the
 * render scale), so the same uniforms work at any resolution.
 *
 * Noise is value noise on a lattice that tiles every 400 dp (3 cells at the base octave → frequency 0.0075/dp, as the
 * mockup's `feTurbulence stitchTiles` rounds 400 × 0.008 = 3.2 to whole tiles), so the flow offset can wrap at 400 dp
 * without a seam. GLSL ES 2 rules apply: uniform arrays are only indexed by loop counters.
 */
internal const val SKY_SHADER = """
uniform float uPxPerDp;
uniform float2 uSkyDp;

uniform float uStopPos[10];
uniform float4 uStopCol[10];

uniform float4 uBlobGeo[5];
uniform float4 uBlobC0[5];
uniform float4 uBlobCm[5];
uniform float4 uBlobMeta[5];

uniform float4 uShim;
uniform float4 uShimCol;

uniform float4 uNoise;
uniform float4 uMask;

uniform float4 uSun;
uniform float4 uSunCore;
uniform float4 uSunMid;
uniform float4 uSunHalo;
uniform float4 uSunGeo;
uniform float4 uSunStops;
uniform float4 uHzLine;
uniform float4 uReflect;
uniform float4 uReflectGeo;

uniform float4 uScrim;
uniform float4 uScrimCol;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 x, float per, float2 seed) {
    float2 i = floor(x);
    float2 f = fract(x);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(mod(i, per) + seed);
    float b = hash12(mod(i + float2(1.0, 0.0), per) + seed);
    float c = hash12(mod(i + float2(0.0, 1.0), per) + seed);
    float d = hash12(mod(i + float2(1.0, 1.0), per) + seed);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 2.0 - 1.0;
}

// Four octaves, lacunarity 2, gain 0.5. The lattice seeds were chosen so the 400 dp tile averages to zero (mean
// 0.004, sd 0.47), close to the mockup's feTurbulence after its colour matrix (contrast set in main).
float fbm(float2 pDp) {
    float2 q = pDp * (3.0 / 400.0);
    float per = 3.0;
    float amp = 1.0;
    float sum = 0.0;
    for (int o = 0; o < 4; o++) {
        float fo = float(o);
        sum += amp * vnoise(q, per, float2(10.0 + 31.0 * fo, 22.0 + 13.0 * fo));
        q *= 2.0;
        per *= 2.0;
        amp *= 0.5;
    }
    return sum;
}

float3 over(float3 base, float4 premul) {
    return base * (1.0 - premul.a) + premul.rgb;
}

float4 premul(float4 c) {
    return float4(c.rgb * c.a, c.a);
}

float4 blob(float2 p, float4 geo, float4 c0, float4 cm, float midStop) {
    float d = length((p - geo.xy) / geo.zw);
    if (d >= 1.0) return float4(0.0);
    if (midStop >= 1.0) return c0 * (1.0 - d);
    if (d < midStop) return mix(c0, cm, d / midStop);
    return cm * (1.0 - (d - midStop) / (1.0 - midStop));
}

float3 softLightWhite(float3 b) {
    float3 lo = ((16.0 * b - 12.0) * b + 4.0) * b;
    float3 hi = sqrt(b);
    return mix(lo, hi, step(0.25, b));
}

half4 main(float2 fragCoord) {
    float2 p = fragCoord / uPxPerDp;
    float yf = p.y / uSkyDp.y;

    // 1. Fill.
    float3 col = uStopCol[0].rgb;
    for (int i = 1; i < 10; i++) {
        float a = uStopPos[i - 1];
        float b = uStopPos[i];
        float t = clamp((yf - a) / max(b - a, 0.00001), 0.0, 1.0);
        col = mix(col, uStopCol[i].rgb, t);
    }

    // 2. Blobs under the noise.
    for (int i = 0; i < 5; i++) {
        if (uBlobMeta[i].y < 0.5) {
            col = over(col, blob(p, uBlobGeo[i], uBlobC0[i], uBlobCm[i], uBlobMeta[i].x));
        }
    }

    // 3. Shimmer band (Tide): a 110 degree linear gradient in a tiled image uShim.z dp wide, offset uShim.y.
    if (uShim.x > 0.5) {
        float iw = uShim.z;
        float u = mod(p.x - uShim.y, iw);
        float2 dir = float2(0.9396926, 0.3420201);
        float len = iw * dir.x + uSkyDp.y * dir.y;
        float t = 0.5 + dot(float2(u - iw * 0.5, p.y - uSkyDp.y * 0.5), dir) / len;
        float a = uShimCol.a * clamp(1.0 - abs(t - 0.5) / 0.15, 0.0, 1.0);
        col = over(col, float4(uShimCol.rgb * a, a));
    }

    // 4. Noise, soft-light white.
    // 0.40 maps the fbm onto the mockup's noise: alpha mean 0.25, sd 0.15 (measured on Chrome's render).
    float n = 0.5 + 0.5 * 0.40 * fbm(p - uNoise.xy);
    float na = clamp(1.6 * n - 0.55, 0.0, 1.0) * uNoise.z;
    if (uMask.w > 0.0) {
        float up = clamp((p.y - uMask.x) / max(uMask.y - uMask.x, 0.001), 0.0, 1.0);
        float down = 1.0 - clamp((p.y - uMask.z) / max(uMask.w - uMask.z, 0.001), 0.0, 1.0);
        na *= up * down;
    }
    col = mix(col, softLightWhite(clamp(col, 0.0, 1.0)), na);

    // 5. Blobs over the noise.
    for (int i = 0; i < 5; i++) {
        if (uBlobMeta[i].y >= 0.5) {
            col = over(col, blob(p, uBlobGeo[i], uBlobC0[i], uBlobCm[i], uBlobMeta[i].x));
        }
    }

    // 6. Sun group (First light). uSun = (cx, cy, horizonY, on); uSunGeo = (radius, haloRadius, haloSoftness,
    //    reflectAlpha); uSunStops = (coreStop, midStop); uReflectGeo = (rx, ry).
    if (uSun.w > 0.5) {
        float hz = uSun.z;
        if (p.y < hz) {
            float d = length(p - uSun.xy);
            float halo = uSunHalo.a * (1.0 - smoothstep(uSunGeo.y - uSunGeo.z, uSunGeo.y + uSunGeo.z, d));
            col = over(col, float4(uSunHalo.rgb * halo, halo));
            // CSS radial-gradient(circle, ...) on a 38 x 38 disc: the gradient runs to the farthest corner
            // (radius * sqrt 2) and the round element clips it at the radius, which gives the crisp rim.
            float r = d / (uSunGeo.x * 1.41421356);
            float4 core = premul(uSunCore);
            float4 mid = premul(uSunMid);
            float4 disc = core;
            if (r >= uSunStops.y) {
                disc = mid * max(1.0 - (r - uSunStops.y) / (1.0 - uSunStops.y), 0.0);
            } else if (r >= uSunStops.x) {
                disc = mix(core, mid, (r - uSunStops.x) / (uSunStops.y - uSunStops.x));
            }
            float rim = clamp((uSunGeo.x - d) * uPxPerDp + 0.5, 0.0, 1.0);
            col = over(col, disc * rim);
        }
        float hp = 0.5 / uPxPerDp;
        float cov = clamp((min(p.y + hp, hz + 1.0) - max(p.y - hp, hz)) / (2.0 * hp), 0.0, 1.0);
        float la = uHzLine.a * (1.0 - abs(p.x / uSkyDp.x - 0.5) * 2.0) * cov;
        col = over(col, float4(uHzLine.rgb * la, la));
        if (p.y >= hz + 1.0) {
            float rd = length((p - float2(uSkyDp.x * 0.5, hz + 1.0)) / uReflectGeo.xy);
            float ra = uReflect.a * max(1.0 - rd, 0.0) * uSunGeo.w;
            col = over(col, float4(uReflect.rgb * ra, ra));
        }
    }

    // 7. Bottom scrim (Night).
    if (uScrim.x > 0.5) {
        float a = clamp((p.y - uScrim.y) / max(uScrim.z - uScrim.y, 0.001), 0.0, 1.0) * uScrim.w;
        col = mix(col, uScrimCol.rgb, a);
    }

    // Dither half an 8-bit step so the half-resolution gradients never band.
    col += (hash12(fragCoord + 17.0) - 0.5) / 255.0;
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""
