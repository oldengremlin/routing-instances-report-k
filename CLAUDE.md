# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn package -DskipTests
# produces target/routing-instances-report-1.0.jar (fat JAR via maven-shade-plugin)
```

```bash
docker build -t routing-instances-report .
```

```bash
# Regenerate docs/classes.svg (PlantUML) and target/reports/dokka (Dokka API docs):
mvn -P docs generate-resources
```

There are no automated tests; the Dockerfile is the primary integration target.

## Architecture

The tool is a single-shot Kotlin 2.1 / JVM 21 CLI that collects VRF/VPLS routing instance definitions from network routers and writes one HTML report. Sources live in `src/main/kotlin/net/ukrhub/routing/instances/report/` and are compiled by `kotlin-maven-plugin`; the entry point is the top-level `main` in `RoutingInstancesReport.kt` (file annotated `@file:JvmName("RoutingInstancesReport")` so the JAR's `Main-Class` stays `net.ukrhub.routing.instances.report.RoutingInstancesReport`). It runs inside a Docker container (nginx + JRE 21) that re-invokes it every 24 hours via a shell loop (`bin/routing-instances-report.sh`), started as a background process by nginx's entrypoint (`docker-entrypoint.d/40-routing-instances-report.sh`).

**Three-phase collection** (`RoutingInstancesReport.main`):

```
env vars → RoutingInstancesReport.main()
    Phase 1: JuniperCollector (SSH → NETCONF, writes xmlCache + /tmp/juniper-<host>.xml)
    Phase 2: JuniperSwitchCollector      (reads xmlCache/disk, no network)
             JuniperL2circuitCollector   (reads xmlCache/disk, no network)
             JuniperBridgedomainsCollector (reads xmlCache/disk, no network)
             CiscoCollector             (Telnet, parses show running-config text)
             RouterOSCollector          (SSH exec, parses /ip route vrf export compact)
    LoAddressMapper.build()             (extracts lo0 IPs from xmlCache for neighbor resolution)
    findOrphans()                       (checks L2CIRCUIT/VPLS peers for missing reverse entries)
    Phase 3: JuniperDownStateCollector  (SSH → NETCONF, get-l2ckt/get-vpls down RPCs)
    LtTunnelLinker.build()              (pairs lt-* units via <peer-unit>; resolves each side via ifaceIndex)
    → ReportGenerator                  (writes indexed HTML to REPORT_PATH)
```

A `Semaphore(5)` limits simultaneous network connections; disk-only collectors (Switch/L2circuit/Bridgedomains) bypass it. All phases use virtual threads (`Executors.newVirtualThreadPerTaskExecutor`).

**Juniper collector hierarchy:**

- `AbstractJuniperCollector` — SSH/NETCONF transport (`subsystem-netconf` or `exec` channel), XML helpers (`readOrFetch`, `parseXml`, `extractRouterName`), in-memory `xmlCache` passed as constructor arg
- `JuniperCollector` — fetches config, populates xmlCache, parses `//routing-instances/instance`
- `JuniperSwitchCollector`, `JuniperL2circuitCollector`, `JuniperBridgedomainsCollector` — read from xmlCache (set by Phase 1); no network access
- `JuniperDownStateCollector` — separate NETCONF session; calls `fetchRpcs()` with two RPCs in one SSH connection; does not implement `collect()` — use `collectDownState()` instead. Rows whose `instance-display-error` / `neighbor-display-error` matches `NO_DOWN_PAT` (`(?i)^No connections found\.?$`) are dropped at parse time: under the `<down/>` filter that text means "no down items found" (instance is healthy), so emitting it would invert the meaning of the "L2CIRCUIT/VPLS неактивний стан" table. Other error texts pass through.

**Data model** (`RoutingInstance.kt`): plain Kotlin class with `var` properties and a `MutableList<String>` for hosts. The `merge()` function lives on the companion object, is `@Synchronized`, and is exposed via `@JvmStatic` so callers use `RoutingInstance.merge(...)` from any context.

**Deduplication key** (`HashUtils.computeKey`): instance name padded to 50 chars + MD5 + SHA-1, matching the original Perl implementation so the same VRF present on multiple routers collapses into one row listing all routers.

**Host entry format** written by `JuniperCollector`:
- VRF: `ROUTER[(-)] [→ iface1, iface2[(-)][(!)]`
- VPLS/L2: `ROUTER[:siteId][(-)] [(vpls-id)][(vlan-id)] [→ ifaces] [→ neighbors]`
- VPLS/L3: like VPLS/L2 but with `→ irb[(-)][(!)]` before interfaces

Interface annotations: `(-)` marks an `inactive="inactive"` reference inside the routing-instance / bridge-domain block; `(!)` marks an interface name that has no matching definition under the top-level `<interfaces>` block (i.e. the operator removed the underlying physical/unit but the routing-instance still references it). In the HTML output `ReportGenerator.colorize()` wraps any `name(-)` / `name(!)` / `name(-)(!)` token in a `<span style="color:…">`: `crimson` when the token contains `(!)`, `magenta` otherwise. The marker is preserved in the visible text. Logging is unaffected.

**`LoAddressMapper`** builds an `IP → router-name` map by XPath-extracting all `lo0` addresses from the cached XML dumps. Used by `ReportGenerator` and `JuniperDownStateCollector` to resolve bare neighbor IPs to router names.

**`InterfaceRegistry`** — per-host index of every `<interfaces>/interface` definition (and its `<unit>` children, recorded as `name.unit`). Lazily parses the cached XML on first lookup. The four config-parsing Juniper collectors (`JuniperCollector`, `JuniperSwitchCollector`, `JuniperL2circuitCollector`, `JuniperBridgedomainsCollector`) call `isMissing(hostname, ifaceName)` for each interface they reference; a `true` answer adds `(!)` after the name in the host entry. When the host's XML is unavailable (no cache, no disk dump, parse error) the registry returns `false` for every query, so a single fetch failure never mass-flags interfaces.

**`ifaceIndex` + `LtTunnelLinker`** — the four config-parsing collectors also write a reverse pointer `(router, iface) → IfaceRef(instance, type)` into a shared `ConcurrentHashMap` (`ifaceIndex`) as they merge each routing-instance / bridge-domain / l2circuit / interface-switch. After Phase 3, `LtTunnelLinker.build(juniperHosts, xmlCache, ifaceIndex)` walks each host's `<interfaces>/interface[starts-with(name,'lt-')]/unit` block, pairs units that mutually point at each other via `<peer-unit>`, and for each pair looks up which service references each side. The result list (`List<LtLink>`) feeds `ReportGenerator.buildLtLinkTable()`, producing the «Логічні тунелі (lt-*)» table with clickable instance links to the main table.

**`ConnectionStatus`** — enum of Juniper L2CIRCUIT/VPLS status codes (e.g. `NP`, `OL`, `VC_DN`). `describe(code)` maps them to human-readable strings; handles `->` / `<-` asymmetric-up cases separately.

**Orphan detection** (`findOrphans`): for every L2CIRCUIT/VPLS instance named `vcId/ROUTER`, extracts neighbor IPs from the host entry and verifies a reverse entry `vcId/NEIGHBOR_ROUTER` exists. Reports two categories: *сусід невідомий* (unknown IP) and *немає зворотного запису* (missing reverse entry).

**Debug dumps**: Juniper XML → `$DUMP_DIR/juniper-<host>.xml`; Cisco config → `/tmp/cisco-<host>.conf`.

## Environment variables

| Variable         | Required              | Default                                   |
|------------------|-----------------------|-------------------------------------------|
| `ROUTER_USER`    | yes                   |                                           |
| `ROUTER_PASS`    | yes                   |                                           |
| `CISCO_ENABLE`   | if CISCO_HOSTS set    |                                           |
| `JUNIPER_HOSTS`  | no                    | (empty)                                   |
| `CISCO_HOSTS`    | no                    | (empty)                                   |
| `ROUTEROS_HOSTS` | no                    | (empty)                                   |
| `REPORT_PATH`    | no                    | `/usr/share/nginx/html/index.html`        |
| `DUMP_DIR`       | no                    | `/tmp`                                    |
| `MAX_CONCURRENT` | no                    | `5` (cap on simultaneous network sessions) |
| `LOG_LEVEL`      | no                    | `info` (Log4j2 levels: trace/debug/info/warn/error) |
| `OPENCHANNEL`    | no                    | `subsystem-netconf` (alt: `exec`)         |

## Logging

Each `.kt` file declares its logger at the top level via `private val log: Logger = LogManager.getLogger(SomeClass::class.java)`. All output goes to stdout (`docker logs`). JSch SSH-handshake noise is hard-capped at WARN regardless of `LOG_LEVEL`.

`ReportGenerator.generate()` echoes the orphan, down-state and lt-tunnel tables to the log right before writing the HTML, one line per row, so they're grep-able without opening the report. Format mirrors the table columns; lt rows are sorted by `(router, sideA.iface)`. Inactive markers `(-)` appear verbatim; `(!)` and the HTML colorize spans are HTML-only.
