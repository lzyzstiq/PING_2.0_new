uniform mat4 uMVPMatrix;
uniform float uPointSize;
attribute vec4 aPosition;
attribute float aLife;
varying float vLife;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    gl_PointSize = uPointSize * (0.5 + aLife * 0.5);
    vLife = aLife;
}
