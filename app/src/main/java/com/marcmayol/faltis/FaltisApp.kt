package com.marcmayol.faltis

import android.app.Application
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.ActualizadorConfig

class FaltisApp : Application() {
    /**
     * Faltis se distribuye fuera de Play Store (DracApps): consulta un manifiesto estatico en
     * GitHub Pages y compara por versionCode entero. Mismo modulo que Kuse y Prompter.
     * La red se usa tambien para los precios web (opcionales) y los precios compartidos
     * de los tickets (Repository.sendSharedPrices); la lista y los tickets enteros nunca salen.
     */
    val actualizador: Actualizador by lazy {
        Actualizador(
            app = this,
            config = ActualizadorConfig(
                manifiestoUrl = MANIFIESTO_URL,
                versionCodeActual = BuildConfig.VERSION_CODE,
                checkHorasPorDefecto = 24,
            ),
        )
    }

    companion object {
        const val MANIFIESTO_URL = "https://marcmayol.com/faltis/updates.json"
    }
}
