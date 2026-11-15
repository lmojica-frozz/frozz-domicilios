package com.frozzcolombia.domicilios

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(Config.API_KEY)
                .setApplicationId(Config.ANDROID_APP_ID)
                .setProjectId(Config.PROJECT_ID)
                .setStorageBucket(Config.STORAGE_BUCKET)
                .setGcmSenderId(Config.SENDER_ID)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        crearCanales(this)
    }

    companion object {
        const val CANAL_OFERTAS = "ofertas_v1"
        const val CANAL_CONEXION = "conexion_v1"
        const val CANAL_AVISOS = "avisos_v1"

        fun crearCanales(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val ofertas = NotificationChannel(CANAL_OFERTAS, "Servicios nuevos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa cuando le ofrecen un domicilio"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
                setSound(sonido, attrs)
            }
            val avisos = NotificationChannel(CANAL_AVISOS, "Avisos del despacho", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cambios en sus servicios"
            }
            val conexion = NotificationChannel(CANAL_CONEXION, "Conexión", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso fijo mientras la app espera servicios"
                setShowBadge(false)
            }
            nm.createNotificationChannels(listOf(ofertas, avisos, conexion))
        }
    }
}

object Config {
    const val API_KEY = "AIzaSyCVVcSAzIkGVk1W-Zmebn7E_wCy4rrvtfo"
    const val ANDROID_APP_ID = "1:56800910652:android:2eaa0296d2571521eb9239"
    const val PROJECT_ID = "frozz-domicilios"
    const val STORAGE_BUCKET = "frozz-domicilios.firebasestorage.app"
    const val SENDER_ID = "56800910652"
    const val DOMINIO_CORREO = "domiciliarios.frozzcolombia.com"

    /** DOM-001 -> dom-001@domiciliarios.frozzcolombia.com */
    fun correoDe(codigo: String): String =
        codigo.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "") + "@" + DOMINIO_CORREO
}
