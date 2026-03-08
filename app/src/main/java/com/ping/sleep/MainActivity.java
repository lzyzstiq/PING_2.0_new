package com.ping.sleep;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
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
    private Button skipBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glView = findViewById(R.id.splash_glview);
        mainContent = findViewById(R.id.main_content);
        skipBtn = findViewById(R.id.skip_btn);
        currentQuoteText = findViewById(R.id.current_quote);
        startServiceBtn = findViewById(R.id.start_service);
        ImageButton settingsBtn = findViewById(R.id.settings_btn);
        prefs = getSharedPreferences("ping_prefs", MODE_PRIVATE);

        // 权限检查
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

        // 获取随机语录用于动画
        String quote = QuoteManager.getRandomQuote(this, prefs);

        // 设置动画监听
        glView.setAnimationListener(new AnimationListener() {
            @Override
            public void onAnimationFinished() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        switchToMain();
                    }
                });
            }
        });

        // 跳过按钮
        skipBtn.setOnClickListener(v -> {
            glView.stopAnimation();
            switchToMain();
        });

        // 开始动画
        glView.startAnimation(quote);

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
        glView.setVisibility(View.GONE);
        skipBtn.setVisibility(View.GONE);
        mainContent.setVisibility(View.VISIBLE);
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
