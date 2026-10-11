pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Faltis"

// :core es Kotlin puro (analizadores, comparador, equivalencias) y se prueba sin emulador.
include(":core")
include(":app")
// Auto-actualizacion fuera de Play Store (DracApps): el mismo modulo que Kuse, Prompter y las demas.
include(":actualizador")
