/*
 * Copyright 2025 Ukrcom
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package net.ukrhub.routing.instances.report

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.JSch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants

private val log: Logger = LogManager.getLogger(AbstractJuniperCollector::class.java)

/**
 * Base class for all Juniper collectors.
 *
 * Provides the NETCONF 1.0 transport over SSH (both `subsystem-netconf` and
 * `exec` channel modes) and three XML helpers shared by the five concrete
 * subclasses ([JuniperCollector], [JuniperSwitchCollector],
 * [JuniperL2circuitCollector], [JuniperBridgedomainsCollector],
 * [JuniperDownStateCollector]):
 *
 *   * [readOrFetch] — returns a cached `$DUMP_DIR/juniper-HOST.xml` dump
 *     when available, falling back to a live NETCONF fetch;
 *   * [parseXml] — parses an XML string into a DOM [Document];
 *   * [extractRouterName] — resolves the router's base hostname from
 *     `//system/host-name`, stripping the `-re\d+` routing-engine suffix.
 *
 * The channel mode is controlled by the `OPENCHANNEL` environment variable
 * (`subsystem-netconf` by default, `exec` as alternative). JSch log noise is
 * suppressed to WARN regardless of the application log level.
 */
abstract class AbstractJuniperCollector(
    private val login: String,
    private val pass: String,
    /** In-memory XML cache shared across all Juniper collectors in one run. */
    protected val xmlCache: ConcurrentHashMap<String, String>,
    /** Per-host index of `<interfaces>` definitions; shared across collectors. */
    protected val ifaceRegistry: InterfaceRegistry,
) : Collector {

    /**
     * Returns the raw XML configuration for [hostname].
     *
     * Lookup order: in-memory [xmlCache] → disk dump → live NETCONF fetch.
     * The cache is populated by [JuniperCollector] so subsequent collectors
     * for the same host bypass both disk and network.
     */
    @Throws(Exception::class)
    protected fun readOrFetch(hostname: String): String {
        val cached = xmlCache[hostname]
        if (cached != null) {
            log.debug("Using in-memory XML cache for {}", hostname)
            return cached
        }
        val dumpFile = Path.of(DUMP_DIR, "juniper-$hostname.xml")
        if (Files.exists(dumpFile)) {
            log.debug("Using disk dump for {}", dumpFile)
            return Files.readString(dumpFile, StandardCharsets.UTF_8)
        }
        return fetchNetconf(hostname)
    }

    /** Parses an XML string into a namespace-unaware DOM [Document]. */
    @Throws(Exception::class)
    protected fun parseXml(xml: String): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val db = dbf.newDocumentBuilder().apply { setErrorHandler(null) }
        return db.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
    }

    /**
     * Extracts the router's canonical base name from `//system/host-name`.
     *
     * JunOS dual-RE systems report two host-name nodes (`router-re0`,
     * `router-re1`). Both are collected, the `-re\d+` suffix stripped,
     * duplicates removed, and the lexicographically first result returned in
     * upper case. Falls back to [fallback] (the DNS hostname) when no
     * host-name element is found.
     */
    @Throws(Exception::class)
    protected fun extractRouterName(doc: Document, xp: XPath, fallback: String): String {
        val nodes = xp.evaluate(
            "//system/host-name[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET,
        ) as NodeList
        val baseNames = TreeSet<String>()
        for (i in 0 until nodes.length) {
            val hn = nodes.item(i).textContent.trim()
            baseNames.add(RE_SUFFIX.matcher(hn).replaceAll(""))
        }
        return (if (baseNames.isEmpty()) fallback else baseNames.first()).uppercase()
    }

    /**
     * Opens an SSH connection to [hostname], runs a NETCONF session
     * (RFC 6241, NETCONF 1.0 framing), retrieves the full running
     * configuration, and returns it as a raw XML string.
     */
    @Throws(Exception::class)
    protected fun fetchNetconf(hostname: String): String =
        fetchRpcs(hostname, listOf(GET_CONFIG_RPC))[0]

    /**
     * Opens an SSH/NETCONF session to [hostname], exchanges hellos, sends
     * each RPC in order, reads the corresponding reply for each, then closes
     * the session.
     *
     * Use this when more than one operational RPC must be sent to the same
     * router in a single SSH connection (e.g. `get-l2ckt-connection-information`
     * and `get-vpls-connection-information`).
     *
     * @param rpcs list of RPC strings, each already terminated with the
     *             NETCONF 1.0 `]]>]]>` delimiter
     */
    @Throws(Exception::class)
    protected fun fetchRpcs(hostname: String, rpcs: List<String>): List<String> {
        val leftover = StringBuilder()
        log.info("Connecting to {} via NETCONF/SSH", hostname)
        val jsch = JSch()
        val session = jsch.getSession(login, hostname, 22)
        session.setPassword(pass)
        val cfg = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive")
        }
        session.setConfig(cfg)
        session.connect(30_000)
        session.timeout = 60_000

        val mode = System.getenv("OPENCHANNEL")?.takeIf { it.isNotBlank() } ?: "subsystem-netconf"

        val channel: Channel
        val out: OutputStream
        val input: InputStream

        if (mode == "exec") {
            val exec = session.openChannel("exec") as ChannelExec
            exec.setCommand("xml-mode netconf need-trailer")
            out = exec.outputStream
            input = exec.inputStream
            exec.connect(15_000)
            channel = exec
        } else {
            val sub = session.openChannel("subsystem") as ChannelSubsystem
            sub.setSubsystem("netconf")
            out = sub.outputStream
            input = sub.inputStream
            sub.connect(15_000)
            channel = sub
        }
        log.debug("NETCONF channel ({}) established: {}", mode, hostname)

        try {
            readUntilDelimiter(input, leftover)
            send(out, NETCONF_HELLO)
            val responses = mutableListOf<String>()
            for (rpc in rpcs) {
                send(out, rpc)
                responses.add(readUntilDelimiter(input, leftover))
            }
            send(out, CLOSE_SESSION_RPC)
            return responses
        } finally {
            channel.disconnect()
            session.disconnect()
            log.debug("NETCONF session closed: {}", hostname)
        }
    }

    private fun send(out: OutputStream, msg: String) {
        out.write(msg.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    /**
     * Reads from [input] until the NETCONF 1.0 `]]>]]>` delimiter is
     * encountered. Bytes read after the delimiter are stored in [leftover]
     * and prepended on the next call so consecutive RPC exchanges on the same
     * channel never lose data.
     */
    private fun readUntilDelimiter(input: InputStream, leftover: StringBuilder): String {
        val sb = StringBuilder(65536)
        sb.append(leftover)
        leftover.setLength(0)

        val buf = ByteArray(8192)
        var idx = sb.indexOf(DELIM)
        while (idx < 0) {
            val n = input.read(buf)
            if (n == -1) break
            sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
            idx = sb.indexOf(DELIM)
        }

        return if (idx >= 0) {
            leftover.append(sb, idx + DELIM.length, sb.length)
            sb.substring(0, idx)
        } else {
            sb.toString()
        }
    }

    companion object {
        const val DELIM: String = "]]>]]>"

        /**
         * Directory where Juniper XML configuration dumps are written and
         * read. Defaults to `/tmp`; overridden by the `DUMP_DIR` env var.
         */
        @JvmField
        val DUMP_DIR: String = System.getenv("DUMP_DIR")?.takeIf { it.isNotBlank() } ?: "/tmp"

        private val NETCONF_HELLO: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hello xmlns="urn:ietf:params:xml:ns:netconf:base:1.0">
              <capabilities>
                <capability>urn:ietf:params:netconf:base:1.0</capability>
              </capabilities>
            </hello>
        """.trimIndent() + DELIM

        private val GET_CONFIG_RPC: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rpc xmlns="urn:ietf:params:xml:ns:netconf:base:1.0" message-id="1">
              <get-config>
                <source><running/></source>
              </get-config>
            </rpc>
        """.trimIndent() + DELIM

        private val CLOSE_SESSION_RPC: String = """
            <rpc xmlns="urn:ietf:params:xml:ns:netconf:base:1.0" message-id="2">
            <close-session/></rpc>
        """.trimIndent() + DELIM

        /** Matches the `-re0` / `-re1` routing-engine suffix in JunOS hostnames. */
        private val RE_SUFFIX: Pattern = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE)
    }
}
