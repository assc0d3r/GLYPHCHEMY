package dev.jason.gboardpatches.patches.gboard.features.customtheme

import app.morphe.patcher.patch.BytecodePatchContext
import dev.jason.gboardpatches.patches.gboard.shared.GboardFieldTarget
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.generated.GboardVersionBindings
import dev.jason.gboardpatches.patches.gboard.shared.selectReleaseTargets

/**
 * The members the Custom Theme import binds to, split by release.
 *
 * 18.8.0 renames the whole theme-listing cluster and moves the plus handler from one enum of list
 * sections to another, but every shape the patch asserts survives:
 *
 * - the plus handler is still a `public final` six-register enum method taking three parameters, and
 *   it still launches `ThemeBuilderActivity` through the presenter's launcher (`Lvku;->b`), which is
 *   how it is told apart from the neighbouring enum methods;
 * - the activity-result handler is still the fragment's five-register `(II,Intent)V`;
 * - the presenter still carries the `Context`, the launcher and `j(Intent)V`, and the fragment still
 *   holds it in one public field;
 * - the archive validator is still the theme-file reader's `public static (File)Z`.
 */
internal data class CustomThemeTargets(
    val plusHandler: GboardMethodTarget,
    val resultHandler: GboardMethodTarget,
    val peerResultHandler: GboardMethodTarget,
    val launcherLaunch: GboardMethodTarget,
    val validator: GboardMethodTarget,
    val fragmentPeerField: GboardFieldTarget,
    val peerContextField: GboardFieldTarget,
    val peerLauncherField: GboardFieldTarget,
    val plusStockRegisters: Int,
    val resultStockRegisters: Int,
    val plusFingerprint: String,
    val resultFingerprint: String,
    /** The release this row was audited against, for the diagnostics the patch prints. */
    val release: String,
)

internal fun customThemeTargets(context: BytecodePatchContext): CustomThemeTargets =
    selectReleaseTargets(
        context = context,
        projection = ::customThemeProjectionTargets,
        probe = CustomThemeTargets::plusHandler,
        audited = CUSTOM_THEME_18_8,
    )

private val customThemeProjectionTargets by lazy {
    CustomThemeTargets(
        plusHandler = GboardVersionBindings.customThemePlusHandler,
        resultHandler = GboardVersionBindings.customThemeResultHandler,
        peerResultHandler = GboardVersionBindings.customThemePeerResultHandler,
        launcherLaunch = GboardVersionBindings.customThemeLauncherLaunch,
        validator = GboardVersionBindings.customThemeValidator,
        fragmentPeerField = GboardVersionBindings.customThemeFragmentPeerField,
        peerContextField = GboardVersionBindings.customThemePeerContextField,
        peerLauncherField = GboardVersionBindings.customThemePeerLauncherField,
        plusStockRegisters = CUSTOM_THEME_PLUS_STOCK_REGISTERS,
        resultStockRegisters = CUSTOM_THEME_RESULT_STOCK_REGISTERS,
        plusFingerprint = CUSTOM_THEME_PLUS_1863_FINGERPRINT,
        resultFingerprint = CUSTOM_THEME_RESULT_1863_FINGERPRINT,
        release = GboardVersionBindings.targetVersion,
    )
}

/**
 * 18.8.0, verified against the release APK.
 *
 * `Lvmj;` is the section enum that reaches the theme builder: its `c(Lvmh;Lvmn;I)V` reads the
 * presenter out of its first argument (`check-cast v3, Lvmb;`), then builds the
 * `ThemeBuilderActivity` intent and calls `Lvmb;->t:Lvku;`'s `b(Intent;I;Bundle)V` with request code
 * 101 or 102. `Lajgb;` is the archive reader that yields a theme (`e(Context,File)` →
 * `Lajgb;`, `f(File,List)` → a colour map), so its `h(File)Z` is the validator.
 */
private val CUSTOM_THEME_18_8 = CustomThemeTargets(
    plusHandler = GboardMethodTarget(
        classType = "Lvmj;",
        name = "c",
        parameterTypes = listOf("Lvmh;", "Lvmn;", "I"),
        returnType = "V",
    ),
    resultHandler = GboardMethodTarget(
        classType = "Lcom/google/android/apps/inputmethod/libs/theme/listing/ThemeListingFragment;",
        name = "X",
        parameterTypes = listOf("I", "I", "Landroid/content/Intent;"),
        returnType = "V",
    ),
    peerResultHandler = GboardMethodTarget(
        classType = "Lvmb;",
        name = "j",
        parameterTypes = listOf("Landroid/content/Intent;"),
        returnType = "V",
    ),
    launcherLaunch = GboardMethodTarget(
        classType = "Lvku;",
        name = "b",
        parameterTypes = listOf("Landroid/content/Intent;", "I", "Landroid/os/Bundle;"),
        returnType = "V",
    ),
    validator = GboardMethodTarget(
        classType = "Lajgb;",
        name = "h",
        parameterTypes = listOf("Ljava/io/File;"),
        returnType = "Z",
    ),
    fragmentPeerField = GboardFieldTarget(
        classType = "Lcom/google/android/apps/inputmethod/libs/theme/listing/ThemeListingFragment;",
        name = "a",
        type = "Lvmb;",
    ),
    peerContextField = GboardFieldTarget(
        classType = "Lvmb;",
        name = "b",
        type = "Landroid/content/Context;",
    ),
    peerLauncherField = GboardFieldTarget(
        classType = "Lvmb;",
        name = "t",
        type = "Lvku;",
    ),
    plusStockRegisters = 6,
    resultStockRegisters = 5,
    plusFingerprint = "4a43629ebfda38c5d74e14116833f177c261c890b3231a4f56192f87eb557739",
    resultFingerprint = "113d1137ff039d25b4e1a925a3513a8ef8fe7d588f45243c9a6317884e3b9324",
    release = "18.8.0",
)
