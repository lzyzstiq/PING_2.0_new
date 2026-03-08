package com.ping.sleep;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import androidx.core.content.res.ResourcesCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class ParticleRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "ParticleRenderer";

    private enum State { CONVERGE, WRITING, FLASH }

    private static final int PARTICLE_COUNT = 1500;
    private static final float CONVERGE_DURATION = 4.0f;
    private static final float PER_CHAR_TIME = 2.0f;
    private static final float MAX_WRITING_TIME = 8.0f;
    private static final float GRAVITY_STRENGTH = 1000.0f;
    private static final float ABSORB_RADIUS = 10.0f;
    private static final float PARTICLE_BASE_SIZE = 8.0f;
    private static final float[] PARTICLE_COLOR = {0.9f, 0.72f, 0.0f, 1.0f};
    private static final float FLASH_PARTICLE_COUNT_PER_CHAR = 20;

    private Context context;
    private String quoteText = "子瑜，晚安";  // 默认语录，稍后会被 setQuote 替换
    private AnimationListener listener;
    private boolean isRunning = true;
    private boolean isFlashing = true;

    private int program;
    private int muMVPMatrixHandle;
    private int muColorHandle;
    private int muPointSizeHandle;
    private int muAlphaHandle;
    private int maPositionHandle;
    private int maLifeHandle;
    private FloatBuffer particleBuffer;
    private float[] mvpMatrix = new float[16];
    private float[] projectionMatrix = new float[16];
    private float[] viewMatrix = new float[16];

    private static final int STRIDE = 5;
    private float[] particleData;
    private FloatBuffer vertexBuffer;

    private State currentState = State.CONVERGE;
    private float convergeTimer = 0;
    private float writingTimer = 0;
    private float flashTimer = 0;
    private float totalWritingTime;
    private long lastTimeNs;

    private List<PointF> textPoints = new ArrayList<>();
    private int totalPoints;
    private float blackHoleX, blackHoleY;
    private List<PointF> writtenPath = new ArrayList<>();

    private static class FlashParticle {
        float x, y;
        float vx, vy;
        float phase;
        float speed;
    }
    private List<FlashParticle> flashParticles = new ArrayList<>();

    private int screenWidth, screenHeight;
    private Random random = new Random();

    // 构造函数：只保存 Context，不做任何需要 SharedPreferences 的操作
    public ParticleRenderer(Context context) {
        this.context = context;
    }

    public void setQuote(String quote) {
        this.quoteText = quote;
    }

    public void setListener(AnimationListener listener) {
        this.listener = listener;
    }

    public void start() {
        isRunning = true;
        currentState = State.CONVERGE;
        convergeTimer = 0;
        writingTimer = 0;
        flashTimer = 0;
        isFlashing = true;
        lastTimeNs = System.nanoTime();
        writtenPath.clear();
    }

    public void stop() {
        isRunning = false;
    }

    public void toggleFlash() {
        isFlashing = !isFlashing;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        String vertexShaderSource = readRawTextFile(context, R.raw.particle_vertex_shader);
        String fragmentShaderSource = readRawTextFile(context, R.raw.particle_fragment_shader);
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        Log.d(TAG, "program link status: " + linkStatus[0]);

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        maLifeHandle = GLES20.glGetAttribLocation(program, "aLife");
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        muColorHandle = GLES20.glGetUniformLocation(program, "uColor");
        muPointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize");
        muAlphaHandle = GLES20.glGetUniformLocation(program, "uAlpha");
    }

    private String readRawTextFile(Context context, int resId) {
        Resources res = context.getResources();
        InputStream is = res.openRawResource(resId);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void initParticles() {
        if (screenWidth == 0 || screenHeight == 0) return;
        particleData = new float[PARTICLE_COUNT * STRIDE];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = Math.max(screenWidth, screenHeight) * 0.8 + random.nextDouble() * 100;
            float x = (float) (screenWidth / 2 + radius * Math.cos(angle));
            float y = (float) (screenHeight / 2 + radius * Math.sin(angle));
            particleData[i * STRIDE] = x;
            particleData[i * STRIDE + 1] = y;
            particleData[i * STRIDE + 2] = 0;
            particleData[i * STRIDE + 3] = 0;
            particleData[i * STRIDE + 4] = 1.0f;
        }

        ByteBuffer bb = ByteBuffer.allocateDirect(particleData.length * 4);
        bb.order(ByteOrder.nativeOrder());
        particleBuffer = bb.asFloatBuffer();
        particleBuffer.put(particleData);
        particleBuffer.position(0);
        Log.d(TAG, "initParticles done, first particle: (" + particleData[0] + "," + particleData[1] + ")");
    }

    private void initTextPath() {
        Typeface typeface;
        try {
            typeface = ResourcesCompat.getFont(context, R.font.my_font);
        } catch (Exception e) {
            typeface = Typeface.DEFAULT;
        }
        if (typeface == null) typeface = Typeface.DEFAULT;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setTextSize(200);

        float maxWidth = screenWidth * 0.8f;
        List<String> lines = wrapText(quoteText, paint, maxWidth);
        float lineSpacing = 1.2f;
        float yOffset = 0;

        textPoints.clear();

        if (lines.isEmpty() || quoteText.isEmpty()) {
            Log.w(TAG, "initTextPath: using fallback diagonal line");
            for (int i = 0; i <= 100; i++) {
                float t = i / 100f;
                float x = screenWidth * 0.2f + t * screenWidth * 0.6f;
                float y = screenHeight * 0.3f + t * screenHeight * 0.4f;
                textPoints.add(new PointF(x, y));
            }
        } else {
            for (String line : lines) {
                Path path = new Path();
                paint.getTextPath(line, 0, line.length(), 0, yOffset, path);
                PathMeasure pm = new PathMeasure(path, false);
                float length = pm.getLength();
                int steps = (int) (length / 2);
                for (int i = 0; i <= steps; i++) {
                    float[] pos = new float[2];
                    pm.getPosTan(i * 2, pos, null);
                    textPoints.add(new PointF(pos[0], pos[1]));
                }
                yOffset += paint.getTextSize() * lineSpacing;
            }

            Rect bounds = new Rect();
            paint.getTextBounds(quoteText, 0, quoteText.length(), bounds);
            float totalHeight = lines.size() * paint.getTextSize() * lineSpacing;
            float offsetX = (screenWidth - bounds.width()) / 2f - bounds.left;
            float offsetY = (screenHeight - totalHeight) / 2f;
            for (PointF p : textPoints) {
                p.x += offsetX;
                p.y += offsetY;
            }
        }

        totalPoints = textPoints.size();
        totalWritingTime = Math.min(quoteText.length() * PER_CHAR_TIME, MAX_WRITING_TIME);
        Log.d(TAG, "initTextPath: totalPoints=" + totalPoints);
    }

    private List<String> wrapText(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0) {
            lines.add(text);
            return lines;
        }
        String remaining = text;
        while (!remaining.isEmpty()) {
            int count = paint.breakText(remaining, true, maxWidth, null);
            if (count == 0) break;
            lines.add(remaining.substring(0, count));
            remaining = remaining.substring(count);
        }
        return lines;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        GLES20.glViewport(0, 0, width, height);
        float ratio = (float) width / height;
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1, 1, 3, 7);
        Matrix.setLookAtM(viewMatrix, 0, 0, 0, 5, 0, 0, 0, 0, 1, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

        initParticles();
        initTextPath();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!isRunning) return;

        long now = System.nanoTime();
        float deltaTime = (now - lastTimeNs) / 1e9f;
        if (deltaTime > 0.1f) deltaTime = 0.016f;
        lastTimeNs = now;

        switch (currentState) {
            case CONVERGE:
                convergeTimer += deltaTime;
                updateConverge(deltaTime);
                if (convergeTimer >= CONVERGE_DURATION) {
                    currentState = State.WRITING;
                    writingTimer = 0;
                    if (!textPoints.isEmpty()) {
                        blackHoleX = textPoints.get(0).x;
                        blackHoleY = textPoints.get(0).y;
                    }
                    writtenPath.clear();
                }
                break;
            case WRITING:
                writingTimer += deltaTime;
                if (totalPoints > 0) {
                    float progress = Math.min(1.0f, writingTimer / totalWritingTime);
                    int targetIndex = (int) (progress * totalPoints);
                    if (targetIndex >= totalPoints) targetIndex = totalPoints - 1;
                    blackHoleX = textPoints.get(targetIndex).x;
                    blackHoleY = textPoints.get(targetIndex).y;
                    for (int i = writtenPath.size(); i <= targetIndex; i++) {
                        writtenPath.add(textPoints.get(i));
                    }
                }
                if (writingTimer >= totalWritingTime) {
                    currentState = State.FLASH;
                    initFlashParticles();
                }
                break;
            case FLASH:
                flashTimer += deltaTime;
                if (isFlashing) {
                    updateFlashParticles(deltaTime);
                }
                break;
        }

        updateVertexBuffer();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniform3f(muColorHandle, PARTICLE_COLOR[0], PARTICLE_COLOR[1], PARTICLE_COLOR[2]);
        GLES20.glUniform1f(muPointSizeHandle, PARTICLE_BASE_SIZE);
        float alpha = (currentState == State.FLASH && !isFlashing) ? 0.3f : 1.0f;
        GLES20.glUniform1f(muAlphaHandle, alpha);

        particleBuffer.position(0);
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, STRIDE * 4, particleBuffer);
        particleBuffer.position(4);
        GLES20.glVertexAttribPointer(maLifeHandle, 1, GLES20.GL_FLOAT, false, STRIDE * 4, particleBuffer);

        GLES20.glEnableVertexAttribArray(maPositionHandle);
        GLES20.glEnableVertexAttribArray(maLifeHandle);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, PARTICLE_COUNT);
        GLES20.glDisableVertexAttribArray(maPositionHandle);
        GLES20.glDisableVertexAttribArray(maLifeHandle);
    }

    private void updateConverge(float dt) {
        float cx = screenWidth / 2f;
        float cy = screenHeight / 2f;
        float k = GRAVITY_STRENGTH;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int idx = i * STRIDE;
            float px = particleData[idx];
            float py = particleData[idx + 1];
            float vx = particleData[idx + 2];
            float vy = particleData[idx + 3];
            float life = particleData[idx + 4];

            float dx = cx - px;
            float dy = cy - py;
            float r2 = dx*dx + dy*dy + 1e-5f;
            float r = (float) Math.sqrt(r2);
            float f = k / r2;
            float ax = f * dx / r;
            float ay = f * dy / r;

            vx += ax * dt;
            vy += ay * dt;
            px += vx * dt;
            py += vy * dt;

            if (r < ABSORB_RADIUS) {
                px = cx;
                py = cy;
                life = Math.max(0, life - dt * 2);
            }

            particleData[idx] = px;
            particleData[idx + 1] = py;
            particleData[idx + 2] = vx;
            particleData[idx + 3] = vy;
            particleData[idx + 4] = life;
        }
    }

    private void initFlashParticles() {
        flashParticles.clear();
        int charCount = quoteText.length();
        for (int c = 0; c < charCount; c++) {
            int start = (c * totalPoints) / charCount;
            int end = ((c + 1) * totalPoints) / charCount;
            float cx = 0, cy = 0;
            for (int i = start; i < end; i++) {
                cx += textPoints.get(i).x;
                cy += textPoints.get(i).y;
            }
            int count = end - start;
            if (count > 0) {
                cx /= count;
                cy /= count;
            } else {
                cx = screenWidth / 2f;
                cy = screenHeight / 2f;
            }

            for (int j = 0; j < FLASH_PARTICLE_COUNT_PER_CHAR; j++) {
                FlashParticle p = new FlashParticle();
                double angle = random.nextDouble() * 2 * Math.PI;
                float dist = 30 + random.nextFloat() * 50;
                p.x = cx + (float) (dist * Math.cos(angle));
                p.y = cy + (float) (dist * Math.sin(angle));
                p.vx = (random.nextFloat() - 0.5f) * 20;
                p.vy = (random.nextFloat() - 0.5f) * 20;
                p.phase = random.nextFloat() * (float) (2 * Math.PI);
                p.speed = 2 + random.nextFloat() * 3;
                flashParticles.add(p);
            }
        }
    }

    private void updateFlashParticles(float dt) {
        for (FlashParticle p : flashParticles) {
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            if (p.x < 0 || p.x > screenWidth) p.vx = -p.vx;
            if (p.y < 0 || p.y > screenHeight) p.vy = -p.vy;
        }
    }

    private void updateVertexBuffer() {
        particleBuffer.position(0);
        particleBuffer.put(particleData);
        particleBuffer.position(0);
    }
}
