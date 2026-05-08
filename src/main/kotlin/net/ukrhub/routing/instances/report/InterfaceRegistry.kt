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
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log: Logger = LogManager.getLogger(InterfaceRegistry::class.java)

/**
 * Per-host index of interfaces actually defined under the top-level
 * `<interfaces>` block of a Juniper running configuration.
 *
 * Lazily parses each host's cached XML on first lookup and records every
 * interface identifier found, in two forms:
 *
 *   * the bare physical name (e.g. `xe-1/2/0`, `irb`);
 *   * each `<unit>` qualified form (e.g. `xe-1/2/0.130`, `irb.640`).
 *
 * The `inactive="inactive"` attribute on an interface or unit does not
 * exclude it from the registry — the operator has still configured it,
 * inactivity is reported separately as `(-)`.
 *
 * Used by the Juniper collectors to flag interface references in
 * routing-instances, bridge-domains, l2circuits and interface-switches that
 * do not have a matching top-level `<interfaces>` definition. When
 * [isMissing] confirms absence, the report adds `(!)` after the interface
 * name.
 *
 * Lookup safety: when a host's XML cannot be loaded (no in-memory cache, no
 * disk dump, parse error) [isMissing] returns `false` for every query —
 * unknown is treated as present, so a single fetch failure never mass-flags
 * every interface on that host.
 */
class InterfaceRegistry(
    private val xmlCache: ConcurrentHashMap<String, String>,
) {
    /** Per-host cache of configured interface identifiers; [UNAVAILABLE] marks load failure. */
    private val cache = ConcurrentHashMap<String, Set<String>>()

    /**
     * Returns `true` only when the host's configuration was successfully
     * loaded AND [ifaceName] is not declared in the top-level `<interfaces>`
     * block. Returns `false` when the registry for [hostname] is unavailable
     * (no XML on hand, parse error) or when the interface is present.
     */
    fun isMissing(hostname: String, ifaceName: String): Boolean {
        if (ifaceName.isEmpty()) return false
        val set = cache.computeIfAbsent(hostname) { build(it) }
        if (set === UNAVAILABLE) return false
        return ifaceName !in set
    }

    private fun build(hostname: String): Set<String> {
        val xml = loadXml(hostname) ?: return UNAVAILABLE
        return try {
            val ifaces = extract(xml)
            log.debug("Interface registry for {}: {} entries", hostname, ifaces.size)
            ifaces
        } catch (e: Exception) {
            log.warn("Interface registry build failed for {}: {}", hostname, e.message)
            UNAVAILABLE
        }
    }

    private fun loadXml(hostname: String): String? {
        xmlCache[hostname]?.let { return it }
        val dump = Path.of(AbstractJuniperCollector.DUMP_DIR, "juniper-$hostname.xml")
        if (!Files.exists(dump)) return null
        return try {
            Files.readString(dump, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("Interface registry XML read failed for {}: {}", hostname, e.message)
            null
        }
    }

    private fun extract(xml: String): Set<String> {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val db = dbf.newDocumentBuilder().apply { setErrorHandler(null) }
        val doc: Document = db.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        val xp = XPathFactory.newInstance().newXPath()

        val result = HashSet<String>()
        val ifaceNodes = xp.evaluate(
            "//interfaces/interface[not(ancestor::dynamic-profiles)]",
            doc, XPathConstants.NODESET,
        ) as NodeList
        for (i in 0 until ifaceNodes.length) {
            val ifn = ifaceNodes.item(i)
            val name = xp.evaluate("name/text()", ifn).trim()
            if (name.isEmpty()) continue
            result.add(name)
            val units = xp.evaluate("unit/name/text()", ifn, XPathConstants.NODESET) as NodeList
            for (j in 0 until units.length) {
                val unit = units.item(j).nodeValue.trim()
                if (unit.isNotEmpty()) result.add("$name.$unit")
            }
        }
        return result
    }

    companion object {
        /** Sentinel stored in [cache] for hosts whose XML could not be loaded or parsed. */
        private val UNAVAILABLE: Set<String> = HashSet()
    }
}
