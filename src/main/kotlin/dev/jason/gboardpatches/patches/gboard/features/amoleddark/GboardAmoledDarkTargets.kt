package dev.jason.gboardpatches.patches.gboard.features.amoleddark

import app.morphe.patcher.patch.BytecodePatchContext
import dev.jason.gboardpatches.patches.gboard.shared.GboardFieldTarget
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.generated.GboardVersionBindings
import dev.jason.gboardpatches.patches.gboard.shared.selectReleaseTargets

/**
 * The six members the AMOLED palette rewrite binds to.
 *
 * 18.8.0 builds the same palette from a new class: `Lajeb;` still takes `(Context, boolean)`, still
 * writes the is-light flag to its first field, and still stores every `color_gm3_*` token into an
 * `int` field of its own. The four entries the patch blacks out are the ones whose tokens are
 * `color_gm3_surface_dim`, `color_gm3_surface_container`, `..._container_low` and
 * `..._container_lowest`, which is what pins `P`, `Q`, `R` and `S`:
 *
 *     Lajeb;->a()Lajmv;   iget v1, v3, Lajeb;->P:I / const-string "color_gm3_surface_dim"
 *                         iget v1, v3, Lajeb;->Q:I / const-string "color_gm3_surface_container"
 *                         iget v1, v3, Lajeb;->R:I / const-string "color_gm3_surface_container_low"
 *                         iget v1, v3, Lajeb;->S:I / const-string "color_gm3_surface_container_lowest"
 *
 * The letters happen to match 18.6.3 because the constructor feeds the same token table in the same
 * order; the class and the tokens are the evidence, not the letters.
 */
internal data class AmoledDarkTargets(
    val paletteConstructor: GboardMethodTarget,
    val isLight: GboardFieldTarget,
    val surfaceDim: GboardFieldTarget,
    val surfaceContainer: GboardFieldTarget,
    val surfaceContainerLow: GboardFieldTarget,
    val surfaceContainerLowest: GboardFieldTarget,
) {
    /** The four surface fields, in the order the patch overwrites them. */
    val surfaceFields: List<GboardFieldTarget>
        get() = listOf(surfaceDim, surfaceContainer, surfaceContainerLow, surfaceContainerLowest)
}

internal fun amoledDarkTargets(context: BytecodePatchContext): AmoledDarkTargets =
    selectReleaseTargets(
        context = context,
        projection = ::amoledDarkProjectionTargets,
        probe = AmoledDarkTargets::paletteConstructor,
        audited = AMOLED_DARK_18_8,
    )

private val amoledDarkProjectionTargets by lazy {
    AmoledDarkTargets(
        paletteConstructor = GboardVersionBindings.dynamicDarkPaletteConstructor,
        isLight = GboardVersionBindings.dynamicDarkPaletteIsLightField,
        surfaceDim = GboardVersionBindings.dynamicDarkPaletteSurfaceDimField,
        surfaceContainer = GboardVersionBindings.dynamicDarkPaletteSurfaceContainerField,
        surfaceContainerLow = GboardVersionBindings.dynamicDarkPaletteSurfaceContainerLowField,
        surfaceContainerLowest = GboardVersionBindings.dynamicDarkPaletteSurfaceContainerLowestField,
    )
}

private val AMOLED_DARK_18_8 = AmoledDarkTargets(
    paletteConstructor = GboardMethodTarget(
        classType = "Lajeb;",
        name = "<init>",
        parameterTypes = listOf("Landroid/content/Context;", "Z"),
        returnType = "V",
    ),
    isLight = GboardFieldTarget(classType = "Lajeb;", name = "a", type = "Z"),
    surfaceDim = GboardFieldTarget(classType = "Lajeb;", name = "P", type = "I"),
    surfaceContainer = GboardFieldTarget(classType = "Lajeb;", name = "Q", type = "I"),
    surfaceContainerLow = GboardFieldTarget(classType = "Lajeb;", name = "R", type = "I"),
    surfaceContainerLowest = GboardFieldTarget(classType = "Lajeb;", name = "S", type = "I"),
)
