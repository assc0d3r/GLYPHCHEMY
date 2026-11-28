package dev.jason.gboardpatches.patches.gboard.features.atmention

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import dev.jason.gboardpatches.patches.gboard.shared.addHelperMethodIfMissing
import dev.jason.gboardpatches.patches.gboard.shared.gboardCompatibility18_8
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.isMethodReference
import dev.jason.gboardpatches.patches.gboard.shared.mutableClass
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId

/**
 * Wires the bundled at-mention runtime into Gboard's own suggestion strip.
 *
 * The runtime holds no Gboard names of its own: it reads them from
 * [AT_MENTION_CONFIG_TYPE], which this patch rewrites. Every name below is proved from the APK in
 * front of us and every lookup is fail-closed, so a build whose suggestion-strip plumbing moved
 * aborts the patch instead of installing hooks that cannot resolve at runtime.
 *
 * Ported from Rambler Bangla (MIT) `installAtMentionLifecycleHooks` and
 * `rewriteDynamicConfigClinit`.
 */

private const val INPUT_VIEW_TYPE = "Lcom/google/android/libraries/inputmethod/inputview/InputView;"
private const val EDITOR_INFO_TYPE = "Landroid/view/inputmethod/EditorInfo;"
private const val AT_MENTION_CONFIG_TYPE =
    "Ldev/jason/gboardpatches/extension/atmention/AtMentionConfig;"
private const val SELECTION_HELPER_NAME = "gboardPatchesAtMentionSelection"
private const val SELECTION_HELPER_REGISTER_COUNT = 4
private const val MAX_SUPERCLASS_HOPS = 5
private const val MIN_HANDLE_EVENT_INSTRUCTIONS = 100
private const val MAX_SELECTION_REGISTER_COUNT = 16

internal val gboardAtMentionSuggestionStripPatch = bytecodePatch(
    description = "Hook Gboard's suggestion strip and bind the at-mention runtime to this build.",
) {
    compatibleWith(gboardCompatibility18_8())
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val targets = resolveAtMentionTargets()
        installAtMentionCandidateHooks(targets)
        installAtMentionLifecycleHooks(targets)
        bindAtMentionConfig(targets)
    }
}

@Suppress("unused")
val gboardAtMentionSuggestionsPatch = bytecodePatch(
    name = "At-mention suggestions",
    description = "Learn the @handles and email domains you type and offer them in Gboard's own " +
        "suggestion strip. Ported from Rambler Bangla (MIT).",
    default = false,
) {
    compatibleWith(gboardCompatibility18_8())

    dependsOn(gboardAtMentionSuggestionStripPatch)
}

internal data class AtMentionTargets(
    val imeType: String,
    val inputViewFieldName: String,
    val bundleType: String,
    val textCandidatesUpdatedName: String,
    val handleEventName: String,
    val handleEventParameterTypes: List<String>,
    val requestCandidatesOwner: String,
    val requestCandidatesName: String,
    val imeActiveBundleMethodName: String,
    val bundleWrapperFieldName: String,
    val wrapperControllerFieldName: String,
    val wrapperActiveMethodName: String,
    val controllerAppendMethodName: String,
    val candidateClass: String,
    val candidateBuilderClass: String,
)

private fun BytecodePatchContext.resolveAtMentionTargets(): AtMentionTargets {
    val classByType = hashMapOf<String, ClassDef>()
    var imeType: String? = null
    var inputViewFieldName: String? = null
    var inputBundleType: String? = null

    classDefForEach { classDef -> classByType[classDef.type] = classDef }

    for (classDef in classByType.values) {
        val inputViewField = classDef.instanceFields.firstOrNull { it.type == INPUT_VIEW_TYPE }
        if (inputViewField != null && classDef.isInputMethodHost()) {
            val previous = imeType
            if (previous != null && previous != classDef.type) {
                throw PatchException(
                    "FAIL-CLOSED: Multiple InputMethodService classes own $INPUT_VIEW_TYPE: " +
                        "$previous and ${classDef.type}",
                )
            }
            imeType = classDef.type
            inputViewFieldName = inputViewField.name
        }

        val updateMethod = classDef.findTextCandidatesUpdated(classByType)
        if (updateMethod != null) {
            val previous = inputBundleType
            if (previous != null && previous != classDef.type) {
                throw PatchException(
                    "FAIL-CLOSED: Multiple InputBundle classes found: $previous and ${classDef.type}",
                )
            }
            inputBundleType = classDef.type
        }
    }

    val resolvedImeType = imeType
        ?: throw PatchException(
            "FAIL-CLOSED: Could not find the InputMethodService class owning $INPUT_VIEW_TYPE",
        )
    val resolvedInputViewField = inputViewFieldName
        ?: throw PatchException("FAIL-CLOSED: $resolvedImeType has no $INPUT_VIEW_TYPE field")
    val ime = classByType.getValue(resolvedImeType)
    val bundleDef = inputBundleType?.let(classByType::getValue)
        ?: throw PatchException(
            "FAIL-CLOSED: Could not locate Gboard's InputBundle class for the at-mention strip",
        )
    val bundleType = bundleDef.type

    val textCandidatesUpdated = bundleDef.methods.singleOrNull { method ->
        method.parameterTypes.map { it.toString() } == listOf("Z") &&
            method.returnType == "V" &&
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL && run {
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference != null &&
                        reference.parameterTypes.isEmpty() &&
                        reference.returnType == "Z"
                }
            } == true
    } ?: throw PatchException(
        "FAIL-CLOSED: Could not find InputBundle.textCandidatesUpdated(Z)V on $bundleType",
    )

    val textCandidatesInstructions = textCandidatesUpdated.implementation?.instructions?.toList()
        ?: throw PatchException(
            "FAIL-CLOSED: $bundleType->${textCandidatesUpdated.name} has no implementation",
        )
    val bundleWrapperField = textCandidatesInstructions.objectFieldsDefinedOn(bundleType)
        .firstOrNull()
        ?: throw PatchException(
            "FAIL-CLOSED: Could not resolve the keyboard wrapper field in " +
                "$bundleType->${textCandidatesUpdated.name}",
        )
    val wrapperType = bundleWrapperField.type
    val wrapperControllerField = textCandidatesInstructions.objectFieldsDefinedOn(wrapperType)
        .firstOrNull()
        ?: throw PatchException(
            "FAIL-CLOSED: Could not resolve the candidate controller field on $wrapperType",
        )
    val wrapperActiveMethod = textCandidatesInstructions
        .mapNotNull { instruction -> instruction.virtualMethodOn(wrapperType) }
        .firstOrNull { reference ->
            reference.parameterTypes.isEmpty() && reference.returnType == "Z"
        }?.name

    val controllerType = wrapperControllerField.type
    val controllerDef = classByType[controllerType]
        ?: throw PatchException("FAIL-CLOSED: Missing candidate controller class $controllerType")
    val appendMethod = controllerDef.methods.singleOrNull { method ->
        method.returnType == "V" &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes[0].toString() == "Ljava/util/List;" &&
            method.parameterTypes[1].toString().startsWith("L") &&
            method.parameterTypes[2].toString() == "Z"
    } ?: throw PatchException(
        "FAIL-CLOSED: Could not find appendTextCandidates(List, Candidate, Z)V on $controllerType",
    )
    val candidateType = appendMethod.parameterTypes[1].toString()
    val candidateDef = classByType[candidateType]
        ?: throw PatchException("FAIL-CLOSED: Missing candidate class $candidateType")
    val candidateBuilderType = candidateDef.methods.singleOrNull { method ->
        method.name == "<init>" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString().startsWith("L")
    }?.parameterTypes?.single()?.toString()
        ?: throw PatchException(
            "FAIL-CLOSED: Could not find the Candidate(Builder) constructor on $candidateType",
        )

    fun ownsBundleField(classDef: ClassDef): Boolean {
        var current: ClassDef? = classDef
        var hops = 0
        while (current != null && hops < MAX_SUPERCLASS_HOPS) {
            if (current.instanceFields.any { it.type == bundleType }) {
                return true
            }
            current = classByType[current.superclass]
            hops++
        }
        return false
    }

    val delegates = mutableListOf<Pair<String, String>>()
    for (classDef in classByType.values) {
        if (!ownsBundleField(classDef)) continue
        for (method in classDef.methods) {
            if (method.parameterTypes.map { it.toString() } != listOf("I", "Z") ||
                method.returnType != "V"
            ) {
                continue
            }
            val forwardsToDelegate = method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE && run {
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference != null &&
                        reference.parameterTypes.map { it.toString() } == listOf("I", "Z") &&
                        reference.returnType == "V"
                }
            } == true
            if (forwardsToDelegate) {
                delegates.add(classDef.type to method.name)
            }
        }
    }
    val (requestCandidatesOwner, requestCandidatesName) = delegates.singleOrNull()
        ?: throw PatchException(
            "FAIL-CLOSED: Expected one IKeyboardDelegate.requestCandidates(IZ)V referencing " +
                "$bundleType, found ${delegates.size}",
        )

    val imeActiveBundleMethod = ime.methods.singleOrNull { method ->
        method.parameterTypes.isEmpty() && method.returnType == bundleType
    } ?: throw PatchException(
        "FAIL-CLOSED: Could not find the no-argument accessor returning $bundleType on " +
            resolvedImeType,
    )

    val handleEvent = ime.methods.singleOrNull { method ->
        method.returnType == "Z" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString().startsWith("L") &&
            (method.implementation?.instructions?.count() ?: 0) > MIN_HANDLE_EVENT_INSTRUCTIONS &&
            classByType[method.parameterTypes[0].toString()]?.instanceFields?.any { field ->
                field.name == "b" && field.type.startsWith("[L")
            } == true
    } ?: throw PatchException(
        "FAIL-CLOSED: Could not find handleEvent(Event)Z on $resolvedImeType",
    )

    return AtMentionTargets(
        imeType = resolvedImeType,
        inputViewFieldName = resolvedInputViewField,
        bundleType = bundleType,
        textCandidatesUpdatedName = textCandidatesUpdated.name,
        handleEventName = handleEvent.name,
        handleEventParameterTypes = handleEvent.parameterTypes.map { it.toString() },
        requestCandidatesOwner = requestCandidatesOwner,
        requestCandidatesName = requestCandidatesName,
        imeActiveBundleMethodName = imeActiveBundleMethod.name,
        bundleWrapperFieldName = bundleWrapperField.name,
        wrapperControllerFieldName = wrapperControllerField.name,
        wrapperActiveMethodName = wrapperActiveMethod ?: DEFAULT_WRAPPER_ACTIVE_METHOD,
        controllerAppendMethodName = appendMethod.name,
        candidateClass = candidateType.toJavaType(),
        candidateBuilderClass = candidateBuilderType.toJavaType(),
    )
}

private fun BytecodePatchContext.installAtMentionCandidateHooks(targets: AtMentionTargets) {
    mutableClass(targets.bundleType).methods.single { method ->
        method.name == targets.textCandidatesUpdatedName &&
            method.parameterTypes.map { it.toString() } == listOf("Z") &&
            method.returnType == "V"
    }.installTextCandidatesUpdatedHook()

    mutableClass(targets.requestCandidatesOwner).methods.single { method ->
        method.name == targets.requestCandidatesName &&
            method.parameterTypes.map { it.toString() } == listOf("I", "Z") &&
            method.returnType == "V"
    }.installRequestCandidatesHook()

    mutableClass(targets.imeType).methods.single { method ->
        method.name == targets.handleEventName &&
            method.parameterTypes.map { it.toString() } == targets.handleEventParameterTypes &&
            method.returnType == "Z"
    }.installHandleEventHook()
}

private fun BytecodePatchContext.installAtMentionLifecycleHooks(targets: AtMentionTargets) {
    val ime = mutableClass(targets.imeType)

    val onStartInputView = ime.methods.single { method ->
        method.name == "onStartInputView" &&
            method.parameterTypes.map { it.toString() } == listOf(EDITOR_INFO_TYPE, "Z") &&
            method.returnType == "V"
    }
    val startCall = RuntimeCallId.AT_MENTION_RUNTIME_ON_INPUT_VIEW_STARTED
    val startInstructions = onStartInputView.instructionsOrThrow()
    val existingStartHooks = startInstructions.count { it.isMethodReference(startCall.reference()) }
    if (existingStartHooks > 0) {
        check(existingStartHooks == 1) {
            "Malformed @mention onStartInputView hook in ${targets.imeType}"
        }
    } else {
        var insertAt = 0
        while (insertAt < startInstructions.size &&
            (startInstructions[insertAt].opcode == Opcode.INVOKE_STATIC ||
                startInstructions[insertAt].opcode == Opcode.INVOKE_STATIC_RANGE)
        ) {
            insertAt++
        }
        onStartInputView.addInstructions(
            insertAt,
            RuntimeCallEmitter.invoke(startCall, "p0 .. p1"),
        )
    }

    ime.addHelperMethodIfMissing(
        name = SELECTION_HELPER_NAME,
        parameterTypes = listOf("I", "I"),
        returnType = "V",
        accessFlags = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
        registerCount = SELECTION_HELPER_REGISTER_COUNT,
        body = selectionHelperBody(targets),
    )

    val onUpdateSelection = ime.methods.single { method ->
        method.name == "onUpdateSelection" &&
            method.parameterTypes.map { it.toString() } == UPDATE_SELECTION_PARAMETER_TYPES &&
            method.returnType == "V"
    }
    val selectionRegisterCount = onUpdateSelection.implementation?.registerCount
        ?: throw PatchException("FAIL-CLOSED: ${targets.imeType}->onUpdateSelection has no body")
    if (selectionRegisterCount > MAX_SELECTION_REGISTER_COUNT) {
        throw PatchException(
            "FAIL-CLOSED: Unexpected register count $selectionRegisterCount in " +
                "${targets.imeType}->onUpdateSelection",
        )
    }
    val helperReference = "${targets.imeType}->$SELECTION_HELPER_NAME(II)V"
    val updateInstructions = onUpdateSelection.instructionsOrThrow()
    val existingSelectionHooks = updateInstructions.count { it.isMethodReference(helperReference) }
    if (existingSelectionHooks > 0) {
        check(existingSelectionHooks == 1) {
            "Malformed @mention onUpdateSelection hook in ${targets.imeType}"
        }
    } else {
        var insertAt = 0
        while (insertAt < updateInstructions.size &&
            (updateInstructions[insertAt].opcode == Opcode.INVOKE_DIRECT ||
                updateInstructions[insertAt].opcode == Opcode.INVOKE_DIRECT_RANGE)
        ) {
            insertAt++
        }
        onUpdateSelection.addInstructions(insertAt, "invoke-direct {p0, p3, p4}, $helperReference")
    }

    val onWindowHidden = ime.methods.single { method ->
        method.name == "onWindowHidden" &&
            method.parameterTypes.isEmpty() &&
            method.returnType == "V"
    }
    val hiddenCall = RuntimeCallId.AT_MENTION_RUNTIME_ON_INPUT_WINDOW_HIDDEN
    val hiddenInstructions = onWindowHidden.instructionsOrThrow()
    val existingHiddenHooks = hiddenInstructions.count { it.isMethodReference(hiddenCall.reference()) }
    if (existingHiddenHooks > 0) {
        check(existingHiddenHooks == 1) {
            "Malformed @mention onWindowHidden hook in ${targets.imeType}"
        }
    } else {
        onWindowHidden.addInstructions(0, RuntimeCallEmitter.invoke(hiddenCall, ""))
    }
}

private fun MutableMethod.installTextCandidatesUpdatedHook() {
    val call = RuntimeCallId.AT_MENTION_RUNTIME_ON_TEXT_CANDIDATES_UPDATED
    val instructions = instructionsOrThrow()
    val existing = instructions.count { it.isMethodReference(call.reference()) }
    if (existing > 0) {
        check(existing == 1) { "Malformed @mention textCandidatesUpdated hook in $definingClass->$name" }
        return
    }
    addInstructions(0, "${RuntimeCallEmitter.invoke(call, "p0, p1")}\nmove-result p1")
}

private fun MutableMethod.installRequestCandidatesHook() {
    val call = RuntimeCallId.AT_MENTION_RUNTIME_TRY_SUPPLY_NATIVE_CANDIDATES_FROM_DELEGATE
    val instructions = instructionsOrThrow()
    val existing = instructions.count { it.isMethodReference(call.reference()) }
    if (existing > 0) {
        check(existing == 1) { "Malformed @mention requestCandidates hook in $definingClass->$name" }
        return
    }
    val scratch = requireLocalScratch()
    addInstructionsWithLabels(
        0,
        """
        ${RuntimeCallEmitter.invoke(call, "p0, p1")}
        move-result $scratch
        if-eqz $scratch, :$STOCK_REQUEST_CANDIDATES_LABEL
        return-void
        """.trimIndent(),
        ExternalLabel(STOCK_REQUEST_CANDIDATES_LABEL, getInstruction(0)),
    )
}

private fun MutableMethod.installHandleEventHook() {
    val call = RuntimeCallId.AT_MENTION_RUNTIME_TRY_HANDLE_NATIVE_CANDIDATE_EVENT
    val instructions = instructionsOrThrow()
    val existing = instructions.count { it.isMethodReference(call.reference()) }
    if (existing > 0) {
        check(existing == 1) { "Malformed @mention handleEvent hook in $definingClass->$name" }
        return
    }
    val scratch = requireLocalScratch()
    addInstructionsWithLabels(
        0,
        """
        ${RuntimeCallEmitter.invoke(call, "p0 .. p1")}
        move-result $scratch
        if-eqz $scratch, :$STOCK_HANDLE_EVENT_LABEL
        const/4 $scratch, 0x1
        return $scratch
        """.trimIndent(),
        ExternalLabel(STOCK_HANDLE_EVENT_LABEL, getInstruction(0)),
    )
}

/**
 * Returns a register that is certainly local to [this].
 *
 * The hook result must never land in a parameter register: the stock branch jumps back to the
 * original first instruction and would read the clobbered argument.
 */
private fun MutableMethod.requireLocalScratch(): String {
    val implementation = implementation
        ?: throw PatchException("FAIL-CLOSED: $definingClass->$name has no implementation")
    val parameterWords = 1 + parameterTypes.sumOf { type ->
        if (type == "J" || type == "D") 2 else 1
    }
    if (implementation.registerCount <= parameterWords) {
        throw PatchException(
            "FAIL-CLOSED: $definingClass->$name has no local register to hold the " +
                "@mention hook result",
        )
    }
    return "v0"
}

private fun MutableMethod.instructionsOrThrow(): List<Instruction> =
    implementation?.instructions?.toList()
        ?: throw PatchException("FAIL-CLOSED: $definingClass->$name has no implementation")

private fun BytecodePatchContext.bindAtMentionConfig(targets: AtMentionTargets) {
    val config = mutableClass(AT_MENTION_CONFIG_TYPE)
    config.requireStaticField(FEATURE_FIELD, "Z")
    STRING_BINDING_FIELDS.forEach { (name, type) -> config.requireStaticField(name, type) }

    // D8 folds static constant initialisers into DEX static_values and may leave a 0-register
    // <clinit> (or none at all), so a fresh implementation is built instead of amended.
    config.methods.removeAll { it.name == "<clinit>" }
    val clinit = ImmutableMethod(
        config.type,
        "<clinit>",
        emptyList(),
        "V",
        AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable()
    clinit.addInstructions(0, atMentionConfigClinitBody(targets))
    config.methods.add(clinit)
}

private fun MutableClass.requireStaticField(name: String, type: String) {
    check(fields.any { field -> field.name == name && field.type == type }) {
        "FAIL-CLOSED: $this does not declare $name:$type; the bundled at-mention runtime " +
            "is out of sync with this patch"
    }
}

private fun atMentionConfigClinitBody(targets: AtMentionTargets): String {
    val bindings = listOf(
        "AT_MENTION_IME_ACTIVE_BUNDLE_METHOD" to targets.imeActiveBundleMethodName,
        "AT_MENTION_TEXT_CANDIDATES_UPDATED_METHOD" to targets.textCandidatesUpdatedName,
        "AT_MENTION_BUNDLE_WRAPPER_FIELD" to targets.bundleWrapperFieldName,
        "AT_MENTION_WRAPPER_CONTROLLER_FIELD" to targets.wrapperControllerFieldName,
        "AT_MENTION_WRAPPER_ACTIVE_METHOD" to targets.wrapperActiveMethodName,
        "AT_MENTION_CONTROLLER_APPEND_METHOD" to targets.controllerAppendMethodName,
        "AT_MENTION_CANDIDATE_CLASS" to targets.candidateClass,
        "AT_MENTION_CANDIDATE_BUILDER_CLASS" to targets.candidateBuilderClass,
    )
    return buildString {
        appendLine("const/4 v0, 0x1")
        appendLine("sput-boolean v0, $AT_MENTION_CONFIG_TYPE->$FEATURE_FIELD:Z")
        bindings.forEach { (field, value) ->
            appendLine("const-string v0, \"${escapeSmaliString(value)}\"")
            appendLine("sput-object v0, $AT_MENTION_CONFIG_TYPE->$field:Ljava/lang/String;")
        }
        append("return-void")
    }
}

private fun selectionHelperBody(targets: AtMentionTargets): String = """
    iget-object v0, p0, ${targets.imeType}->${targets.inputViewFieldName}:$INPUT_VIEW_TYPE

    ${RuntimeCallEmitter.invoke(
        RuntimeCallId.AT_MENTION_RUNTIME_ON_SELECTION_UPDATED,
        "p0, v0, p1, p2",
    )}

    return-void
""".trimIndent()

private fun ClassDef.isInputMethodHost(): Boolean =
    methods.any { method ->
        method.name == "onStartInputView" &&
            method.parameterTypes.map { it.toString() } == listOf(EDITOR_INFO_TYPE, "Z") &&
            method.returnType == "V"
    } &&
        methods.any { method ->
            method.name == "onUpdateSelection" &&
                method.parameterTypes.map { it.toString() } == UPDATE_SELECTION_PARAMETER_TYPES &&
                method.returnType == "V"
        } &&
        methods.any { method ->
            method.name == "onWindowHidden" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "V"
        }

/**
 * Returns the class's candidate-update entry point, or `null` when the class is not an InputBundle.
 *
 * The shape is the one Gboard has kept for this pipeline: a `(Z)V` that reads its own keyboard
 * wrapper, asks it whether candidates are showing, then forwards the flag to the candidate
 * controller. Resolving by shape rather than by a log string keeps the build honest: on 18.8 the
 * class carrying the `InputBundle.activate` tag is a different type, so the tagfinder silently
 * resolved the wrong class.
 */
private fun ClassDef.findTextCandidatesUpdated(
    classByType: Map<String, ClassDef>,
): String? = methods.firstNotNullOfOrNull { method ->
    if (method.parameterTypes.map { it.toString() } != listOf("Z") || method.returnType != "V") {
        return@firstNotNullOfOrNull null
    }
    val instructions = method.implementation?.instructions?.toList() ?: return@firstNotNullOfOrNull null
    val wrapperType = instructions.objectFieldsDefinedOn(type).firstOrNull()?.type
        ?: return@firstNotNullOfOrNull null
    val controllerType = instructions.objectFieldsDefinedOn(wrapperType).firstOrNull()?.type
        ?: return@firstNotNullOfOrNull null
    val showsCandidates = instructions.mapNotNull { instruction ->
        instruction.virtualMethodOn(wrapperType)
    }.any { reference -> reference.parameterTypes.isEmpty() && reference.returnType == "Z" }
    if (!showsCandidates) return@firstNotNullOfOrNull null
    val controllerDef = classByType[controllerType] ?: return@firstNotNullOfOrNull null
    val appendsCandidates = controllerDef.methods.any { controllerMethod ->
        controllerMethod.returnType == "V" &&
            controllerMethod.parameterTypes.size == 3 &&
            controllerMethod.parameterTypes[0].toString() == "Ljava/util/List;" &&
            controllerMethod.parameterTypes[1].toString().startsWith("L") &&
            controllerMethod.parameterTypes[2].toString() == "Z"
    }
    if (!appendsCandidates) return@firstNotNullOfOrNull null
    method.name
}

private fun List<Instruction>.objectFieldsDefinedOn(owner: String): List<FieldReference> =
    mapNotNull { instruction ->
        if (instruction.opcode == Opcode.IGET_OBJECT) {
            (instruction as? ReferenceInstruction)?.reference as? FieldReference
        } else {
            null
        }
    }.filter { reference -> reference.definingClass == owner }

private fun Instruction.virtualMethodOn(owner: String): MethodReference? {
    if (opcode != Opcode.INVOKE_VIRTUAL) return null
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
    return reference?.takeIf { it.definingClass == owner }
}

private fun RuntimeCallId.reference(): String = RuntimeAbiCatalog.abi(this).reference

private fun String.toJavaType(): String =
    removePrefix("L").removeSuffix(";").replace('/', '.')

private fun escapeSmaliString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private const val FEATURE_FIELD = "FEATURE_AT_MENTION_SUGGESTIONS"
private const val STOCK_REQUEST_CANDIDATES_LABEL = "gboardPatchesAtMentionStockRequestCandidates"
private const val STOCK_HANDLE_EVENT_LABEL = "gboardPatchesAtMentionStockHandleEvent"
private const val DEFAULT_WRAPPER_ACTIVE_METHOD = "p"
private val UPDATE_SELECTION_PARAMETER_TYPES = listOf("I", "I", "I", "I", "I", "I")
private val STRING_BINDING_FIELDS = listOf(
    "AT_MENTION_IME_ACTIVE_BUNDLE_METHOD",
    "AT_MENTION_TEXT_CANDIDATES_UPDATED_METHOD",
    "AT_MENTION_BUNDLE_WRAPPER_FIELD",
    "AT_MENTION_WRAPPER_CONTROLLER_FIELD",
    "AT_MENTION_WRAPPER_ACTIVE_METHOD",
    "AT_MENTION_CONTROLLER_APPEND_METHOD",
    "AT_MENTION_CANDIDATE_CLASS",
    "AT_MENTION_CANDIDATE_BUILDER_CLASS",
).map { it to "Ljava/lang/String;" }
