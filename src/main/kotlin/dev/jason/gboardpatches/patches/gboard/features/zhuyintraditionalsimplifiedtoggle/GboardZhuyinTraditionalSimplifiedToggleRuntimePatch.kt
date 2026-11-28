package dev.jason.gboardpatches.patches.gboard.features.zhuyintraditionalsimplifiedtoggle

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.generated.GboardVersionBindings
import dev.jason.gboardpatches.patches.gboard.shared.selectReleaseTargets
import dev.jason.gboardpatches.patches.gboard.shared.gboardStructuralFingerprint
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.isInvoke
import dev.jason.gboardpatches.patches.gboard.shared.isMethodReference
import dev.jason.gboardpatches.patches.gboard.shared.isReference
import dev.jason.gboardpatches.patches.gboard.shared.isRegisterOperation
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

private val POPUP_RUNTIME_CALL =
    RuntimeCallId.ZHUYIN_TRADITIONAL_SIMPLIFIED_TOGGLE_RUNTIME_PATCH_POPUP_ACTION
private val POPUP_RUNTIME_ABI = RuntimeAbiCatalog.abi(POPUP_RUNTIME_CALL)
private val POPUP_RUNTIME_REFERENCE = POPUP_RUNTIME_ABI.reference
private const val GBOARD_18_5_3_POPUP_STOCK_FINGERPRINT =
    "91b00b861c281f7477cf45c6eec4853968bb4d0ceea598e76da5bc4a21c7c617"

// 18.6.3 popup body: same stock shape, different register assignment/codegen.
private const val GBOARD_18_6_3_POPUP_STOCK_FINGERPRINT =
    "73e29b09de5c80e0c03396b7507e9f3c80e4b9855cf502632f69cef58a6b460e"

// 18.8.0 popup body: the same stock shape again, recompiled against the renamed key-data class.
private const val GBOARD_18_8_POPUP_STOCK_FINGERPRINT =
    "8de88d6378242fbcafbf522264798fceda0f9d64a95bbc6802da99d72bbe6f5f"

private val ACCEPTED_POPUP_STOCK_FINGERPRINTS = setOf(
    GBOARD_18_5_3_POPUP_STOCK_FINGERPRINT,
    GBOARD_18_6_3_POPUP_STOCK_FINGERPRINT,
    GBOARD_18_8_POPUP_STOCK_FINGERPRINT,
)
private val POPUP_TARGET_ACCESS_FLAGS = AccessFlags.PUBLIC.value

internal val gboardZhuyinTraditionalSimplifiedTogglePopupPatch = bytecodePatch(
    description = "修改 18.5.4 注音繁簡切換 popup action。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val binding = zhuyinTogglePopupAction(this)
        binding.ownerClass(this).applyZhuyinTogglePopupActionPatch(binding)
    }
}

/**
 * The popup action the toggle rewrites.
 *
 * 18.8.0 keeps the class, the name and every parameter, and only renames the key-data type the
 * method returns: `Lpva;` on 18.6.3, `Lagmb;` on 18.8.0 - the same class the 18.8 SoftKey metadata
 * table calls a key data. Choosing by what the APK's `BasicPopupView` actually declares keeps the
 * delegation body's `check-cast` on the matching type.
 */
internal fun zhuyinTogglePopupAction(context: BytecodePatchContext): GboardMethodTarget =
    selectReleaseTargets(
        context = context,
        projection = { GboardVersionBindings.zhuyinTogglePopupAction },
        probe = { target -> target },
        audited = ZHUYIN_TOGGLE_POPUP_18_8,
    )

private val ZHUYIN_TOGGLE_POPUP_18_8 = GboardMethodTarget(
    classType = "Lcom/google/android/libraries/inputmethod/popup/BasicPopupView;",
    name = "b",
    parameterTypes = listOf(
        "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;",
        "Landroid/view/View;",
        "F",
        "F",
        "Lcom/google/android/libraries/inputmethod/metadata/ActionDef;",
        "[I",
        "Z",
    ),
    returnType = "Lagmb;",
)

internal fun MutableClass.applyZhuyinTogglePopupActionPatch(binding: GboardMethodTarget) {
    val plan = ZhuyinTogglePopupTransformationPlan.preflight(this, binding)
    plan.commit()
}

private data class ZhuyinTogglePopupTransformationPlan(
    val ownerClass: MutableClass,
    val committedMethods: Set<MutableMethod>?,
) {
    fun commit() {
        val methods = committedMethods ?: return
        ownerClass.methods.clear()
        ownerClass.methods.addAll(methods)
    }

    companion object {
        fun preflight(
            ownerClass: MutableClass,
            binding: GboardMethodTarget,
        ): ZhuyinTogglePopupTransformationPlan {
            val target = ownerClass.resolvePopupTargetReadOnly(binding)
            target.requirePopupImplementation()
            if (target.hasAnyPopupRuntimeState()) {
                target.verifyExactPatchedPopupState()
                return ZhuyinTogglePopupTransformationPlan(ownerClass, null)
            }

            target.requireExact1853PopupStockFingerprint()
            val candidate = MutableClass(ownerClass)
            val candidateTarget = binding.resolve(candidate)
            candidateTarget.addInstructions(0, ZHUYIN_TOGGLE_POPUP_DELEGATE)
            candidateTarget.verifyExactPatchedPopupState()

            val committed = ownerClass.methods.toMutableSet().apply {
                check(remove(target)) { "Could not stage Zhuyin toggle popup target replacement" }
                add(MutableMethod(candidateTarget))
            }
            return ZhuyinTogglePopupTransformationPlan(ownerClass, committed)
        }
    }
}

private fun MutableClass.resolvePopupTargetReadOnly(binding: GboardMethodTarget): MutableMethod {
    val exact = methods.filter(binding::matches)
    check(exact.size <= 1) { "Ambiguous popup target: ${binding.reference}" }
    exact.singleOrNull()?.let { return it }

    val nearMisses = methods.filter { method ->
        method.definingClass == binding.ownerDescriptor && method.name == binding.name
    }
    check(nearMisses.isEmpty()) {
        val actual = nearMisses.joinToString { method ->
            "(${method.parameterTypes.joinToString("")})${method.returnType}"
        }
        "Popup target prototype drift: expected ${binding.reference}, found $actual"
    }
    error("Could not find ${binding.reference}")
}

private fun MutableMethod.requirePopupImplementation() {
    check(accessFlags == POPUP_TARGET_ACCESS_FLAGS && implementation != null) {
        "Unexpected popup target access or implementation: " +
            ZHUYIN_TOGGLE_POPUP_18_8.reference
    }
}

private fun MutableMethod.hasAnyPopupRuntimeState(): Boolean =
    implementation!!.instructions.any { instruction ->
        instruction.popupRuntimeCallNearMatch()
    }

private fun MutableMethod.requireExact1853PopupStockFingerprint() {
    val actual = gboardStructuralFingerprint()
    check(actual in ACCEPTED_POPUP_STOCK_FINGERPRINTS) {
        "popup stock body drift (no accepted stock fingerprint matches) in $definingClass->$name: $actual"
    }
}

private fun MutableMethod.verifyExactPatchedPopupState() {
    verifyExactPopupDelegate()
    val normalized = MutableMethod(this)
    repeat(POPUP_DELEGATE_INSTRUCTION_COUNT) {
        normalized.implementation!!.removeInstruction(0)
    }
    check(normalized.implementation!!.instructions.none { instruction ->
        instruction.popupRuntimeCallNearMatch()
    }) { "Extraneous or near-miss popup runtime call after the managed delegate" }
    normalized.requireExact1853PopupStockFingerprint()

    val expected = MutableMethod(normalized)
    expected.addInstructions(0, ZHUYIN_TOGGLE_POPUP_DELEGATE)
    check(gboardStructuralFingerprint() == expected.gboardStructuralFingerprint()) {
        "Malformed popup patched state"
    }
}

private fun MutableMethod.verifyExactPopupDelegate() {
    val implementation = implementation ?: error("Popup target has no implementation")
    val layout = PopupRegisterLayout.fromBinding()
    val parameterBase = implementation.registerCount - layout.parameterWordCount
    check(parameterBase >= 0) { "Popup target register layout is smaller than its prototype" }
    val viewRegister = parameterBase + layout.parameterRegister(POPUP_VIEW_PARAMETER_INDEX)
    val actionRegister = parameterBase + layout.parameterRegister(POPUP_ACTION_PARAMETER_INDEX)
    val instructions = implementation.instructions
    val runtimeCallIndices = instructions.indices.filter { index ->
        instructions[index].isMethodReference(POPUP_RUNTIME_REFERENCE)
    }
    check(runtimeCallIndices == listOf(0)) {
        "Popup runtime delegate must appear exactly once at method entry"
    }
    check(
        instructions[0].isInvoke(
            "INVOKE_STATIC",
            POPUP_RUNTIME_REFERENCE,
            viewRegister,
            actionRegister,
        ) &&
            instructions.getOrNull(1)?.isRegisterOperation(
                "MOVE_RESULT_OBJECT",
                actionRegister,
            ) == true &&
            instructions.getOrNull(2)?.isRegisterOperation(
                "CHECK_CAST",
                actionRegister,
            ) == true &&
            instructions[2].isReference(layout.actionType)
    ) {
        "Popup runtime delegate is malformed"
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction
    .popupRuntimeCallNearMatch(): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == POPUP_RUNTIME_ABI.owner &&
        reference.name == POPUP_RUNTIME_ABI.name
}

/** Both releases declare the same parameters; only the returned key-data type differs. */
private val POPUP_ACTION_TYPES = ZHUYIN_TOGGLE_POPUP_18_8.parameterTypes

private data class PopupRegisterLayout(
    val parameterWordOffsets: List<Int>,
    val parameterWordCount: Int,
    val actionType: String,
) {
    fun parameterRegister(index: Int): Int = parameterWordOffsets[index]

    companion object {
        fun fromBinding(): PopupRegisterLayout {
            val parameters = POPUP_ACTION_TYPES
            var nextWord = 1
            val offsets = parameters.map { type ->
                nextWord.also { nextWord += if (type == "J" || type == "D") 2 else 1 }
            }
            return PopupRegisterLayout(
                parameterWordOffsets = offsets,
                parameterWordCount = nextWord,
                actionType = parameters[POPUP_ACTION_PARAMETER_INDEX],
            )
        }
    }
}

private val POPUP_REGISTER_LAYOUT by lazy { PopupRegisterLayout.fromBinding() }
private val POPUP_VIEW_PARAMETER_REGISTER: Int
    get() = POPUP_REGISTER_LAYOUT.parameterRegister(POPUP_VIEW_PARAMETER_INDEX)
private val POPUP_ACTION_PARAMETER_REGISTER: Int
    get() = POPUP_REGISTER_LAYOUT.parameterRegister(POPUP_ACTION_PARAMETER_INDEX)

internal val ZHUYIN_TOGGLE_POPUP_DELEGATE by lazy { """
    ${RuntimeCallEmitter.invoke(
        POPUP_RUNTIME_CALL,
        "p$POPUP_VIEW_PARAMETER_REGISTER, p$POPUP_ACTION_PARAMETER_REGISTER",
    )}

    move-result-object p$POPUP_ACTION_PARAMETER_REGISTER

    check-cast p$POPUP_ACTION_PARAMETER_REGISTER, ${POPUP_REGISTER_LAYOUT.actionType}
""".trimIndent() }

private const val POPUP_VIEW_PARAMETER_INDEX = 1
private const val POPUP_ACTION_PARAMETER_INDEX = 4
private const val POPUP_DELEGATE_INSTRUCTION_COUNT = 3
