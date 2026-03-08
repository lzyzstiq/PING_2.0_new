package com.ping.sleep;

import android.content.Context;
import android.content.SharedPreferences;
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

    private enum State { CONVERGE, WRITING, DONE }

    // 粒子参数
    private static final int PARTICLE_COUNT = 2000;           // 粒子总数
    private static final float PARTICLE_SIZE = 8.0f;          // 单个粒子大小
    private static final float CENTER_DOT_SIZE = 4.0f;        // 中心点大小（小于粒子）
    private static final float WRITE_DOT_SIZE = 3.0f;         // 文字闪烁点大小
    private static final float CONVERGE_DURATION = 5.0f;      // 汇聚阶段持续时间（秒）
    private static final float WRITE_DURATION_PER_CHAR = 1.5f; // 每个字符书写时间
    private static final float MAX_WRITE_TIME = 10.0f;        // 最长书写时间

    // 粒子运动参数
    private static final float RADIAL_SPEED = 100.0f;          // 径向速度（像素/秒）
    private static final float ANGULAR_SPEED_RANGE = 2.0f;     // 角速度范围（弧度/秒）
    private static final float DEAD_RADIUS = 1.0f;             // 粒子死亡半径（小于此值即消失）

    private Context context;
    private String quoteText = "子瑜，晚安";  // 默认语录
    private AnimationListener listener;
    private boolean isRunning = true;
    private SharedPreferences prefs;

    // OpenGL 相关
    private int program;
    private int muMVPMatrixHandle;
    private int muColorHandle;
    private int muPointSizeHandle;
    private int maPositionHandle;
    private float[] mvpMatrix = new float[16];
    private float[] projectionMatrix = new float[16];
    private float[] viewMatrix = new float[16];

    // 粒子数据 (交错布局: x, y, life, angle, radius, radialSpeed, angularSpeed)
    private static final int STRIDE = 7; // x, y, life, angle, radius, radialSpeed, angularSpeed
    private float[] particleData;
    private FloatBuffer particleBuffer;

    // 状态机
    private State currentState = State.CONVERGE;
    private float convergeTimer = 0;
    private float writingTimer = 0;
    private long lastTimeNs;

    // 文字路径采样点
    private List<PointF> textPoints = new ArrayList<>();
    private int totalPoints;
    private float penX, penY;            // 当前笔的位置
    private List<PointF> writtenPoints = new ArrayList<>(); // 已书写的点
    private float flashTimer = 0;         // 用于闪烁的时间计数器

    // 屏幕尺寸
    private int screenWidth, screenHeight;
    private float maxRadius;              // 最大半径（对角线一半）

    // 随机数生成器
    private Random random = new Random();

    public ParticleRenderer(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
        this.quoteText = QuoteManager.getRandomQuote(context, prefs);
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
        lastTimeNs = System.nanoTime();
        writtenPoints.clear();
        initParticles();
        initTextPath();
    }

    public void stop() {
        isRunning = false;
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
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        muColorHandle = GLES20.glGetUniformLocation(program, "uColor");
        muPointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize");
    }

    private String readRawTextFile(Context context, int resId) {
        Resources res = context.getResources();
        InputStream is = res.openRawResource(resId);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\\n");
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
        maxRadius = (float) Math.sqrt(screenWidth * screenWidth + screenHeight * screenHeight) / 2;
        particleData = new float[PARTICLE_COUNT * STRIDE];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float radius = maxRadius; // 所有粒子初始都在屏幕边界（最大半径）
            float angle = random.nextFloat() * 2 * (float) Math.PI;
            float radialSpeed = RADIAL_SPEED;
            float angularSpeed = (random.nextFloat() - 0.5f) * 2 * ANGULAR_SPEED_RANGE;
            float x = (float) (screenWidth / 2 + radius * Math.cos(angle));
            float y = (float) (screenHeight / 2 + radius * Math.sin(angle));
            float life = 1.0f; // 初始生命为1
            particleData[i * STRIDE] = x;
            particleData[i * STRIDE + 1] = y;
            particleData[i * STRIDE + 2] = life;
            particleData[i * STRIDE + 3] = angle;
            particleData[i * STRIDE + 4] = radius;
            particleData[i * STRIDE + 5] = radialSpeed;
            particleData[i * STRIDE + 6] = angularSpeed;
        }

        ByteBuffer bb = ByteBuffer.allocateDirect(particleData.length * 4);
        bb.order(ByteOrder.nativeOrder());
        particleBuffer = bb.asFloatBuffer();
        particleBuffer.put(particleData);
        particleBuffer.position(0);
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

        totalPoints = textPoints.size();
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

        // 状态更新
        switch (currentState) {
            case CONVERGE:
                convergeTimer += deltaTime;
                updateConverge(deltaTime);
                if (convergeTimer >= CONVERGE_DURATION) {
                    currentState = State.WRITING;
                    writingTimer = 0;
                    // 清空粒子（不再绘制）
                    clearParticles();
                    if (totalPoints > 0) {
                        penX = textPoints.get(0).x;
                        penY = textPoints.get(0).y;
                    }
                }
                break;
            case WRITING:
                writingTimer += deltaTime;
                float totalWriteTime = Math.min(quoteText.length() * WRITE_DURATION_PER_CHAR, MAX_WRITE_TIME);
                float progress = Math.min(1.0f, writingTimer / totalWriteTime);
                int targetIndex = (int) (progress * totalPoints);
                if (targetIndex >= totalPoints) targetIndex = totalPoints - 1;
                penX = textPoints.get(targetIndex).x;
                penY = textPoints.get(targetIndex).y;
                // 记录已写点
                for (int i = writtenPoints.size(); i <= targetIndex; i++) {
                    writtenPoints.add(textPoints.get(i));
                }
                if (writingTimer >= totalWriteTime) {
                    currentState = State.DONE;
                    if (listener != null) {
                        listener.onAnimationFinished();
                    }
                }
                break;
            case DONE:
                flashTimer += deltaTime;
                break;
        }

        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0);

        // 绘制汇聚粒子（仅在汇聚阶段）
        if (currentState == State.CONVERGE) {
            updateVertexBuffer();
            GLES20.glUniform3f(muColorHandle, 1.0f, 1.0f, 1.0f); // 白色
            GLES20.glUniform1f(muPointSizeHandle, PARTICLE_SIZE);
            particleBuffer.position(0);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, STRIDE * 4, particleBuffer);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, PARTICLE_COUNT);
            GLES20.glDisableVertexAttribArray(maPositionHandle);
        }

        // 绘制已书写的文字点和连接线段（在 WRITING 和 DONE 阶段）
        if (currentState != State.CONVERGE && !writtenPoints.isEmpty()) {
            // 计算闪烁颜色
            float flash = 0.5f + 0.5f * (float) Math.sin(flashTimer * 5.0f);

            // 绘制连接线段（光痕）
            if (writtenPoints.size() >= 2) {
                float[] lineVertices = new float[writtenPoints.size() * 2];
                for (int i = 0; i < writtenPoints.size(); i++) {
                    lineVertices[i * 2] = writtenPoints.get(i).x;
                    lineVertices[i * 2 + 1] = writtenPoints.get(i).y;
                }
                ByteBuffer lb = ByteBuffer.allocateDirect(lineVertices.length * 4);
                lb.order(ByteOrder.nativeOrder());
                FloatBuffer lineBuffer = lb.asFloatBuffer();
                lineBuffer.put(lineVertices);
                lineBuffer.position(0);

                GLES20.glUniform3f(muColorHandle, flash * 0.8f, flash * 0.8f, flash * 0.8f); // 稍暗的线段
                GLES20.glUniform1f(muPointSizeHandle, 1.0f); // 线宽由 glLineWidth 控制，这里用点大小无效
                // 设置线宽（OpenGL ES 可能不支持所有线宽，但尝试）
                GLES20.glLineWidth(2.0f);
                GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, lineBuffer);
                GLES20.glEnableVertexAttribArray(maPositionHandle);
                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, writtenPoints.size());
                GLES20.glDisableVertexAttribArray(maPositionHandle);
            }

            // 绘制文字点阵（闪烁）
            float[] pointVertices = new float[writtenPoints.size() * 2];
            for (int i = 0; i < writtenPoints.size(); i++) {
                pointVertices[i * 2] = writtenPoints.get(i).x;
                pointVertices[i * 2 + 1] = writtenPoints.get(i).y;
            }
            ByteBuffer pb = ByteBuffer.allocateDirect(pointVertices.length * 4);
            pb.order(ByteOrder.nativeOrder());
            FloatBuffer pointBuffer = pb.asFloatBuffer();
            pointBuffer.put(pointVertices);
            pointBuffer.position(0);

            GLES20.glUniform3f(muColorHandle, flash, flash, flash); // 白色闪烁
            GLES20.glUniform1f(muPointSizeHandle, WRITE_DOT_SIZE);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, pointBuffer);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, writtenPoints.size());
            GLES20.glDisableVertexAttribArray(maPositionHandle);
        }

        // 绘制中心笔点（在 WRITING 阶段）
        if (currentState == State.WRITING) {
            float[] center = {penX, penY};
            ByteBuffer cb = ByteBuffer.allocateDirect(8);
            cb.order(ByteOrder.nativeOrder());
            FloatBuffer centerBuffer = cb.asFloatBuffer();
            centerBuffer.put(center);
            centerBuffer.position(0);

            GLES20.glUniform3f(muColorHandle, 1.0f, 1.0f, 1.0f);
            GLES20.glUniform1f(muPointSizeHandle, CENTER_DOT_SIZE);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, centerBuffer);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1);
            GLES20.glDisableVertexAttribArray(maPositionHandle);
        }
    }

    private void updateConverge(float dt) {
        float cx = screenWidth / 2f;
        float cy = screenHeight / 2f;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int idx = i * STRIDE;
            float life = particleData[idx + 2];
            if (life <= 0) continue; // 已死亡的粒子不再更新

            float radius = particleData[idx + 4];
            float angle = particleData[idx + 3];
            float radialSpeed = particleData[idx + 5];
            float angularSpeed = particleData[idx + 6];

            // 更新半径和角度
            radius -= radialSpeed * dt;
            angle += angularSpeed * dt;

            if (radius <= DEAD_RADIUS) {
                // 粒子到达中心，标记为死亡（移到屏幕外）
                particleData[idx] = -10000; // 移到屏幕外
                particleData[idx + 1] = -10000;
                particleData[idx + 2] = 0;   // life=0
                // 其余数据不再重要
                continue;
            }

            // 计算新位置
            float x = cx + radius * (float) Math.cos(angle);
            float y = cy + radius * (float) Math.sin(angle);
            life = 1.0f;

            // 存储更新后的数据
            particleData[idx] = x;
            particleData[idx + 1] = y;
            particleData[idx + 2] = life;
            particleData[idx + 3] = angle;
            particleData[idx + 4] = radius;
            // radialSpeed 和 angularSpeed 不变
        }
    }

    private void clearParticles() {
        // 将所有粒子移到屏幕外，并设置 life=0
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int idx = i * STRIDE;
            particleData[idx] = -10000;
            particleData[idx + 1] = -10000;
            particleData[idx + 2] = 0;
        }
    }

    private void updateVertexBuffer() {
        particleBuffer.position(0);
        particleBuffer.put(particleData);
        particleBuffer.position(0);
    }
}