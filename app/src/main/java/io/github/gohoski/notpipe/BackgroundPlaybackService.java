package io.github.gohoski.notpipe;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;

/**
 * Keeps the current audio stream alive while the video screen is in the background.
 * The implementation uses APIs available on Android 4.1 (API 16).
 */
public class BackgroundPlaybackService extends Service {
    public static final String ACTION_START = "io.github.gohoski.notpipe.action.BACKGROUND_START";
    public static final String ACTION_TOGGLE = "io.github.gohoski.notpipe.action.BACKGROUND_TOGGLE";
    public static final String ACTION_STOP = "io.github.gohoski.notpipe.action.BACKGROUND_STOP";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_VIDEO_ID = "video_id";

    private static final int NOTIFICATION_ID = 41;
    private static volatile boolean running;
    private static volatile int lastPosition;
    private static volatile boolean playbackRequested;
    private static volatile boolean playbackSessionAvailable;
    private static volatile String playbackVideoId;
    private static volatile String playbackUrl;

    private MediaPlayer player;
    private String title;
    private String videoId;
    private boolean prepared;
    private Handler positionHandler;
    private final Runnable positionRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared && playbackRequested) {
                try {
                    if (player.isPlaying()) lastPosition = player.getCurrentPosition();
                } catch (IllegalStateException ignored) {}
                positionHandler.postDelayed(this, 1000);
            }
        }
    };

    public static boolean isRunning() {
        return running;
    }

    public static int getPosition() {
        return lastPosition;
    }

    public static boolean hasPlaybackSession() {
        return playbackSessionAvailable;
    }

    public static boolean wasPlaybackRequested() {
        return playbackRequested;
    }

    public static String getPlaybackVideoId() {
        return playbackVideoId;
    }

    public static String getPlaybackUrl() {
        return playbackUrl;
    }

    public static void consumePlaybackSession() {
        playbackSessionAvailable = false;
        playbackRequested = false;
        lastPosition = 0;
        playbackVideoId = null;
        playbackUrl = null;
    }

    public static void clearPlaybackSession() {
        playbackSessionAvailable = false;
        playbackRequested = false;
        lastPosition = 0;
        playbackVideoId = null;
        playbackUrl = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        positionHandler = new Handler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_STOP.equals(action)) {
            playbackRequested = false;
            stopSelf();
        } else if (ACTION_START.equals(action)) {
            startPlayback(intent);
        }
        return START_NOT_STICKY;
    }

    private void startPlayback(Intent intent) {
        releasePlayer();
        String url = intent.getStringExtra(EXTRA_URL);
        title = intent.getStringExtra(EXTRA_TITLE);
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        playbackVideoId = videoId;
        playbackUrl = url;
        lastPosition = intent.getIntExtra(EXTRA_POSITION, 0);
        if (url == null || url.length() == 0) {
            clearPlaybackSession();
            stopSelf();
            return;
        }
        playbackRequested = true;
        playbackSessionAvailable = true;

        startForeground(NOTIFICATION_ID, buildNotification(playbackRequested));
        player = new MediaPlayer();
        player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                prepared = true;
                if (lastPosition > 0) mediaPlayer.seekTo(lastPosition);
                if (playbackRequested) mediaPlayer.start();
                updateNotification(playbackRequested);
                positionHandler.removeCallbacks(positionRunnable);
                positionHandler.postDelayed(positionRunnable, 1000);
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                lastPosition = 0;
                playbackRequested = false;
                stopSelf();
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                try {
                    lastPosition = mediaPlayer.getCurrentPosition();
                } catch (IllegalStateException ignored) {}
                stopSelf();
                return true;
            }
        });

        try {
            Uri source = url.indexOf("://") >= 0 ? Uri.parse(url) : Uri.fromFile(new File(url));
            player.setDataSource(this, source);
            player.prepareAsync();
        } catch (IOException e) {
            stopSelf();
        } catch (IllegalArgumentException e) {
            stopSelf();
        } catch (IllegalStateException e) {
            stopSelf();
        }
    }

    private void togglePlayback() {
        playbackRequested = !playbackRequested;
        try {
            if (player != null && prepared) {
                if (playbackRequested) {
                    player.start();
                    positionHandler.removeCallbacks(positionRunnable);
                    positionHandler.postDelayed(positionRunnable, 1000);
                } else {
                    lastPosition = player.getCurrentPosition();
                    player.pause();
                    positionHandler.removeCallbacks(positionRunnable);
                }
            }
        } catch (IllegalStateException ignored) {}
        updateNotification(playbackRequested);
    }

    private void updateNotification(boolean playing) {
        if (player != null && prepared) {
            try {
                lastPosition = player.getCurrentPosition();
            } catch (IllegalStateException ignored) {}
        }
        startForeground(NOTIFICATION_ID, buildNotification(playing));
    }

    private Notification buildNotification(boolean playing) {
        Intent openIntent = new Intent(this, VideoActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("ID", videoId);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggleIntent = new Intent(this, BackgroundPlaybackService.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent togglePendingIntent = PendingIntent.getService(this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.video_icon)
                .setContentTitle(title == null ? getString(R.string.app_name) : title)
                .setContentText(getString(playing ? R.string.background_playing : R.string.background_paused))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? getString(R.string.pause) : getString(R.string.resume), togglePendingIntent);
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.getNotification();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                if (prepared && player.isPlaying()) lastPosition = player.getCurrentPosition();
            } catch (Exception ignored) {}
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        prepared = false;
    }

    @Override
    public void onDestroy() {
        if (positionHandler != null) positionHandler.removeCallbacks(positionRunnable);
        releasePlayer();
        running = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
