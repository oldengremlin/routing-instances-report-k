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
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Builds an IP-address → router-name lookup map from Juniper XML dump files.
 *
 * After [JuniperCollector] populates the shared in-memory XML cache (and
 * writes `$DUMP_DIR/juniper-HOST.xml` as a side effect), this object reads
 * each host's configuration — preferring the in-memory cache, falling back to
 * the disk dump when absent — and extracts all addresses configured on the
 * `lo0` loopback interface (both IPv4 and IPv6, across all address families).
 *
 * The resulting map is used by [ReportGenerator] to replace bare neighbor IPs
 * with `ROUTERNAME/IP`. Prefix lengths (`/32`, `/128`, etc.) are stripped
 * before storing; when the same address appears in multiple dumps, the first
 * one wins.
 */
internal object LoAddressMapper {

    private val log: Logger = LogManager.getLogger(LoAddressMapper::class.java)
    private val RE_SUFFIX: Pattern = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE)

    /**
     * Reads each host's XML configuration and returns a map of loopback IP
     * address → upper-cased router base name.
     *
     * Hosts with neither cached XML nor an on-disk dump are silently skipped.
     * Parse errors are logged at WARN and do not abort processing of remaining
     * hosts.
     */
    fun build(hosts: List<String>, xmlCache: ConcurrentHashMap<String, String>): MutableMap<String, String> {
        val result = HashMap<String, String>()
        for (host in hosts) {
            var xml: String? = xmlCache[host]
            if (xml == null) {
                val dumpFile = Path.of(AbstractJuniperCollector.DUMP_DIR, "juniper-$host.xml")
                if (!Files.exists(dumpFile)) {
                    log.debug("No cache or dump for lo0 extraction: {}", host)
                    continue
                }
                xml = try {
                    Files.readString(dumpFile, StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    log.warn("lo0 address extraction failed for {}: {}", host, e.message)
                    continue
                }
            }
            try {
                processXml(xml, host, result)
            } catch (e: Exception) {
                log.warn("lo0 address extraction failed for {}: {}", host, e.message)
            }
        }
        log.debug("Built lo0 address map: {} entries", result.size)
        return result
    }

    private fun processXml(xml: String, fallback: String, result: MutableMap<String, String>) {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
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
        val routerName = (if (baseNames.isEmpty()) fallback else baseNames.first()).uppercase()

        val addrNodes = xp.evaluate(
            "//interfaces/interface[name='lo0'][not(ancestor::dynamic-profiles)]" +
                "/unit/family/*/address/name",
            doc, XPathConstants.NODESET,
        ) as NodeList

        for (i in 0 until addrNodes.length) {
            val raw = addrNodes.item(i).textContent.trim()
            val ip = if (raw.contains("/")) raw.substring(0, raw.indexOf('/')) else raw
            if (ip.isNotEmpty()) {
                result.putIfAbsent(ip, routerName)
                log.debug("lo0 {} → {}", ip, routerName)
            }
        }
    }
}
