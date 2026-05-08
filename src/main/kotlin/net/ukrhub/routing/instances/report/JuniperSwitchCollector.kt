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

private val log: Logger = LogManager.getLogger(JuniperSwitchCollector::class.java)

/**
 * Collects `connections/interface-switch` entries from Juniper routers.
 *
 * Reads the XML dump written by [JuniperCollector] if available, otherwise
 * fetches via NETCONF independently. Parses
 * `//protocols/connections/interface-switch` nodes, excluding those inside
 * `<dynamic-profiles>`.
 *
 * Each switch is recorded with type `SWITCH`. A deactivated `interface-switch`
 * node is marked with `(-)` after the router name.
 *
 * Host entry format: `ROUTER[(−)] → iface1, iface2, …`
 */
class JuniperSwitchCollector(
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

        val switches = xp.evaluate(
            "//protocols/connections/interface-switch[not(ancestor::dynamic-profiles)]",
            doc,
            XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until switches.length) {
            val sw = switches.item(i)
            val name = xp.evaluate("name/text()", sw).trim()
            val inactive = if (sw is Element) sw.getAttribute("inactive") else ""

            val ifaceNodes = xp.evaluate("interface/name/text()", sw, XPathConstants.NODESET) as NodeList
            val ifaces = mutableListOf<String>()
            val perRouter = ifaceIndex.computeIfAbsent(hostname.uppercase()) { ConcurrentHashMap() }
            val ifaceRef = IfaceRef(name, "SWITCH")
            for (j in 0 until ifaceNodes.length) {
                val ifaceName = ifaceNodes.item(j).nodeValue.trim()
                if (ifaceName.isNotEmpty()) perRouter[ifaceName] = ifaceRef
                ifaces.add(
                    ifaceName +
                        (if (ifaceRegistry.isMissing(hostname, ifaceName)) "(!)" else ""),
                )
            }

            val hostEntry = routerName +
                (if (inactive == "inactive") "(-)" else "") +
                " → " + ifaces.joinToString(", ")
            RoutingInstance.merge(instances, vrfVplsList, name, "switch", "", hostEntry)
        }

        log.info("Parsed {} switches from {}", switches.length, hostname)
    }
}
