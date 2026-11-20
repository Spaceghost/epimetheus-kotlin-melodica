package tk.hacker1024.epimetheus.melodica

import android.app.Activity
import android.content.*
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*

class MelodicaActivity : Activity() {
    private var service: MelodicaService? = null
    private lateinit var message: TextView
    private lateinit var stations: ListView
    private lateinit var pause: Button
    private lateinit var signOut: Button
    private lateinit var login: LinearLayout
    private lateinit var history: CheckBox
    private var visible = listOf<Station>()
    private var bound = false
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                service = (b as MelodicaService.LocalBinder).service
                service?.update = { draw() }
                draw()
            }

            override fun onServiceDisconnected(n: ComponentName) {
                service = null
                draw()
            }
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val dp = resources.displayMetrics.density
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((18 * dp).toInt(), (30 * dp).toInt(), (18 * dp).toInt(), 0)
            }
        setContentView(root)
        root.addView(
            TextView(this).apply {
                text = "Epimetheus for Melodica"
                textSize = 24f
            }
        )
        message = TextView(this).apply { textSize = 16f }
        root.addView(message)
        login = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(login)
        val email =
            EditText(this).apply {
                hint = "Pandora email"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
        val password =
            EditText(this).apply {
                hint = "Pandora password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        login.addView(email)
        login.addView(password)
        login.addView(
            button("Sign in") {
                if (email.text.isNotBlank() && password.text.isNotBlank()) {
                    service?.signIn(email.text.toString().trim(), password.text.toString())
                    password.text.clear()
                }
            }
        )
        history =
            CheckBox(this).apply {
                text = "Save listens to Spaceghost Scrobbles"
                setOnCheckedChangeListener { _, v -> service?.wantsHistory = v }
            }
        root.addView(history)
        val controls = LinearLayout(this)
        signOut = button("Sign out") { service?.signOut() }
        pause =
            button("Play / pause") { service?.let { if (it.playing) it.pause() else it.resume() } }
        controls.addView(pause)
        controls.addView(button("Next") { service?.next() })
        controls.addView(signOut)
        root.addView(controls)
        stations = ListView(this)
        root.addView(stations, LinearLayout.LayoutParams(-1, 0, 1f))
        stations.setOnItemClickListener { _, _, pos, _ ->
            visible.getOrNull(pos)?.let { service?.choose(it) }
        }
    }

    private fun draw() {
        val s = service
        message.text = s?.status ?: "Connecting…"
        history.isChecked = s?.wantsHistory ?: false
        pause.text = if (s?.playing == true) "Pause" else "Play"
        val rows = s?.stations.orEmpty()
        login.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        signOut.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (visible != rows) {
            visible = rows
            stations.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { it.name })
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, MelodicaService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.update = null
        service = null
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }
}
