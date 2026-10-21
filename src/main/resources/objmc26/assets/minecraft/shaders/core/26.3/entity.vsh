#version 450
#extension GL_ARB_separate_shader_objects : require
#extension GL_KHR_shader_subgroup_quad : enable

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler0;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif

layout(location = 10) out vec3 Pos;
layout(location = 11) out float transition;
layout(location = 12) out vec4 objOverlay;
layout(location = 13) out vec4 objLight;

layout(location = 14) flat out int isCustom;
layout(location = 15) flat out int isGUI;
layout(location = 16) flat out int isHand;
layout(location = 17) flat out int noshadow;

#include <minecraft:objmc_tools.glsl>

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    lightMapColor = sample_lightmap(Sampler2, UV2);
#endif

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

    texCoord0 = UV0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

    //objmc
    Pos = Position;
    vec2 texCoord = texCoord0;
    vec4 objOverlayLocal = vec4(1);
#define overlayColor objOverlayLocal
    #define ENTITY
    #include <minecraft:objmc_main.glsl>
#undef overlayColor
    texCoord0 = texCoord;
    objOverlay = objOverlayLocal;
#if defined(EMISSIVE) || defined(OIT_ALPHA_ONLY)
    objLight = vec4(1);
#else
    objLight = sample_lightmap(Sampler2, UV2);
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(Pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Pos);
    cylindricalVertexDistance = fog_cylindrical_distance(Pos);
    // A custom model is lit by the fragment shader, so what the game worked out from the item colour (which carries objmc's settings) is cleared.
    if (isCustom == 1) {
#ifdef PER_FACE_LIGHTING
        vertexPerFaceColorBack = vec4(1);
        vertexPerFaceColorFront = vec4(1);
#else
        vertexColor = vec4(1);
#endif
#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
        lightMapColor = vec4(1);
#endif
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
        overlayColor = vec4(1);
#endif
    }
}
