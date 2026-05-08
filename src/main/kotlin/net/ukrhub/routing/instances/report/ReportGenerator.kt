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
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/**
 * Generates the HTML report from the collected routing instance data.
 *
 * The report consists of three index sections followed by the main table:
 *
 *   1. **VRF/VPLS list ordered by RD** — instances that carry a Route
 *      Distinguisher, sorted by the numeric product AS×ID, with bidirectional
 *      anchor links to the main table.
 *   2. **VC-ID/VPLS-ID list** — instances whose name starts with a numeric
 *      prefix followed by `/` (L2CIRCUIT and secondary VPLS rows), grouped by
 *      that prefix, with a count of endpoints per ID and bidirectional links
 *      to the first matching table row.
 *   3. **Main table** — all collected instances in deduplication-key order,
 *      with type, name, RD, and router host entries.
 *
 * IP addresses in host entries are resolved to router names via the
 * `loAddresses` map built by [LoAddressMapper].
 */
object ReportGenerator {

    private val log: Logger = LogManager.getLogger(ReportGenerator::class.java)

    private val HTML_TEMPLATE: String = """<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta content="text/html; charset=UTF-8" http-equiv="Content-Type">
<title>AS12593 VRF/VPLS</title>
</head>
<body>
    <!--VRFVPLSLIST-->
    <h1>Опис VRF/VPLS</h1>
    <table border="1" cellpadding="2" cellspacing="0">
${"\t"}<tbody>
${"\t"}    <tr><th style="vertical-align: top;">№<sup>п</sup>/<sub>п</sub></th><th style="vertical-align: top;">Тип</th><th style="vertical-align: top;">Найменування</th><th style="vertical-align: top;">RD</th><th style="vertical-align: top;">Маршрутизатор</th></tr>
${"\t"}    <!--VRFVPLSINFO-->
${"\t"}</tbody>
    </table>
    <!--ORPHANTABLE-->
    <!--DOWNSTATETABLE-->
    <!--LTLINKTABLE-->
    <!--VRFVPPOSTBR-->
</body>
</html>
"""

    private fun h(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /** Matches IPv4 and IPv6 addresses inside host entry strings. */
    private val IP_PATTERN: Regex = Regex(
        "(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}" +
            "|(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(?:\\.(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)){3}",
    )

    /** Matches instance names of the form `DIGITS/...` (L2CIRCUIT, secondary VPLS). */
    private val VCID_PAT: Regex = Regex("^(\\d+)/")

    /**
     * Matches a host-entry token (interface name, router name, etc.) that
     * carries one or more `(-)` / `(!)` status markers. Group 0 is the whole
     * token including its markers, used to wrap the token in a colored span.
     */
    private val MARKED_TOKEN: Regex = Regex("""[A-Za-z0-9._/:-]+(?:\(-\)|\(!\))+""")

    /**
     * Builds and writes the HTML report.
     */
    @Throws(IOException::class)
    fun generate(
        instances: Map<String, RoutingInstance>,
        vrfVplsList: Map<String, Map<String, String>>,
        outputPath: String,
        loAddresses: Map<String, String>,
        orphans: List<Array<String>>,
        downConnections: List<Array<String>>,
        ltLinks: List<LtLink>,
    ) {
        val html = HTML_TEMPLATE
            .replace(
                "    <!--VRFVPLSLIST-->",
                buildVrfList(vrfVplsList) + buildVcidList(instances) + "    <!--VRFVPLSLIST-->",
            )
            .replace(
                "\t    <!--VRFVPLSINFO-->",
                buildVrfInfo(instances, loAddresses) + "\t    <!--VRFVPLSINFO-->",
            )
            .replace("    <!--ORPHANTABLE-->", buildOrphanTable(orphans))
            .replace("    <!--DOWNSTATETABLE-->", buildDownStateTable(downConnections))
            .replace("    <!--LTLINKTABLE-->", buildLtLinkTable(ltLinks, instances))
            .replace(
                "    <!--VRFVPPOSTBR-->",
                buildPostBr(instances.size) + "    <!--VRFVPPOSTBR-->",
            )

        log.info("Writing report to {} ({} entries)", outputPath, instances.size)
        if (orphans.isNotEmpty()) {
            log.info("L2CIRCUIT/VPLS без пар: {} записів", orphans.size)
            orphans.forEach { o ->
                log.info("  без пар: {} | {} | {} | {} | {}", o[0], o[1], o[2], o[3], o[4])
            }
        }
        if (downConnections.isNotEmpty()) {
            log.info("L2CIRCUIT/VPLS неактивний стан: {} з'єднань", downConnections.size)
            downConnections.forEach { r ->
                log.info("  down: {} | {} | {} | {} | {} | {}", r[0], r[1], r[2], r[3], r[4], r[5])
            }
        }
        if (ltLinks.isNotEmpty()) {
            log.info("Логічні тунелі (lt-*): {} пар", ltLinks.size)
            ltLinks.sortedWith(compareBy({ it.router }, { it.sideA.iface })).forEach { l ->
                log.info(
                    "  lt: {} | {} | {} → {} | {} | {} | {}",
                    l.sideA.type, l.sideA.instanceName,
                    l.sideA.iface, l.sideB.iface,
                    l.router,
                    l.sideB.instanceName, l.sideB.type,
                )
            }
        }
        PrintWriter(OutputStreamWriter(FileOutputStream(outputPath), StandardCharsets.UTF_8)).use { it.print(html) }
        log.info("Report written: {}", outputPath)
    }

    private fun buildVrfList(vrfVplsList: Map<String, Map<String, String>>): String {
        val sb = StringBuilder("    <p><h1>Список VRF/VPLS упорядкований за RD</h1><ol>\n")
        val rdPat = Regex("RD:(\\d+):(\\d+)")
        vrfVplsList.entries
            .sortedBy { e ->
                val m = rdPat.find(e.key)
                if (m != null) m.groupValues[1].toLong() * m.groupValues[2].toLong() else 0L
            }
            .forEach { e ->
                val rdl = e.key.replace(Regex("\\s"), "")
                val name = e.value["name"] ?: ""
                val href = e.value["href"] ?: ""
                sb.append(
                    "    <li><a href=\"#%s\">%s</a> - <a name=\"%s\" />%s<br /></li>\n"
                        .format(href, h(rdl), name, h(name)),
                )
            }
        sb.append("    </ol></p>\n")
        return sb.toString()
    }

    private fun buildVcidList(instances: Map<String, RoutingInstance>): String {
        val firstHref = LinkedHashMap<Int, String>()
        val counts = LinkedHashMap<Int, Int>()
        instances.values.forEach { ri ->
            val m = VCID_PAT.find(ri.name)
            if (m != null) {
                val vcid = m.groupValues[1].toInt()
                firstHref.putIfAbsent(vcid, ri.hrefname)
                counts.merge(vcid, 1, Int::plus)
            }
        }
        if (counts.isEmpty()) return ""

        val sb = StringBuilder("    <p><h1>Список VC-ID/VPLS-ID</h1><ol>\n")
        counts.entries
            .sortedBy { it.key }
            .forEach { (vcid, count) ->
                sb.append(
                    "    <li><a name=\"vcid-%d\" /><a href=\"#%s\">%d</a> — %d</li>\n"
                        .format(vcid, firstHref[vcid] ?: "", vcid, count),
                )
            }
        sb.append("    </ol></p>\n")
        return sb.toString()
    }

    private fun buildVrfInfo(
        instances: Map<String, RoutingInstance>,
        loAddresses: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        val sp = "\t    "
        var num = 0

        instances.values.forEach { ri ->
            num++
            val resolvedHosts = sortedHosts(ri).map { resolveIps(it, loAddresses) }.map { h(it) }
            log.info(
                "[{}] {} {} {}",
                "%-4s".format(ri.type),
                "%-50s".format(ri.name),
                ri.rd,
                resolvedHosts.joinToString(", "),
            )

            val hostsHtml = resolvedHosts.joinToString("<br>") { colorize(it) }

            val vcm = VCID_PAT.find(ri.name)
            val vcidBack = if (vcm != null) {
                " <sup><a href=\"#vcid-${vcm.groupValues[1]}\">↑</a></sup>"
            } else {
                ""
            }

            sb.append(
                (
                    sp + "<tr>" +
                        "<td style=\"vertical-align: top; text-align: right;\">%d</td>" +
                        "<td style=\"vertical-align: top;\">%s</td>" +
                        "<td style=\"vertical-align: top;\"><a name=\"%s\" />%s%s</td>" +
                        "<td style=\"vertical-align: top;\"><a href=\"#%s\">%s</a></td>" +
                        "<td style=\"vertical-align: top;\">%s</td>" +
                        "</tr>\n"
                    ).format(
                    num,
                    h(ri.type),
                    ri.hrefname, h(ri.name), vcidBack,
                    ri.name, h(ri.rd),
                    hostsHtml,
                ),
            )
        }
        return sb.toString()
    }

    /**
     * Wraps each `name(-)` / `name(!)` / `name(-)(!)` token in a colored
     * `<span>`. A token containing `(!)` (interface absent from the running
     * configuration) is rendered in crimson; one with `(-)` only (inactive
     * reference) is rendered in magenta. The marker itself is preserved in
     * the visible text. Operates on already HTML-escaped input.
     */
    private fun colorize(html: String): String =
        MARKED_TOKEN.replace(html) { mr ->
            val token = mr.value
            val color = if ("(!)" in token) "crimson" else "magenta"
            "<span style=\"color:$color\">$token</span>"
        }

    /**
     * Replaces bare IPv4/IPv6 addresses in [s] with `ROUTERNAME/ADDRESS`
     * using [loAddresses]. Addresses not present in the map are left as-is.
     */
    private fun resolveIps(s: String, loAddresses: Map<String, String>): String {
        if (loAddresses.isEmpty()) return s
        return IP_PATTERN.replace(s) { mr ->
            val name = loAddresses[mr.value]
            if (name != null) "$name/${mr.value}" else mr.value
        }
    }

    /**
     * Returns the host list for [ri] in display order. VPLS instances are
     * sorted by the numeric site-ID after the colon; ties broken alphabetically.
     */
    private fun sortedHosts(ri: RoutingInstance): List<String> {
        if (ri.type.startsWith("VPLS")) {
            return ri.hosts.sortedWith(
                compareBy<String> { h ->
                    val colon = h.indexOf(':')
                    if (colon < 0) {
                        0
                    } else {
                        val num = h.substring(colon + 1).replace(Regex("\\D.*"), "")
                        num.toIntOrNull() ?: 0
                    }
                }.thenBy { it },
            )
        }
        return ri.hosts.sorted()
    }

    private fun buildOrphanTable(orphans: List<Array<String>>): String {
        if (orphans.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("    <h2>L2CIRCUIT/VPLS без пар</h2>\n")
        sb.append("    <table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n")
        sb.append("\t<tbody>\n")
        sb.append(
            "\t    <tr>" +
                "<th>Тип</th>" +
                "<th>VC-ID</th>" +
                "<th>Маршрутизатор</th>" +
                "<th>Сусід</th>" +
                "<th>Примітка</th>" +
                "</tr>\n",
        )
        for (o in orphans) {
            sb.append(
                "\t    <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n"
                    .format(h(o[0]), h(o[1]), h(o[2]), h(o[3]), h(o[4])),
            )
        }
        sb.append("\t</tbody>\n")
        sb.append("    </table>\n")
        return sb.toString()
    }

    private fun buildDownStateTable(downConnections: List<Array<String>>): String {
        if (downConnections.isEmpty()) return ""
        val sorted = downConnections.sortedWith(
            compareBy(
                { it[0] },
                { it[1] },
                { it[2].toIntOrNull() ?: 0 },
                { it[3] },
            ),
        )

        val sb = StringBuilder()
        sb.append("    <h2>L2CIRCUIT/VPLS неактивний стан</h2>\n")
        sb.append("    <table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n")
        sb.append("\t<tbody>\n")
        sb.append(
            "\t    <tr>" +
                "<th>Тип</th>" +
                "<th>Маршрутизатор</th>" +
                "<th>VC-ID/VPLS-ID</th>" +
                "<th>Instance</th>" +
                "<th>Neighbor/Site</th>" +
                "<th>Статус</th>" +
                "</tr>\n",
        )
        for (r in sorted) {
            sb.append(
                "\t    <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n"
                    .format(h(r[0]), h(r[1]), h(r[2]), h(r[3]), h(r[4]), h(r[5])),
            )
        }
        sb.append("\t</tbody>\n")
        sb.append("    </table>\n")
        return sb.toString()
    }

    private fun buildLtLinkTable(
        ltLinks: List<LtLink>,
        instances: Map<String, RoutingInstance>,
    ): String {
        if (ltLinks.isEmpty()) return ""
        val hrefByKey = HashMap<String, String>()
        instances.values.forEach { ri -> hrefByKey["${ri.name}|${ri.type}"] = ri.hrefname }

        val sorted = ltLinks.sortedWith(
            compareBy({ it.router }, { it.sideA.iface }),
        )

        val sb = StringBuilder()
        sb.append("    <h2>Логічні тунелі (lt-*)</h2>\n")
        sb.append("    <table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n")
        sb.append("\t<tbody>\n")
        sb.append(
            "\t    <tr>" +
                "<th>Тип A</th>" +
                "<th>Інстанс A</th>" +
                "<th>lt-A → lt-B</th>" +
                "<th>Маршрутизатор</th>" +
                "<th>Інстанс B</th>" +
                "<th>Тип B</th>" +
                "</tr>\n",
        )
        for (link in sorted) {
            val arrow = colorize(h(link.sideA.iface)) + " → " + colorize(h(link.sideB.iface))
            sb.append(
                "\t    <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n"
                    .format(
                        h(link.sideA.type),
                        instanceLink(link.sideA, hrefByKey),
                        arrow,
                        h(link.router),
                        instanceLink(link.sideB, hrefByKey),
                        h(link.sideB.type),
                    ),
            )
        }
        sb.append("\t</tbody>\n")
        sb.append("    </table>\n")
        return sb.toString()
    }

    private fun instanceLink(side: LtSide, hrefByKey: Map<String, String>): String {
        val href = hrefByKey["${side.instanceName}|${side.type}"]
        val safeName = h(side.instanceName)
        return if (href.isNullOrEmpty()) safeName else "<a href=\"#${h(href)}\">$safeName</a>"
    }

    /**
     * Pads the page with `<br />` elements so anchor links to the last table
     * rows scroll the target row to the top of the viewport rather than the
     * bottom.
     */
    private fun buildPostBr(count: Int): String = "    " + "<br />".repeat(count) + "\n"
}
