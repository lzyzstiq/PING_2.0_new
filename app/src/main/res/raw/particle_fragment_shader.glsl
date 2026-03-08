precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
varying float vLife;

void main() {
    vec2 center = vec2(0.5, 0.5);
    float dist = distance(gl_PointCoord, center);
    if (dist > 0.5) discard;
    float alpha = uAlpha * vLife * (1.0 - smoothstep(0.4, 0.5, dist));
    gl_FragColor = vec4(uColor, alpha);
}
