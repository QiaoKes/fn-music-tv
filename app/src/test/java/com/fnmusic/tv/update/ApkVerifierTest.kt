package com.fnmusic.tv.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {
    @Test fun `signer comparison requires equal non-empty signer sets`() {
        assertTrue(signerSetsMatch(setOf("official"), setOf("official")))
        assertFalse(signerSetsMatch(emptySet(), emptySet()))
        assertFalse(signerSetsMatch(setOf("candidate"), setOf("installed")))
        assertFalse(signerSetsMatch(setOf("candidate"), emptySet()))
    }
}
