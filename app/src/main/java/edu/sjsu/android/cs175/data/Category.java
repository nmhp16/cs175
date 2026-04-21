package edu.sjsu.android.cs175.data;

import androidx.annotation.NonNull;

public enum Category {
    LEASE("Lease"),
    MEDICAL("Medical"),
    WARRANTY("Warranty"),
    INSURANCE("Insurance"),
    TAX("Tax"),
    BILL("Bill"),
    CONTRACT("Contract"),
    OTHER("Other");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public static Category fromName(String name) {
        if (name == null) return OTHER;
        for (Category c : values()) {
            if (c.name().equalsIgnoreCase(name)) return c;
        }
        return OTHER;
    }
}
