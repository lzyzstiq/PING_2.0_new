package com.ping.sleep;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

public class ParticleGLSurfaceView extends GLSurfaceView {
    private ParticleRenderer renderer;
    private AnimationListener listener;

    public ParticleGLSurfaceView(Context context) {
        super(context);
        init();
    }

    public ParticleGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);
        renderer = new ParticleRenderer(getContext());
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void startAnimation(String quote) {
        renderer.setQuote(quote);
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
