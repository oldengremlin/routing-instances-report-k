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
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(JuniperCollector::class.java)

/**
 * Collects routing instances from Juniper routers via NETCONF over SSH.
 *
 * Fetches the full running configuration, populates the shared in-memory
 * `xmlCache` and atomically writes `$DUMP_DIR/juniper-HOST.xml` for reuse by
 * the other Juniper collectors in the same run, then parses
 * `//routing-instances/instance` nodes (excluding those inside
 * `<dynamic-profiles>`).
 *
 * Type mapping:
 *
 *   * `vrf` → `VRF`
 *   * `vpls` without `<routing-interface>` → `VPLS/L2`
 *   * `vpls` with `<routing-interface>` → `VPLS/L3`
 *
 * For VPLS instances an additional secondary row is emitted when LDP
 * neighbors and a VPLS-ID are present, keyed as
 * `vpls-id/ROUTER (instance-name)`, so related circuits appear together in
 * the sorted table.
 *
 * Deactivated sections (`inactive="inactive"`) are marked with `(-)` in the
 * output.
 */
class JuniperCollector(
    login: String,
    pass: String,
    xmlCache: ConcurrentHashMap<String, String>,
    ifaceRegistry: InterfaceRegistry,
) : AbstractJuniperCollector(login, pass, xmlCache, ifaceRegistry) {

    /**
     * Fetches and parses all routing instances for [hostname].
     *
     * Host entry format varies by type:
     *
     *   * VRF: `ROUTER[(−)] [→ iface1, iface2[(−)]]`
     *   * VPLS/L2: `ROUTER[:siteId][(−)] [(vpls-id)][(vlan-id)] [→ iface1, …] [→ neighbor, …]`
     *   * VPLS/L3: `ROUTER[:siteId][(−)] [(vpls-id)][(vlan-id)] → irb[(−)] → iface1, … [→ neighbor, …]`
     */
    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        val xmlResponse = fetchNetconf(hostname)
        xmlCache[hostname] = xmlResponse

        val dumpDir = Path.of(AbstractJuniperCollector.DUMP_DIR)
        val dumpFile = dumpDir.resolve("juniper-$hostname.xml")
        val tmp = Files.createTempFile(dumpDir, "juniper-$hostname-", ".xml")
        var moved = false
        try {
            Files.writeString(tmp, xmlResponse, StandardCharsets.UTF_8)
            Files.move(tmp, dumpFile, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (!moved) Files.deleteIfExists(tmp)
        }
        log.debug("Configuration cached in memory and saved to {}", dumpFile)

        val doc = parseXml(xmlResponse)
        val xp = XPathFactory.newInstance().newXPath()
        val riNodes = xp.evaluate(
            "//routing-instances/instance[not(ancestor::dynamic-profiles)]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until riNodes.length) {
            val ri: Node = riNodes.item(i)

            val name = xp.evaluate("name/text()", ri).trim()
            var type = xp.evaluate("instance-type/text()", ri).trim()
            val rd = xp.evaluate("route-distinguisher/rd-type/text()", ri).trim()
            val inactive = if (ri is Element) ri.getAttribute("inactive") else ""

            var siteId = ""
            var routingIfaceStr = ""
            var routingIfaceInactive = ""
            var vplsId = ""
            var vlanId = ""
            val ldpNeighbors = mutableListOf<String>()
            if (type == "vpls") {
                if (rd.isNotEmpty()) {
                    siteId = xp.evaluate("protocols/vpls/site/site-identifier/text()", ri).trim()
                }
                val routingIfaceNode = xp.evaluate("routing-interface", ri, XPathConstants.NODE) as Node?
                if (routingIfaceNode != null) {
                    routingIfaceStr = routingIfaceNode.textContent.trim()
                    routingIfaceInactive =
                        if (routingIfaceNode is Element) routingIfaceNode.getAttribute("inactive") else ""
                }
                vplsId = xp.evaluate("protocols/vpls/vpls-id/text()", ri).trim()
                vlanId = xp.evaluate("vlan-id/text()", ri).trim()
                val neighborNodes = xp.evaluate(
                    "protocols/vpls/neighbor | protocols/vpls/mesh-group/neighbor",
                    ri,
                    XPathConstants.NODESET,
                ) as NodeList
                for (j in 0 until neighborNodes.length) {
                    val nip = xp.evaluate("name/text()", neighborNodes.item(j)).trim()
                    if (nip.isNotEmpty()) ldpNeighbors.add(nip)
                }
                type = if (routingIfaceStr.isEmpty()) "vpls/l2" else "vpls/l3"
            }

            val ifaceNodes = xp.evaluate("interface", ri, XPathConstants.NODESET) as NodeList
            val ifaces = mutableListOf<String>()
            for (j in 0 until ifaceNodes.length) {
                val iface = ifaceNodes.item(j)
                val ifaceName = xp.evaluate("name/text()", iface).trim()
                val ifaceInactive = if (iface is Element) iface.getAttribute("inactive") else ""
                ifaces.add(
                    ifaceName +
                        (if (ifaceInactive == "inactive") "(-)" else "") +
                        (if (ifaceRegistry.isMissing(hostname, ifaceName)) "(!)" else ""),
                )
            }

            val idsPart = (if (vplsId.isEmpty()) "" else " ($vplsId)") +
                (if (vlanId.isEmpty()) "" else " ($vlanId)")
            val riPart = if (routingIfaceStr.isEmpty()) {
                ""
            } else {
                " → $routingIfaceStr" +
                    (if (routingIfaceInactive == "inactive") "(-)" else "") +
                    (if (ifaceRegistry.isMissing(hostname, routingIfaceStr)) "(!)" else "")
            }
            val neighborsPart = if (ldpNeighbors.isEmpty()) "" else " → " + ldpNeighbors.joinToString(", ")
            val hostEntry = buildString {
                append(hostname.uppercase())
                if (siteId.isNotEmpty()) append(":").append(siteId)
                if (inactive == "inactive") append("(-)")
                append(idsPart)
                append(riPart)
                if (ifaces.isNotEmpty()) append(" → ").append(ifaces.joinToString(", "))
                append(neighborsPart)
            }

            RoutingInstance.merge(instances, vrfVplsList, name, type, rd, hostEntry)
            if (ldpNeighbors.isNotEmpty() && vplsId.isNotEmpty()) {
                RoutingInstance.merge(
                    instances,
                    vrfVplsList,
                    "$vplsId/${hostname.uppercase()} ($name)",
                    type,
                    rd,
                    hostEntry,
                )
            }
        }

        log.info("Parsed {} routing instances from {}", riNodes.length, hostname)
    }
}
