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

import org.apache.commons.net.telnet.TelnetClient
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log: Logger = LogManager.getLogger(CiscoCollector::class.java)

/**
 * Collects VRF definitions from Cisco IOS routers via Telnet.
 *
 * Connects on port 23, authenticates with username/password, enters
 * privileged mode (`enable`), disables pagination (`terminal length 0`), and
 * captures `show running-config`. The raw output is saved atomically to
 * `$DUMP_DIR/cisco-HOST.conf` for debugging.
 *
 * Parser looks for `ip vrf NAME` / `rd X:Y` block pairs. Each complete pair
 * is merged as type `VRF`.
 */
class CiscoCollector(
    private val login: String,
    private val pass: String,
    private val enablePass: String,
) : Collector {

    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        log.info("Connecting to {} via Telnet", hostname)
        val telnet = TelnetClient()
        telnet.connectTimeout = TIMEOUT_MS
        telnet.connect(hostname, 23)
        telnet.soTimeout = TIMEOUT_MS

        val runningConfig: String
        try {
            val input: InputStream = telnet.inputStream
            val out = PrintStream(telnet.outputStream, true, StandardCharsets.UTF_8)

            readUntil(input, "Username:")
            out.println(login)
            readUntil(input, "Password:")
            out.println(pass)
            readUntil(input, ">")
            out.println("enable")
            readUntil(input, "Password:")
            out.println(enablePass)
            readUntil(input, "#")
            log.debug("Telnet session established: {}", hostname)
            out.println("terminal length 0")
            readUntil(input, "#")
            out.println("show running-config")
            runningConfig = readUntil(input, "#")
            out.println("exit")
        } finally {
            telnet.disconnect()
        }

        val dumpDir = Path.of(AbstractJuniperCollector.DUMP_DIR)
        val dumpFile = dumpDir.resolve("cisco-$hostname.conf")
        val tmp = Files.createTempFile(dumpDir, "cisco-$hostname-", ".conf")
        var moved = false
        try {
            Files.writeString(tmp, runningConfig, StandardCharsets.UTF_8)
            Files.move(tmp, dumpFile, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (!moved) Files.deleteIfExists(tmp)
        }
        log.debug("Configuration saved to {}", dumpFile)

        parseConfig(hostname, runningConfig.split(Regex("\r?\n")), instances, vrfVplsList)
        log.info("Parsed VRF definitions from {}", hostname)
    }

    /**
     * Reads from the Telnet stream until [target] appears in the buffer or
     * the timeout expires.
     */
    private fun readUntil(input: InputStream, target: String): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val avail = input.available()
            if (avail > 0) {
                val buf = ByteArray(avail)
                val n = input.read(buf)
                if (n > 0) {
                    sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
                    if (sb.contains(target)) return sb.toString()
                }
            } else {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        throw IOException("Timeout waiting for '$target'")
    }

    /**
     * Scans config lines for `ip vrf` / `rd` pairs and merges each into the
     * shared instance map.
     */
    private fun parseConfig(
        hostname: String,
        lines: List<String>,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        val vrfPat = Regex("^ip\\s+vrf\\s+(.+)")
        val rdPat = Regex("^\\s+rd\\s+(.+)")

        var instance = ""
        var rd = ""

        for (s in lines) {
            val mVrf = vrfPat.matchEntire(s)
            val mRd = rdPat.matchEntire(s)

            when {
                mVrf != null -> {
                    instance = mVrf.groupValues[1].trim()
                    rd = ""
                }
                mRd != null && instance.isNotEmpty() -> {
                    rd = mRd.groupValues[1].trim()
                }
            }

            if (instance.isNotEmpty() && rd.isNotEmpty()) {
                RoutingInstance.merge(
                    instances, vrfVplsList,
                    instance, "vrf", rd, hostname.uppercase(),
                )
                instance = ""
                rd = ""
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 60_000
    }
}
