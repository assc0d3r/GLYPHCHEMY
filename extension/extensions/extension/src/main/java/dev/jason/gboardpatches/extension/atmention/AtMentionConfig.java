package dev.jason.gboardpatches.extension.atmention;

/**
 * Patch-time activation flag and dynamically fingerprinted Gboard bindings for the
 * @mention / email-domain suggestion strip.
 *
 * <p>The defaults below describe Gboard 18.5.4. The patch rewrites this class's
 * {@code <clinit>} with the exact method, field and class names found in the target
 * APK, and sets {@code FEATURE_AT_MENTION_SUGGESTIONS} to {@code true} only when the
 * user selected the patch, so the runtime stays inert otherwise.</p>
 */
public final class AtMentionConfig {
    public static boolean FEATURE_AT_MENTION_SUGGESTIONS = false;

    public static String AT_MENTION_IME_ACTIVE_BUNDLE_METHOD = "ah";
    public static String AT_MENTION_TEXT_CANDIDATES_UPDATED_METHOD = "K";
    public static String AT_MENTION_BUNDLE_WRAPPER_FIELD = "k";
    public static String AT_MENTION_WRAPPER_CONTROLLER_FIELD = "s";
    public static String AT_MENTION_WRAPPER_ACTIVE_METHOD = "p";
    public static String AT_MENTION_CONTROLLER_APPEND_METHOD = "c";
    public static String AT_MENTION_CANDIDATE_CLASS = "owu";
    public static String AT_MENTION_CANDIDATE_BUILDER_CLASS = "owr";

    private AtMentionConfig() {
    }
}
