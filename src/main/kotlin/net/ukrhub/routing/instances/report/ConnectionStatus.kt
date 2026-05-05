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

/**
 * Status codes reported in `connection-status` elements of Juniper
 * `get-l2ckt-connection-information` and `get-vpls-connection-information`
 * RPC replies.
 *
 * Most codes are shared between L2CIRCUIT and VPLS; a few are specific to one
 * type. [describe] converts a raw code string to a human-readable description.
 */
internal enum class ConnectionStatus(val description: String) {
    /* ----- common ----- */
    EI("encapsulation invalid"),
    EM("encapsulation mismatch"),
    MM("MTU mismatch"),
    CM("control-word mismatch"),
    VM("VLAN ID mismatch"),
    OL("no outgoing label"),
    LD("local site signaled down"),
    RD("remote site signaled down"),
    BK("backup connection"),
    ST("standby connection"),
    RS("remote site standby"),
    HS("hot-standby connection"),
    NP("interface h/w not present"),
    XX("unknown"),
    UP("operational"),
    DN("down"),
    CF("call admission control failure"),
    VC_DN("virtual circuit down"),

    /* ----- L2CIRCUIT only ----- */
    NC("interface encaps not CCC/TCC"),
    IB("TDM incompatible bitrate"),
    TM("TDM misconfiguration"),
    CB("rcvd cell-bundle size bad"),
    SP("static pseudowire"),

    /* ----- VPLS only ----- */
    WE("interface and instance encaps not same"),
    CN("circuit not provisioned"),
    OR("out of range"),
    SC("local and remote site ID collision"),
    LN("local site not designated"),
    LM("local site ID not minimum designated"),
    RN("remote site not designated"),
    RM("remote site ID not minimum designated"),
    IL("no incoming label"),
    MI("mesh-group ID not available"),
    PF("profile parse failure"),
    PB("profile busy"),
    LB("local site not best-site"),
    RB("remote site not best-site"),
    SN("static neighbor");

    companion object {
        /**
         * Returns the human-readable description for [code], or [code] itself
         * when the code is not recognised.
         *
         * Lookup is case-insensitive; hyphens (e.g. `VC-Dn`) are replaced with
         * underscores before matching.
         */
        fun describe(code: String?): String? {
            if (code.isNullOrBlank()) return code
            if (code == "->") return "only outbound connection is up"
            if (code == "<-") return "only inbound connection is up"
            val normalized = code.replace("-", "_").uppercase()
            return try {
                valueOf(normalized).description
            } catch (_: IllegalArgumentException) {
                code
            }
        }
    }
}
