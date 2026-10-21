#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float vMarker;
layout(location = 5) in vec2 markerScreen;
layout(location = 6) in vec3 markerView;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Data layout shared with distortion:post/ripple(_mask): slot s is row MARKER_FIRST_ROW + s % 16, and its five
// fields start at column 1 + (s / 16) * MARKER_GROUP_STRIDE.
const int MARKER_FIRST_ROW = 8;
const int MARKER_GROUP_STRIDE = 6;
// Must match text.vsh.
const float MARKER_BLOCK_PX = 96.0;
const vec3 MARKER_SIGNATURE = vec3(127.0, 1.0, 126.0) / 255.0;
// Marker glyph edge in blocks at transformation scale 1 (16 font units * 0.025).
const float MARKER_GLYPH_BLOCKS = 0.4;
// The plugin sets scale = (strength, radius in blocks) * this, so the glyph stays tiny and centered on the origin.
const float MARKER_SCALE_UNIT = 0.01;

// High/low byte split so a value keeps 16-bit precision in an 8-bit target.
vec2 encode16(float v) {
    float scaled = clamp(v, 0.0, 1.0) * 255.0;
    return vec2(floor(scaled) / 255.0, fract(scaled));
}

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

void main() {
    int markerSlot = int(round(vMarker)) - 1;
    if (markerSlot >= 0) {
        // Derivatives first, before any discard: they need the whole 2x2 quad.
        vec2 screenDx = dFdx(markerScreen);
        vec2 screenDy = dFdy(markerScreen);
        vec3 viewDx = dFdx(markerView);
        vec3 viewDy = dFdy(markerView);
        vec2 texDx = dFdx(texCoord0);

        ivec2 pixel = ivec2(gl_FragCoord.xy);
        int field = pixel.x - 1 - (markerSlot / 16) * MARKER_GROUP_STRIDE;
        if (pixel.y != MARKER_FIRST_ROW + markerSlot % 16 || field < 0 || field > 4) {
            discard;
        }

        // Both triangles share the glyph diagonal through the block center, so the value there is the glyph center, and
        // block x/y derivatives are its two edges; the edge whose UV changes in v (not u) is the height.
        vec2 fromCenter = gl_FragCoord.xy - 0.5 * MARKER_BLOCK_PX;
        vec2 screenCenter = markerScreen - screenDx * fromCenter.x - screenDy * fromCenter.y;
        vec3 viewCenter = markerView - viewDx * fromCenter.x - viewDy * fromCenter.y;
        float edgeX = length(viewDx) * MARKER_BLOCK_PX;
        float edgeY = length(viewDy) * MARKER_BLOCK_PX;
        bool xIsHeight = abs(texDx.y) > abs(texDx.x);
        float height = xIsHeight ? edgeX : edgeY;
        float width = xIsHeight ? edgeY : edgeX;

        float depth = (ProjMat * vec4(viewCenter, 1.0)).w;
        if (depth <= 0.0) {
            discard;
        }
        float glyphUnit = MARKER_GLYPH_BLOCKS * MARKER_SCALE_UNIT;
        // Screen heights per block at the marker's distance.
        float screenPerBlock = abs(ProjMat[1][1]) * 0.5 / depth;
        float radius = height / glyphUnit * screenPerBlock;
        float strength = clamp(width / glyphUnit, 0.0, 1.0);

        vec2 cx = encode16((screenCenter.x + 1.0) / 3.0);
        vec2 cy = encode16((screenCenter.y + 1.0) / 3.0);
        vec4 data;
        if (field == 0) {
            data = vec4(MARKER_SIGNATURE, 1.0);
        } else if (field == 1) {
            data = vec4(cx, cy.x, 1.0);
        } else if (field == 2) {
            data = vec4(cy.y, encode16(radius / 4.0), 1.0);
        } else if (field == 3) {
            data = vec4(vertexColor.rgb, 1.0);
        } else {
            data = vec4(strength, encode16(screenPerBlock / 2.0), 1.0);
        }

        // Opaque data, no texture or fog; under Improved Transparency it must still go through OIT to reach main.
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
        #else
        #ifdef OIT_ACCUMULATE
        data = sampleColorForAccumulation(data);
        #endif
        fragColor = data;
        #endif
        return;
    }

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
