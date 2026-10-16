package com.marcmayol.faltis

import androidx.core.content.FileProvider

/** FileProvider propio para la foto del ticket: el del actualizador usa la clase base y chocarian al fusionar. */
class FotosProvider : FileProvider(R.xml.fotos)
