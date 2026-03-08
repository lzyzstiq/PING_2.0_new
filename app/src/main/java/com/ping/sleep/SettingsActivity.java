package com.ping.sleep;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private NumberPicker hourPicker, minutePicker, intervalPicker;
    private EditText pathEdit;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("ping_prefs", MODE_PRIVATE);

        hourPicker = findViewById(R.id.hour_picker);
        minutePicker = findViewById(R.id.minute_picker);
        intervalPicker = findViewById(R.id.interval_picker);
        pathEdit = findViewById(R.id.path_edit);
        Button saveBtn = findViewById(R.id.save_btn);

        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        intervalPicker.setMinValue(1);
        intervalPicker.setMaxValue(120);

        hourPicker.setValue(prefs.getInt("start_hour", 23));
        minutePicker.setValue(prefs.getInt("start_minute", 0));
        intervalPicker.setValue(prefs.getInt("interval_minutes", 30));
        pathEdit.setText(prefs.getString("quote_path", "/sdcard/语料库.txt"));

        saveBtn.setOnClickListener(v -> {
            prefs.edit()
                    .putInt("start_hour", hourPicker.getValue())
                    .putInt("start_minute", minutePicker.getValue())
                    .putInt("interval_minutes", intervalPicker.getValue())
                    .putString("quote_path", pathEdit.getText().toString())
                    .apply();
            Toast.makeText(SettingsActivity.this, "设置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
