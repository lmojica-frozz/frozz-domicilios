package com.frozzcolombia.domicilios

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : AppCompatActivity() {

    private var perfil: Perfil? = null
    private var reg: ListenerRegistration? = null
    private val adapter = ServAdapter()
    private lateinit var vacio: TextView
    private lateinit var estadoTxt: TextView
    private lateinit var bannerBateria: View

    private val pedirNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "Sin permiso de notificaciones no le sonarán los servicios nuevos.", Toast.LENGTH_LONG).show()
        ServicioService.iniciar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        vacio = findViewById(R.id.vacio)
        estadoTxt = findViewById(R.id.estado)
        bannerBateria = findViewById(R.id.bannerBateria)
        findViewById<Button>(R.id.btnBateria).setOnClickListener { pedirBateria() }
        val rv = findViewById<RecyclerView>(R.id.lista)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val uid = Repo.uid
        if (uid == null) { irLogin(); return }
        Repo.perfil(uid) { p, e ->
            if (p == null || !p.activo) {
                if (e == null) { Repo.auth.signOut(); irLogin() }
                else estadoTxt.text = "Sin conexión: ${Repo.mensajeError(e)}"
                return@perfil
            }
            perfil = p
            supportActionBar?.title = p.nombre.split(" ").take(2).joinToString(" ").ifBlank { "Frozz" }
            supportActionBar?.subtitle = "${p.codigo} · ${p.veh}"
            Repo.marcarVisto(BuildConfig.VERSION_NAME)
            escuchar(uid)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) pedirNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ServicioService.iniciar(this)
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        bannerBateria.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun pedirBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun escuchar(uid: String) {
        reg?.remove()
        estadoTxt.text = "Conectando…"
        reg = Repo.escuchar(uid) { lista, e ->
            if (e != null) {
                estadoTxt.text = "Sin conexión con el despacho: ${Repo.mensajeError(e)}"
                return@escuchar
            }
            val l = lista ?: emptyList()
            val ofertas = l.filter { it.esOferta(uid) }
            val mios = l.filter { it.esMio(uid) }.sortedBy { it.programado ?: it.creado }
            val hechos = l.filter { it.domUid == uid && it.estado == "Finalizado" }.take(10)
            val items = mutableListOf<Item>()
            if (ofertas.isNotEmpty()) { items += Item.Titulo("Servicios nuevos (${ofertas.size})"); ofertas.forEach { items += Item.Serv(it) } }
            if (mios.isNotEmpty()) { items += Item.Titulo("Mis servicios"); mios.forEach { items += Item.Serv(it) } }
            if (hechos.isNotEmpty()) { items += Item.Titulo("Finalizados recientes"); hechos.forEach { items += Item.Serv(it) } }
            adapter.set(items, uid)
            vacio.visibility = if (ofertas.isEmpty() && mios.isEmpty()) View.VISIBLE else View.GONE
            estadoTxt.text = "Conectado · le avisaremos cuando llegue un servicio"
        }
    }

    override fun onDestroy() {
        reg?.remove()
        super.onDestroy()
    }

    private fun irLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Cambiar PIN")
        menu.add(0, 2, 1, "Cerrar sesión")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> cambiarPin()
            2 -> AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("Si cierra sesión dejará de recibir servicios en este celular.")
                .setPositiveButton("Cerrar sesión") { _, _ ->
                    ServicioService.detener(this)
                    Repo.auth.signOut()
                    irLogin()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun campo(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun cambiarPin() {
        val user = Repo.auth.currentUser ?: return
        val actual = campo("PIN actual")
        val nuevo = campo("PIN nuevo (6 dígitos o más)")
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(actual); addView(nuevo)
        }
        AlertDialog.Builder(this)
            .setTitle("Cambiar PIN")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val a = actual.text.toString().trim()
                val n = nuevo.text.toString().trim()
                if (n.length < 6) { toast("El PIN nuevo debe tener al menos 6 dígitos."); return@setPositiveButton }
                val email = user.email ?: return@setPositiveButton
                user.reauthenticate(EmailAuthProvider.getCredential(email, a))
                    .continueWithTask { t -> if (t.isSuccessful) user.updatePassword(n) else throw t.exception!! }
                    .addOnSuccessListener { toast("PIN actualizado.") }
                    .addOnFailureListener { toast("No se pudo cambiar: revise el PIN actual.") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- Acciones ----------

    private fun accion(s: Servicio, que: String) {
        val p = perfil ?: return
        when (que) {
            "aceptar" -> Repo.aceptar(s.id, p)
                .addOnSuccessListener { toast("¡Servicio ${s.codigo} es suyo! Toque «Iniciar» cuando salga.") }
                .addOnFailureListener { e -> toast(Repo.mensajeError(e)) }
            "rechazar" -> AlertDialog.Builder(this)
                .setMessage("¿No puede tomar el servicio ${s.codigo}?")
                .setPositiveButton("No puedo") { _, _ ->
                    Repo.rechazar(s.id, p).addOnFailureListener { e -> toast(Repo.mensajeError(e)) }
                }
                .setNegativeButton("Volver", null).show()
            "iniciar" -> Repo.iniciar(s.id, p)
                .addOnSuccessListener { toast("Recorrido iniciado. ¡Buen viaje!") }
                .addOnFailureListener { e -> toast(Repo.mensajeError(e)) }
            "finalizar" -> finalizar(s, p)
            "ruta" -> abrir(s.urlRuta())
            "llamar" -> s.paradas.firstOrNull { it.tel.isNotBlank() }?.let {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it.tel.filter { c -> c.isDigit() || c == '+' })))
            }
        }
    }

    private fun finalizar(s: Servicio, p: Perfil) {
        val desde = maxOf(s.inicio ?: 0L, s.aceptado ?: 0L)
        if (System.currentTimeMillis() - desde < 2 * 60 * 1000) {
            toast("Espere a terminar la entrega para finalizar.")
            return
        }
        val esp = EditText(this).apply { hint = "Minutos de espera (si hubo)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val nota = EditText(this).apply { hint = "Nota para el despacho (opcional)" }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(esp); addView(nota)
        }
        AlertDialog.Builder(this)
            .setTitle("Finalizar ${s.codigo}")
            .setMessage("Confirme que entregó todo.")
            .setView(caja)
            .setPositiveButton("Finalizar") { _, _ ->
                val m = esp.text.toString().trim().toIntOrNull()?.coerceIn(0, 600) ?: 0
                Repo.finalizar(s.id, p, m, nota.text.toString().trim())
                    .addOnSuccessListener { toast("Servicio finalizado. ¡Gracias!") }
                    .addOnFailureListener { e -> toast(Repo.mensajeError(e)) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrir(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("No se pudo abrir el mapa.") }
    }

    // ---------- Lista ----------

    sealed class Item {
        data class Titulo(val texto: String) : Item()
        data class Serv(val s: Servicio) : Item()
    }

    inner class ServAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()
        private var uid: String = ""

        @SuppressLint("NotifyDataSetChanged")
        fun set(l: List<Item>, u: String) { items = l; uid = u; notifyDataSetChanged() }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Titulo) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_titulo, parent, false)) {}
            else object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_servicio, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is Item.Titulo -> (h.itemView as TextView).text = it.texto
                is Item.Serv -> pintar(h.itemView, it.s)
            }
        }

        private fun pintar(v: View, s: Servicio) {
            v.findViewById<TextView>(R.id.codigo).text = (if (s.urgente) "⚠️ " else "") + "Domicilio ${s.codigo}"
            val oferta = s.esOferta(uid)
            v.findViewById<TextView>(R.id.chip).text = when {
                oferta -> "NUEVO"
                s.estado == "Aceptado" -> "ACEPTADO"
                s.estado == "En curso" -> "EN CURSO"
                else -> s.estado.uppercase()
            }
            v.findViewById<TextView>(R.id.paradas).text = s.paradas.mapIndexed { i, p ->
                val letra = ('A' + i)
                val icono = if (p.recoger) "🟢 Recoger" else "🔴 Entregar"
                val lugar = listOf(p.dir.takeIf { it.isNotBlank() && it != p.nombre }, p.mun.takeIf { it.isNotBlank() && !it.equals("Barranquilla", true) })
                    .filterNotNull().joinToString(", ")
                "$letra · $icono: ${p.nombre}" + (if (lugar.isNotBlank()) "\n      $lugar" else "") + (if (p.tel.isNotBlank()) "\n      tel. ${p.tel}" else "")
            }.joinToString("\n") + (if (s.idaVuelta) "\n↩️ Regresa al punto A" else "")
            v.findViewById<TextView>(R.id.detalle).text = buildString {
                append("📦 ").append(s.material.ifBlank { "—" })
                append("\n🛵 ").append(s.vehiculo).append(" · ").append(Servicio.km(s.km))
                if (s.esProgramado) append("\n📅 Programado: ").append(Servicio.fecha(s.programado))
                if (s.obs.isNotBlank() && !s.obs.startsWith("Finalizado")) append("\n📝 ").append(s.obs)
            }
            v.findViewById<TextView>(R.id.valor).text = Servicio.cop(s.valor) + if (s.estado == "Finalizado") "" else " aprox."

            val b1 = v.findViewById<Button>(R.id.btn1)
            val b2 = v.findViewById<Button>(R.id.btn2)
            val b3 = v.findViewById<Button>(R.id.btn3)
            fun conf(b: Button, texto: String?, que: String) {
                if (texto == null) { b.visibility = View.GONE; return }
                b.visibility = View.VISIBLE; b.text = texto; b.setOnClickListener { accion(s, que) }
            }
            val tel = s.paradas.any { it.tel.isNotBlank() }
            when {
                oferta -> { conf(b1, "Aceptar", "aceptar"); conf(b2, "Ver ruta", "ruta"); conf(b3, "No puedo", "rechazar") }
                s.estado == "Aceptado" && s.domUid == uid -> { conf(b1, "Iniciar recorrido", "iniciar"); conf(b2, "Ver ruta", "ruta"); conf(b3, if (tel) "Llamar" else null, "llamar") }
                s.estado == "En curso" && s.domUid == uid -> { conf(b1, "Finalizar", "finalizar"); conf(b2, "Ver ruta", "ruta"); conf(b3, if (tel) "Llamar" else null, "llamar") }
                else -> { conf(b1, null, ""); conf(b2, null, ""); conf(b3, null, "") }
            }
        }
    }
}
