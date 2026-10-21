#version 150

#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out float vMarker;
out vec2 markerScreen;
out vec3 markerView;
out vec2 blockUv;

// text_opacity that turns a world text display into a distortion:ripple marker.
const float MARKER_OPACITY = 123.0;
const int MARKER_SLOTS = 256;
// Fraction of the screen, per axis, that the data block at framebuffer texel (0,0) covers. This version's text program
// has no ScreenSize uniform, so the fragment shader recovers the block's pixel size from blockUv instead. The data
// strip is 96 px wide, so this needs a framebuffer at least 800 px wide.
const float MARKER_BLOCK_FRACTION = 0.12;

void main() {
    vMarker = 0.0;
    markerScreen = vec2(0.0);
    markerView = vec3(0.0);
    blockUv = vec2(0.0);
    texCoord0 = UV0;

    if (abs(Color.a * 255.0 - MARKER_OPACITY) < 0.5) {
        vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
        vec4 clip = ProjMat * viewPos;

        // Slot = brightness block + 16 * sky (UV2 is light * 16).
        vMarker = float(clamp(UV2.x / 16 + 16 * (UV2.y / 16), 0, MARKER_SLOTS - 1) + 1);
        markerScreen = clip.xy / clip.w * 0.5 + 0.5;
        markerView = viewPos.xyz;
        vertexColor = Color;
        vertexDistance = 0.0;

        // Depth of a point 0.1 blocks ahead via the real ProjMat, so it passes the depth test under any convention.
        vec4 nearClip = ProjMat * vec4(viewPos.xyz * (0.1 / length(viewPos.xyz)), 1.0);

        // Counter-clockwise perimeter order: any rotation of it (mods can shift where a glyph's vertices start in the
        // buffer) still fills the whole block with the winding that survives culling. The fragment shader doesn't rely
        // on order.
        const vec2 CORNERS[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
        blockUv = CORNERS[gl_VertexID % 4];
        gl_Position = vec4(vec2(-1.0) + blockUv * (2.0 * MARKER_BLOCK_FRACTION), nearClip.z / nearClip.w, 1.0);
        return;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
}
