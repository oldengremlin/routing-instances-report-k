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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(LtTunnelLinker::class.java)

/**
 * Reverse pointer from a configured Juniper interface to the routing service
 * that owns it.
 *
 * Populated by the four config-parsing Juniper collectors as they encounter
 * each `<interface>` / `<routing-interface>` reference. [type] mirrors
 * [RoutingInstance.type] (uppercase, e.g. `BRIDGE/L3`, `L2CIRCUIT`,
 * `VPLS/L2`) so look-ups join cleanly with `instances`.
 */
data class IfaceRef(val instanceName: String, val type: String)

/**
 * One end of a logical-tunnel pair on a single router.
 *
 * @param iface         qualified unit name (e.g. `lt-1/0/10.3`); appended
 *                      with `(-)` when the unit is `inactive="inactive"`,
 *                      so the report's [ReportGenerator.colorize] paints it.
 * @param instanceName  routing service that references this unit, or `"?"`
 *                      when nothing claims it.
 * @param type          uppercase service type, or `"?"` when unknown.
 */
data class LtSide(val iface: String, val instanceName: String, val type: String)

/**
 * A pair of mutually peered `lt-X/Y/Z.<unit>` entries on the same router.
 *
 * Sides are placed in canonical order (smaller unit number on [sideA]) so
 * each physical pair appears exactly once.
 */
data class LtLink(val router: String, val sideA: LtSide, val sideB: LtSide)

/**
 * Builds the list of logical-tunnel pairings for every Juniper host whose
 * configuration is in [xmlCache].
 *
 * For each host the linker:
 *
 *   1. parses `<interfaces>/interface[starts-with(name,'lt-')]/unit`,
 *      collecting `(unitName, peerUnit, inactive)` tuples;
 *   2. groups units that mutually point at each other via `<peer-unit>`;
 *   3. for each side looks up the routing service that references it through
 *      [ifaceIndex] (populated during Phase 1/2 by the config-parsing
 *      collectors);
 *   4. emits one [LtLink] per pair.
 *
 * Unbound sides surface as `"?"` so dangling tunnel halves are visible in
 * the report instead of being silently dropped. Hosts with no XML on hand
 * (no in-memory cache, no on-disk dump) are skipped.
 */
internal object LtTunnelLinker {

    private val RE_SUFFIX: Pattern = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE)

    fun build(
        hosts: List<String>,
        xmlCache: ConcurrentHashMap<String, String>,
        ifaceIndex: Map<String, Map<String, IfaceRef>>,
    ): List<LtLink> {
        val result = mutableListOf<LtLink>()
        for (host in hosts) {
            val xml = loadXml(host, xmlCache) ?: continue
            try {
                processXml(host, xml, ifaceIndex, result)
            } catch (e: Exception) {
                log.warn("LT linker failed for {}: {}", host, e.message)
            }
        }
        log.debug("Built LT-link list: {} entries", result.size)
        return result
    }

    private fun loadXml(hostname: String, xmlCache: ConcurrentHashMap<String, String>): String? {
        xmlCache[hostname]?.let { return it }
        val dump = Path.of(AbstractJuniperCollector.DUMP_DIR, "juniper-$hostname.xml")
        if (!Files.exists(dump)) return null
        return try {
            Files.readString(dump, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("LT linker XML read failed for {}: {}", hostname, e.message)
            null
        }
    }

    private fun processXml(
        hostname: String,
        xml: String,
        ifaceIndex: Map<String, Map<String, IfaceRef>>,
        out: MutableList<LtLink>,
    ) {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val db = dbf.newDocumentBuilder().apply { setErrorHandler(null) }
        val doc: Document = db.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        val xp = XPathFactory.newInstance().newXPath()

        val hnNodes = xp.evaluate(
            "//system/host-name[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET,
        ) as NodeList
        val baseNames = TreeSet<String>()
        for (i in 0 until hnNodes.length) {
            val hn = hnNodes.item(i).textContent.trim()
            baseNames.add(RE_SUFFIX.matcher(hn).replaceAll(""))
        }
        val routerName = (if (baseNames.isEmpty()) hostname else baseNames.first()).uppercase()
        val perRouterIndex = ifaceIndex[hostname.uppercase()] ?: emptyMap()

        val ltIfaces = xp.evaluate(
            "//interfaces/interface[starts-with(name,'lt-')][not(ancestor::dynamic-profiles)]",
            doc, XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until ltIfaces.length) {
            val ltIf = ltIfaces.item(i)
            val ltName = xp.evaluate("name/text()", ltIf).trim()
            if (ltName.isEmpty()) continue

            // unit-name → (peer-unit, inactive)
            val units = LinkedHashMap<String, Pair<String, Boolean>>()
            val unitNodes = xp.evaluate("unit", ltIf, XPathConstants.NODESET) as NodeList
            for (j in 0 until unitNodes.length) {
                val u = unitNodes.item(j)
                val uName = xp.evaluate("name/text()", u).trim()
                val peer = xp.evaluate("peer-unit/text()", u).trim()
                if (uName.isEmpty() || peer.isEmpty()) continue
                val inactive = (u is Element) && u.getAttribute("inactive") == "inactive"
                units[uName] = peer to inactive
            }

            val seen = HashSet<Pair<String, String>>()
            for ((uName, info) in units) {
                val peerName = info.first
                val peerInfo = units[peerName] ?: continue
                if (peerInfo.first != uName) continue   // require mutual peering

                val (aName, bName) = orderPair(uName, peerName)
                if (!seen.add(aName to bName)) continue

                val aInactive = if (aName == uName) info.second else peerInfo.second
                val bInactive = if (bName == uName) info.second else peerInfo.second

                out.add(
                    LtLink(
                        router = routerName,
                        sideA = makeSide(ltName, aName, aInactive, perRouterIndex),
                        sideB = makeSide(ltName, bName, bInactive, perRouterIndex),
                    ),
                )
            }
        }
    }

    private fun makeSide(
        ltName: String,
        unit: String,
        inactive: Boolean,
        idx: Map<String, IfaceRef>,
    ): LtSide {
        val full = "$ltName.$unit"
        val ref = idx[full]
        return LtSide(
            iface = full + if (inactive) "(-)" else "",
            instanceName = ref?.instanceName ?: "?",
            type = ref?.type ?: "?",
        )
    }

    /** Lower-numbered (or lexicographically smaller) unit goes on side A. */
    private val UNIT_ORDER: Comparator<String> =
        compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })

    private fun orderPair(a: String, b: String): Pair<String, String> =
        if (UNIT_ORDER.compare(a, b) <= 0) a to b else b to a
}
