#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
in float vMarker;
in vec2 markerScreen;
in vec3 markerView;

out vec4 fragColor;

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

        // Opaque data, no texture or fog.
        fragColor = data;
        return;
    }

#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
