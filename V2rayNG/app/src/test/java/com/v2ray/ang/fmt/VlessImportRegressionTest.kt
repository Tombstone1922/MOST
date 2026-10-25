package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI

class VlessImportRegressionTest {
    @Test fun queryPreservesPaddingFlagsAndEmptyValues() {
        assertEquals(mapOf("pbk" to "abc==", "authority" to "", "flag" to "", "serviceName" to "abc"),
            FmtBase().getQueryParam(URI("vless://id@example.test:443?pbk=abc==&authority=&flag&serviceName=abc&")))
        assertTrue(FmtBase().getQueryParam(URI("vless://id@example.test:443")).isEmpty())
    }
    @Test fun baseVlessDoesNotNeedAQuery() {
        val p = VlessFmt.parse("vless://id@example.test:443#Name")
        assertNotNull(p);assertEquals("id", p?.password);assertEquals("tcp", p?.network)
        assertNull(VlessFmt.parse("vless://id@example.test:0"))
    }
    @Test fun realityTransportsKeepTheirOwnParameters() {
        for (type in listOf("tcp", "grpc", "xhttp")) {
            val p = VlessFmt.parse("vless://id@example.test:443?type=$type&security=reality&authority=&pbk=abc==&fp=firefox&sid=ab&sni=cover.test&serviceName=bridge&path=%2Fpath&mode=auto&flag&")!!
            assertEquals(type,p.network);assertEquals("abc==",p.publicKey);assertEquals("cover.test",p.sni)
            assertEquals("bridge",p.serviceName);assertEquals("/path",p.path);assertEquals("auto",p.xhttpMode)
        }
    }
}
