#version 450
#extension GL_KHR_shader_subgroup_quad: enable

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

out vec3 Pos;
out float transition;
out vec4 objOverlay;
out vec4 objLight;

flat out int isCustom;
flat out int isGUI;
flat out int isHand;
flat out int noshadow;

#moj_import <objmc_tools.glsl>

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * sample_lightmap(Sampler2, UV2);

    texCoord0 = UV0;

    //objmc
    Pos = Position;
    vec2 texCoord = texCoord0;
    // This shadows the output of the same name where there is one: objmc changes it, and it is passed on as objOverlay.
    vec4 overlayColor = vec4(1);
    #define ENTITY
    #moj_import <objmc_main.glsl>
    texCoord0 = texCoord;
    objOverlay = overlayColor;
#if defined(EMISSIVE)
    objLight = vec4(1);
#else
    objLight = sample_lightmap(Sampler2, UV2);
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(Pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Pos);
    cylindricalVertexDistance = fog_cylindrical_distance(Pos);
}
