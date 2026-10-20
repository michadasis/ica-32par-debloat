package com.cleanhome.tv;

import java.util.Calendar;

/**
 * Tiny two-language string table (Greek / English), toggled at runtime.
 * No resource qualifiers so we can flip language instantly without recreating.
 */
public class L {
    public static boolean greek = true; // default Greek (TV locale is el_GR)

    public static String t(String key) {
        switch (key) {
            case "inputs":   return greek ? "Είσοδοι"    : "Inputs";
            case "network":  return greek ? "Δίκτυο"      : "Network";
            case "settings": return greek ? "Ρυθμίσεις"   : "Settings";
            case "ethernet": return greek ? "Ενσύρματο"   : "Ethernet";
            case "wifi":     return greek ? "Wi-Fi"       : "Wi-Fi";
            case "offline":  return greek ? "Χωρίς δίκτυο" : "Offline";
            case "livetv":   return greek ? "Τηλεόραση"   : "Live TV";
            case "av":       return greek ? "AV"          : "AV";
            case "apps":     return greek ? "Εφαρμογές"   : "Apps";
            case "lang":     return greek ? "EL"          : "EN";
        }
        return key;
    }

    /** Time-based greeting in the selected language. */
    public static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (greek) {
            if (h < 5)  return "Καλό βράδυ";
            if (h < 12) return "Καλημέρα";
            if (h < 18) return "Καλό απόγευμα";
            return "Καλησπέρα";
        } else {
            if (h < 5)  return "Good night";
            if (h < 12) return "Good morning";
            if (h < 18) return "Good afternoon";
            return "Good evening";
        }
    }
}
