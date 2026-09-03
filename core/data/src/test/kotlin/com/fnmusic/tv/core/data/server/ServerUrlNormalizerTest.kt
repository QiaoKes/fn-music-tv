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
        val toggledHttps = ServerUrlNormalizer.normalize("nas.example.com", true) as ServerUrlResult.Valid
        assertEquals("https://nas.example.com/music/api/v1/", toggledHttps.server.apiBase.toString())
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

    @Test fun `supports five digit explicit ports`() {
        assertEquals(
            "http://nas.local:12345/music/api/v1/",
            (ServerUrlNormalizer.normalize("nas.local:12345", false) as ServerUrlResult.Valid)
                .server.apiBase.toString(),
        )
    }

    @Test fun `normalizes ipv4 ipv6 and domain combinations`() {
        val cases = listOf(
            Triple(
                "192.168.1.20",
                false,
                "http://192.168.1.20:5666/music/api/v1/",
            ),
            Triple(
                "192.168.1.20:12345",
                false,
                "http://192.168.1.20:12345/music/api/v1/",
            ),
            Triple(
                "https://192.168.1.20",
                false,
                "https://192.168.1.20/music/api/v1/",
            ),
            Triple(
                "https://192.168.1.20:5443",
                false,
                "https://192.168.1.20:5443/music/api/v1/",
            ),
            Triple(
                "http://nas.example.com",
                true,
                "http://nas.example.com/music/api/v1/",
            ),
            Triple(
                "nas.example.com:8080",
                false,
                "http://nas.example.com:8080/music/api/v1/",
            ),
            Triple(
                "nas.example.com",
                true,
                "https://nas.example.com/music/api/v1/",
            ),
            Triple(
                "nas.example.com:5443",
                true,
                "https://nas.example.com:5443/music/api/v1/",
            ),
            Triple(
                "[2001:db8::20]",
                false,
                "http://[2001:db8::20]:5666/music/api/v1/",
            ),
            Triple(
                "[2001:db8::20]:12345",
                false,
                "http://[2001:db8::20]:12345/music/api/v1/",
            ),
            Triple(
                "https://[2001:db8::20]",
                false,
                "https://[2001:db8::20]/music/api/v1/",
            ),
            Triple(
                "https://[2001:db8::20]:5443",
                false,
                "https://[2001:db8::20]:5443/music/api/v1/",
            ),
        )

        cases.forEach { (input, useHttps, expectedApiBase) ->
            val result = ServerUrlNormalizer.normalize(input, useHttps) as? ServerUrlResult.Valid
            assertEquals("input=$input useHttps=$useHttps", expectedApiBase, result?.server?.apiBase.toString())
            val expectedHttps = when {
                input.startsWith("https://", ignoreCase = true) -> true
                input.startsWith("http://", ignoreCase = true) -> false
                else -> useHttps
            }
            assertEquals("input=$input useHttps=$useHttps", expectedHttps, result?.server?.useHttps)
        }
    }

    @Test fun `preserves ipv6 brackets and explicit port in persistent address`() {
        val normalized = ServerUrlNormalizer.normalize("https://[2001:db8::30]:5443", false)
            as ServerUrlResult.Valid

        assertEquals(
            "https://[2001:db8::30]:5443/music/api/v1/",
            normalized.server.persistentApiBase(),
        )
        assertEquals(
            EditableServerInput("[2001:db8::30]:5443", true),
            ServerUrlNormalizer.editableInput(normalized.server.persistentApiBase(), false),
        )
    }
}
