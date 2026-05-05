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

/**
 * Data model for a single routing service instance (VRF, VPLS, L2CIRCUIT,
 * SWITCH, BRIDGE, …).
 *
 * One `RoutingInstance` may be present on several routers; each router
 * contributes one formatted string to [hosts]. Inserts/updates go through
 * the synchronized [merge] helper on the companion object.
 */
class RoutingInstance {

    /** Display name (instance name as it appears in router config). */
    var name: String = ""

    /** Uppercase type label (e.g. `VRF`, `VPLS/L3`, `L2CIRCUIT`). */
    var type: String = ""

    /**
     * Formatted RD string: `" [RD:AS:ID      ]"` for instances with an RD,
     * or 17 spaces for instances without one (SWITCH, L2CIRCUIT, BRIDGE).
     */
    var rd: String = ""

    /**
     * Sanitized anchor name derived from the RD string (colons replaced with
     * underscores, whitespace and brackets removed). For instances without an
     * RD this falls back to the sanitized instance name.
     */
    var hrefname: String = ""

    /** One entry per router that hosts this instance, in collection order. */
    val hosts: MutableList<String> = mutableListOf()

    companion object {
        private val log: Logger = LogManager.getLogger(RoutingInstance::class.java)

        /**
         * Inserts or updates a routing instance in the shared result maps.
         *
         * If an instance with the same dedup key already exists, [hostEntry]
         * is appended to its [hosts] list; otherwise a new instance is created.
         * Instances that carry an RD are also registered in `vrfVplsList` for
         * the RD index.
         *
         * @param instances    shared map keyed by [HashUtils.computeKey]
         * @param vrfVplsList  shared ordered map of RD-string → {name, href}
         * @param name         instance name (unpadded)
         * @param type         instance type (lowercase; stored uppercased)
         * @param rd           route distinguisher string, or `""` if absent
         * @param hostEntry    formatted router description string to append to [hosts]
         */
        @JvmStatic
        @Synchronized
        fun merge(
            instances: MutableMap<String, RoutingInstance>,
            vrfVplsList: MutableMap<String, MutableMap<String, String>>,
            name: String,
            type: String,
            rd: String,
            hostEntry: String,
        ) {
            val padded = name.padEnd(50)
            val key = HashUtils.computeKey(padded, type)

            val ri = instances.getOrPut(key) { RoutingInstance() }
            val rdStr = if (rd.isEmpty()) " ".repeat(17) else " [RD:%-11s]".format(rd)
            var hrefname = rdStr.replace(Regex("[\\[\\]\\s+]"), "").replace(":", "_")
            if (hrefname.isEmpty()) {
                hrefname = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
            }

            ri.name = name
            ri.type = type.uppercase()
            ri.rd = rdStr
            ri.hrefname = hrefname
            ri.hosts.add(hostEntry)
            log.debug("Merge: [{}] {} {} @ {}", ri.type, name, ri.rd.trim(), hostEntry)

            if (ri.rd.contains("RD:")) {
                val sub = vrfVplsList.getOrPut(ri.rd) { LinkedHashMap() }
                sub.putIfAbsent("name", ri.name)
                sub["href"] = ri.hrefname
            }
        }
    }
}
