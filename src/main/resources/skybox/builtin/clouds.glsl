// A bright sky with clouds that drift across it, darker towards evening.
vec3 sun = skySunDirection(skyTime);
float day = smoothstep(-0.15, 0.25, sun.y);
vec3 top = mix(vec3(0.02, 0.03, 0.10), vec3(0.20, 0.45, 0.90), day);
vec3 horizon = mix(vec3(0.05, 0.06, 0.12), vec3(0.75, 0.88, 1.00), day);
color = skyGradient(top, horizon, dir);
// Project the sky onto a plane above the viewer, so the clouds shrink towards the horizon.
vec2 plane = dir.xz / max(dir.y + 0.15, 0.05);
float cloud = skyFbm(vec3(plane * 0.6 + vec2(skyAnim * 0.01, 0.0), skyAnim * 0.005));
cloud = smoothstep(0.45, 0.8, cloud) * smoothstep(0.0, 0.25, dir.y);
vec3 cloudColor = mix(vec3(0.15, 0.17, 0.25), vec3(1.0), day);
color = mix(color, cloudColor, cloud * 0.9);
color = mix(color, horizon * 0.6, smoothstep(0.0, -0.4, dir.y));
