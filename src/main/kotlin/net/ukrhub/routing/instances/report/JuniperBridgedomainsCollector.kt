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
import java.util.concurrent.ConcurrentHashMap
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(JuniperBridgedomainsCollector::class.java)

/**
 * Collects `bridge-domains/domain` entries from Juniper routers.
 *
 * Reads the XML dump written by [JuniperCollector] if available, otherwise
 * fetches via NETCONF independently. Parses `//bridge-domains/domain` nodes
 * (excluding those inside `<dynamic-profiles>`).
 *
 * Type mapping:
 *
 *   * Domain without `<routing-interface>` → `BRIDGE/L2`
 *   * Domain with `<routing-interface>`     → `BRIDGE/L3`
 *
 * Inactive state is handled at three levels: the whole `domain` node marks
 * the router name with `(-)`, a deactivated `routing-interface` marks the
 * IRB name, and per-interface inactive marks the individual interface name.
 *
 * Host entry format:
 *     `ROUTER[(−)][, vlan-id] → [irb[(−)] →] iface1, iface2[(−)], …`
 */
class JuniperBridgedomainsCollector(
    login: String,
    pass: String,
    xmlCache: ConcurrentHashMap<String, String>,
    ifaceRegistry: InterfaceRegistry,
    ifaceIndex: ConcurrentHashMap<String, ConcurrentHashMap<String, IfaceRef>>,
) : AbstractJuniperCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex) {

    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        val doc = parseXml(readOrFetch(hostname))
        val xp = XPathFactory.newInstance().newXPath()
        val routerName = extractRouterName(doc, xp, hostname)

        val domains = xp.evaluate(
            "//bridge-domains/domain[not(ancestor::dynamic-profiles)]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until domains.length) {
            val domain = domains.item(i)
            val name = xp.evaluate("name/text()", domain).trim()
            val vlanId = xp.evaluate("vlan-id/text()", domain).trim()
            val inactive = if (domain is Element) domain.getAttribute("inactive") else ""

            val ifaceNodes = xp.evaluate("interface", domain, XPathConstants.NODESET) as NodeList
            val ifaces = mutableListOf<String>()
            val pendingIfaces = mutableListOf<String>()
            for (j in 0 until ifaceNodes.length) {
                val iface = ifaceNodes.item(j)
                val ifaceName = xp.evaluate("name/text()", iface).trim()
                val ifaceInactive = if (iface is Element) iface.getAttribute("inactive") else ""
                if (ifaceName.isNotEmpty()) pendingIfaces.add(ifaceName)
                ifaces.add(
                    ifaceName +
                        (if (ifaceInactive == "inactive") "(-)" else "") +
                        (if (ifaceRegistry.isMissing(hostname, ifaceName)) "(!)" else ""),
                )
            }

            val routingIfaceNode = xp.evaluate("routing-interface", domain, XPathConstants.NODE) as Node?
            var routingIfaceStr = ""
            var routingIfaceInactive = ""
            if (routingIfaceNode != null) {
                routingIfaceStr = routingIfaceNode.textContent.trim()
                routingIfaceInactive =
                    if (routingIfaceNode is Element) routingIfaceNode.getAttribute("inactive") else ""
            }
            val type = if (routingIfaceStr.isEmpty()) "bridge/l2" else "bridge/l3"

            val perRouter = ifaceIndex.computeIfAbsent(hostname.uppercase()) { ConcurrentHashMap() }
            val ifaceRef = IfaceRef(name, type.uppercase())
            for (n in pendingIfaces) perRouter[n] = ifaceRef
            if (routingIfaceStr.isNotEmpty()) perRouter[routingIfaceStr] = ifaceRef

            val riPart = if (routingIfaceStr.isEmpty()) {
                ""
            } else {
                routingIfaceStr +
                    (if (routingIfaceInactive == "inactive") "(-)" else "") +
                    (if (ifaceRegistry.isMissing(hostname, routingIfaceStr)) "(!)" else "") +
                    " → "
            }
            val hostEntry = routerName +
                (if (inactive == "inactive") "(-)" else "") +
                (if (vlanId.isEmpty()) "" else ", $vlanId") +
                " → " + riPart + ifaces.joinToString(", ")
            RoutingInstance.merge(instances, vrfVplsList, name, type, "", hostEntry)
        }

        log.info("Parsed {} bridge-domains from {}", domains.length, hostname)
    }
}
