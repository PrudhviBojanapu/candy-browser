pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeGroup("com.github.Dimezis")
                includeGroup("com.github.abdallahmehiz")
                includeGroup("io.github.abdallahmehiz")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.mozilla.org/maven2/") }
            filter { includeGroup("org.mozilla.geckoview") }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "mpvlibGitHubReleases"
                    url = uri("https://github.com/Riteshp2001/mpvlibAndroid/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("app.gyrolet.mpvlib")
            }
        }
    }
}

rootProject.name = "MaterialBrowser"
include(":app")
include(":shared")
