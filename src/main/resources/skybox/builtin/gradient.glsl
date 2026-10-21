// A sky that follows the time of day: blue by day, dark by night, and warm at sunrise and sunset.
vec3 sun = skySunDirection(skyTime);
float day = smoothstep(-0.15, 0.25, sun.y);
vec3 top = mix(vec3(0.01, 0.02, 0.08), vec3(0.25, 0.50, 0.95), day);
vec3 horizon = mix(vec3(0.03, 0.04, 0.10), vec3(0.70, 0.85, 1.00), day);
float glow = exp(-pow(sun.y * 4.0, 2.0)) * pow(max(dot(normalize(vec3(dir.x, 0.0, dir.z) + 1e-5), normalize(vec3(sun.x, 0.0, sun.z) + 1e-5)), 0.0), 3.0);
horizon = mix(horizon, vec3(1.00, 0.55, 0.25), glow * 0.8);
color = skyGradient(top, horizon, dir);
color = mix(color, horizon * 0.5, smoothstep(0.0, -0.4, dir.y));
