#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;
out float vMarker;
out vec2 markerScreen;
out vec3 markerView;

// text_opacity that turns a world text display into a distortion:ripple marker.
const float MARKER_OPACITY = 123.0;
const int MARKER_SLOTS = 256;
// Side of the data block at framebuffer texel (0,0); must match text.fsh.
const float MARKER_BLOCK_PX = 96.0;

void main() {
    vMarker = 0.0;
    markerScreen = vec2(0.0);
    markerView = vec3(0.0);
    texCoord0 = UV0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (abs(Color.a * 255.0 - MARKER_OPACITY) < 0.5) {
        vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
        vec4 clip = ProjMat * viewPos;

        // Slot = brightness block + 16 * sky (UV2 is light * 16).
        vMarker = float(clamp(UV2.x / 16 + 16 * (UV2.y / 16), 0, MARKER_SLOTS - 1) + 1);
        markerScreen = clip.xy / clip.w * 0.5 + 0.5;
        markerView = viewPos.xyz;
        vertexColor = Color;
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;

        // Depth of a point 0.1 blocks ahead via the real ProjMat, so it passes the depth test under any convention.
        vec4 nearClip = ProjMat * vec4(viewPos.xyz * (0.1 / length(viewPos.xyz)), 1.0);

        // Counter-clockwise perimeter order: any rotation of it (mods can shift where a glyph's vertices start in the
        // buffer) still fills the whole block with the winding that survives culling. The fragment shader doesn't rely
        // on order.
        const vec2 CORNERS[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
        vec2 cornerUv = CORNERS[gl_VertexID % 4];
        gl_Position = vec4(vec2(-1.0) + cornerUv * (2.0 * MARKER_BLOCK_PX / ScreenSize), nearClip.z / nearClip.w, 1.0);
        return;
    }
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
}
