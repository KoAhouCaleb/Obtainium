package dev.imranr.obtainium.revanced

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatches
import java.io.File
import kotlin.reflect.KType

/**
 * Loads a compiled ReVanced patch bundle (a jar of patch classes) and exposes only
 * the universal patches (those with no compatiblePackages restriction) along with
 * their configurable options, for display in Obtainium's per-app patch-config UI.
 *
 * Obtainium intentionally only supports universal patches (see plan) - patches
 * that require a specific target package are filtered out entirely.
 *
 * API shapes here (loadPatches/Patch/Option) are verified against
 * revanced-manager's own PatchBundle.kt/PatchInfo.kt/CoroutineRuntime.kt, which
 * consume revanced-patcher directly.
 */
object PatchBundleLoader {
    private fun loadAllPatches(bundlePath: String): Set<Patch> {
        val file = File(bundlePath)
        val result = loadPatches(file, onFailedToLoad = { _, throwable -> throw throwable })
        return result.patchesByFile[file] ?: emptySet()
    }

    fun listUniversalPatches(bundlePath: String): List<Map<String, Any?>> {
        return loadAllPatches(bundlePath)
            .filter { it.compatiblePackages == null }
            .map { patch ->
                mapOf(
                    "name" to (patch.name ?: ""),
                    "description" to (patch.description ?: ""),
                    "options" to patch.options.values.map { option ->
                        mapOf(
                            "key" to option.name,
                            "description" to (option.description ?: ""),
                            "required" to option.required,
                            "type" to simpleTypeName(option.type),
                            "default" to option.default,
                        )
                    },
                )
            }
    }

    /**
     * Collapses KType down to the handful of primitive shapes the Dart-side form
     * understands. Deliberately avoids kotlin.reflect.full (a separate
     * kotlin-reflect artifact this project doesn't depend on) - KType.classifier
     * is part of the core kotlin-stdlib reflect API and needs no extra dependency.
     */
    private fun simpleTypeName(type: KType): String = when (type.classifier) {
        String::class -> "string"
        Boolean::class -> "boolean"
        Int::class, Long::class -> "integer"
        List::class, Set::class -> "stringList"
        else -> "string"
    }

    /**
     * Resolves the caller-selected universal patches by name and applies the
     * caller-supplied option values (Dart's PatchConfig) directly via
     * Patch.options' indexed setter - the same mechanism revanced-manager's
     * CoroutineRuntime.kt uses (patchOptions[key] = value), rather than a
     * revanced-library convenience wrapper. Throws if an option key/value
     * doesn't match what the patch declares - the caller (PatchEngine) treats
     * that as a patch failure.
     */
    fun resolveSelectedPatches(
        bundlePath: String,
        selectedPatchNames: List<String>,
        options: Map<String, Map<String, Any?>>,
    ): Set<Patch> {
        val allPatches = loadAllPatches(bundlePath)
        val selected = allPatches
            .filter { patch ->
                patch.compatiblePackages == null && selectedPatchNames.any { it == patch.name }
            }
            .toSet()
        selected.forEach { patch ->
            val name = patch.name ?: return@forEach
            val patchOptions = options[name] ?: return@forEach
            patchOptions.forEach { (key, value) ->
                patch.options[key] = value
            }
        }
        return selected
    }
}
