package com.frozzcolombia.domicilios

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuthException

class LoginActivity : AppCompatActivity() {

    private lateinit var inCodigo: EditText
    private lateinit var inPin: EditText
    private lateinit var btn: Button
    private lateinit var prog: ProgressBar
    private lateinit var msg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        inCodigo = findViewById(R.id.inCodigo)
        inPin = findViewById(R.id.inPin)
        btn = findViewById(R.id.btnEntrar)
        prog = findViewById(R.id.prog)
        msg = findViewById(R.id.msg)
        findViewById<TextView>(R.id.version).text = "Versión ${BuildConfig.VERSION_NAME}"

        val guardado = getSharedPreferences("login", MODE_PRIVATE).getString("codigo", "")
        inCodigo.setText(guardado)

        btn.setOnClickListener { entrar() }
        inPin.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { entrar(); true } else false
        }

        if (Repo.uid != null) {
            ocupado(true)
            verificarPerfil()
        }
    }

    private fun ocupado(si: Boolean) {
        prog.visibility = if (si) View.VISIBLE else View.GONE
        btn.isEnabled = !si
        inCodigo.isEnabled = !si
        inPin.isEnabled = !si
    }

    private fun entrar() {
        val codigo = inCodigo.text.toString().trim().uppercase()
        val pin = inPin.text.toString().trim()
        if (codigo.isEmpty()) { msg.text = "Escriba su código (por ejemplo DOM-001)."; return }
        if (pin.length < 6) { msg.text = "El PIN tiene al menos 6 dígitos."; return }
        msg.text = ""
        ocupado(true)
        Repo.auth.signInWithEmailAndPassword(Config.correoDe(codigo), pin)
            .addOnSuccessListener {
                getSharedPreferences("login", MODE_PRIVATE).edit().putString("codigo", codigo).apply()
                verificarPerfil()
            }
            .addOnFailureListener { e ->
                ocupado(false)
                val code = (e as? FirebaseAuthException)?.errorCode ?: ""
                msg.text = when {
                    code.contains("NETWORK") || e.message?.contains("network", true) == true ->
                        "Sin conexión a internet. Intente de nuevo."
                    code.contains("TOO_MANY") -> "Demasiados intentos. Espere unos minutos."
                    else -> "Código o PIN incorrectos. Si no los tiene, pídalos al despacho de Frozz."
                }
            }
    }

    private fun verificarPerfil() {
        val uid = Repo.uid ?: run { ocupado(false); return }
        Repo.perfil(uid) { p, e ->
            when {
                p != null && p.activo -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                p != null && !p.activo -> {
                    Repo.auth.signOut()
                    ocupado(false)
                    msg.text = "Su usuario está inactivo. Comuníquese con el despacho de Frozz."
                }
                e != null -> {
                    ocupado(false)
                    msg.text = "No se pudo verificar su usuario: ${Repo.mensajeError(e)}"
                }
                else -> {
                    Repo.auth.signOut()
                    ocupado(false)
                    msg.text = "Su usuario no está habilitado en el despacho. Comuníquese con Frozz."
                }
            }
        }
    }
}
