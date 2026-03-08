package com.ping.sleep;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1001;

    private TextView currentQuoteText;
    private Button startServiceBtn;
    private SharedPreferences prefs;
    private ParticleGLSurfaceView glView;
    private View mainContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glView = findViewById(R.id.splash_glview);
        mainContent = findViewById(R.id.main_content);
        currentQuoteText = findViewById(R.id.current_quote);
        startServiceBtn = findViewById(R.id.start_service);
        ImageButton settingsBtn = findViewById(R.id.settings_btn);
        prefs = getSharedPreferences("ping_prefs", MODE_PRIVATE);

        // 权限检查（与之前相同）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
            } else {
                loadExternalQuotes();
            }
        } else {
            loadExternalQuotes();
        }

        // 恢复守护状态
        boolean alarmEnabled = prefs.getBoolean("alarm_enabled", false);
        if (alarmEnabled) {
            AlarmHelper.setAlarm(this, prefs);
            startServiceBtn.setText("守护中");
        } else {
            AlarmHelper.cancelAlarm(this);
            startServiceBtn.setText("启动守护");
        }

        // 设置动画监听
        glView.setAnimationListener(() -> runOnUiThread(this::switchToMain));

        // 开始动画（传入 SharedPreferences，以便渲染器获取语录）
        glView.startAnimation(prefs);

        // 守护按钮点击
        startServiceBtn.setOnClickListener(v -> {
            boolean enabled = prefs.getBoolean("alarm_enabled", false);
            if (!enabled) {
                AlarmHelper.setAlarm(this, prefs);
                prefs.edit().putBoolean("alarm_enabled", true).apply();
                startServiceBtn.setText("守护中");
                Toast.makeText(this, "夜间提醒已开启", Toast.LENGTH_SHORT).show();
            } else {
                AlarmHelper.cancelAlarm(this);
                prefs.edit().putBoolean("alarm_enabled", false).apply();
                startServiceBtn.setText("启动守护");
                Toast.makeText(this, "夜间提醒已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        settingsBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    private void switchToMain() {
        // 先隐藏 GLSurfaceView
        glView.setVisibility(View.GONE);

        // 设置主内容背景为 background.png（已在布局中指定）
        // 渐变动画：从透明到不透明
        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        fadeIn.setDuration(1000); // 1秒
        mainContent.startAnimation(fadeIn);
        mainContent.setVisibility(View.VISIBLE);

        // 按钮浮现动画（缩放效果）
        ScaleAnimation scaleIn = new ScaleAnimation(0.0f, 1.0f, 0.0f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        scaleIn.setDuration(800);
        scaleIn.setStartOffset(500); // 延迟0.5秒开始
        startServiceBtn.startAnimation(scaleIn);
        findViewById(R.id.settings_btn).startAnimation(scaleIn);

        // 更新语录显示
        updateQuoteDisplay();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    loadExternalQuotes();
                    updateQuoteDisplay();
                } else {
                    Toast.makeText(this, "需要文件管理权限才能读取自定义语录", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void loadExternalQuotes() {
        QuoteManager.loadQuotes(this, prefs);
    }

    private void updateQuoteDisplay() {
        String quote = QuoteManager.getRandomQuote(this, prefs);
        currentQuoteText.setText(quote);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainContent.getVisibility() == View.VISIBLE) {
            QuoteManager.loadQuotes(this, prefs);
            updateQuoteDisplay();
            boolean enabled = prefs.getBoolean("alarm_enabled", false);
            startServiceBtn.setText(enabled ? "守护中" : "启动守护");
        }
        glView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }
}
