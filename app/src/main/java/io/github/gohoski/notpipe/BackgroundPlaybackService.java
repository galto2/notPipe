package io.github.gohoski.notpipe;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import io.github.gohoski.notpipe.api.Codec;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.http.HttpClient;

/**
 * Keeps the current audio stream alive while the video screen is in the background.
 * The implementation uses APIs available on Android 4.1 (API 16).
 */
public class BackgroundPlaybackService extends Service {
    public static final String ACTION_START = "io.github.gohoski.notpipe.action.BACKGROUND_START";
    public static final String ACTION_TOGGLE = "io.github.gohoski.notpipe.action.BACKGROUND_TOGGLE";
    public static final String ACTION_STOP = "io.github.gohoski.notpipe.action.BACKGROUND_STOP";
    public static final String ACTION_NEXT = "io.github.gohoski.notpipe.action.BACKGROUND_NEXT";
    public static final String ACTION_PREVIOUS = "io.github.gohoski.notpipe.action.BACKGROUND_PREVIOUS";
    public static final String ACTION_UPDATE_QUEUE = "io.github.gohoski.notpipe.action.BACKGROUND_UPDATE_QUEUE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CHANNEL = "channel";
    public static final String EXTRA_THUMBNAIL_URL = "thumbnail_url";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_VIDEO_ID = "video_id";
    public static final String EXTRA_OPEN_FROM_NOTIFICATION = "open_from_background_notification";
    public static final String EXTRA_QUEUE_IDS = "queue_ids";
    public static final String EXTRA_QUEUE_TITLES = "queue_titles";
    public static final String EXTRA_QUEUE_CHANNELS = "queue_channels";
    public static final String EXTRA_QUEUE_THUMBNAILS = "queue_thumbnails";
    public static final String EXTRA_QUEUE_POSITION = "queue_position";

    private static final int NOTIFICATION_ID = 41;
    private static volatile boolean running;
    private static volatile int lastPosition;
    private static volatile boolean playbackRequested;
    private static volatile boolean playbackSessionAvailable;
    private static volatile String playbackVideoId;
    private static volatile String playbackUrl;
    private static ArrayList<String> activeQueueIds;
    private static ArrayList<String> activeQueueTitles;
    private static ArrayList<String> activeQueueChannels;
    private static ArrayList<String> activeQueueThumbnails;
    private static int activeQueuePosition;

    private MediaPlayer player;
    private String title;
    private String channel;
    private String videoId;
    private String thumbnailUrl;
    private Bitmap notificationThumbnail;
    private AsyncTask<String, Void, Bitmap> thumbnailTask;
    private ArrayList<QueueItem> playbackQueue = new ArrayList<QueueItem>();
    private int playbackQueuePosition;
    private QueueTrackTask queueTrackTask;
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

    public static synchronized void copyPlaybackQueueToIntent(Intent intent) {
        if (intent == null) return;
        if (activeQueueIds == null) return;
        intent.putStringArrayListExtra(EXTRA_QUEUE_IDS, new ArrayList<String>(activeQueueIds));
        intent.putStringArrayListExtra(EXTRA_QUEUE_TITLES, copyQueue(activeQueueTitles));
        intent.putStringArrayListExtra(EXTRA_QUEUE_CHANNELS, copyQueue(activeQueueChannels));
        intent.putStringArrayListExtra(EXTRA_QUEUE_THUMBNAILS, copyQueue(activeQueueThumbnails));
        intent.putExtra(EXTRA_QUEUE_POSITION, activeQueuePosition);
    }

    private static ArrayList<String> copyQueue(ArrayList<String> source) {
        return source == null ? new ArrayList<String>() : new ArrayList<String>(source);
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
        } else if (ACTION_NEXT.equals(action)) {
            playQueueTrack(playbackQueuePosition + 1);
        } else if (ACTION_PREVIOUS.equals(action)) {
            playPrevious();
        } else if (ACTION_UPDATE_QUEUE.equals(action)) {
            if (queueTrackTask != null) {
                queueTrackTask.cancel(true);
                queueTrackTask = null;
            }
            readPlaybackQueue(intent);
            updateNotification(playbackRequested);
        } else if (ACTION_STOP.equals(action)) {
            clearPlaybackSession();
            stopSelf();
        } else if (ACTION_START.equals(action)) {
            startPlayback(intent);
        }
        return START_NOT_STICKY;
    }

    private void startPlayback(Intent intent) {
        if (queueTrackTask != null) {
            queueTrackTask.cancel(true);
            queueTrackTask = null;
        }
        releasePlayer();
        cancelThumbnailLoad();
        releaseThumbnail();
        readPlaybackQueue(intent);
        String url = intent.getStringExtra(EXTRA_URL);
        title = intent.getStringExtra(EXTRA_TITLE);
        channel = intent.getStringExtra(EXTRA_CHANNEL);
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL);
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        playbackVideoId = videoId;
        playbackUrl = url;
        lastPosition = intent.getIntExtra(EXTRA_POSITION, 0);
        updateActivePlaybackQueue();
        if (url == null || url.length() == 0) {
            clearPlaybackSession();
            stopSelf();
            return;
        }
        playbackRequested = true;
        playbackSessionAvailable = true;

        startForeground(NOTIFICATION_ID, buildNotification(playbackRequested));
        if (thumbnailUrl != null && thumbnailUrl.length() > 0) {
            thumbnailTask = new NotificationThumbnailTask();
            thumbnailTask.execute(thumbnailUrl);
        }
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
                try {
                    lastPosition = mediaPlayer.getDuration();
                } catch (IllegalStateException ignored) {
                    lastPosition = 0;
                }
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

    private void readPlaybackQueue(Intent intent) {
        ArrayList<String> ids = intent.getStringArrayListExtra(EXTRA_QUEUE_IDS);
        if (ids == null) return;
        ArrayList<String> titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES);
        ArrayList<String> channels = intent.getStringArrayListExtra(EXTRA_QUEUE_CHANNELS);
        ArrayList<String> thumbnails = intent.getStringArrayListExtra(EXTRA_QUEUE_THUMBNAILS);
        playbackQueue.clear();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || id.length() == 0) continue;
            playbackQueue.add(new QueueItem(id,
                    queueValue(titles, i), queueValue(channels, i), queueValue(thumbnails, i)));
        }
        int requestedPosition = intent.getIntExtra(EXTRA_QUEUE_POSITION, 0);
        playbackQueuePosition = requestedPosition < 0 || requestedPosition >= playbackQueue.size()
                ? 0 : requestedPosition;
        updateActivePlaybackQueue();
    }

    private String queueValue(ArrayList<String> values, int position) {
        return values == null || position < 0 || position >= values.size() || values.get(position) == null
                ? "" : values.get(position);
    }

    private void updateActivePlaybackQueue() {
        synchronized (BackgroundPlaybackService.class) {
            activeQueueIds = new ArrayList<String>();
            activeQueueTitles = new ArrayList<String>();
            activeQueueChannels = new ArrayList<String>();
            activeQueueThumbnails = new ArrayList<String>();
            for (int i = 0; i < playbackQueue.size(); i++) {
                QueueItem item = playbackQueue.get(i);
                activeQueueIds.add(item.id);
                activeQueueTitles.add(item.title);
                activeQueueChannels.add(item.channel);
                activeQueueThumbnails.add(item.thumbnailUrl);
            }
            activeQueuePosition = playbackQueuePosition;
        }
    }

    private void playPrevious() {
        if (player != null && prepared) {
            try {
                if (player.getCurrentPosition() > 3000) {
                    player.seekTo(0);
                    lastPosition = 0;
                    updateNotification(playbackRequested);
                    return;
                }
            } catch (IllegalStateException ignored) {}
        }
        if (playbackQueuePosition > 0) {
            playQueueTrack(playbackQueuePosition - 1);
        } else if (player != null && prepared) {
            try {
                player.seekTo(0);
                lastPosition = 0;
                updateNotification(playbackRequested);
            } catch (IllegalStateException ignored) {}
        }
    }

    private void playQueueTrack(int position) {
        if (position < 0 || position >= playbackQueue.size()) return;
        if (queueTrackTask != null) queueTrackTask.cancel(true);
        queueTrackTask = new QueueTrackTask(position);
        queueTrackTask.execute();
    }

    private class QueueTrackTask extends AsyncTask<Void, Void, QueueTrackResult> {
        private final int targetPosition;
        private final QueueItem targetItem;

        QueueTrackTask(int position) {
            targetPosition = position;
            targetItem = position < 0 || position >= playbackQueue.size() ? null : playbackQueue.get(position);
        }

        @Override
        protected QueueTrackResult doInBackground(Void... ignored) {
            if (targetItem == null) return null;
            try {
                Config config = ConfigManager.getInstance().getConfig();
                String quality = config.getPreferredQuality();
                if (quality == null || quality.length() == 0) quality = "360";
                String url;
                if (config.isConvertVideos()) {
                    url = Manager.getInstance().getConvUrl(targetItem.id, quality,
                            Codec.fromIndex(config.getConvertCodec()), null);
                } else {
                    url = Manager.getInstance().getVideoUrl(targetItem.id, quality, HttpClient.TIMEOUT, null);
                }
                return new QueueTrackResult(targetPosition, targetItem, url);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(QueueTrackResult result) {
            if (queueTrackTask != this) return;
            queueTrackTask = null;
            if (result == null || result.url == null || result.url.length() == 0) return;
            playbackQueuePosition = result.position;
            Intent playbackIntent = new Intent(BackgroundPlaybackService.this, BackgroundPlaybackService.class);
            playbackIntent.setAction(ACTION_START);
            playbackIntent.putExtra(EXTRA_URL, result.url);
            playbackIntent.putExtra(EXTRA_VIDEO_ID, result.item.id);
            playbackIntent.putExtra(EXTRA_TITLE, result.item.title);
            playbackIntent.putExtra(EXTRA_CHANNEL, result.item.channel);
            playbackIntent.putExtra(EXTRA_THUMBNAIL_URL, result.item.thumbnailUrl);
            playbackIntent.putExtra(EXTRA_POSITION, 0);
            addQueueToIntent(playbackIntent);
            startService(playbackIntent);
        }
    }

    private void addQueueToIntent(Intent intent) {
        ArrayList<String> ids = new ArrayList<String>();
        ArrayList<String> titles = new ArrayList<String>();
        ArrayList<String> channels = new ArrayList<String>();
        ArrayList<String> thumbnails = new ArrayList<String>();
        for (int i = 0; i < playbackQueue.size(); i++) {
            QueueItem item = playbackQueue.get(i);
            ids.add(item.id);
            titles.add(item.title);
            channels.add(item.channel);
            thumbnails.add(item.thumbnailUrl);
        }
        intent.putStringArrayListExtra(EXTRA_QUEUE_IDS, ids);
        intent.putStringArrayListExtra(EXTRA_QUEUE_TITLES, titles);
        intent.putStringArrayListExtra(EXTRA_QUEUE_CHANNELS, channels);
        intent.putStringArrayListExtra(EXTRA_QUEUE_THUMBNAILS, thumbnails);
        intent.putExtra(EXTRA_QUEUE_POSITION, playbackQueuePosition);
    }

    private static class QueueItem {
        final String id;
        final String title;
        final String channel;
        final String thumbnailUrl;

        QueueItem(String id, String title, String channel, String thumbnailUrl) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    private static class QueueTrackResult {
        final int position;
        final QueueItem item;
        final String url;

        QueueTrackResult(int position, QueueItem item, String url) {
            this.position = position;
            this.item = item;
            this.url = url;
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
        openIntent.putExtra(EXTRA_OPEN_FROM_NOTIFICATION, true);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggleIntent = new Intent(this, BackgroundPlaybackService.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent togglePendingIntent = PendingIntent.getService(this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent previousIntent = new Intent(this, BackgroundPlaybackService.class);
        previousIntent.setAction(ACTION_PREVIOUS);
        PendingIntent previousPendingIntent = PendingIntent.getService(this, 2, previousIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent nextIntent = new Intent(this, BackgroundPlaybackService.class);
        nextIntent.setAction(ACTION_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        String playbackState = getString(playing ? R.string.background_playing : R.string.background_paused);
        String contentText = channel == null || channel.length() == 0 ? playbackState : channel;
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.youtube_notification_small)
                .setContentTitle(title == null || title.length() == 0 ? getString(R.string.app_name) : title)
                .setContentText(contentText)
                .setSubText(playbackState)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.youtube_notification_previous, getString(R.string.previous), previousPendingIntent)
                .addAction(playing ? R.drawable.youtube_notification_pause : R.drawable.youtube_notification_play,
                        playing ? getString(R.string.pause) : getString(R.string.resume), togglePendingIntent)
                .addAction(R.drawable.youtube_notification_next, getString(R.string.next), nextPendingIntent);
        if (notificationThumbnail != null && !notificationThumbnail.isRecycled()) {
            builder.setLargeIcon(notificationThumbnail);
        }
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.getNotification();
    }

    private class NotificationThumbnailTask extends AsyncTask<String, Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(String... params) {
            HttpURLConnection connection = null;
            InputStream input = null;
            try {
                connection = (HttpURLConnection) new URL(params[0]).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                input = connection.getInputStream();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeStream(input, null, options);
            } catch (Exception ignored) {
                return null;
            } finally {
                if (input != null) {
                    try { input.close(); } catch (IOException ignored) {}
                }
                if (connection != null) connection.disconnect();
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (thumbnailTask != this) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                return;
            }
            thumbnailTask = null;
            if (bitmap == null) return;
            if (!playbackSessionAvailable || !running) {
                bitmap.recycle();
                return;
            }
            notificationThumbnail = bitmap;
            updateNotification(playbackRequested);
        }

        @Override
        protected void onCancelled(Bitmap bitmap) {
            if (thumbnailTask == this) thumbnailTask = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void cancelThumbnailLoad() {
        if (thumbnailTask != null) {
            thumbnailTask.cancel(true);
            thumbnailTask = null;
        }
    }

    private void releaseThumbnail() {
        if (notificationThumbnail != null && !notificationThumbnail.isRecycled()) {
            notificationThumbnail.recycle();
        }
        notificationThumbnail = null;
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
        if (queueTrackTask != null) {
            queueTrackTask.cancel(true);
            queueTrackTask = null;
        }
        cancelThumbnailLoad();
        releasePlayer();
        releaseThumbnail();
        running = false;
        synchronized (BackgroundPlaybackService.class) {
            activeQueueIds = null;
            activeQueueTitles = null;
            activeQueueChannels = null;
            activeQueueThumbnails = null;
            activeQueuePosition = 0;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
