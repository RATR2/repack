// A night sky full of stars that fade out as the sun comes up, with a slowly turning sky.
vec3 sun = skySunDirection(skyTime);
float night = smoothstep(0.1, -0.2, sun.y);
vec3 turned = vec3(dir.x * cos(skyAnim * 0.002) - dir.z * sin(skyAnim * 0.002), dir.y, dir.x * sin(skyAnim * 0.002) + dir.z * cos(skyAnim * 0.002));
vec3 top = mix(vec3(0.20, 0.40, 0.85), vec3(0.00, 0.01, 0.05), night);
vec3 horizon = mix(vec3(0.65, 0.80, 1.00), vec3(0.02, 0.03, 0.10), night);
color = skyGradient(top, horizon, dir);
color += vec3(skyStars(turned, 0.6)) * night;
color += vec3(0.6, 0.65, 0.9) * skyNoise(turned * 3.0) * 0.08 * night;
color = mix(color, horizon * 0.4, smoothstep(0.0, -0.3, dir.y));
