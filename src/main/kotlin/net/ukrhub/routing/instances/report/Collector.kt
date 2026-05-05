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
 * Common contract for all router collectors.
 *
 * Each implementation connects to one router, retrieves its configuration,
 * and populates the shared `instances` and `vrfVplsList` maps via
 * [RoutingInstance.merge].
 */
interface Collector {

    /**
     * Collects routing service definitions from the specified router and
     * merges them into the shared result maps.
     *
     * @param hostname     router hostname or IP address to connect to
     * @param instances    shared map keyed by [HashUtils.computeKey]; updated in place
     * @param vrfVplsList  shared ordered map of RD-string → {name, href} used to build the RD index
     */
    @Throws(Exception::class)
    fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    )
}
