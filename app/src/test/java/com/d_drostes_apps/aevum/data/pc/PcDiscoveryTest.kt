package com.d_drostes_apps.aevum.data.pc

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Discovery-Parsing: Nur echte Aevum-Announce-Pakete werden akzeptiert,
 * fremder UDP-Verkehr im Netz wird ignoriert. (Der Broadcast selbst wird
 * nicht getestet — dafür gibt es den Ende-zu-Ende-Test am echten PC.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PcDiscoveryTest {

    private val discovery = PcDiscovery()

    @Test
    fun `gueltiges Announce wird geparst`() {
        val json = """
            {"magic":"AEVUM-PC-ANNOUNCE","v":1,"deviceId":"dev-1",
             "name":"Devons-PC","apiPort":47825,"pairingActive":true}
        """.trimIndent()

        val pc = discovery.parseAnnounce(json, "192.168.178.42")

        assertThat(pc).isNotNull()
        assertThat(pc!!.deviceId).isEqualTo("dev-1")
        assertThat(pc.name).isEqualTo("Devons-PC")
        assertThat(pc.host).isEqualTo("192.168.178.42")
        assertThat(pc.port).isEqualTo(47825)
        assertThat(pc.pairingActive).isTrue()
    }

    @Test
    fun `fremdes Magic wird ignoriert`() {
        val json = """{"magic":"SOME-OTHER-SERVICE","v":1,"deviceId":"x","apiPort":1234}"""
        assertThat(discovery.parseAnnounce(json, "10.0.0.5")).isNull()
    }

    @Test
    fun `kaputtes JSON wird ignoriert`() {
        assertThat(discovery.parseAnnounce("not json at all", "10.0.0.5")).isNull()
    }

    @Test
    fun `fehlender Port wird ignoriert`() {
        val json = """{"magic":"AEVUM-PC-ANNOUNCE","v":1,"deviceId":"dev-1","name":"PC"}"""
        assertThat(discovery.parseAnnounce(json, "10.0.0.5")).isNull()
    }

    @Test
    fun `leerer Name faellt auf die Host-Adresse zurueck`() {
        val json = """{"magic":"AEVUM-PC-ANNOUNCE","v":1,"deviceId":"dev-2","name":"","apiPort":47825}"""
        val pc = discovery.parseAnnounce(json, "10.0.0.7")
        assertThat(pc!!.name).isEqualTo("10.0.0.7")
    }
}
