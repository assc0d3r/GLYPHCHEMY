package dev.jason.gboardpatches.patches.gboard.features.customtheme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.gboardStructuralFingerprint
import dev.jason.gboardpatches.patches.gboard.shared.isMethodReference
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardCustomThemeOfficialImportPatch = bytecodePatch(
    description = "Add a ZIP theme import option to Gboard's own theme plus button.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val targets = customThemeTargets(this)
        val (plusHandler, resultHandler) = validateCustomThemeTargetContract(targets)
        plusHandler.applyCustomThemeGuard(
            RuntimeCallId.CUSTOM_THEME_RUNTIME_INTERCEPT_PLUS,
            "p0 .. p3",
            "custom_theme_plus_continue",
            targets.plusStockRegisters,
        )
        resultHandler.applyCustomThemeGuard(
            RuntimeCallId.CUSTOM_THEME_RUNTIME_HANDLE_ACTIVITY_RESULT,
            "p0 .. p3",
            "custom_theme_result_continue",
            targets.resultStockRegisters,
        )
    }
}

context(context: app.morphe.patcher.patch.BytecodePatchContext)
private fun validateCustomThemeTargetContract(
    targets: CustomThemeTargets,
): Pair<MutableMethod, MutableMethod> = with(context) {
    val publicFinal = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
    val plusHandler = findMutableMethodOrThrow(targets.plusHandler)
    val resultHandler = findMutableMethodOrThrow(targets.resultHandler)
    listOf(
        plusHandler,
        resultHandler,
        findMutableMethodOrThrow(targets.peerResultHandler),
        findMutableMethodOrThrow(targets.launcherLaunch),
    ).forEach { method ->
        check(method.accessFlags == publicFinal && method.implementation != null) {
            "Unexpected Custom Theme method shape for ${method.definingClass}->${method.name}"
        }
    }
    plusHandler.requireStockFingerprintUnlessPatched(
        RuntimeCallId.CUSTOM_THEME_RUNTIME_INTERCEPT_PLUS,
        targets.plusFingerprint,
        targets.release,
    )
    resultHandler.requireStockFingerprintUnlessPatched(
        RuntimeCallId.CUSTOM_THEME_RUNTIME_HANDLE_ACTIVITY_RESULT,
        targets.resultFingerprint,
        targets.release,
    )
    findMutableMethodOrThrow(targets.validator).let { method ->
        check(method.accessFlags == publicStatic && method.implementation != null) {
            "Unexpected Custom Theme validator shape for ${method.definingClass}->${method.name}"
        }
    }
    check(targets.fragmentPeerField.resolve(this).accessFlags == AccessFlags.PUBLIC.value) {
        "Unexpected Custom Theme fragment peer field shape"
    }
    listOf(
        targets.peerContextField,
        targets.peerLauncherField,
    ).forEach { binding ->
        check(binding.resolve(this).accessFlags == publicFinal) {
            "Unexpected Custom Theme peer field shape for ${binding.reference}"
        }
    }
    plusHandler to resultHandler
}

internal fun MutableMethod.requireStockFingerprintUnlessPatched(
    call: RuntimeCallId,
    expected: String,
    release: String,
) {
    val runtimeReference = RuntimeAbiCatalog.abi(call).reference
    if (implementation!!.instructions.any { it.isMethodReference(runtimeReference) }) return
    val actual = gboardStructuralFingerprint()
    check(actual == expected) {
        "Unexpected $release Custom Theme fingerprint for $definingClass->$name: " +
            "$actual != $expected"
    }
}

// Stock 18.6.3 structure: Lkbl;->f(Lkbf;Lkbo;I)V and the fragment's activity result handler.
internal const val CUSTOM_THEME_PLUS_1863_FINGERPRINT =
    "847c767ae8a96d901f25a912eb42071221255933fee93b12fa3487f6a868e910"
internal const val CUSTOM_THEME_RESULT_1863_FINGERPRINT =
    "0686622b70cde1e986f10d2bb83162eccd3d52fa0e5abcfe5b644432d984df11"

// Register files the stock bodies are compiled with; the guard only needs v0 as scratch,
// and the entry copies keep those values readable after the adapter is prepended.
internal const val CUSTOM_THEME_PLUS_STOCK_REGISTERS = 6
internal const val CUSTOM_THEME_RESULT_STOCK_REGISTERS = 5

private fun MutableMethod.applyCustomThemeGuard(
    call: RuntimeCallId,
    registers: String,
    labelName: String,
    expectedRegisters: Int,
) {
    check(accessFlags == (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)) {
        "Unexpected access flags for $definingClass->$name"
    }
    val implementation = implementation ?: error("No implementation in $definingClass->$name")
    check(implementation.registerCount == expectedRegisters) {
        "Unexpected register count in $definingClass->$name: ${implementation.registerCount}"
    }
    val abi = RuntimeAbiCatalog.abi(call)
    val existing = implementation.instructions.count { it.isMethodReference(abi.reference) }
    if (existing > 0) {
        check(existing == 1 && implementation.instructions.first().isMethodReference(abi.reference)) {
            "Malformed Custom Theme adapter in $definingClass->$name"
        }
        return
    }
    addInstructions(0, "nop")
    val continuation = implementation.instructions[0]
    addInstructionsWithLabels(
        0,
        """
            ${RuntimeCallEmitter.invoke(call, registers)}

            move-result v0

            if-eqz v0, :$labelName

            return-void
        """.trimIndent(),
        ExternalLabel(labelName, continuation),
    )
}
