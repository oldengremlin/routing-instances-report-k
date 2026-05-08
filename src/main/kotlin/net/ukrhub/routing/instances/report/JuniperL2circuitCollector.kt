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
import org.w3c.dom.NodeList
import java.util.concurrent.ConcurrentHashMap
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(JuniperL2circuitCollector::class.java)

/**
 * Collects `protocols/l2circuit/neighbor/interface` entries from Juniper
 * routers.
 *
 * Reads the XML dump written by [JuniperCollector] if available, otherwise
 * fetches via NETCONF independently. Parses
 * `//protocols/l2circuit/neighbor` nodes (excluding those inside
 * `<dynamic-profiles>`) and iterates their `interface` children.
 *
 * Each circuit is recorded with type `L2CIRCUIT`. The instance name is
 * composed as `virtual-circuit-id/ROUTER` so circuits with the same VC-ID
 * from different routers appear as separate rows, which is intentional for
 * point-to-point circuits.
 *
 * Host entry format: `ROUTER[(−)], ifaceName → neighborIP`
 */
class JuniperL2circuitCollector(
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

        val neighbors = xp.evaluate(
            "//protocols/l2circuit/neighbor[not(ancestor::dynamic-profiles)]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        var total = 0
        for (i in 0 until neighbors.length) {
            val neighbor = neighbors.item(i)
            val neighborIp = xp.evaluate("name/text()", neighbor).trim()

            val ifaces = xp.evaluate("interface", neighbor, XPathConstants.NODESET) as NodeList
            for (j in 0 until ifaces.length) {
                val iface = ifaces.item(j)
                val ifaceName = xp.evaluate("name/text()", iface).trim()
                val vcId = xp.evaluate("virtual-circuit-id/text()", iface).trim()
                val inactive = if (iface is Element) iface.getAttribute("inactive") else ""

                val ifaceMissing = if (ifaceRegistry.isMissing(hostname, ifaceName)) "(!)" else ""
                val hostEntry = routerName +
                    (if (inactive == "inactive") "(-)" else "") +
                    ", " + ifaceName + ifaceMissing + " → " + neighborIp
                val instanceName = "$vcId/$routerName"
                RoutingInstance.merge(
                    instances, vrfVplsList,
                    instanceName, "l2circuit", "", hostEntry,
                )
                if (ifaceName.isNotEmpty()) {
                    ifaceIndex.computeIfAbsent(hostname.uppercase()) { ConcurrentHashMap() }[ifaceName] =
                        IfaceRef(instanceName, "L2CIRCUIT")
                }
                total++
            }
        }

        log.info("Parsed {} l2circuits from {}", total, hostname)
    }
}
