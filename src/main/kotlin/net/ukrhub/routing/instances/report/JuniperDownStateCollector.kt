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

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.w3c.dom.NodeList
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(JuniperDownStateCollector::class.java)

/**
 * Fetches operational down-state for L2CIRCUIT and VPLS from Juniper routers.
 *
 * Sends two NETCONF RPCs in a single SSH session per router:
 *
 *   * `get-l2ckt-connection-information` with `<down/>` filter;
 *   * `get-vpls-connection-information` with `<down/>` filter.
 *
 * Each down connection is returned as a six-element array
 * `[type, routerName, vcId, instance, neighborSite, statusDescription]`.
 * Neighbor IPs are resolved via the `loAddresses` map built by
 * [LoAddressMapper]; unresolved IPs are kept as-is.
 */
class JuniperDownStateCollector(
    login: String,
    pass: String,
    xmlCache: ConcurrentHashMap<String, String>,
    ifaceRegistry: InterfaceRegistry,
) : AbstractJuniperCollector(login, pass, xmlCache, ifaceRegistry) {

    /** Not used — operational state is fetched via [collectDownState]. */
    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ): Unit = throw UnsupportedOperationException("Use collectDownState() instead")

    /**
     * Fetches and parses all down L2CIRCUIT and VPLS connections for
     * [hostname] in a single NETCONF session.
     *
     * The local router name is read from the cached config dump written by
     * [JuniperCollector] (if available) so that L2CIRCUIT instance names
     * match the format used in the main table. Falls back to the upper-cased
     * hostname when the dump is absent.
     */
    @Throws(Exception::class)
    fun collectDownState(hostname: String, loAddresses: Map<String, String>): List<Array<String>> {
        val routerName = resolveRouterName(hostname)
        val responses = fetchRpcs(hostname, listOf(L2CKT_DOWN_RPC, VPLS_DOWN_RPC))

        val results = mutableListOf<Array<String>>()
        parseL2ckt(responses[0], routerName, loAddresses, results)
        parseVpls(responses[1], routerName, loAddresses, results)

        log.info("Down state from {}: {} connections down", hostname, results.size)
        return results
    }

    private fun resolveRouterName(hostname: String): String {
        var xml: String? = xmlCache[hostname]
        if (xml == null) {
            val dump = Path.of(AbstractJuniperCollector.DUMP_DIR, "juniper-$hostname.xml")
            if (Files.exists(dump)) {
                xml = try {
                    Files.readString(dump, StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    log.warn("Could not read dump for {}: {}", hostname, e.message)
                    null
                }
            }
        }
        if (xml != null) {
            try {
                return extractRouterName(parseXml(xml), XPathFactory.newInstance().newXPath(), hostname)
            } catch (e: Exception) {
                log.warn("Could not extract router name for {}: {}", hostname, e.message)
            }
        }
        return hostname.uppercase()
    }

    private fun parseL2ckt(
        xml: String,
        routerName: String,
        loAddresses: Map<String, String>,
        results: MutableList<Array<String>>,
    ) {
        val doc = parseXml(xml)
        val xp = XPathFactory.newInstance().newXPath()

        val neighbors = xp.evaluate(
            "//l2circuit-neighbor[connection or neighbor-display-error]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until neighbors.length) {
            val neighbor = neighbors.item(i)
            val neighborIp = xp.evaluate("neighbor-address/text()", neighbor).trim()
            val neighborName = loAddresses[neighborIp] ?: neighborIp
            val neighborDisplay = if (neighborName == neighborIp) neighborIp else "$neighborName/$neighborIp"

            val connections = xp.evaluate("connection", neighbor, XPathConstants.NODESET) as NodeList
            if (connections.length > 0) {
                for (j in 0 until connections.length) {
                    val conn = connections.item(j)
                    val connId = xp.evaluate("connection-id/text()", conn).trim()
                    val statusCode = xp.evaluate("connection-status/text()", conn).trim()

                    val m = VC_ID_PAT.matcher(connId)
                    val vcId = if (m.find()) m.group(1) else connId
                    val iface = connId.replace(Regex("\\s*\\(vc \\d+\\)$"), "").trim()

                    results.add(
                        arrayOf(
                            "L2CIRCUIT",
                            routerName,
                            vcId,
                            "$vcId/$routerName",
                            "$neighborDisplay, $iface",
                            ConnectionStatus.describe(statusCode) ?: statusCode,
                        ),
                    )
                }
            } else {
                val errorText = xp.evaluate("neighbor-display-error/text()", neighbor).trim()
                results.add(
                    arrayOf(
                        "L2CIRCUIT",
                        routerName,
                        "?",
                        "?/$routerName",
                        neighborDisplay,
                        if (errorText.isEmpty()) "no connections" else errorText,
                    ),
                )
            }
        }
    }

    private fun parseVpls(
        xml: String,
        routerName: String,
        loAddresses: Map<String, String>,
        results: MutableList<Array<String>>,
    ) {
        val doc = parseXml(xml)
        val xp = XPathFactory.newInstance().newXPath()

        val instances = xp.evaluate(
            "//instance[reference-site/connection or instance-display-error]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until instances.length) {
            val inst = instances.item(i)
            val instanceName = xp.evaluate("instance-name/text()", inst).trim()

            var vplsId = xp.evaluate("ldp-vpls-reference-site/vpls-id/text()", inst).trim()
            if (vplsId.isEmpty()) {
                vplsId = xp.evaluate("reference-site/vpls-id/text()", inst).trim()
            }

            val connections = xp.evaluate("reference-site/connection", inst, XPathConstants.NODESET) as NodeList

            if (connections.length > 0) {
                for (j in 0 until connections.length) {
                    val conn = connections.item(j)
                    val connId = xp.evaluate("connection-id/text()", conn).trim()
                    val statusCode = xp.evaluate("connection-status/text()", conn).trim()

                    val neighborSite = run {
                        val m = VPLS_NEIGHBOR_PAT.matcher(connId)
                        if (m.find()) {
                            val ip = m.group(1)
                            val name = loAddresses[ip] ?: ip
                            if (name == ip) ip else "$name/$ip"
                        } else {
                            connId
                        }
                    }

                    results.add(
                        arrayOf(
                            "VPLS",
                            routerName,
                            if (vplsId.isEmpty()) "?" else vplsId,
                            instanceName,
                            neighborSite,
                            ConnectionStatus.describe(statusCode) ?: statusCode,
                        ),
                    )
                }
            } else {
                val errorText = xp.evaluate("instance-display-error/text()", inst).trim()
                results.add(
                    arrayOf(
                        "VPLS",
                        routerName,
                        if (vplsId.isEmpty()) "?" else vplsId,
                        instanceName,
                        "-",
                        if (errorText.isEmpty()) "no connections" else errorText,
                    ),
                )
            }
        }
    }

    companion object {
        private const val L2CKT_DOWN_RPC =
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"1\">" +
                "<get-l2ckt-connection-information><down/></get-l2ckt-connection-information>" +
                "</rpc>" + AbstractJuniperCollector.DELIM

        private const val VPLS_DOWN_RPC =
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"2\">" +
                "<get-vpls-connection-information><down/></get-vpls-connection-information>" +
                "</rpc>" + AbstractJuniperCollector.DELIM

        /** Extracts VC-ID from L2CIRCUIT connection-id, e.g. `xe-0/2/0.1228(vc 1228)` → `1228`. */
        private val VC_ID_PAT: Pattern = Pattern.compile("\\(vc (\\d+)\\)$")

        /** Extracts the IP part from VPLS LDP neighbor connection-id. */
        private val VPLS_NEIGHBOR_PAT: Pattern = Pattern.compile("^([\\d:.a-fA-F]+)\\(")
    }
}
