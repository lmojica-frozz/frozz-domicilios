package com.frozzcolombia.domicilios

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Vuelve a conectar la app después de reiniciar el celular o de actualizarla. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ServicioService.iniciar(context)
        }
    }
}
