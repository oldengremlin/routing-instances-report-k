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

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

private val log: Logger = LogManager.getLogger(RouterOSCollector::class.java)

/**
 * Collects VRF definitions from MikroTik RouterOS devices via SSH.
 *
 * Executes `/ip route vrf export compact` over an SSH exec channel and parses
 * the continuation-line output. RouterOS exports multi-line entries with a
 * trailing backslash (`\`) on all but the last line; this collector
 * reassembles them before matching.
 *
 * A complete VRF entry is a line matching:
 *
 *     /ip route vrf add ... route-distinguisher=AS:ID ... routing-mark=NAME
 *
 * and is merged with type `VRF`.
 */
class RouterOSCollector(
    private val login: String,
    private val pass: String,
) : Collector {

    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        log.info("Connecting to {} via SSH", hostname)
        val jsch = JSch()
        val session = jsch.getSession(login, hostname, 22)
        session.setPassword(pass)
        val cfg = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive")
        }
        session.setConfig(cfg)
        session.connect(30_000)
        session.timeout = 60_000
        log.debug("SSH session established: {}", hostname)

        val baos = ByteArrayOutputStream()
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("/ip route vrf export compact")
            val input = channel.inputStream
            channel.connect()
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    baos.write(buf, 0, n)
                }
            } finally {
                channel.disconnect()
            }
        } finally {
            session.disconnect()
        }

        parseConfig(
            hostname,
            baos.toString(StandardCharsets.UTF_8).split(Regex("\r?\n")),
            instances,
            vrfVplsList,
        )
        log.info("Parsed VRF definitions from {}", hostname)
    }

    /**
     * Reassembles continuation lines and extracts VRF definitions.
     *
     * Lines starting with `#` or empty lines are skipped. A line containing
     * `/` and not ending with `\` starts a new section context. Continuation
     * lines (ending with `\`) are concatenated. The final line of a logical
     * entry is matched against the VRF pattern.
     */
    private fun parseConfig(
        hostname: String,
        lines: List<String>,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        val vrfPat = Regex("/ip route vrf add .+ route-distinguisher=([^ ]+) routing-mark=(.+)")

        var csect = ""
        var cstr = ""

        for (rawLine in lines) {
            val s = rawLine.trim()
            if (s.startsWith("#") || s.isEmpty()) continue

            if (s.contains("/") && !s.endsWith("\\")) {
                csect = "$s "
                cstr = csect
            } else if (s.endsWith("\\")) {
                cstr += s.replace(Regex("\\\\\\s*$"), "")
            } else {
                cstr += s
                val m = vrfPat.find(cstr)
                if (m != null) {
                    RoutingInstance.merge(
                        instances, vrfVplsList,
                        m.groupValues[2].trim(), "vrf", m.groupValues[1].trim(),
                        hostname.uppercase(),
                    )
                }
                cstr = csect
            }
        }
    }
}
