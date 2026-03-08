package com.ping.sleep;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

public class SleepAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("ping_prefs", Context.MODE_PRIVATE);

        String quote = QuoteManager.getRandomQuote(context, prefs);
        NotificationHelper.showNotification(context, quote);

        int interval = prefs.getInt("interval_minutes", 30);
        long nextTime = System.currentTimeMillis() + interval * 60 * 1000L;

        int hour = prefs.getInt("start_hour", 23);
        int minute = prefs.getInt("start_minute", 0);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        long tomorrowStart = todayStart + 24 * 60 * 60 * 1000L;

        if (nextTime > tomorrowStart) {
            nextTime = tomorrowStart;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent nextIntent = new Intent(context, SleepAlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        // 使用普通闹钟
        alarmManager.set(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent);
    }
}
