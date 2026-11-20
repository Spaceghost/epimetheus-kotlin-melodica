package tk.hacker1024.epimetheus.melodica

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal object Wire {
    init {
        System.loadLibrary("melodicawire")
    }

    external fun transform(data: ByteArray, key: ByteArray, decrypt: Boolean): Boolean
}

internal data class Station(val id: String, val name: String)

internal data class Song(
    val title: String,
    val artist: String,
    val album: String,
    val url: String,
    val insert: Boolean,
    val tracking: JSONArray? = null,
    val station: String = "",
)

internal class MelodicaRadio {
    private val base = "https://spacegho.st/melodica/api/"
    private var partner = ""
    private var user = ""
    private var auth = ""
    private var access = ""
    private var historyToken = ""
    private var offset = 0L

    private fun call(method: String, body: JSONObject): JSONObject {
        val address = Uri.parse(base).buildUpon().appendQueryParameter("method", method)
        if (partner.isNotEmpty()) address.appendQueryParameter("partner_id", partner)
        if (user.isNotEmpty()) address.appendQueryParameter("user_id", user)
        if (auth.isNotEmpty()) {
            address.appendQueryParameter("auth_token", auth)
            body.put(if (user.isEmpty()) "partnerAuthToken" else "userAuthToken", auth)
            body.put("syncTime", System.currentTimeMillis() / 1000 + offset)
        }
        var bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (method != "auth.partnerLogin") {
            bytes = bytes.copyOf((bytes.size + 7) / 8 * 8)
            check(Wire.transform(bytes, "6#26FRL\$ZWD".toByteArray(), false))
            bytes = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.toByteArray()
        }
        val conn = (URL(address.toString()).openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain")
            if (access.isNotEmpty()) conn.setRequestProperty("x-melodica-access", access)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299)
                throw IllegalStateException("Melodica returned HTTP " + conn.responseCode)
            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            check(response.optString("stat") == "ok") { "Melodica declined this request" }
            if (method == "auth.userLogin") {
                access = conn.getHeaderField("x-melodica-access").orEmpty()
                historyToken = conn.getHeaderField("x-melodica-scrobble").orEmpty()
            }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    fun signIn(email: String, password: String): List<Station> {
        val p =
            call(
                "auth.partnerLogin",
                JSONObject()
                    .put("username", "android")
                    .put("password", "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7")
                    .put("deviceModel", "android-generic")
                    .put("version", "5"),
            )
        val clock = p.getString("syncTime").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        check(Wire.transform(clock, "R=U!LH\$O2B#".toByteArray(), true))
        offset =
            String(clock.copyOfRange(4, clock.size), Charsets.US_ASCII)
                .takeWhile { it.isDigit() }
                .toLong() - System.currentTimeMillis() / 1000
        partner = p.getString("partnerId")
        auth = p.getString("partnerAuthToken")
        val u =
            call(
                "auth.userLogin",
                JSONObject()
                    .put("loginType", "user")
                    .put("username", email)
                    .put("password", password),
            )
        user = u.getString("userId")
        auth = u.getString("userAuthToken")
        val stations =
            call("user.getStationList", JSONObject().put("returnAllStations", true))
                .getJSONArray("stations")
        return (0 until stations.length()).map {
            val s = stations.getJSONObject(it)
            Station(s.getString("stationToken"), s.getString("stationName"))
        }
    }

    fun mix(station: String): List<Song> {
        val items =
            call(
                    "station.getPlaylist",
                    JSONObject()
                        .put("stationToken", station)
                        .put("additionalAudioUrl", "HTTP_128_MP3"),
                )
                .getJSONArray("items")
        return (0 until items.length()).mapNotNull { i ->
            var s = items.getJSONObject(i)
            val insert = s.has("adToken")
            if (insert)
                s =
                    call(
                        "ad.getAdMetadata",
                        JSONObject()
                            .put("adToken", s.getString("adToken"))
                            .put("returnAdTrackingTokens", true),
                    )
            val urls = s.optJSONObject("audioUrlMap")
            val extra = s.opt("additionalAudioUrl")
            val audio =
                listOf("highQuality", "mediumQuality", "lowQuality").firstNotNullOfOrNull { q ->
                    urls?.optJSONObject(q)?.optString("audioUrl")?.takeIf { it.isNotEmpty() }
                } ?: (if (extra is JSONArray) extra.optString(0) else extra as? String)
            val secure =
                audio?.replaceFirst(Regex("^http://"), "https://") ?: return@mapNotNull null
            if (Uri.parse(secure).scheme != "https") return@mapNotNull null
            Song(
                if (insert) "Advertisement" else s.optString("songName"),
                s.optString("artistName"),
                s.optString("albumName"),
                secure,
                insert,
                s.optJSONArray("adTrackingTokens"),
                station,
            )
        }
    }

    fun insertPlayed(song: Song) {
        song.tracking?.let {
            call(
                "ad.registerAd",
                JSONObject().put("stationId", song.station).put("adTrackingTokens", it),
            )
        }
    }

    fun submit(song: Song, duration: Int, started: Long?) {
        if (historyToken.isEmpty() || song.insert || song.artist.isBlank() || song.title.isBlank())
            return
        val info =
            JSONObject()
                .put("submission_client", "Epimetheus Kotlin Melodica")
                .put("music_service", "pandora.com")
                .put("duration_ms", duration)
        val meta =
            JSONObject()
                .put("artist_name", song.artist)
                .put("track_name", song.title)
                .put("release_name", song.album)
                .put("additional_info", info)
        val listen = JSONObject().put("track_metadata", meta)
        if (started != null) listen.put("listened_at", started)
        val body =
            JSONObject()
                .put("listen_type", if (started == null) "playing_now" else "single")
                .put("payload", JSONArray().put(listen))
        val conn =
            URL("https://spacegho.st/scrobble/1/submit-listens").openConnection()
                as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Token " + historyToken)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
