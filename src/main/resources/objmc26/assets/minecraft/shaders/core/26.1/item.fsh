#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:light.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

in vec3 Pos;
in float transition;
in vec4 objOverlay;
in vec4 objLight;

flat in int isCustom;
flat in int isGUI;
flat in int isHand;
flat in int noshadow;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    if (isCustom == 0) {
        color *= vertexColor * ColorModulator;
    } else {
        if (noshadow == 0) {
            vec3 normal = normalize(cross(dFdx(Pos), dFdy(Pos)));
            if (isGUI == 1) normal.y *= -1;
            color *= minecraft_mix_light(Light0_Direction, Light1_Direction, normal, objOverlay);
            color *= objLight;
        }
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
