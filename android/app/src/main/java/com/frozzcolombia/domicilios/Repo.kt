package com.frozzcolombia.domicilios

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

data class Perfil(val uid: String, val codigo: String, val nombre: String, val cel: String, val veh: String, val activo: Boolean)

class YaTomado(msg: String) : Exception(msg)

object Repo {
    private const val DIAS_HISTORIAL = 3L

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val uid: String? get() = auth.currentUser?.uid

    fun perfil(uid: String, cb: (Perfil?, Exception?) -> Unit) {
        db.collection("doms").document(uid).get()
            .addOnSuccessListener { d ->
                if (!d.exists()) cb(null, null) else cb(
                    Perfil(
                        uid = uid,
                        codigo = d.getString("codigo") ?: "",
                        nombre = d.getString("nombre") ?: "",
                        cel = d.getString("cel") ?: "",
                        veh = d.getString("veh") ?: "",
                        activo = d.getBoolean("activo") != false
                    ), null
                )
            }
            .addOnFailureListener { cb(null, it) }
    }

    fun marcarVisto(version: String) {
        val u = uid ?: return
        db.collection("doms").document(u)
            .set(mapOf("visto" to System.currentTimeMillis(), "app" to version), SetOptions.merge())
    }

    /** Servicios donde este domiciliario es candidato (ofertas y los que tomó), de los últimos días. */
    fun escuchar(uid: String, cb: (List<Servicio>?, Exception?) -> Unit): ListenerRegistration {
        val desde = System.currentTimeMillis() - DIAS_HISTORIAL * 24 * 3600 * 1000
        return db.collection("servicios")
            .whereArrayContains("candUids", uid)
            .whereGreaterThanOrEqualTo("creado", desde)
            .orderBy("creado", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { q, e ->
                if (e != null) cb(null, e)
                else cb(q?.documents?.mapNotNull { Servicio.desde(it) } ?: emptyList(), null)
            }
    }

    private fun entrada(texto: String) = mapOf("ts" to System.currentTimeMillis(), "a" to texto, "u" to (uid ?: ""))

    fun aceptar(id: String, p: Perfil): Task<Void> {
        val ref = db.collection("servicios").document(id)
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val estado = snap.getString("estado")
            val duenio = snap.getString("domUid")
            if (estado != "Ofertado") {
                throw YaTomado(if (duenio == p.uid) "Este servicio ya es suyo." else "Otro domiciliario ya tomó este servicio.")
            }
            tx.update(
                ref, mapOf(
                    "estado" to "Aceptado",
                    "domUid" to p.uid,
                    "dom" to mapOf("codigo" to p.codigo, "nombre" to p.nombre, "cel" to p.cel),
                    "t.aceptado" to System.currentTimeMillis(),
                    "historial" to FieldValue.arrayUnion(entrada("Aceptado por ${p.nombre} desde la app")),
                    "upd" to FieldValue.serverTimestamp()
                )
            )
            null
        }
    }

    fun rechazar(id: String, p: Perfil): Task<Void> =
        db.collection("servicios").document(id).update(
            mapOf(
                "rechazos" to FieldValue.arrayUnion(p.uid),
                "upd" to FieldValue.serverTimestamp()
            )
        )

    fun iniciar(id: String, p: Perfil): Task<Void> =
        db.collection("servicios").document(id).update(
            mapOf(
                "estado" to "En curso",
                "t.inicio" to System.currentTimeMillis(),
                "historial" to FieldValue.arrayUnion(entrada("Recorrido iniciado por ${p.nombre} desde la app")),
                "upd" to FieldValue.serverTimestamp()
            )
        )

    fun finalizar(id: String, p: Perfil, esperaMin: Int, nota: String): Task<Void> =
        db.collection("servicios").document(id).update(
            mapOf(
                "estado" to "Finalizado",
                "t.fin" to System.currentTimeMillis(),
                "esperaDom" to esperaMin,
                "notaDom" to nota,
                "historial" to FieldValue.arrayUnion(
                    entrada("Finalizado por ${p.nombre} desde la app" + (if (esperaMin > 0) " · $esperaMin min de espera" else "") + (if (nota.isNotBlank()) " · $nota" else ""))
                ),
                "upd" to FieldValue.serverTimestamp()
            )
        )

    fun mensajeError(e: Exception?): String = when (e) {
        null -> "Error desconocido"
        is YaTomado -> e.message ?: "Ya no está disponible"
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "El despacho no le permite esta acción (puede que el servicio ya no esté disponible)."
            FirebaseFirestoreException.Code.UNAVAILABLE -> "Sin conexión. Revise los datos móviles e intente de nuevo."
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "La base de datos aún se está preparando. Intente en unos minutos."
            else -> e.message ?: e.code.name
        }
        else -> e.message ?: "Error"
    }
}
