package com.frozzcolombia.domicilios

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration

/**
 * Servicio en primer plano: mantiene la conexión con la base de datos y avisa
 * con sonido cuando el despacho le ofrece un domicilio, aunque la app esté cerrada.
 */
class ServicioService : Service() {

    private var reg: ListenerRegistration? = null
    private var uidActual: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            detenerTodo()
            return START_NOT_STICKY
        }
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, ID_FIJA, notifFija("Conectado · esperando servicios"), tipo)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val uid = Repo.uid
        if (uid == null) {
            detenerTodo()
            return START_NOT_STICKY
        }
        if (uid != uidActual) {
            reg?.remove()
            uidActual = uid
            reg = Repo.escuchar(uid) { lista, e ->
                if (e != null) {
                    actualizarFija("Reconectando… (${Repo.mensajeError(e).take(60)})")
                } else if (lista != null) {
                    procesar(uid, lista)
                }
            }
        }
        return START_STICKY
    }

    private fun procesar(uid: String, lista: List<Servicio>) {
        val prefs = getSharedPreferences("avisos", Context.MODE_PRIVATE)
        val avisados = prefs.getStringSet("ofertas", emptySet())!!.toMutableSet()
        val asignados = prefs.getStringSet("tomados", emptySet())!!.toMutableSet()
        val nm = NotificationManagerCompat.from(this)
        var nuevas = 0
        for (s in lista) {
            val nid = s.id.hashCode()
            when {
                s.esOferta(uid) -> {
                    nuevas++
                    if (s.id !in avisados) {
                        avisados.add(s.id)
                        notificar(nid, notifOferta(s))
                    }
                }
                s.tomadoPorOtro(uid) && s.id in avisados -> {
                    nm.cancel(nid)
                    if (s.id !in asignados) {
                        asignados.add(s.id)
                        notificar(nid, notifAviso("Domicilio ${s.codigo}", "Otro domiciliario tomó este servicio."))
                    }
                    avisados.remove(s.id)
                }
                s.estado == "Cancelado" && s.id in avisados -> {
                    nm.cancel(nid)
                    notificar(nid, notifAviso("Domicilio ${s.codigo}", "El despacho canceló este servicio."))
                    avisados.remove(s.id)
                }
                s.domUid == uid && s.estado in Servicio.ESTADOS_ACTIVOS -> nm.cancel(nid)
            }
        }
        // Mantener las listas cortas
        val vivos = lista.map { it.id }.toSet()
        avisados.retainAll(vivos)
        asignados.retainAll(vivos)
        prefs.edit().putStringSet("ofertas", avisados).putStringSet("tomados", asignados).apply()
        val mios = lista.count { it.esMio(uid) }
        actualizarFija(
            when {
                nuevas > 0 -> "$nuevas servicio(s) nuevo(s) por aceptar"
                mios > 0 -> "$mios servicio(s) en curso"
                else -> "Conectado · esperando servicios"
            }
        )
    }

    private fun abrirApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notifFija(texto: String): Notification =
        NotificationCompat.Builder(this, App.CANAL_CONEXION)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Frozz Domiciliarios")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(abrirApp())
            .build()

    private fun actualizarFija(texto: String) = notificar(ID_FIJA, notifFija(texto))

    private fun notifOferta(s: Servicio): Notification {
        val texto = buildString {
            append(s.resumenRuta())
            append("\n📦 ").append(s.material.ifBlank { "—" })
            append("\n🛵 ").append(s.vehiculo).append(" · ").append(Servicio.km(s.km)).append(" · ").append(Servicio.cop(s.valor))
            if (s.esProgramado) append("\n📅 ").append(Servicio.fecha(s.programado))
        }
        return NotificationCompat.Builder(this, App.CANAL_OFERTAS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle((if (s.urgente) "⚠️ URGENTE · " else "") + "Nuevo domicilio ${s.codigo}")
            .setContentText(s.resumenRuta())
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(abrirApp())
            .build()
    }

    private fun notifAviso(titulo: String, texto: String): Notification =
        NotificationCompat.Builder(this, App.CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setContentIntent(abrirApp())
            .build()

    private fun notificar(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun detenerTodo() {
        reg?.remove()
        reg = null
        uidActual = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        reg?.remove()
        super.onDestroy()
    }

    companion object {
        private const val ID_FIJA = 1001
        private const val ACCION_DETENER = "detener"

        fun iniciar(ctx: Context) {
            if (Repo.uid == null) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioService::class.java))
            } catch (_: Exception) {
            }
        }

        fun detener(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ServicioService::class.java).setAction(ACCION_DETENER))
            } catch (_: Exception) {
            }
        }
    }
}
