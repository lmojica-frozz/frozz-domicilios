package com.frozzcolombia.domicilios

import com.google.firebase.firestore.DocumentSnapshot
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Parada(
    val accion: String,
    val nombre: String,
    val dir: String,
    val mun: String,
    val tel: String,
    val lat: Double?,
    val lng: Double?
) {
    val recoger get() = accion.equals("Recoger", ignoreCase = true)
    fun destinoMaps(): String =
        if (lat != null && lng != null) "$lat,$lng"
        else listOf(dir.ifBlank { nombre }, mun.ifBlank { "Barranquilla" }, "Colombia").joinToString(", ")
}

data class Servicio(
    val id: String,
    val codigo: String,
    val estado: String,
    val paradas: List<Parada>,
    val material: String,
    val vehiculo: String,
    val km: Double?,
    val valor: Long?,
    val urgente: Boolean,
    val programado: Long?,
    val esProgramado: Boolean,
    val idaVuelta: Boolean,
    val domUid: String?,
    val candUids: List<String>,
    val rechazos: List<String>,
    val creado: Long,
    val aceptado: Long?,
    val inicio: Long?,
    val obs: String
) {
    fun esOferta(uid: String) = estado == "Ofertado" && uid in candUids && uid !in rechazos
    fun esMio(uid: String) = domUid == uid && estado in ESTADOS_ACTIVOS
    fun tomadoPorOtro(uid: String) = domUid != null && domUid != uid

    fun urlRuta(): String {
        val ps = if (idaVuelta && paradas.isNotEmpty()) paradas + paradas.first() else paradas
        if (ps.isEmpty()) return "https://www.google.com/maps"
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val destino = ps.last().destinoMaps()
        val origen = ps.first().destinoMaps()
        val medio = ps.drop(1).dropLast(1).joinToString("|") { it.destinoMaps() }
        var url = "https://www.google.com/maps/dir/?api=1&origin=${enc(origen)}&destination=${enc(destino)}&travelmode=driving"
        if (medio.isNotEmpty()) url += "&waypoints=${enc(medio)}"
        return url
    }

    fun resumenRuta(): String = paradas.joinToString(" → ") { it.nombre.take(28) }

    companion object {
        val ESTADOS_ACTIVOS = setOf("Aceptado", "En curso")

        private fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        private fun str(v: Any?): String = v?.toString() ?: ""

        @Suppress("UNCHECKED_CAST")
        fun desde(d: DocumentSnapshot): Servicio? {
            val m = d.data ?: return null
            val ps = (m["paradas"] as? List<Map<String, Any?>>)
                ?: listOfNotNull(m["origen"] as? Map<String, Any?>, m["destino"] as? Map<String, Any?>)
            val paradas = ps.map {
                Parada(
                    accion = str(it["acc"]).ifBlank { "Entregar" },
                    nombre = str(it["nombre"]),
                    dir = str(it["dir"]),
                    mun = str(it["mun"]),
                    tel = str(it["tel"]),
                    lat = num(it["lat"]),
                    lng = num(it["lng"])
                )
            }
            val t = m["t"] as? Map<String, Any?> ?: emptyMap()
            return Servicio(
                id = d.id,
                codigo = str(m["codigo"]).ifBlank { d.id },
                estado = str(m["estado"]),
                paradas = paradas,
                material = str(m["material"]),
                vehiculo = str(m["vehiculo"]),
                km = num(m["kmReal"]) ?: num(m["kmEst"]),
                valor = (num(m["valorFinal"]) ?: num(m["valorEst"]))?.toLong(),
                urgente = str(m["prioridad"]) == "Urgente",
                programado = num(m["programado"])?.toLong(),
                esProgramado = m["prog"] == true,
                idaVuelta = m["idaVuelta"] == true,
                domUid = (m["domUid"] as? String)?.takeIf { it.isNotBlank() },
                candUids = (m["candUids"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                rechazos = (m["rechazos"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                creado = num(m["creado"])?.toLong() ?: 0L,
                aceptado = num(t["aceptado"])?.toLong(),
                inicio = num(t["inicio"])?.toLong(),
                obs = str(m["obs"])
            )
        }

        private val pesos: NumberFormat = NumberFormat.getCurrencyInstance(Locale("es", "CO")).apply {
            maximumFractionDigits = 0
        }
        fun cop(v: Long?): String = if (v == null) "—" else pesos.format(v).replace(" ", " ")
        fun km(v: Double?): String = if (v == null) "—" else String.format(Locale("es", "CO"), "%.1f km", v)
        fun fecha(ms: Long?): String =
            if (ms == null) "" else SimpleDateFormat("EEE d MMM, h:mm a", Locale("es", "CO")).format(Date(ms))
    }
}
