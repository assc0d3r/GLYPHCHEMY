package dev.jason.gboardpatches.extension.atmention;

import android.content.Context;
import android.content.SharedPreferences;

/** Preference access shared by the at-mention store and its settings page. */
public final class AtMentionPrefs {
    public static final String PREF_FILE = "gboard_at_mention_prefs";

    private AtMentionPrefs() {
    }

    public static SharedPreferences preferencesOrNull(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        Context base = app != null ? app : context;
        try {
            return base.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        } catch (IllegalStateException directBootLocked) {
            try {
                return base.createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
