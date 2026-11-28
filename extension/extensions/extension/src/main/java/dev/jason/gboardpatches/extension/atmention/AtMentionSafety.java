package dev.jason.gboardpatches.extension.atmention;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Decides whether a field must never see learned suggestions or learned handles. */
public final class AtMentionSafety {
    private AtMentionSafety() {
    }

    public static boolean isSensitiveOrIncognitoField(EditorInfo editorInfo) {
        if (editorInfo == null) {
            return false;
        }
        if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            return true;
        }
        int inputType = editorInfo.inputType;
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }
}
