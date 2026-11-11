package app.familygem.relationships

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildFamilySelectionPolicyTest {
    @Test fun newParentAndOnlyOneFamilyDoNotRequireAnAmbiguousChoice() {
        assertFalse(ChildFamilySelectionPolicy.requiresChoice(0, false))
        assertFalse(ChildFamilySelectionPolicy.requiresChoice(1, false))
    }

    @Test fun twoFamiliesRequireExplicitParentsUnlessTheFamilyWasAlreadySelected() {
        assertTrue(ChildFamilySelectionPolicy.requiresChoice(2, false))
        assertTrue(ChildFamilySelectionPolicy.requiresChoice(3, false))
        assertFalse(ChildFamilySelectionPolicy.requiresChoice(2, true))
    }
}
