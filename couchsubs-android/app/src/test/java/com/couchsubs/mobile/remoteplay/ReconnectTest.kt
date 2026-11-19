// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    /** PS5 oturumu birkaç saniyede düşürüyorsa aralık sıfırlanmaz; uzun süren oturumdan sonra baştan başlar (Mac ve Windows ile aynı). */
    @Test
    fun shortSessionsKeepBackingOff() {
        val policy = ReconnectPolicy()
        assertEquals(2, policy.nextDelaySeconds())
        policy.connected(10_000)
        policy.ended(14_000)
        assertEquals(4, policy.nextDelaySeconds())
        policy.connected(20_000)
        policy.ended(20_000 + ReconnectPolicy.STABLE_AFTER_MS)
        assertEquals(2, policy.nextDelaySeconds())
    }

    @Test
    fun inUseWaitsAtLeastEightSeconds() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(8, 16, 30), List(3) { policy.nextDelaySeconds(inUse = true) })
    }

    /** Kablosuz ağda akış sık kopuyordu (GitHub #388, #401): on dakikada iki kopmadan sonra 720p istenir. */
    @Test
    fun twoDropsWithinWindowLowerTo720p() {
        val quality = QualityFallback()
        assertFalse(quality.dropped(0))
        assertFalse(quality.lowered)
        assertTrue(quality.dropped(120_000))
        assertTrue(quality.lowered)
        assertFalse(quality.dropped(130_000))
        assertTrue(quality.lowered)
    }

    @Test
    fun dropsFarApartKeep1080p() {
        val quality = QualityFallback()
        assertFalse(quality.dropped(0))
        assertFalse(quality.dropped(QualityFallback.WINDOW_MS + 1))
        assertFalse(quality.dropped(2 * QualityFallback.WINDOW_MS + 2))
        assertFalse(quality.lowered)
    }

    @Test
    fun onlyNetworkLikeDropsCount() {
        assertTrue(QualityFallback.countsAsDrop(DisconnectKind.NETWORK))
        assertTrue(QualityFallback.countsAsDrop(DisconnectKind.OTHER))
        for (kind in listOf(DisconnectKind.IN_USE, DisconnectKind.REJECTED, DisconnectKind.REGISTRATION_INVALID, DisconnectKind.STOPPED))
            assertFalse(QualityFallback.countsAsDrop(kind))
    }

    /** 0x80108b26: PS5 bu cihazın eşlemesini bulamadı (yeniden eşlenmeli); 0x80108b12: PS5'te Uzaktan Oynatma kapalı (GitHub #9). */
    @Test
    fun knownApplicationReasonsAreClassified() {
        assertEquals(DisconnectKind.REGISTRATION_INVALID,
            DisconnectKind.sessionRequestHint("Reported Application Reason: 0x80108b26 (unknown)")?.kind)
        assertEquals(SessionState.Disconnected(DisconnectKind.REJECTED, DisconnectKind.REMOTE_PLAY_DISABLED),
            DisconnectKind.sessionRequestHint("Reported Application Reason: 0x80108b12 (unknown)"))
    }
}
