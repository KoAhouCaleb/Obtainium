package dev.imranr.obtainium.revanced

import android.content.Context
import app.revanced.library.ApkUtils.applyTo
import app.revanced.patcher.patcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class PatchResult(
    val success: Boolean,
    val outputPath: String?,
    val error: String?,
)

/**
 * Runs a patch job entirely in-process (no separate-process OOM isolation, unlike
 * ReVanced Manager's ProcessRuntime - out of scope per the plan) and signs the
 * result with Obtainium's own keystore.
 *
 * Also supports signOnly mode: re-sign the input APK with the keystore without
 * applying any patches, used as the opt-in fallback when a configured patch fails
 * to apply to a new app version (see Phase 3 "patch failure fallback" decision).
 *
 * The patcher(...) call shape here is verified against revanced-manager's own
 * Session.kt, which is the actual (and only) consumer of revanced-patcher's
 * public API in that codebase - there is no Patcher/PatcherConfig class; it's a
 * top-level `patcher(...)` function that returns a callable you invoke with an
 * emit callback to get a PatchesResult.
 */
class PatchEngine(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
) {
    suspend fun patchAndSign(
        bundlePath: String,
        inputApkPath: String,
        outputApkPath: String,
        packageName: String,
        selectedPatchNames: List<String>,
        options: Map<String, Map<String, Any?>>,
        alias: String,
        password: String,
        signOnly: Boolean = false,
    ): PatchResult {
        val inputApk = File(inputApkPath)
        val outputApk = File(outputApkPath)
        outputApk.parentFile?.mkdirs()

        if (signOnly || selectedPatchNames.isEmpty()) {
            return try {
                keystoreManager.sign(inputApk, outputApk, alias, password)
                PatchResult(success = true, outputPath = outputApkPath, error = null)
            } catch (e: Exception) {
                PatchResult(success = false, outputPath = null, error = e.message ?: "Signing failed")
            }
        }

        return try {
            val aaptBinary = Aapt.binary(context)
                ?: return PatchResult(
                    success = false,
                    outputPath = null,
                    error = "aapt2 binary not found for this device ABI",
                )

            val selectedPatches = PatchBundleLoader.resolveSelectedPatches(
                bundlePath = bundlePath,
                selectedPatchNames = selectedPatchNames,
                options = options,
            )
            if (selectedPatches.isEmpty()) {
                return PatchResult(
                    success = false,
                    outputPath = null,
                    error = "None of the configured patches were found in the bundle for $packageName",
                )
            }

            val workDir = context.cacheDir.resolve("revanced-work").apply { mkdirs() }
            val frameworkDir = context.cacheDir.resolve("framework").apply { mkdirs() }

            withContext(Dispatchers.Default) {
                val runPatcher = patcher(
                    apkFile = inputApk,
                    temporaryFilesPath = workDir,
                    frameworkFileDirectory = frameworkDir.absolutePath,
                    aaptBinaryPath = aaptBinary,
                ) { _, _ -> selectedPatches }

                val result = runPatcher { patchResult ->
                    patchResult.exception?.let { throw it }
                }

                val patchedTmp = File(workDir, "patched.apk")
                withContext(Dispatchers.IO) {
                    Files.copy(
                        inputApk.toPath(),
                        patchedTmp.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                result.applyTo(patchedTmp)

                keystoreManager.sign(patchedTmp, outputApk, alias, password)
                patchedTmp.delete()
            }

            PatchResult(success = true, outputPath = outputApkPath, error = null)
        } catch (e: Exception) {
            PatchResult(success = false, outputPath = null, error = e.message ?: "Patching failed")
        }
    }
}
