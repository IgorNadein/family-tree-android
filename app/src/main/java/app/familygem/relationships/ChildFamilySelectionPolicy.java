package app.familygem.relationships;

/**
 * Do not silently select an earlier marriage when the parent has more than one
 * family. A contextual family is allowed only when it actually belongs to the parent.
 */
public final class ChildFamilySelectionPolicy {
    private ChildFamilySelectionPolicy() {}

    public static boolean requiresChoice(int spouseFamilyCount, boolean hasValidPreferredFamily) {
        return spouseFamilyCount > 1 && !hasValidPreferredFamily;
    }
}
