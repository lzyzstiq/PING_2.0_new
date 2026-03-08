package com.ping.sleep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;

public class NotificationHelper {
    private static final String CHANNEL_ID = "ping_reminder_channel";
    private static final int NOTIFICATION_ID = 1990;

    public static void showNotification(Context context, String quote) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "平平的提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("平平提醒你该睡了");
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#E6B800"));
            manager.createNotificationChannel(channel);
        }

        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.custom_notification);
        remoteViews.setTextViewText(R.id.notification_text, quote);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon)
                .setCustomContentView(remoteViews)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        manager.notify(NOTIFICATION_ID, notification);
    }
}
