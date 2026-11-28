package com.maestrovpn.client.core.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDataFilesTest {

    @Test
    fun `config with geosite rule needs geo data`() {
        val config = """{"routing":{"rules":[{"domain":["geosite:ru"],"outboundTag":"direct"}]}}"""
        assertTrue(GeoDataFiles.configNeedsGeo(config))
    }

    @Test
    fun `config with geoip rule needs geo data`() {
        assertTrue(GeoDataFiles.configNeedsGeo("""{"routing":{"rules":[{"ip":["geoip:cn"]}]}}"""))
    }

    @Test
    fun `plain config does not need geo data`() {
        assertFalse(GeoDataFiles.configNeedsGeo("""{"outbounds":[{"protocol":"freedom"}]}"""))
    }
}
