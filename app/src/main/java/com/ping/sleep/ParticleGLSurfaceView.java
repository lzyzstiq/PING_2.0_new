package com.ping.sleep;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

public class ParticleGLSurfaceView extends GLSurfaceView {
    private ParticleRenderer renderer;
    private AnimationListener listener;

    public ParticleGLSurfaceView(Context context) {
        super(context);
        init(context);
    }

    public ParticleGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setEGLContextClientVersion(2);
        // 注意：这里需要传入 SharedPreferences，因为渲染器需要获取语录
        // 但 SharedPreferences 在 Activity 中才能获得，我们将在 startAnimation 时设置
        renderer = new ParticleRenderer(context, null); // 稍后设置 prefs
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void startAnimation(SharedPreferences prefs) {
        renderer = new ParticleRenderer(getContext(), prefs);
        setRenderer(renderer);
        renderer.start();
    }

    public void stopAnimation() {
        renderer.stop();
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setAnimationListener(AnimationListener listener) {
        this.listener = listener;
        renderer.setListener(listener);
    }
}
