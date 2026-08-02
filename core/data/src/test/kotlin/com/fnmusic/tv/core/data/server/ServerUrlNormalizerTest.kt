package com.fnmusic.tv.core.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlNormalizerTest {
    @Test fun `normalizes host web root and api base`() {
        val ip = ServerUrlNormalizer.normalize("192.0.2.10", false) as ServerUrlResult.Valid
        assertEquals("http://192.0.2.10:5666/music/api/v1/", ip.server.apiBase.toString())
        val host = ServerUrlNormalizer.normalize("nas.local:5666", false) as ServerUrlResult.Valid
        assertEquals("http://nas.local:5666/music/api/v1/", host.server.apiBase.toString())
        val explicitWeb = ServerUrlNormalizer.normalize("http://nas.local", true) as ServerUrlResult.Valid
        assertEquals("http://nas.local/music/api/v1/", explicitWeb.server.apiBase.toString())
        val web = ServerUrlNormalizer.normalize("https://nas.local/music/", false) as ServerUrlResult.Valid
        assertEquals("https://nas.local/music/api/v1/", web.server.apiBase.toString())
        assertTrue(web.server.useHttps)
        val toggledHttps = ServerUrlNormalizer.normalize("nas.dqchub.top", true) as ServerUrlResult.Valid
        assertEquals("https://nas.dqchub.top/music/api/v1/", toggledHttps.server.apiBase.toString())
        val customPort = ServerUrlNormalizer.normalize("nas.local:7443", true) as ServerUrlResult.Valid
        assertEquals("https://nas.local:7443/music/api/v1/", customPort.server.apiBase.toString())
        val explicitHttpPort = ServerUrlNormalizer.normalize("http://nas.local:80", false) as ServerUrlResult.Valid
        assertEquals("http://nas.local/music/api/v1/", explicitHttpPort.server.apiBase.toString())
    }

    @Test fun `rejects credentials and unsupported schemes`() {
        assertTrue(ServerUrlNormalizer.normalize("http://user:pass@nas.local", false) is ServerUrlResult.Invalid)
        assertTrue(ServerUrlNormalizer.normalize("ftp://nas.local", false) is ServerUrlResult.Invalid)
    }

    @Test fun `rejects query fragment and invalid host`() {
        assertEquals(
            ServerUrlResult.Reason.QueryOrFragment,
            (ServerUrlNormalizer.normalize("nas.local?token=secret", false) as ServerUrlResult.Invalid).reason,
        )
        assertEquals(
            ServerUrlResult.Reason.QueryOrFragment,
            (ServerUrlNormalizer.normalize("https://nas.local/#music", false) as ServerUrlResult.Invalid).reason,
        )
        assertEquals(
            ServerUrlResult.Reason.InvalidHost,
            (ServerUrlNormalizer.normalize("http://", false) as ServerUrlResult.Invalid).reason,
        )
    }

    @Test fun `normalizes standard and custom paths without losing explicit ports`() {
        val api = ServerUrlNormalizer.normalize("nas.local:80/music/api/v1/", false) as ServerUrlResult.Valid
        assertEquals("http://nas.local/music/api/v1/", api.server.apiBase.toString())
        assertEquals(80, api.server.origin.port)
        assertEquals("http://nas.local:80/music/api/v1/", api.server.persistentApiBase())
        assertEquals(
            EditableServerInput("nas.local:80", false),
            ServerUrlNormalizer.editableInput(api.server.persistentApiBase(), true),
        )

        val prefixed = ServerUrlNormalizer.normalize("https://nas.local:7443/fn", false) as ServerUrlResult.Valid
        assertEquals("https://nas.local:7443/fn/music/api/v1/", prefixed.server.apiBase.toString())
    }

    @Test fun `turns pasted and saved urls into switch controlled editable input`() {
        assertEquals(
            EditableServerInput("nas.local", true),
            ServerUrlNormalizer.editableInput("https://nas.local/music/api/v1/", false),
        )
        assertEquals(
            EditableServerInput("nas.local", true),
            ServerUrlNormalizer.editableInput("https://nas.local:443/music/api/v1/", false),
        )
        assertEquals(
            EditableServerInput("nas.local", false),
            ServerUrlNormalizer.editableInput("http://nas.local:5666/music/", true),
        )
        assertEquals(
            EditableServerInput("nas.local:80", false),
            ServerUrlNormalizer.editableInput("http://nas.local:80/music/api/v1/", true),
        )
        assertEquals(
            EditableServerInput("nas.local:7443", true),
            ServerUrlNormalizer.editableInput("https://nas.local:7443/music/api/v1/", false),
        )
    }
}
