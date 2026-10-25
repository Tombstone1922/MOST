package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImportTextParserTest {
    private val tcp = "vless://11111111-1111-4111-8111-111111111111@entry.example.test:443?type=tcp&security=reality&pbk=key&sid=ab#NL"
    private val grpc = tcp.replace("type=tcp", "type=grpc&serviceName=service").replace("#NL", "#DE")
    private val xhttp = tcp.replace("type=tcp", "type=xhttp&path=%2Fbridge&mode=auto").replace("#NL", "#PL")

    @Test fun emptyAndUnsupported() {
        assertEquals(ImportTextIssue.EMPTY, ImportTextParser.parse(" \n\u200B").issue)
        assertEquals(ImportTextIssue.UNSUPPORTED, ImportTextParser.parse("happ://secret").issue)
        assertFalse(ImportTextParser.parse("").ready)
    }
    @Test fun allTransportsAndHeadings() {
        val p = ImportTextParser.parse("Германия:\n$tcp\n$grpc\nПольша:\n$xhttp")
        assertTrue(p.ready); assertEquals(listOf(tcp, grpc, xhttp), p.profiles)
    }
    @Test fun markdownQuotesAndInvisibleCopyCharacters() {
        val p = ImportTextParser.parse("[NL]($tcp)\n<$grpc>\n`\uFEFF${xhttp.replace("vless", "vl\u200Bess")}`")
        assertEquals(listOf(tcp, grpc, xhttp), p.profiles)
    }
    @Test fun multipleLinksOnOneLine() {
        assertEquals(3, ImportTextParser.parse("$tcp $grpc $xhttp").profiles.size)
    }
    @Test fun duplicatesIgnoreNamesAndQueryOrder() {
        val reordered = tcp.substringBefore('?') + "?sid=ab&pbk=key&security=reality&type=tcp#Renamed"
        val p = ImportTextParser.parse("$tcp\n$reordered\n$tcp")
        assertEquals(listOf(tcp), p.profiles);assertEquals(2, p.duplicates)
    }
    @Test fun literalSpacesInNamesArePreservedAsEscapes() {
        assertTrue(ImportTextParser.parse(tcp.replace("#NL", "#Москва → NL")).profiles.single().endsWith("#Москва%20→%20NL"))
    }
    @Test fun httpConsentAndDuplicateSubscription() {
        val p = ImportTextParser.parse("http://example.test/sub\nhttp://example.test/sub")
        assertTrue(p.needsHttpConsent);assertEquals(1, p.duplicates)
        assertFalse(ImportTextParser.parse("HTTPS://example.test/sub").needsHttpConsent)
        assertEquals("https://example.test/sub", ImportTextParser.parse("HTTPS://example.test/sub").subscription)
    }
    @Test fun malformedLinksNeverPartiallyImport() {
        for (bad in listOf("vless://@example.test:443", "vless://id@example.test:0", "vless://id@example.test:99999", "vless://id@bad host:443", "https://user:pass@example.test/sub", "http:///sub")) {
            assertEquals(ImportTextIssue.INVALID, ImportTextParser.parse("$tcp\n$bad").issue, bad)
        }
    }
    @Test fun mixedTypesAndMultipleSubscriptionsAreExplicitErrors() {
        assertEquals(ImportTextIssue.MIXED, ImportTextParser.parse("$tcp\nhttps://example.test/sub").issue)
        assertEquals(ImportTextIssue.MULTIPLE_SUBSCRIPTIONS, ImportTextParser.parse("http://a.test/s\nhttps://b.test/s").issue)
    }
    @Test fun ipv6AndBareVless() {
        assertTrue(ImportTextParser.parse("VLESS://id@[2001:db8::1]:443#IPv6").ready)
        assertTrue(ImportTextParser.parse("vless://id@example.test:443").ready)
    }
    @Test fun limitAndEmbeddedUrlParameter() {
        assertEquals(ImportTextIssue.TOO_LARGE, ImportTextParser.parse("x".repeat(262145)).issue)
        assertEquals(1, ImportTextParser.parse("https://example.test/sub?next=https://another.test").let { if(it.ready) 1 else 0 })
    }
}
