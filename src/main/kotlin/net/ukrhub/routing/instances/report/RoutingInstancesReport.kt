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
@file:JvmName("RoutingInstancesReport")

package net.ukrhub.routing.instances.report

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

private val log: Logger = LogManager.getLogger("net.ukrhub.routing.instances.report.RoutingInstancesReport")

/**
 * Application entry point.
 *
 * Reads configuration from environment variables, runs all collectors in
 * parallel (virtual threads, semaphore-bounded), builds the lo0 address map,
 * and writes the HTML report.
 *
 * **Collection phases**
 *
 *   1. **Phase 1** — [JuniperCollector] for every Juniper host in parallel;
 *      each call fetches XML via NETCONF and writes
 *      `$DUMP_DIR/juniper-HOST.xml`.
 *   2. **Phase 2** — all remaining collectors in parallel:
 *      [JuniperSwitchCollector], [JuniperL2circuitCollector],
 *      [JuniperBridgedomainsCollector] (read the cached dumps),
 *      [CiscoCollector], [RouterOSCollector].
 *   3. [LoAddressMapper.build] builds the IP→name map.
 *   4. **Phase 3** — [JuniperDownStateCollector] for every Juniper host in
 *      parallel (requires the lo0 map from the previous step).
 *
 * The semaphore (`MAX_CONCURRENT`) limits simultaneous network connections;
 * disk-only collectors (Switch/L2circuit/Bridgedomains) bypass it.
 * [RoutingInstance.merge] is `@Synchronized` to guard the shared maps.
 */
fun main(args: Array<String>) {
    val login = require("ROUTER_USER")
    val pass = require("ROUTER_PASS")
    val ciscoEnable = env("CISCO_ENABLE", "")
    val reportPath = env("REPORT_PATH", "/usr/share/nginx/html/index.html")

    val juniperHosts = parseList(env("JUNIPER_HOSTS", ""))
    val ciscoHosts = parseList(env("CISCO_HOSTS", ""))
    val routerosHosts = parseList(env("ROUTEROS_HOSTS", ""))
    val maxConcurrent = env("MAX_CONCURRENT", "5").toInt()

    log.info(
        "Starting collection — Juniper: {}, Cisco: {}, RouterOS: {} (max {} concurrent)",
        juniperHosts, ciscoHosts, routerosHosts, maxConcurrent,
    )

    val instances: MutableMap<String, RoutingInstance> = TreeMap()
    val vrfVplsList: MutableMap<String, MutableMap<String, String>> = LinkedHashMap()
    val semaphore = Semaphore(maxConcurrent)
    val xmlCache = ConcurrentHashMap<String, String>()
    val ifaceRegistry = InterfaceRegistry(xmlCache)
    val ifaceIndex = ConcurrentHashMap<String, ConcurrentHashMap<String, IfaceRef>>()

    val juniper = JuniperCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex)
    val juniperSwitch = JuniperSwitchCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex)
    val juniperL2circuit = JuniperL2circuitCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex)
    val juniperBridges = JuniperBridgedomainsCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex)
    val cisco = CiscoCollector(login, pass, ciscoEnable)
    val routeros = RouterOSCollector(login, pass)

    // Phase 1: fetch Juniper XML dumps (one SSH session per host)
    log.info("Phase 1: fetching Juniper configs")
    runParallel(juniperHosts) { host ->
        semaphore.acquireUninterruptibly()
        try {
            juniper.collect(host, instances, vrfVplsList)
        } finally {
            semaphore.release()
        }
    }

    // Phase 2: parse cached XML (disk only) + Cisco + RouterOS
    log.info("Phase 2: parsing cached dumps, Cisco, RouterOS")
    runParallel(juniperHosts) { host ->
        juniperSwitch.collect(host, instances, vrfVplsList)
        juniperL2circuit.collect(host, instances, vrfVplsList)
        juniperBridges.collect(host, instances, vrfVplsList)
    }
    runParallel(ciscoHosts) { host ->
        semaphore.acquireUninterruptibly()
        try {
            cisco.collect(host, instances, vrfVplsList)
        } finally {
            semaphore.release()
        }
    }
    runParallel(routerosHosts) { host ->
        semaphore.acquireUninterruptibly()
        try {
            routeros.collect(host, instances, vrfVplsList)
        } finally {
            semaphore.release()
        }
    }

    log.info("Collection complete: {} instances total", instances.size)

    val loAddresses = LoAddressMapper.build(juniperHosts, xmlCache)
    log.info("Built lo0 address map: {} entries", loAddresses.size)

    val orphans = findOrphans(instances, loAddresses)
    log.info("L2CIRCUIT/VPLS orphan check: {} unpaired entries found", orphans.size)

    // Phase 3: operational down-state (needs loAddresses)
    log.info("Phase 3: collecting down state")
    val downConnections: MutableList<Array<String>> = Collections.synchronizedList(mutableListOf())
    val downCollector = JuniperDownStateCollector(login, pass, xmlCache, ifaceRegistry, ifaceIndex)
    runParallel(juniperHosts) { host ->
        semaphore.acquireUninterruptibly()
        try {
            downConnections.addAll(downCollector.collectDownState(host, loAddresses))
        } finally {
            semaphore.release()
        }
    }
    log.info("Down state check: {} connections down total", downConnections.size)

    val ltLinks = LtTunnelLinker.build(juniperHosts, xmlCache, ifaceIndex)
    log.info("LT-tunnel pairs: {} entries", ltLinks.size)

    try {
        ReportGenerator.generate(
            instances, vrfVplsList, reportPath, loAddresses, orphans, downConnections, ltLinks,
        )
    } catch (e: IOException) {
        log.error("Failed to write report to {}: {}", reportPath, e.message)
    }
}

/**
 * Submits one virtual-thread task per host, waits for all to finish.
 * Exceptions are caught and logged; they do not abort other tasks.
 */
private fun runParallel(hosts: List<String>, task: (String) -> Unit) {
    if (hosts.isEmpty()) return
    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        for (host in hosts) {
            executor.submit {
                try {
                    task(host)
                } catch (ex: Exception) {
                    log.error("Task failed for {}: {}", host, ex.message, ex)
                }
            }
        }
    }
}

private fun require(name: String): String {
    val value = System.getenv(name)
    check(!value.isNullOrBlank()) { "Required environment variable not set: $name" }
    return value
}

private fun env(name: String, defaultValue: String): String {
    val value = System.getenv(name)
    return if (!value.isNullOrBlank()) value else defaultValue
}

private fun parseList(csv: String?): List<String> {
    if (csv.isNullOrBlank()) return emptyList()
    return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Finds L2CIRCUIT and VPLS instances that lack a valid reverse peer entry.
 *
 * For every instance whose name matches `DIGITS/ROUTER[...]`, every neighbor
 * IP from the host entry's last `→` segment is resolved via [loAddresses],
 * then the reverse entry `vcId/NEIGHBOR_ROUTER` is looked up. Two mismatch
 * categories are reported:
 *
 *   * **сусід невідомий** — neighbor IP absent from the lo0 map;
 *   * **немає зворотного запису** — neighbor router known but no L2CIRCUIT
 *     or VPLS secondary entry with that router exists for the same VC-ID
 *     or VPLS-ID.
 *
 * @return list of `[type, vcId, localRouter, neighborInfo, note]` arrays
 */
private fun findOrphans(
    instances: Map<String, RoutingInstance>,
    loAddresses: Map<String, String>,
): List<Array<String>> {
    val namePattern = Regex("^(\\d+)/(.+)$")
    val reSuffix = Regex("(?i)-re\\d+$")

    // vcId → set of clean router names that have any entry for this vcId
    val vcidRouterSet = HashMap<String, MutableSet<String>>()
    // instance name → [type, ip1, ip2, …] (neighbor IPs from last " → " segment)
    val entryMap = LinkedHashMap<String, Array<String>>()

    for (ri in instances.values) {
        val t = ri.type
        if (t != "L2CIRCUIT" && !t.startsWith("VPLS")) continue
        val m = namePattern.matchEntire(ri.name) ?: continue

        val vcId = m.groupValues[1]
        val localRouter = stripVplsSuffix(m.groupValues[2], reSuffix)
        vcidRouterSet.getOrPut(vcId) { HashSet() }.add(localRouter)

        val ips = mutableListOf<String>()
        for (host in ri.hosts) {
            val arrow = host.lastIndexOf(" → ")
            if (arrow >= 0) {
                for (ip in host.substring(arrow + 3).split(Regex(",\\s*"))) {
                    if (ip.isNotBlank()) ips.add(ip.trim())
                }
            }
        }
        if (ips.isNotEmpty()) {
            val entry = Array(ips.size + 1) { "" }
            entry[0] = t
            for (i in ips.indices) entry[i + 1] = ips[i]
            entryMap[ri.name] = entry
        }
    }

    val orphans = mutableListOf<Array<String>>()

    for ((name, entry) in entryMap) {
        val m = namePattern.matchEntire(name) ?: continue
        val vcId = m.groupValues[1]
        val localRouter = stripVplsSuffix(m.groupValues[2], reSuffix)
        val type = entry[0]

        for (i in 1 until entry.size) {
            val ip = entry[i]
            val neighborRouter = loAddresses[ip]
            if (neighborRouter == null) {
                orphans.add(arrayOf(type, vcId, localRouter, ip, "сусід невідомий"))
            } else {
                val existing = vcidRouterSet[vcId]
                if (existing == null || neighborRouter !in existing) {
                    orphans.add(arrayOf(type, vcId, localRouter, neighborRouter, "немає зворотного запису"))
                }
            }
        }
    }

    return orphans
}

/**
 * Strips a trailing VPLS instance name in parentheses and the JunOS
 * routing-engine suffix from a raw router name token, then upper-cases.
 */
private fun stripVplsSuffix(raw: String, reSuffix: Regex): String {
    val s = raw.replace(Regex("\\s*\\([^)]*\\)$"), "").trim()
    return reSuffix.replace(s, "").uppercase()
}
