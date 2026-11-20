package tk.hacker1024.epimetheus.melodica

import android.app.*
import android.content.Intent
import android.media.*
import android.media.session.*
import android.os.*
import java.util.concurrent.Executors

class MelodicaService : Service() {
    inner class LocalBinder : Binder() {
        val service
            get() = this@MelodicaService
    }

    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val history = Executors.newSingleThreadExecutor()
    private lateinit var session: MediaSession
    private var engine = MelodicaRadio()
    private var player: MediaPlayer? = null
    internal var stations = listOf<Station>()
        private set

    internal var status = "Sign in to your Melodica stations."
        private set

    internal var update: (() -> Unit)? = null
    private val queue = java.util.ArrayDeque<Song>()
    private var station: Station? = null
    private var current: Song? = null
    private var generation = 0
    private var active = false
    private var started = 0L
    private var heard = 0L
    private var lastPosition = 0
    private var lastTick = 0L
    private var nowSent = false
    private var finalSent = false
    private var requestedFocus = false
    private lateinit var focus: AudioFocusRequest
    internal val playing
        get() = player?.let { runCatching { it.isPlaying }.getOrDefault(false) } == true

    internal var wantsHistory: Boolean
        get() = getSharedPreferences("melodica", 0).getBoolean("history", false)
        set(value) {
            getSharedPreferences("melodica", 0).edit().putBoolean("history", value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("radio", "Station playback", NotificationManager.IMPORTANCE_LOW)
        )
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change -> if (change < 0 && playing) pause() }
                .build()
        session = MediaSession(this, "Epimetheus Melodica")
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    next()
                }

                override fun onStop() {
                    stopRadio()
                }
            }
        )
    }

    override fun onBind(intent: Intent) = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> stopRadio()
            "toggle" -> if (playing) pause() else resume()
            "next" -> next()
            else -> if (active) showNotification()
        }
        return START_NOT_STICKY
    }

    private fun notice(text: String) {
        status = text
        update?.invoke()
    }

    internal fun signIn(email: String, password: String) {
        stopRadio()
        stations = emptyList()
        val client = MelodicaRadio()
        engine = client
        val turn = generation
        notice("Loading stations…")
        network.execute {
            val result = runCatching { client.signIn(email, password) }
            handler.post {
                if (turn == generation)
                    result.fold(
                        {
                            stations = it
                            notice("Choose a station.")
                        },
                        { notice("Could not sign in. Check your account and connection.") },
                    )
            }
        }
    }

    internal fun signOut() {
        stopRadio()
        stations = emptyList()
        engine = MelodicaRadio()
        notice("Sign in to your Melodica stations.")
    }

    internal fun choose(value: Station) {
        generation++
        releasePlayer()
        queue.clear()
        station = value
        active = true
        startForegroundService(Intent(this, MelodicaService::class.java))
        session.isActive = true
        notice("Tuning " + value.name + "…")
        showNotification()
        next()
    }

    internal fun next() {
        if (!active) return
        generation++
        val turn = generation
        releasePlayer()
        if (queue.isNotEmpty()) {
            openSong(queue.removeFirst())
            return
        }
        val id = station?.id ?: return
        val radio = engine
        notice("Loading the next track…")
        network.execute {
            val result = runCatching { radio.mix(id) }
            handler.post {
                if (turn == generation) {
                    val tracks = result.getOrNull().orEmpty()
                    if (tracks.isEmpty()) {
                        stopRadio()
                        notice("Could not play this station. Choose it to retry.")
                    } else {
                        queue.addAll(tracks)
                        openSong(queue.removeFirst())
                    }
                }
            }
        }
    }

    private fun openSong(song: Song) {
        current = song
        val p = MediaPlayer()
        player = p
        heard = 0
        lastPosition = 0
        lastTick = SystemClock.elapsedRealtime()
        started = 0
        nowSent = false
        finalSent = false
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnPreparedListener {
            if (p === player) {
                notice(if (song.artist.isEmpty()) song.title else song.artist + " — " + song.title)
                resume()
            }
        }
        p.setOnCompletionListener {
            if (p === player) {
                sample()
                next()
            }
        }
        p.setOnErrorListener { _, _, _ ->
            if (p === player) {
                stopRadio()
                notice("Audio could not start. Choose a station to retry.")
            }
            true
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .build()
        )
        try {
            p.setDataSource(song.url)
            p.prepareAsync()
        } catch (e: Exception) {
            stopRadio()
            notice("Could not open the audio stream.")
        }
    }

    private val pulse =
        object : Runnable {
            override fun run() {
                sample()
                if (playing) handler.postDelayed(this, 1000)
            }
        }

    private fun sample() {
        val p = player ?: return
        val song = current ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        val duration = runCatching { p.duration }.getOrDefault(0)
        val tick = SystemClock.elapsedRealtime()
        val delta = pos - lastPosition
        val elapsed = tick - lastTick
        lastPosition = pos
        lastTick = tick
        if (delta <= 0 || elapsed < 0 || elapsed > 8000 || delta > elapsed + 500) return
        heard += minOf(delta.toLong(), elapsed)
        if (started == 0L) started = System.currentTimeMillis() / 1000
        val radio = engine
        if (song.insert) {
            if (!nowSent) {
                nowSent = true
                network.execute { runCatching { radio.insertPlayed(song) } }
            }
            return
        }
        if (!wantsHistory) return
        if (!nowSent) {
            nowSent = true
            history.execute { runCatching { radio.submit(song, duration, null) } }
        }
        if (!finalSent && duration > 30000 && heard >= minOf(duration / 2, 240000)) {
            finalSent = true
            val timestamp = started
            history.execute { runCatching { radio.submit(song, duration, timestamp) } }
        }
    }

    internal fun resume() {
        val p = player ?: return
        val mgr = getSystemService(AudioManager::class.java)
        requestedFocus = mgr.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!requestedFocus) return
        runCatching {
                p.start()
                lastPosition = p.currentPosition
                lastTick = SystemClock.elapsedRealtime()
            }
            .onFailure {
                return
            }
        handler.removeCallbacks(pulse)
        handler.postDelayed(pulse, 1000)
        changed()
    }

    internal fun pause() {
        if (!playing) return
        sample()
        player?.pause()
        handler.removeCallbacks(pulse)
        changed()
    }

    private fun changed() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, player?.currentPosition?.toLong() ?: 0, if (playing) 1f else 0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_STOP
                )
                .build()
        )
        showNotification()
        update?.invoke()
    }

    private fun showNotification() {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MelodicaActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        fun action(code: Int, name: String) =
            PendingIntent.getService(
                this,
                code,
                Intent(this, MelodicaService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE,
            )
        session.setSessionActivity(open)
        startForeground(
            5,
            Notification.Builder(this, "radio")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(current?.title ?: station?.name ?: "Melodica")
                .setContentText(current?.artist ?: status)
                .setContentIntent(open)
                .addAction(
                    Notification.Action.Builder(
                            if (playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play,
                            if (playing) "Pause" else "Play",
                            action(1, "toggle"),
                        )
                        .build()
                )
                .addAction(
                    Notification.Action.Builder(
                            android.R.drawable.ic_media_next,
                            "Next",
                            action(2, "next"),
                        )
                        .build()
                )
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1)
                )
                .build(),
        )
    }

    private fun releasePlayer() {
        handler.removeCallbacks(pulse)
        player?.release()
        player = null
        current = null
    }

    internal fun stopRadio() {
        generation++
        active = false
        queue.clear()
        releasePlayer()
        station = null
        session.isActive = false
        if (requestedFocus)
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)
        requestedFocus = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRadio()
        session.release()
        network.shutdownNow()
        history.shutdownNow()
        super.onDestroy()
    }
}
