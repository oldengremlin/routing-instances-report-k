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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Computes the deduplication key used to merge routing instances that appear
 * on multiple routers into a single report row.
 *
 * Key format (intentionally compatible with the original Perl implementation):
 *
 *     $padded:md5_hex($padded . $type):sha1_hex($padded . $type)
 *
 * `$padded` is the instance name left-justified in a 50-character field. The
 * same VRF present on several routers produces the same key and therefore
 * collapses into one row listing all routers.
 */
object HashUtils {

    /**
     * Builds the composite deduplication key for a routing instance.
     *
     * @param paddedName instance name padded to exactly 50 characters
     * @param type       instance type string (e.g. `"vrf"`, `"vpls"`)
     * @return           `paddedName:md5hex:sha1hex`
     */
    fun computeKey(paddedName: String, type: String): String {
        val input = paddedName + type
        return "$paddedName:${hexDigest("MD5", input)}:${hexDigest("SHA-1", input)}"
    }

    private fun hexDigest(algorithm: String, input: String): String {
        val bytes = MessageDigest.getInstance(algorithm)
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) append("%02x".format(b))
        }
    }
}
