#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
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
// Must match rendertype_text.vsh.
const float MARKER_BLOCK_PX = 96.0;
const vec3 MARKER_SIGNATURE = vec3(127.0, 1.0, 126.0) / 255.0;
// A shake marker uses rows of its own below the ripple slots: slot s is row SHAKE_FIRST_ROW + s, with three pixels from column 1.
const int SHAKE_FIRST_ROW = 40;
const vec3 SHAKE_SIGNATURE = vec3(125.0, 2.0, 124.0) / 255.0;
// Marker glyph edge in blocks at transformation scale 1 (16 font units * 0.025).
const float MARKER_GLYPH_BLOCKS = 0.4;
// The plugin sets scale = (strength, radius in blocks) * this, so the glyph stays tiny and centered on the origin.
const float MARKER_SCALE_UNIT = 0.01;

// High/low byte split so a value keeps 16-bit precision in an 8-bit target.
vec2 encode16(float v) {
    float scaled = clamp(v, 0.0, 1.0) * 255.0;
    return vec2(floor(scaled) / 255.0, fract(scaled));
}

// An impact frame focus marker is a shake marker whose slot is over IMPACT_SLOT_OFFSET, with a signature of its own. It sits at the focus point in the world.
// Its block light is the row of its data and its sky light the type; the glyph width says the direction (an angle on the screen over a full turn, from 0.05 to 1
// of IMPACT_WIDTH_UNIT) and the height the size in blocks (over IMPACT_SIZE_UNIT), and its text color is the color. Its data is: the point on the screen and the
// type, the point's other coordinate and the direction, the color, the size in screen heights, and the depth value of a point very far away, which the post pass
// measures other depths against. Bigger glyphs than the shake's are measured more exactly.
const int IMPACT_SLOT_OFFSET = 64;
const vec3 IMPACT_SIGNATURE = vec3(123.0, 3.0, 122.0) / 255.0;
const float IMPACT_WIDTH_UNIT = 1.0;
const float IMPACT_SIZE_UNIT = 0.1;

// A value from 0 to 1 in three bytes.
vec3 encode24(float v) {
    float scaled = clamp(v, 0.0, 1.0) * 255.0;
    float rest = fract(scaled) * 255.0;
    return vec3(floor(scaled) / 255.0, floor(rest) / 255.0, fract(rest));
}

// The depth buffer value of a point this many blocks straight ahead. Whether the projection maps depth to -1..1 or 0..1 is told by the marker's own
// depth (a point 0.1 blocks ahead, see text.vsh) which the hardware turned into the value in the buffer.
float impactDeviceDepth(float blocks, bool minusOneToOne) {
    float ndc = ProjMat[3][2] / blocks - ProjMat[2][2];
    return minusOneToOne ? ndc * 0.5 + 0.5 : ndc;
}

vec3 impactData(int field, int type, float angle, vec2 center, float radius, vec3 color, float markerDepth) {
    if (field == 0) {
        return IMPACT_SIGNATURE;
    } else if (field == 1) {
        return vec3(encode16((center.x + 1.0) / 3.0), float(type) / 255.0);
    } else if (field == 2) {
        return vec3(encode16((center.y + 1.0) / 3.0), round(angle * 255.0) / 255.0);
    } else if (field == 3) {
        return color;
    } else if (field == 4) {
        return vec3(encode16(radius / 4.0), 0.0);
    }
    float ahead = ProjMat[3][2] / 0.1 - ProjMat[2][2];
    bool minusOneToOne = abs(ahead * 0.5 + 0.5 - markerDepth) < abs(ahead - markerDepth);
    return encode24(impactDeviceDepth(100000.0, minusOneToOne));
}

void main() {
    bool shakeMarker = vMarker < 0.0;
    int markerSlot = int(round(abs(vMarker))) - 1;
    bool impactMarker = shakeMarker && markerSlot >= IMPACT_SLOT_OFFSET;
    if (impactMarker) {
        markerSlot -= IMPACT_SLOT_OFFSET;
    }
    // The block light of an impact marker is its row and its sky light is its type.
    int impactType = impactMarker ? markerSlot / 16 : 0;
    if (markerSlot >= 0) {
        // Derivatives first, before any discard: they need the whole 2x2 quad.
        vec2 screenDx = dFdx(markerScreen);
        vec2 screenDy = dFdy(markerScreen);
        vec3 viewDx = dFdx(markerView);
        vec3 viewDy = dFdy(markerView);
        vec2 texDx = dFdx(texCoord0);

        ivec2 pixel = ivec2(gl_FragCoord.xy);
        int field = shakeMarker ? pixel.x - 1 : pixel.x - 1 - (markerSlot / 16) * MARKER_GROUP_STRIDE;
        int row = (shakeMarker ? SHAKE_FIRST_ROW : MARKER_FIRST_ROW) + markerSlot % 16;
        if (pixel.y != row || field < 0 || field > (impactMarker ? 5 : shakeMarker ? 2 : 4)) {
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
        if (impactMarker) {
            // The type from the sky light, the direction from the width of the glyph, the size from its height, the color from the text color: see impactData.
            float impactAngle = clamp((width / (MARKER_GLYPH_BLOCKS * IMPACT_WIDTH_UNIT) - 0.05) / 0.95, 0.0, 1.0);
            float impactRadius = height / (MARKER_GLYPH_BLOCKS * IMPACT_SIZE_UNIT) * screenPerBlock;
            data = vec4(impactData(field, impactType, impactAngle, screenCenter, impactRadius, vertexColor.rgb, gl_FragCoord.z), 1.0);
        } else if (shakeMarker) {
            // Strength from the width of the glyph, the rest from the text color: rate, up and down against left and right, roll.
            if (field == 0) {
                data = vec4(SHAKE_SIGNATURE, 1.0);
            } else if (field == 1) {
                data = vec4(encode16(strength), vertexColor.r, 1.0);
            } else {
                data = vec4(vertexColor.g, vertexColor.b, 0.0, 1.0);
            }
        } else if (field == 0) {
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

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
