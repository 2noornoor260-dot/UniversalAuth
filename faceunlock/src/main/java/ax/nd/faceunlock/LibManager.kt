package ax.nd.faceunlock

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class RequiredLib(
    val name: String,
    val armeabi_v7a: String? = null,
    val arm64_v8a: String? = null
) {
    fun hashForCurrentAbi(): String? {
        val abis = Build.SUPPORTED_ABIS
        if ("arm64-v8a" in abis) {
            return arm64_v8a
        }
        return null
    }
}

data class LibraryState(
    val library: RequiredLib,
    val valid: Boolean
)

object LibManager {
    const val LIB_DIR = "facelibs"
    const val HASH_TYPE = "SHA-512"

    val requiredLibraries = listOf(
        RequiredLib("SmartFaceAI-MLKit"),
        RequiredLib("SmartFaceAI-MiniFASNet"),
        RequiredLib("SmartFaceAI-InsightFace")
    )

    val librariesData = MutableStateFlow<List<LibraryState>>(emptyList())
    val libsLoaded = AtomicBoolean(true)
    val libLoadError = MutableStateFlow<Throwable?>(null)

    fun init(context: Context) {
        updateLibraryData(context)
    }

    fun updateLibraryData(context: Context) {
        val newStatus = requiredLibraries.map {
            LibraryState(
                library = it,
                valid = true
            )
        }
        librariesData.value = newStatus
        libsLoaded.set(true)
        libLoadError.value = null
    }

    fun getLibFile(context: Context, lib: RequiredLib, temp: Boolean = false): File {
        val libsDir = File(context.filesDir, LIB_DIR)
        val fname = if (temp) "${lib.name}.tmp" else lib.name
        return File(libsDir, fname)
    }
}
